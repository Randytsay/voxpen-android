"""Authenticated gateway for Vertex Gemini and Cloud Speech-to-Text V2."""

from __future__ import annotations

import asyncio
import hmac
import inspect
import json
import os
import re
import uuid
from collections import deque
from typing import Any

import google.auth
from google.auth.transport.requests import Request as GoogleAuthRequest
import httpx
from fastapi import FastAPI, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import JSONResponse

DEFAULT_LOCATION = "global"
DEFAULT_MODEL = "google/gemini-3.7-flash"
DEFAULT_MAX_TOKENS = 4096
VERTEX_OPENAI_SCOPE = "https://www.googleapis.com/auth/cloud-platform"
DEFAULT_SPEECH_LOCATION = "us"
CHIRP_MODEL = "chirp_3"
CHIRP_SAMPLE_RATE = 16_000
CHIRP_CHANNELS = 1
CHIRP_ENCODING = "LINEAR16"
MAX_AUDIO_FRAME_BYTES = 25 * 1024
MAX_ADAPTATION_PHRASES = 200
MAX_PHRASE_LENGTH = 100
MAX_PENDING_AUDIO_FRAMES = 30
REPLAY_FRAME_COUNT = 10
ROLLOVER_SECONDS = 270
SPEECH_SAMPLE_WIDTH_BYTES = 2

app = FastAPI(title="VoxPen Vertex Gateway", version="1.0.0")


def _config(name: str, default: str | None = None) -> str | None:
    value = os.environ.get(name, default)
    return value.strip() if value else value


def _authorized(request: Request) -> bool:
    configured = _config("VOXPEN_GATEWAY_TOKEN") or ""
    supplied = request.headers.get("authorization", "")
    expected = f"Bearer {configured}" if configured else ""
    return bool(configured) and hmac.compare_digest(supplied, expected)


def _vertex_endpoint(project: str, location: str) -> str:
    return (
        "https://aiplatform.googleapis.com/v1/"
        f"projects/{project}/locations/{location}/endpoints/openapi/chat/completions"
    )


def _google_access_token() -> str:
    # ADC may be backed by a user credential, workload identity, or a service
    # account on the gateway host. The Android app never reaches this code.
    credentials, _ = google.auth.default(scopes=[VERTEX_OPENAI_SCOPE])
    if not credentials.valid or not credentials.token:
        credentials.refresh(GoogleAuthRequest())
    if not credentials.token:
        raise RuntimeError("Google ADC did not provide an access token")
    return credentials.token


def _build_forwarded(payload: dict[str, Any]) -> tuple[dict[str, Any] | None, str | None]:
    model = str(payload.get("model") or DEFAULT_MODEL).strip()
    messages = payload.get("messages")
    if not model or not isinstance(messages, list) or not messages:
        return None, "model and messages are required"

    try:
        max_tokens = int(payload.get("max_tokens", DEFAULT_MAX_TOKENS))
    except (TypeError, ValueError):
        return None, "max_tokens must be an integer"
    if not 1 <= max_tokens <= DEFAULT_MAX_TOKENS:
        return None, "max_tokens must be between 1 and 4096"

    # Keep this allow-list deliberately small. In particular, temperature and
    # provider-specific controls are never forwarded to Gemini 3.7 Flash.
    forwarded: dict[str, Any] = {
        "model": model,
        "messages": messages,
        "max_tokens": max_tokens,
    }
    reasoning_effort = payload.get("reasoning_effort")
    if isinstance(reasoning_effort, str) and reasoning_effort.strip():
        forwarded["reasoning_effort"] = reasoning_effort.strip()
    return forwarded, None


@app.get("/health")
async def health() -> dict[str, str]:
    return {
        "status": "ok",
        "model": DEFAULT_MODEL,
        "location": _config("GOOGLE_CLOUD_LOCATION", DEFAULT_LOCATION) or DEFAULT_LOCATION,
        "speechModel": CHIRP_MODEL,
        "speechLocation": _config("GOOGLE_CLOUD_SPEECH_LOCATION", DEFAULT_SPEECH_LOCATION)
        or DEFAULT_SPEECH_LOCATION,
    }


