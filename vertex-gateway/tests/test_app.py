from fastapi.testclient import TestClient

from app import DEFAULT_MODEL, _build_forwarded, app


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
