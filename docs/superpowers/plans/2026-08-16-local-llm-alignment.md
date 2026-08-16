# Local LLM Alignment (Tailscale / LiteLLM) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Align Android's local-LLM support with voxpen-desktop: connect to the OpenAI-compatible LiteLLM proxy on the NVIDIA Spark host (`http://100.102.183.27:4000`, Tailscale) with model `qwen36-fast`, including keyless-local support, base-URL normalization, and a "test provider" button, verified on the physical device.

**Architecture:** Desktop (`voxpen-desktop`, Tauri/Rust) is the spec source. Android already has the Custom provider, encrypted base-URL storage, and IME wiring; this plan fills the gaps found in the audit below. No new provider types — the existing `LlmProvider.Custom` becomes fully usable for tailnet/LAN endpoints.

**Tech Stack:** Kotlin, Retrofit/OkHttp (existing `ChatCompletionApi` at `v1/chat/completions`), DataStore + EncryptedSharedPreferences (existing), Jetpack Compose settings UI, JUnit5 + MockK + MockWebServer + Truth.

## Contract (desktop ↔ android)

| Aspect | Desktop (verified in code/settings) | Android target |
|---|---|---|
| Custom provider label | "Custom / LiteLLM / Ollama" | same string (en + zh-TW) |
| Base URL placeholder | `http://localhost:11434/` | same |
| Base URL hint | "API endpoint URL (e.g., LiteLLM, Ollama, or another OpenAI-compatible server)" | same |
| URL normalization | `api_url()` in `voxpen-core/src/api/groq.rs`: trims, strips trailing `/`, de-dups `/v1` suffix | `ChatCompletionApiFactory.normalizeBaseUrl()` |
| Keyless local servers | `is_keyless_provider` → empty key proceeds, sends no-op `Bearer ` header | allow blank key when `provider == Custom` |
| Test provider | `test_refinement_provider` command: short refine call, reports result text | Settings "Test provider" button via `LlmRepository.editText` |
| HTTP cleartext to tailnet | N/A (desktop OS allows http) | `network_security_config.xml` base-config cleartext (Tailscale = WireGuard-encrypted overlay; domain-config cannot match IPs/CIDR) |
| Timeouts | 30s connect / 60s read | already 30/60/60 in `NetworkModule` |

Real endpoint (from desktop `settings.json`): `custom_base_url = http://100.102.183.27:4000`, `refinement_model = qwen36-fast`, provider key `sk-…` (LiteLLM). Device `seeker` (100.90.231.54) is on the tailnet; device→Spark HTTP 200 verified via `adb shell nc`.

## Gap audit result (already done)

- G1 **Blocker**: `AndroidManifest.xml` has no cleartext allowance → `http://100.102.183.27:4000` is blocked by OkHttp on API 28+.
- G2: `LlmRepository.refine`/`editText` fail fast on blank API key → keyless local servers (Ollama/llama.cpp) unusable; desktop allows.
- G3: `ChatCompletionApiFactory.buildApi` does no normalization → `http://host:4000` (no trailing slash) throws `IllegalArgumentException` in Retrofit; `http://host:4000/v1` would double the path to `/v1/v1/chat/completions`. Desktop handles both.
- G4: no "test provider" feature in Android settings.
- G5: provider label/placeholder/hint copy not aligned.
- Already aligned (no work): Custom provider model, encrypted base URL storage, IME/file-transcription wiring of `customBaseUrl`, `Bearer` per-call auth, speech-tag prompt injection guard, `<think>` stripping (`qwen36-fast` matches the existing `qwen3` reasoning-hidden rule), timeouts.

## Global Constraints

- Target SDK 35; min SDK 26. Kotlin style per repo CLAUDE.md; tests named with backtick sentences; Truth assertions; JUnit5.
- Never weaken or delete existing tests to make checks pass. Red → green only.
- Small commits, conventional messages (`feat:`, `fix:`, `docs:`, `test:`), matching repo history.
- Verification commands: `./gradlew :app:testDebugUnitTest`, `./gradlew :app:assembleDebug`, `./gradlew detekt`, `./gradlew :app:lintDebug`.

---

### Task 1: Allow cleartext HTTP to tailnet/LAN endpoints

**Files:**
- Create: `app/src/main/res/xml/network_security_config.xml`
- Modify: `app/src/main/AndroidManifest.xml` (application tag)

**Interfaces:** none (manifest-level).

- [ ] Create `network_security_config.xml` with `base-config cleartextTrafficPermitted="true"` and a comment explaining the Tailscale rationale (WireGuard overlay; domain-config cannot match raw IPs).
- [ ] Reference it from the `<application>` tag: `android:networkSecurityConfig="@xml/network_security_config"`.
- [ ] Verify: `./gradlew :app:processDebugMainManifest` succeeds; `./gradlew :app:lintDebug` reports no new errors.
- [ ] Commit: `feat: allow cleartext HTTP for tailnet/LAN LLM endpoints`.

### Task 2: Normalize custom base URLs (TDD)

**Files:**
- Modify: `app/src/main/java/com/voxpen/app/data/remote/ChatCompletionApiFactory.kt`
- Test: `app/src/test/java/com/voxpen/app/data/remote/ChatCompletionApiFactoryTest.kt`

