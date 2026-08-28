package com.voxpen.app.util

data class ChirpAdaptation(
    val phrases: List<String>,
)

object ChirpAdaptationBuilder {
    const val MAX_PHRASES = 200

    fun build(
        importantTerms: List<String>,
        ordinaryTerms: List<String>,
        maxPhrases: Int = MAX_PHRASES,
    ): ChirpAdaptation {
        require(maxPhrases > 0) { "maxPhrases must be positive" }
        val important = importantTerms.cleanedDistinct()
        val ordinary = ordinaryTerms.cleanedDistinct()
        return ChirpAdaptation(
            phrases = (important + ordinary).distinct().take(maxPhrases),
        )
    }

    private fun List<String>.cleanedDistinct(): List<String> =
        map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
}
