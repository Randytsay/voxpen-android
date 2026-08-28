package com.voxpen.app.util

import com.voxpen.app.data.model.SttLanguage

object ChirpLanguageMapper {
    const val DEFAULT_REGION = "us"

    private val localeByLanguage =
        mapOf(
            SttLanguage.Auto to "auto",
            SttLanguage.Chinese to "cmn-Hant-TW",
            SttLanguage.English to "en-US",
            SttLanguage.Japanese to "ja-JP",
            SttLanguage.Korean to "ko-KR",
            SttLanguage.French to "fr-FR",
            SttLanguage.German to "de-DE",
            SttLanguage.Spanish to "es-ES",
            SttLanguage.Vietnamese to "vi-VN",
            SttLanguage.Indonesian to "id-ID",
            SttLanguage.Thai to "th-TH",
        )

    fun map(language: SttLanguage): String =
        requireNotNull(localeByLanguage[language]) {
            "Chirp 3 does not support the selected language"
        }

    fun isSupported(language: SttLanguage): Boolean = localeByLanguage.containsKey(language)
}