**Interfaces:**
- Produces: `ChatCompletionApiFactory.Companion.normalizeBaseUrl(raw: String): String` — trims; strips one trailing `/v1` or `/v1/` suffix (Retrofit path is `v1/chat/completions`, mirroring desktop `api_url` de-dup); guarantees a single trailing `/`. `buildApi` applies it to every base URL (both `create` and `createForCustom`).

- [ ] Write failing tests: `http://100.102.183.27:4000` → `http://100.102.183.27:4000/`; `http://h:4000/v1` → `http://h:4000/`; `http://h:4000/v1/` → `http://h:4000/`; `http://h:11434/` unchanged; ` https://x ` trimmed.
- [ ] Run: `./gradlew :app:testDebugUnitTest --tests 'com.voxpen.app.data.remote.ChatCompletionApiFactoryTest'` → FAIL (no such function).
- [ ] Implement `normalizeBaseUrl` in a `companion object`; use it in `buildApi`. Cache key in `createForCustom` stays the raw string (instances still dedupe after normalization via same normalized key — normalize the cache key too).
- [ ] Re-run test class → PASS. Commit: `fix(llm): normalize custom base URLs (trailing slash, /v1 de-dup)`.

### Task 3: Support keyless local providers (TDD)

**Files:**
- Modify: `app/src/main/java/com/voxpen/app/data/repository/LlmRepository.kt`
- Test: `app/src/test/java/com/voxpen/app/data/repository/LlmRepositoryTest.kt`

**Interfaces:**
- Behavior change: `refine(...)` and `editText(...)` return failure on blank `apiKey` **unless** `provider == LlmProvider.Custom`. Keyless requests still send the `Authorization` header as `Bearer ` (no-op for local servers), matching desktop `bearer_auth` behavior.

- [ ] Write failing tests (MockWebServer): `refine` with `provider=Custom`, blank key, 200 response → success and recorded request has `Authorization: Bearer `; `editText` same; blank key + `provider=Groq` still fails fast.
- [ ] Run test class → FAIL.
- [ ] Implement: guard becomes `if (apiKey.isBlank() && provider != LlmProvider.Custom)`.
- [ ] Re-run → PASS; full `:app:testDebugUnitTest` green. Commit: `feat(llm): allow keyless custom providers for local servers`.

### Task 4: Settings "Test provider" + copy alignment (TDD)

**Files:**
- Modify: `app/src/main/java/com/voxpen/app/ui/settings/SettingsUiState.kt` (add `llmTestStatus`)
- Modify: `app/src/main/java/com/voxpen/app/ui/settings/SettingsViewModel.kt` (inject `LlmRepository`, add `testLlmProvider()`)
- Modify: `app/src/main/java/com/voxpen/app/ui/settings/SettingsScreen.kt` (button + status text + label/placeholder/hint)
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-zh-rTW/strings.xml`
- Test: `app/src/test/java/com/voxpen/app/ui/settings/SettingsViewModelTest.kt`

**Interfaces:**
- Produces: `sealed interface LlmTestStatus { Idle; Testing; Success(detail: String); Error(message: String) }` (nested in `SettingsUiState.kt` or same file), `SettingsUiState.llmTestStatus: LlmTestStatus = LlmTestStatus.Idle`, `SettingsViewModel.testLlmProvider(): Unit`.
- Strings (aligned with desktop `en.json`/`zh-TW.json`): `provider_test` = "Test provider" / 「測試供應商」; `provider_testing` = "Testing…" / 「測試中…」; `provider_test_ok` = "Provider works. %1$s" / 「供應商可用。%1$s」; `provider_test_failed` = "Failed: %1$s" / 「失敗：%1$s」; `provider_custom_base_url_hint` = desktop `baseUrlHint`; `provider_custom` label → "Custom / LiteLLM / Ollama" / 「自訂（LiteLLM / Ollama）」; placeholder `http://localhost:11434/`.

- [ ] Write failing VM tests (MockK `LlmRepository`): success → `Success` with reply text; repository failure → `Error` with message; Custom provider without base URL → `Error` without invoking repository.
- [ ] Implement status type + VM method (resolve model like `RecordingController`: Custom → `customLlmModel.ifBlank { llmModel }`; key from `apiKeyManager.getApiKey(provider).orEmpty()`; call `editText("Reply with exactly: ok", ...)`).
- [ ] UI: button under Custom provider fields; status `Text`; provider label/hint/placeholder updates.
- [ ] Tests green. Commit: `feat(settings): test-provider button and local LLM copy alignment`.

### Task 5: On-device verification

- [ ] `./gradlew :app:assembleDebug` → `adb install -r`.
- [ ] Settings → LLM Provider → Custom: base URL `http://100.102.183.27:4000`, model `qwen36-fast`, LiteLLM key → "Test provider" → success text; `adb exec-out screencap -p` + visual inspection.
- [ ] Repeat with trailing-slash and `/v1` URL variants (normalization proof) and with a bogus URL (error path).
- [ ] IME dictation end-to-end requires a human speaker at the mic; the settings test exercises the identical `LlmRepository.chatCompletion` path plus `RecordingController` unit tests cover the wiring — state this scope explicitly in the report.

### Task 6: Gate + code review

- [ ] `./gradlew :app:testDebugUnitTest detekt :app:lintDebug :app:assembleDebug` all green.
- [ ] `code-reviewer` subagent on the full diff; fix high-confidence findings; fix commits.
