# VoxPen Vertex Gateway

This service keeps Google authentication and Vertex credentials outside the
Android APK. The Android app sends an OpenAI-compatible request to this
gateway with a separate `VOXPEN_GATEWAY_TOKEN`; the gateway obtains a Google
access token through Application Default Credentials (ADC) and calls the
Vertex global OpenAI-compatible endpoint.

## Configuration

Set these environment variables on the gateway host. Start from
`.env.example`, but never commit a populated `.env` or credential JSON file.

- `GOOGLE_CLOUD_PROJECT`: required Google Cloud project ID.
- `GOOGLE_CLOUD_LOCATION`: normally `global`; defaults to `global`.
- `GOOGLE_APPLICATION_CREDENTIALS`: optional ADC credential path. The service
  uses `google.auth.default` and refreshes credentials in process; it never
  invokes a `gcloud` subprocess.
- `VOXPEN_GATEWAY_TOKEN`: required long random token shared with the Android
  app. It is checked as an exact `Authorization: Bearer ...` value and is
  never logged or returned.

Install and run:

```sh
cd vertex-gateway
python -m venv .venv
. .venv/bin/activate
python -m pip install .
export GOOGLE_CLOUD_PROJECT=your-project-id
export GOOGLE_CLOUD_LOCATION=global
export VOXPEN_GATEWAY_TOKEN=choose-a-long-random-token
uvicorn app:app --host 127.0.0.1 --port 8787
```

The gateway host must have ADC configured by its normal deployment mechanism.
Do not put a service-account JSON, Google private key, or token in this
repository, the APK, screenshots, or documentation.

## Endpoints

- `GET /health` returns a non-secret status, model, and location.
- `POST /v1/chat/completions` requires the gateway Bearer token and accepts
  `model`, `messages`, `max_tokens` (1–4096), and optional
  `reasoning_effort`.

The gateway forwards only those allow-listed fields to Vertex using model
`google/gemini-3.7-flash`. It intentionally drops `temperature` and other
provider-specific generation controls. Android sends `reasoning_effort=low`
and `max_tokens=4096`.

In VoxPen Settings, select **Google Vertex**, enter this gateway's `/v1`
base URL and the same gateway token. The token is stored using the Android
app's encrypted provider-key storage; Google credentials never enter the app.

## Android behavior and privacy boundary

The IME records context only after `commitText()` returns success. It keeps
the latest five committed strings per `EditorInfo.packageName` in local
DataStore JSON. Password and secure numeric input types neither read nor
write this context. Commands, blank results, failed commits, and edit
instructions are excluded. If the gateway, Vertex request, or response fails,
the Android refinement flow retains the current raw transcription and can
insert it once; it does not send the message automatically.
