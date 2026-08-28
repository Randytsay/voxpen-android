<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.webp" width="100" alt="VoxPen logo" />
</p>

<h1 align="center">VoxPen (語墨)</h1>

<p align="center">
  開源 AI 語音鍵盤，專為 Android 打造。<br/>
  自然說話，即時取得精修文字。
</p>

<p align="center">
  <a href="https://github.com/Randytsay/voxpen-android/releases"><img src="https://img.shields.io/github/v/release/Randytsay/voxpen-android?style=flat-square" alt="Release" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/Randytsay/voxpen-android?style=flat-square" alt="License" /></a>
  <a href="https://github.com/Randytsay/voxpen-android/actions"><img src="https://img.shields.io/github/actions/workflow/status/Randytsay/voxpen-android/ci.yml?style=flat-square&label=CI" alt="CI" /></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-green?style=flat-square" alt="Min SDK 26" />
</p>

<p align="center">
  <a href="https://voxpen.app">Website</a> &nbsp;|&nbsp;
  <a href="README.md">English</a>
</p>

---

## 截圖

<p align="center">
  <img src="docs/screenshots/home.png" width="180" alt="主畫面" />
  &nbsp;&nbsp;
  <img src="docs/screenshots/keyboard.png" width="180" alt="鍵盤" />
  &nbsp;&nbsp;
  <img src="docs/screenshots/keyboard-tone.png" width="180" alt="語氣選擇" />
  &nbsp;&nbsp;
  <img src="docs/screenshots/settings-llm.png" width="180" alt="設定 - LLM" />
  &nbsp;&nbsp;
  <img src="docs/screenshots/settings-tone.png" width="180" alt="設定 - 語氣" />
</p>

---

## VoxPen 是什麼？

VoxPen 是一款 Android 語音鍵盤，透過 Whisper 將語音轉為文字，再以 LLM 潤稿，將乾淨的文字插入任何 App。採用 **BYOK（自帶金鑰）** 模式 — 使用你自己的 API 金鑰，用多少付多少。無需訂閱、無遙測、完全開源。

## 功能特色

### 語音聽寫
點擊麥克風說話，VoxPen 以 16 kHz、單聲道、16-bit PCM 錄音，每 60 秒分段上傳 Whisper，再以 LLM 潤稿，並在候選列顯示辨識結果。每次錄音最長 10 分鐘。預設需點擊候選文字才會插入；也可以開啟「辨識結果自動插入」。

### 辨識結果自動插入
至 **設定 → 辨識結果自動插入** 開啟後，完成辨識的文字會直接放入目前使用中的文字欄位：

- 未啟用潤稿時，自動插入原始 `Result` 辨識文字。
- 啟用潤稿時，`Refining` 階段不插入，等待完成後自動插入 `Refined` 最終文字。
- 潤稿失敗時，回退並只自動插入一次原始辨識文字。
- 語音指令、編輯指令與錯誤訊息不會被自動插入。
- 只會插入文字，不會自動按 Enter 或送出訊息。

關閉此設定時，維持原本的候選列操作方式不變。

### 自定義詞彙
可在「字典」加入人名、地名與專有名詞。標記為重要詞彙後，會優先提供給語音辨識與 LLM 潤稿。Whisper 會在約 200 token 提示預算內優先放入重要詞；LLM 則分開收到重要詞、相關詞，以及目前 App 最近 5 筆成功插入文字。Free 使用者最多可儲存 10 個詞彙，Pro 版本則支援無限詞彙。

### 上下文記憶
只有文字確實插入成功後，VoxPen 才會在本機 DataStore 為目標 App 保留最多 5 筆短文字。上下文依 App package 隔離，只作下一次潤稿的參考；密碼輸入欄位不讀取也不儲存。失敗、空白、語音指令與編輯指令都不會寫入記憶。

### Chirp 3 串流語音辨識
選擇 **Google Chirp 3 Streaming** 後，說話時會在鍵盤候選列看到 interim 即時預覽。IME 以 16 kHz、單聲道、PCM16 frame 傳送到已驗證的 Speech-to-Text V2 gateway；interim 只作預覽，不會插入文字、不會送進 Gemini，也不會寫入修正記憶。final 文字仍沿用既有的修正記憶、上下文記憶、Gemini 潤稿與自動插入流程。Gateway 會以有限 replay buffer 處理重連，並在五分鐘串流上限前 rollover；若串流失敗，也可在設定中選擇用本機保留錄音回退 Groq。

