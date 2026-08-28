from types import SimpleNamespace

from fastapi.testclient import TestClient

from app import (
    CHIRP_MODEL,
    CHIRP_SAMPLE_RATE,
    DEFAULT_MODEL,
    _build_forwarded,
    _validate_speech_start,
    app,
)


def test_health_is_non_secret():
    response = TestClient(app).get("/health")
    assert response.status_code == 200
    assert response.json()["model"] == DEFAULT_MODEL


def test_chat_requires_gateway_token(monkeypatch):
    monkeypatch.setenv("VOXPEN_GATEWAY_TOKEN", "test-token")
    response = TestClient(app).post(
        "/v1/chat/completions",
        json={"model": DEFAULT_MODEL, "messages": [{"role": "user", "content": "ok"}]},
    )
    assert response.status_code == 401


def test_forwarded_payload_drops_vertex_unsupported_fields():
    forwarded, error = _build_forwarded(
        {
            "model": DEFAULT_MODEL,
            "messages": [{"role": "user", "content": "ok"}],
            "max_tokens": 4096,
            "reasoning_effort": "low",
            "temperature": 0.3,
            "top_p": 0.9,
            "reasoning_format": "hidden",
        }
    )

    assert error is None
    assert forwarded == {
        "model": DEFAULT_MODEL,
        "messages": [{"role": "user", "content": "ok"}],
        "max_tokens": 4096,
        "reasoning_effort": "low",
    }


def test_chirp_start_requires_expected_audio_contract():
    validated, error = _validate_speech_start(
        {
            "type": "start",
            "model": CHIRP_MODEL,
            "languageCode": "cmn-Hant-TW",
            "sampleRateHz": CHIRP_SAMPLE_RATE,
            "channels": 1,
            "encoding": "LINEAR16",
            "automaticPunctuation": True,
            "adaptationPhrases": ["兜率天", "context memory", "兜率天"],
        }
    )

    assert error is None
    assert validated is not None
    assert validated["adaptation_phrases"] == ["兜率天", "context memory"]


def test_chirp_start_rejects_oversized_adaptation():
    validated, error = _validate_speech_start(
        {
            "type": "start",
            "model": CHIRP_MODEL,
            "languageCode": "en-US",
            "sampleRateHz": CHIRP_SAMPLE_RATE,
            "channels": 1,
            "encoding": "LINEAR16",
            "adaptationPhrases": ["x" * 101],
        }
    )

    assert validated is None
    assert error == "adaptation phrase is invalid"


def test_chirp_websocket_protocol_with_fake_upstream(monkeypatch):
    monkeypatch.setenv("VOXPEN_GATEWAY_TOKEN", "test-token")
    monkeypatch.setenv("GOOGLE_CLOUD_PROJECT", "test-project")

    class FakeSpeechClient:
        async def streaming_recognize(self, requests):
            audio_frame_count = 0
            async for request in requests:
                if request.audio:
                    audio_frame_count += 1

            assert audio_frame_count == 1

            async def responses():
                yield SimpleNamespace(
                    results=[
                        SimpleNamespace(
                            alternatives=[SimpleNamespace(transcript="你好")],
                            is_final=True,
                            result_end_offset=None,
                        )
                    ]
                )

            return responses()

        async def close(self):
            return None

    monkeypatch.setattr("app._speech_client", lambda project, location: FakeSpeechClient())

    start = {
        "type": "start",
        "model": CHIRP_MODEL,
        "languageCode": "cmn-Hant-TW",
        "sampleRateHz": CHIRP_SAMPLE_RATE,
        "channels": 1,
        "encoding": "LINEAR16",
    }
    with TestClient(app).websocket_connect(
        "/v1/speech/stream",
        headers={"authorization": "Bearer test-token"},
    ) as websocket:
        websocket.send_json(start)
        assert websocket.receive_json()["type"] == "ready"
        websocket.send_bytes(b"\x00" * 3200)
        websocket.send_json({"type": "stop"})

        assert websocket.receive_json()["type"] == "final"
        assert websocket.receive_json()["type"] == "closed"
