"""Small authenticated OpenAI-compatible gateway for Vertex AI Gemini."""

from __future__ import annotations

import hmac
import os
from typing import Any

import google.auth
from google.auth.transport.requests import Request as GoogleAuthRequest
import httpx
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

DEFAULT_LOCATION = "global"
DEFAULT_MODEL = "google/gemini-3.7-flash"
DEFAULT_MAX_TOKENS = 4096
VERTEX_OPENAI_SCOPE = "https://www.googleapis.com/auth/cloud-platform"

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


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("app:app", host="127.0.0.1", port=8787)
