package com.voxpen.app.data.model

/** Context supplied to refinement as reference data, never as executable instructions. */
data class RefinementContext(
    val importantTerms: List<String> = emptyList(),
    val relevantTerms: List<String> = emptyList(),
    val recentContext: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = importantTerms.isEmpty() && relevantTerms.isEmpty() && recentContext.isEmpty()
}