@app.post("/v1/chat/completions")
async def chat_completions(request: Request) -> JSONResponse:
    if not _authorized(request):
        return JSONResponse({"detail": "Unauthorized"}, status_code=401)

    project = _config("GOOGLE_CLOUD_PROJECT")
    if not project:
        return JSONResponse({"detail": "Gateway project is not configured"}, status_code=503)

    try:
        payload: Any = await request.json()
    except Exception:
        return JSONResponse({"detail": "Request body must be JSON"}, status_code=400)

    if not isinstance(payload, dict):
        return JSONResponse({"detail": "Request body must be an object"}, status_code=400)

    forwarded, validation_error = _build_forwarded(payload)
    if validation_error is not None:
        return JSONResponse({"detail": validation_error}, status_code=400)
    assert forwarded is not None

    location = _config("GOOGLE_CLOUD_LOCATION", DEFAULT_LOCATION) or DEFAULT_LOCATION
    try:
        access_token = _google_access_token()
        async with httpx.AsyncClient(timeout=httpx.Timeout(45.0, connect=5.0)) as client:
            upstream = await client.post(
                _vertex_endpoint(project, location),
                headers={
                    "Authorization": f"Bearer {access_token}",
                    "Content-Type": "application/json",
                },
                json=forwarded,
            )
    except Exception:
        # Do not expose credential or upstream details to the Android client.
        return JSONResponse({"detail": "Vertex upstream request failed"}, status_code=502)

    if upstream.status_code >= 400:
        return JSONResponse({"detail": "Vertex upstream request failed"}, status_code=502)
    try:
        response_body = upstream.json()
    except ValueError:
        return JSONResponse({"detail": "Vertex returned an invalid response"}, status_code=502)
    return JSONResponse(response_body, status_code=upstream.status_code)


def _speech_authorized(websocket: WebSocket) -> bool:
    configured = _config("VOXPEN_GATEWAY_TOKEN") or ""
    supplied = websocket.headers.get("authorization", "")
    expected = f"Bearer {configured}" if configured else ""
    return bool(configured) and hmac.compare_digest(supplied, expected)


def _validate_speech_start(payload: Any) -> tuple[dict[str, Any] | None, str | None]:
    if not isinstance(payload, dict) or payload.get("type") != "start":
        return None, "first websocket message must be a start object"
    if payload.get("model", CHIRP_MODEL) != CHIRP_MODEL:
        return None, "only chirp_3 is supported"
    if payload.get("sampleRateHz") != CHIRP_SAMPLE_RATE:
        return None, "sampleRateHz must be 16000"
    if payload.get("channels") != CHIRP_CHANNELS:
        return None, "channels must be 1"
    if payload.get("encoding") != CHIRP_ENCODING:
        return None, "encoding must be LINEAR16"

    language_code = str(payload.get("languageCode") or "").strip()
    if language_code != "auto" and not re.fullmatch(
        r"[a-z]{2,3}(?:-[A-Za-z0-9]{2,8})+",
        language_code,
    ):
        return None, "languageCode is invalid"

    raw_phrases = payload.get("adaptationPhrases", [])
    if not isinstance(raw_phrases, list):
        return None, "adaptationPhrases must be an array"
    if len(raw_phrases) > MAX_ADAPTATION_PHRASES:
        return None, "too many adaptation phrases"

    phrases: list[str] = []
    for raw_phrase in raw_phrases:
        phrase = str(raw_phrase).strip()
        if not phrase or len(phrase) > MAX_PHRASE_LENGTH:
            return None, "adaptation phrase is invalid"
        if phrase not in phrases:
            phrases.append(phrase)

    return {
        "language_code": language_code,
        "automatic_punctuation": bool(payload.get("automaticPunctuation", True)),
        "adaptation_phrases": phrases,
    }, None