### 個人修正記憶
VoxPen 插入辨識結果後，只觀察目前 App 編輯器中接續發生的文字變更。700ms debounce 會即時學習保守的區域修正，因此不需要再按一次麥克風。只有移動游標、純標點/格式變更、數字變更、大幅改寫、切換 App 或敏感欄位不會建立規則；同一段插入文字中的多個修正可以分別學習，既有 App scope 與手動修正等級維持不變。

### Google Vertex Gemini
Android 支援透過 [`vertex-gateway/`](vertex-gateway/) 使用 **Google Vertex**。在設定選擇 Google Vertex，輸入私人 gateway 的 `/v1` 網址與 gateway token；Google ADC 憑證只放在 gateway 主機。App 使用 `google/gemini-3.7-flash`、`reasoning_effort=low` 與 `max_tokens=4096`，不會包含 service-account key 或 Google 憑證。

### 翻譯模式
說 A 語言，輸出 B 語言。直接在鍵盤上快速切換翻譯目標語言，無需進入設定。

### 語音編輯
在任何 App 中選取文字，切換到 VoxPen，啟用編輯模式，說出你的指令（例如「讓它更正式」）。LLM 會直接改寫選取的文字。

### 自動語氣
VoxPen 偵測目前使用的 App，自動選擇適合的寫作風格 — 通訊軟體用口語、Email 用正式語氣、Slack 用專業語氣。支援自訂 App 規則。

### 語音指令
10 個三語指令（中/英/日）— 送出、刪除、換行、空格、復原、全選、複製、貼上、剪下、全部刪除。不需呼叫 API。

### 音檔轉錄
匯入音訊/影片檔案進行轉錄，支援進度追蹤。可匯出為 TXT 純文字或 SRT 字幕檔。

### 隱私優先
- **BYOK**：音訊從你的裝置直接傳送至所選服務商；Vertex 則經由你設定的私人 gateway
- **無遙測、無分析、無使用者帳號**
- API 金鑰以 Android Keystore 加密儲存
- 僅需 2 個權限：`INTERNET` + `RECORD_AUDIO`

## Personal Build 與免費額度

這個個人版本保留 **Personal Build** 模式：Debug APK 會以 Personal Pro 狀態進行本機測試；Release APK 則維持原本的授權流程。

非 Pro 使用者的每日限制如下：

| 使用項目 | 免費額度 |
|----------|----------|
| 語音輸入 | 每日 30 次 |
| LLM 潤稿 | 每日 10 次 |
| 音訊/影片檔案轉錄 | 每日 2 次 |
| 自定義詞彙 | 共 10 個 |

Personal/Pro 版本不受上述免費額度限制。

## 鍵盤配置

```
┌──────────────────────────────────────┐
│  🔄 說中文 → English            [×] │  ← 翻譯指示列
│  🔵 原文：[語音辨識結果]             │  ← 點擊插入
│  ✨ 潤稿：[精修文字]                 │  ← 點擊插入
├──────────────────────────────────────┤
│  🌐  │  ⌫  │    🎤    │  ⏎  │  ⚙️  │
└──────────────────────────────────────┘
```

| 按鍵 | 點擊 | 長按 |
|------|------|------|
| 🌐 | 切換上一個鍵盤 | 系統輸入法選擇器 |
| ⌫ | 退格 | — |
| 🎤 | 開始/停止錄音 | — |
| ⏎ | Enter | — |
| ⚙️ | 開啟設定 | 快速設定（語言/潤稿/翻譯/編輯模式） |

## 支援語言

| 語言 | 語音轉文字 | LLM 潤稿 | 翻譯 |
|------|-----------|---------|------|
| 中文（繁體） | Whisper | 專屬提示詞 | 目標/來源 |
| English | Whisper | 專屬提示詞 | 目標/來源 |
| 日本語 | Whisper | 專屬提示詞 | 目標/來源 |
| 自動偵測 | Whisper | 混合語言提示詞 | — |

