# Changelog

## 1.2.4 — 2026-09-26

### Added

- Mixed Boshiamy and Pinyin input in one keyboard, backed by the bundled Traditional Chinese VanguardLexicon data used by MixType.
- Local first-launch dictionary import with resumable progress, separate Boshiamy/Pinyin lookup, Pinyin syllable segmentation, initials lookup, and candidate ranking.
- Personal dictionary management for adding, editing, searching, and deleting words with Pinyin and initials (for example, `耀文 / yao wen` searchable as `yw`).
- Persistent word learning and single-character preference ranking after repeated confirmed selections.
- Clipboard history, pinned common phrases/symbols, and an in-keyboard editor.
- Caps Lock in Chinese input mode: Shift toggles direct uppercase English output; the key icon fills while enabled.

### Improved

- Candidate ordering prioritizes exact Boshiamy codes, then direct Pinyin matches, personal entries, frequency, and learned preferences; partial Boshiamy matches remain available after direct matches.
- Keyboard proportions: increased key-row height, enlarged Enter icon, wider spacebar, relocated comma, and symbol access within the `123` key.
- Edit overlay proportions and cursor controls, including the visible outer cursor ring and dedicated selection actions.
- Chinese punctuation normalization for recognized/refined Chinese text while preserving common numeric and identifier patterns.
- Database schema and import paths for the bundled lexicon, personal words, and clipboard records.

### Notes

- The bundled dictionary files retain their upstream contents and notices. See [`app/src/main/assets/licenses/mixtype-vanguard/ASSET-NOTICE.md`](app/src/main/assets/licenses/mixtype-vanguard/ASSET-NOTICE.md) and the included license/readme files.
- This release uses VoxPen's existing speech recognition providers, including Groq; the dictionary and keyboard changes do not require a Groq API key. Speech recognition itself still requires the selected provider's configuration.
- Samsung S26 installation and on-device typing/voice acceptance remain separate from source/build verification and must be confirmed on the device.