def _speech_client(project: str, location: str) -> Any:
    # Import lazily so health and configuration tests do not need the cloud SDK
    # or ADC credentials merely to import the gateway.
    from google.api_core.client_options import ClientOptions
    from google.cloud.speech_v2 import SpeechAsyncClient

    return SpeechAsyncClient(
        client_options=ClientOptions(
            api_endpoint=f"{location}-speech.googleapis.com",
            quota_project_id=project,
        )
    )


def _speech_config(
    project: str,
    location: str,
    validated: dict[str, Any],
) -> Any:
    from google.cloud.speech_v2.types import cloud_speech

    adaptation = None
    if validated["adaptation_phrases"]:
        adaptation = cloud_speech.SpeechAdaptation(
            phrase_sets=[
                cloud_speech.SpeechAdaptation.AdaptationPhraseSet(
                    inline_phrase_set=cloud_speech.PhraseSet(
                        phrases=[{"value": phrase} for phrase in validated["adaptation_phrases"]]
                    )
                )
            ]
        )

    recognition_config = cloud_speech.RecognitionConfig(
        explicit_decoding_config=cloud_speech.ExplicitDecodingConfig(
            encoding=cloud_speech.ExplicitDecodingConfig.AudioEncoding.LINEAR16,
            sample_rate_hertz=CHIRP_SAMPLE_RATE,
            audio_channel_count=CHIRP_CHANNELS,
        ),
        language_codes=[validated["language_code"]],
        model=CHIRP_MODEL,
        features=cloud_speech.RecognitionFeatures(
            enable_automatic_punctuation=validated["automatic_punctuation"],
        ),
        adaptation=adaptation,
    )
    streaming_config = cloud_speech.StreamingRecognitionConfig(
        config=recognition_config,
        streaming_features=cloud_speech.StreamingRecognitionFeatures(
            interim_results=True,
        ),
    )
    return cloud_speech.StreamingRecognizeRequest(
        recognizer=f"projects/{project}/locations/{location}/recognizers/_",
        streaming_config=streaming_config,
    )


def _offset_millis(result: Any) -> int:
    offset = getattr(result, "result_end_offset", None)
    if offset is None:
        return 0
    return int(getattr(offset, "seconds", 0) * 1000 + getattr(offset, "nanos", 0) / 1_000_000)


async def _close_speech_client(client: Any) -> None:
    close = getattr(client, "close", None)
    if close is None:
        return
    result = close()
    if inspect.isawaitable(result):
        await result


async def _run_speech_stream(
    websocket: WebSocket,
    project: str,
    location: str,
    validated: dict[str, Any],
    audio_queue: asyncio.Queue[bytes | None],
) -> None:
    from google.cloud.speech_v2.types import cloud_speech

    session_id = uuid.uuid4().hex
    recent_frames: deque[bytes] = deque(maxlen=REPLAY_FRAME_COUNT)
    stream_index = 0
    stopped = False

    while not stopped:
        client = _speech_client(project, location)
        rollover_requested = False
        audio_bytes = 0
        stream_id = f"{session_id}:{stream_index}"
        config_request = _speech_config(project, location, validated)

        async def request_generator():
            nonlocal audio_bytes, rollover_requested, stopped
            yield config_request
            if stream_index > 0:
                for frame in tuple(recent_frames):
                    yield cloud_speech.StreamingRecognizeRequest(audio=frame)

            while True:
                frame = await audio_queue.get()
                if frame is None:
                    stopped = True
                    return
                recent_frames.append(frame)
                audio_bytes += len(frame)
                yield cloud_speech.StreamingRecognizeRequest(audio=frame)
                if audio_bytes >= ROLLOVER_SECONDS * CHIRP_SAMPLE_RATE * CHIRP_CHANNELS * SPEECH_SAMPLE_WIDTH_BYTES:
                    rollover_requested = True
                    return

        try:
            responses = await client.streaming_recognize(requests=request_generator())
            result_index = 0
            async for response in responses:
                for result in response.results:
                    if not result.alternatives:
                        continue
                    transcript = result.alternatives[0].transcript.strip()
                    if not transcript:
                        continue
                    result_index += 1
                    message_type = "final" if result.is_final else "interim"
                    message: dict[str, Any] = {
                        "type": message_type,
                        "text": transcript,
                    }
                    if result.is_final:
                        message["segmentId"] = f"{stream_id}:{result_index}:{_offset_millis(result)}"
                    await websocket.send_json(message)
        except WebSocketDisconnect:
            raise
        except Exception:
            await websocket.send_json({"type": "error", "code": "speech_upstream_failed"})
            return
        finally:
            await _close_speech_client(client)

        if rollover_requested and not stopped:
            stream_index += 1
            continue
        if not stopped:
            # Keep the session alive if Google closes a stream before the
            # configured rollover point.
            stream_index += 1

    await websocket.send_json({"type": "closed"})


