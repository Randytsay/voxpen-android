<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.webp" width="100" alt="VoxPen logo" />
</p>

<h1 align="center">VoxPen (語墨)</h1>

<p align="center">
  Open-source AI voice keyboard for Android.<br/>
  Speak naturally — get polished text instantly.
</p>

<p align="center">
  <a href="https://github.com/Randytsay/voxpen-android/releases"><img src="https://img.shields.io/github/v/release/Randytsay/voxpen-android?style=flat-square" alt="Release" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/Randytsay/voxpen-android?style=flat-square" alt="License" /></a>
  <a href="https://github.com/Randytsay/voxpen-android/actions"><img src="https://img.shields.io/github/actions/workflow/status/Randytsay/voxpen-android/ci.yml?style=flat-square&label=CI" alt="CI" /></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-green?style=flat-square" alt="Min SDK 26" />
</p>

<p align="center">
  <a href="https://voxpen.app">Website</a> &nbsp;|&nbsp;
  <a href="README.zh-TW.md">繁體中文</a>
</p>

---

## Screenshots

<p align="center">
  <img src="docs/screenshots/home.png" width="180" alt="Home screen" />
  &nbsp;&nbsp;
  <img src="docs/screenshots/keyboard.png" width="180" alt="Keyboard" />
  &nbsp;&nbsp;
  <img src="docs/screenshots/keyboard-tone.png" width="180" alt="Tone selector" />
  &nbsp;&nbsp;
  <img src="docs/screenshots/settings-llm.png" width="180" alt="Settings - LLM" />
  &nbsp;&nbsp;
  <img src="docs/screenshots/settings-tone.png" width="180" alt="Settings - Tone" />
</p>

---

## What is VoxPen?

VoxPen is an Android voice keyboard that transcribes your speech with Whisper, refines it with an LLM, and inserts clean text into any app. It runs on a **BYOK (Bring Your Own Key)** model — you use your own API keys and pay only for what you use. No subscription, no telemetry, fully open-source.

## Features

### Voice Dictation
Tap the mic and speak. VoxPen records 16 kHz mono 16-bit PCM, uploads 60-second chunks to Whisper, optionally refines with an LLM, and shows the result in the candidate bar. Each recording can last up to 10 minutes. By default, tap a candidate to insert it; you can enable Automatic Result Insertion to insert the final result automatically.

### Automatic Result Insertion
Enable **Settings → Automatic Result Insertion** to place completed recognition text directly into the active text field:

- Without refinement, the original `Result` text is inserted automatically.
- With refinement enabled, VoxPen waits during `Refining` and inserts the final `Refined` text.
- If refinement fails, the original recognized text is inserted once as a fallback.
- Voice commands, edit instructions, and error messages are never auto-inserted.
- This feature only inserts text; it never presses Enter or sends a message automatically.

When the setting is off, the existing candidate-bar workflow remains unchanged.

### Custom Vocabulary
Add names, places, and specialized terms in the Dictionary. Star important entries to prioritize them in recognition and LLM refinement prompts. Whisper receives important terms first within its approximately 200-token hint budget; the LLM receives separate important and relevant-term sections plus up to five recent committed inputs for the current app. Free users can store up to 10 dictionary entries, while Pro builds support unlimited entries.

### Context Memory
After a successful text commit, VoxPen keeps up to five short entries per target app locally in DataStore. Context is isolated by package name, is used only as reference for the next refinement, and is excluded for password input fields. Failed, blank, command, and edit-instruction flows are not stored.

### Chirp 3 Streaming ASR
Select **Google Chirp 3 Streaming** to see interim recognition while speaking. The IME sends 16 kHz mono PCM16 frames to the authenticated Speech-to-Text V2 gateway; interim text is preview-only and is never inserted, sent to Gemini, or written to correction memory. Final text continues through the existing correction-memory, context-memory, Gemini refinement, and Auto Insert pipeline. The gateway reconnects with a bounded replay buffer and rolls over before the five-minute streaming limit; an optional setting can retry the locally retained recording through Groq when streaming fails.

### Personal Correction Memory
After VoxPen inserts a result, the IME observes only subsequent changes in that active editor. A 700 ms debounce learns conservative local corrections immediately, so the user does not need to start another recording. Selection-only changes, punctuation/formatting-only changes, numeric edits, broad rewrites, package changes, and sensitive fields do not create rules. Multiple corrections in one committed utterance can be learned independently, while existing app scope and manual correction levels remain unchanged.

### Google Vertex Gemini
The Android app supports **Google Vertex** through the private gateway in [`vertex-gateway/`](vertex-gateway/). Select Google Vertex in Settings, enter your gateway `/v1` URL and gateway token, and keep Google ADC credentials on the gateway host. The app sends model `google/gemini-3.7-flash`, `reasoning_effort=low`, and `max_tokens=4096`; it does not contain a service-account key or Google credential.

### Translation Mode
Speak in one language, output in another. Quick-switch target languages directly from the keyboard — no need to open Settings.

### Speak to Edit
Select text in any app, switch to VoxPen, enable Edit Mode, and speak your instruction (e.g., "make it more formal"). The LLM rewrites the selection in place.

### Auto Tone
VoxPen detects the active app and auto-selects the appropriate writing style — casual for messaging, formal for email, professional for Slack. Customizable per-app rules.

### Voice Commands
10 trilingual commands (zh-TW / en / ja) — send, delete, newline, space, undo, select all, copy, paste, cut, clear all. No API call needed.

### Audio File Transcription
Transcribe audio/video files with progress tracking. Export as TXT or SRT subtitles.

