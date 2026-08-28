# Third-party input dictionaries

VoxPen's hybrid text input engine does not bundle a proprietary Boshiamy code table.
Users may import a `.cin` table that they are legally entitled to use. The imported table
is stored only in the app's local Room database.

The optional full Pinyin dictionary installer downloads `luna_pinyin.dict.yaml` from the
Rime `rime-luna-pinyin` project at pinned commit
`56b934b099dfbeab842320f13aa8b461a6ab3e42`.

- Project: https://github.com/rime/rime-luna-pinyin
- Dictionary license: LGPL-3.0 (see the upstream repository for complete terms)
- Rime/librime engine code is not copied into this feature.

Baidu custom dictionary import in this version accepts text exports (TXT/CSV/TSV). VoxPen
does not include or redistribute Baidu dictionaries and does not attempt to decode
proprietary binary `.dat`/`.bcd` backups.