@app.websocket("/v1/speech/stream")
async def speech_stream(websocket: WebSocket) -> None:
    if not _speech_authorized(websocket):
        await websocket.close(code=1008, reason="Unauthorized")
        return

    await websocket.accept()
    try:
        first = await websocket.receive()
        raw_text = first.get("text")
        if raw_text is None or first.get("bytes") is not None:
            await websocket.send_json({"type": "error", "code": "invalid_start"})
            await websocket.close(code=1003, reason="Invalid start")
            return
        try:
            payload = json.loads(raw_text)
        except json.JSONDecodeError:
            await websocket.send_json({"type": "error", "code": "invalid_start"})
            await websocket.close(code=1003, reason="Invalid start")
            return

        validated, validation_error = _validate_speech_start(payload)
        if validation_error is not None or validated is None:
            await websocket.send_json({"type": "error", "code": "invalid_config"})
            await websocket.close(code=1008, reason="Invalid config")
            return

        project = _config("GOOGLE_CLOUD_PROJECT")
        if not project:
            await websocket.send_json({"type": "error", "code": "speech_not_configured"})
            await websocket.close(code=1011, reason="Speech not configured")
            return
        location = _config("GOOGLE_CLOUD_SPEECH_LOCATION", DEFAULT_SPEECH_LOCATION) or DEFAULT_SPEECH_LOCATION

        await websocket.send_json(
            {
                "type": "ready",
                "model": CHIRP_MODEL,
                "location": location,
            }
        )
        audio_queue: asyncio.Queue[bytes | None] = asyncio.Queue(maxsize=MAX_PENDING_AUDIO_FRAMES)
        upstream_task = asyncio.create_task(
            _run_speech_stream(websocket, project, location, validated, audio_queue)
        )

        try:
            while not upstream_task.done():
                receive_task = asyncio.create_task(websocket.receive())
                done, pending = await asyncio.wait(
                    {receive_task, upstream_task},
                    return_when=asyncio.FIRST_COMPLETED,
                )
                if upstream_task in done:
                    receive_task.cancel()
                    await upstream_task
                    break

                message = receive_task.result()
                if message.get("type") == "websocket.disconnect":
                    break
                if message.get("bytes") is not None:
                    frame = message["bytes"]
                    if len(frame) > MAX_AUDIO_FRAME_BYTES:
                        await websocket.send_json({"type": "error", "code": "audio_frame_too_large"})
                        await audio_queue.put(None)
                        break
                    await audio_queue.put(frame)
                    continue
                control = (message.get("text") or "").strip()
                try:
                    control_type = json.loads(control).get("type")
                except (json.JSONDecodeError, AttributeError):
                    control_type = None
                if control_type == "stop":
                    await audio_queue.put(None)
                    await upstream_task
                    break
                await websocket.send_json({"type": "error", "code": "invalid_control_message"})
        finally:
            if not upstream_task.done():
                await audio_queue.put(None)
                await upstream_task
    except WebSocketDisconnect:
        return
    except Exception:
        # Never expose credential, audio, transcript, or upstream details.
        return


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("app:app", host="127.0.0.1", port=8787)