Whisper 支援 99 種語言的語音轉文字。VoxPen 目前提供 3 種語言 + 自動偵測，並附有專屬潤稿提示詞。

## 支援的 API 服務商

| 服務商 | 語音轉文字 | LLM | 備註 |
|--------|-----------|-----|------|
| **Groq** | Whisper large-v3（預設；仍可選 Turbo） | LLaMA、Qwen 等 | 有免費額度 |
| **OpenAI** | Whisper、GPT-4o transcribe | GPT-4o 等 | |
| **Google Vertex** | — | Gemini 3.7 Flash（經私人 gateway） | ADC 憑證留在 gateway |
| **Google Chirp 3** | Speech-to-Text V2 串流（經 gateway） | — | 與 Vertex 共用 gateway 網址/token；interim 不會插入 |
| **自訂** | 任何 Whisper 相容端點 | 任何 OpenAI 相容端點 | 支援自架伺服器 |

## 開始使用

### 從 Release 安裝

1. 從 [Releases](https://github.com/Randytsay/voxpen-android/releases) 下載最新 APK
2. 安裝至你的 Android 裝置（8.0 以上）
3. 依照引導精靈設定 API 金鑰

### 從原始碼建置

**前置需求**：Android Studio Ladybug+ / JDK 17

```bash
git clone https://github.com/Randytsay/voxpen-android.git
cd voxpen-android
./gradlew assembleDebug
```

Debug APK 位於 `app/build/outputs/apk/debug/app-debug.apk`。

### 設定步驟

1. 至 [console.groq.com](https://console.groq.com) 免費取得 Groq API 金鑰
2. 開啟 VoxPen → 輸入 API 金鑰
3. 至 **設定 → 系統 → 鍵盤** 啟用 VoxPen Voice
4. 在任何文字欄位切換至 VoxPen，開始說話

### Vertex gateway 設定

完整的 Android → gateway → Vertex 流程請見 [`vertex-gateway/README.md`](vertex-gateway/README.md)。在伺服器以環境變數與 ADC 設定 gateway，再只把 gateway 網址與 token 輸入 Android。請勿提交 `.env`、service-account JSON、私鑰或 token。

## 架構

```
┌─────────────┐
│   IME 層     │  VoxPenIME (InputMethodService)
│              │  AudioRecorder, KeyboardView, CandidateView
├──────────────┤
│  Domain 層   │  TranscribeAudioUseCase, RefineTextUseCase, EditTextUseCase
├──────────────┤
│  Data 層     │  SttRepository, LlmRepository, SettingsRepository
│              │  Retrofit APIs, Room DB, DataStore
├──────────────┤
│  DI          │  Hilt modules (AppModule, NetworkModule)
└──────────────┘
```

- **語言**：Kotlin
- **UI**：Jetpack Compose + Material 3
- **依賴注入**：Hilt
- **非同步**：Coroutines + Flow
- **網路**：Retrofit + OkHttp
- **儲存**：DataStore（偏好設定）+ Room（歷史紀錄）
- **測試**：JUnit 5 + MockK + Turbine

## 貢獻

歡迎貢獻！步驟如下：

1. Fork 此儲存庫
2. 建立功能分支（`git checkout -b feature/my-feature`）
3. 進行修改
4. 執行測試：`./gradlew test`
5. 執行 lint 檢查：`./gradlew ktlintCheck detekt`
6. 使用 conventional commits 提交（`feat:`、`fix:`、`refactor:` 等）
7. 開啟 Pull Request

### 開發注意事項

- 專案遵循 TDD（測試驅動開發）— 先寫測試
- 提交 PR 前請執行 `./gradlew test`
- IME 測試需要實體裝置或已啟用鍵盤的模擬器
- 詳細架構文件請參閱 [CLAUDE.md](CLAUDE.md)

## 授權條款

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

VoxPen 是從 [Dictate Keyboard](https://github.com/DevEmperor/Dictate)（DevEmperor 開發，Apache 2.0 授權）fork 而來，已全面改寫為 Kotlin 並採用新架構。

## 隱私權

VoxPen 採用 BYOK 模式。你的音訊從裝置直接傳送至你選擇的 API 服務商，我們絕不會接觸你的資料。完整 [隱私權政策](docs/privacy-policy.md)。