### Privacy-First
- **BYOK**: Audio goes directly from your device to your selected provider; Vertex calls go through your configured private gateway
- **No telemetry**, no analytics, no user accounts
- API keys encrypted with Android Keystore
- Only 2 permissions: `INTERNET` + `RECORD_AUDIO`

## Personal Build and Free Limits

This personal fork keeps a **Personal Build** mode for debug APKs: debug builds resolve to the personal Pro status for local testing, while release builds retain the normal licensing flow.

For non-Pro users, the daily limits are:

| Usage | Free limit |
|-------|------------|
| Voice inputs | 30 per day |
| LLM refinements | 10 per day |
| Audio/video file transcriptions | 2 per day |
| Custom vocabulary entries | 10 total |

Personal/Pro builds are not subject to these free-tier limits.

## Keyboard Layout

```
┌──────────────────────────────────────┐
│  🔄 說中文 → English            [×] │  ← translation indicator
│  🔵 Original: [raw transcription]    │  ← tap to insert
│  ✨ Refined:  [polished text]        │  ← tap to insert
├──────────────────────────────────────┤
│  🌐  │  ⌫  │    🎤    │  ⏎  │  ⚙️  │
└──────────────────────────────────────┘
```

| Button | Tap | Long-press |
|--------|-----|------------|
| 🌐 | Previous keyboard | System IME picker |
| ⌫ | Backspace | — |
| 🎤 | Start/stop recording | — |
| ⏎ | Enter | — |
| ⚙️ | Open Settings | Quick settings (language / refinement / translation / edit mode) |

## Supported Languages

| Language | STT | LLM Refinement | Translation |
|----------|-----|----------------|-------------|
| 中文（繁體） | Whisper | Dedicated prompt | Target/source |
| English | Whisper | Dedicated prompt | Target/source |
| 日本語 | Whisper | Dedicated prompt | Target/source |
| Auto-detect | Whisper | Mixed-language prompt | — |

Whisper supports 99 languages for STT. VoxPen currently exposes 3 + auto-detect with dedicated refinement prompts.

## Supported Providers

| Provider | STT | LLM | Notes |
|----------|-----|-----|-------|
| **Groq** | Whisper large-v3 (default; Turbo remains selectable) | LLaMA, Qwen, etc. | Free tier available |
| **OpenAI** | Whisper, GPT-4o transcribe | GPT-4o, etc. | |
| **Google Vertex** | — | Gemini 3.7 Flash via private gateway | ADC credentials stay on gateway |
| **Google Chirp 3** | Streaming ASR via Speech-to-Text V2 gateway | — | Shares the Vertex gateway URL/token; interim preview is not committed |
| **Custom** | Any Whisper-compatible endpoint | Any OpenAI-compatible endpoint | Self-hosted support |

## Getting Started

### Install from Release

1. Download the latest APK from [Releases](https://github.com/Randytsay/voxpen-android/releases)
2. Install on your Android device (8.0+)
3. Follow the onboarding wizard to set up your API key

### Build from Source

**Prerequisites**: Android Studio Ladybug+ / JDK 17

```bash
git clone https://github.com/Randytsay/voxpen-android.git
cd voxpen-android
./gradlew assembleDebug
```

The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

### Setup

1. Get a free Groq API key at [console.groq.com](https://console.groq.com)
2. Open VoxPen → enter your API key
3. Enable VoxPen Voice in **Settings → System → Keyboard**
4. Switch to VoxPen in any text field and start speaking

### Vertex gateway setup

See [`vertex-gateway/README.md`](vertex-gateway/README.md) for the complete Android → gateway → Vertex flow. Configure the gateway with environment variables and ADC on the server, then enter only the gateway URL and token in Android. Never commit `.env` files, service-account JSON, private keys, or tokens.

## Architecture

```
┌─────────────┐
│   IME Layer  │  VoxPenIME (InputMethodService)
│              │  AudioRecorder, KeyboardView, CandidateView
├──────────────┤
│  Domain      │  TranscribeAudioUseCase, RefineTextUseCase, EditTextUseCase
├──────────────┤
│  Data        │  SttRepository, LlmRepository, SettingsRepository
│              │  Retrofit APIs, Room DB, DataStore
├──────────────┤
│  DI          │  Hilt modules (AppModule, NetworkModule)
└──────────────┘
```

- **Language**: Kotlin
- **UI**: Jetpack Compose + Material 3
- **DI**: Hilt
- **Async**: Coroutines + Flow
- **Network**: Retrofit + OkHttp
- **Storage**: DataStore (preferences) + Room (history)
- **Testing**: JUnit 5 + MockK + Turbine

## Contributing

Contributions are welcome! Here's how:

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/my-feature`)
3. Make your changes
4. Run tests: `./gradlew test`
5. Run lint: `./gradlew ktlintCheck detekt`
6. Commit with conventional commits (`feat:`, `fix:`, `refactor:`, etc.)
7. Open a Pull Request

### Development Notes

- The project follows TDD (Test-Driven Development) — write tests first
- Run `./gradlew test` before submitting PRs
- IME testing requires a physical device or emulator with keyboard enabled
- See [CLAUDE.md](CLAUDE.md) for detailed architecture documentation

## License

```
Copyright 2026 VoxPen Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

VoxPen is forked from [Dictate Keyboard](https://github.com/DevEmperor/Dictate) by DevEmperor (Apache 2.0). It has been fully rewritten in Kotlin with a new architecture.

## Privacy

VoxPen uses a BYOK model. Your audio is sent directly from your device to the API provider you choose. We never see your data. See the full [Privacy Policy](docs/privacy-policy.md).
