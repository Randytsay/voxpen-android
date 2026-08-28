package com.voxpen.app.ime

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CorrectionEditObserverTest {
    @Test
    fun repeatedSelectionCallbacksDoNotDuplicateCandidate() {
        val observer = observerFor("今天帶師兄會來")

        val first = observer.observe("今天戴師兄會來", "com.example.chat")
        val second = observer.observe("今天戴師兄會來", "com.example.chat")

        assertThat(first).isInstanceOf(CorrectionEditObserver.Observation.CandidateDetected::class.java)
        assertThat(second).isEqualTo(CorrectionEditObserver.Observation.Unchanged)
    }

    @Test
    fun punctuationEditKeepsSnapshotForLaterCorrection() {
        val observer = observerFor("今天帶師兄會來")

        assertThat(observer.observe("今天帶師兄會來！", "com.example.chat"))
            .isEqualTo(CorrectionEditObserver.Observation.Ignored)
        val result = observer.observe("今天戴師兄會來！", "com.example.chat")

        assertThat(result).isInstanceOf(CorrectionEditObserver.Observation.CandidateDetected::class.java)
    }

    @Test
    fun twoCorrectionsInOneUtteranceCanBeLearned() {
        val observer = observerFor("今天帶師兄要去都率天")

        val first = observer.observe("今天戴師兄要去都率天", "com.example.chat")
        val second = observer.observe("今天戴師兄要去兜率天", "com.example.chat")

        assertThat(first).isInstanceOf(CorrectionEditObserver.Observation.CandidateDetected::class.java)
        assertThat(second).isInstanceOf(CorrectionEditObserver.Observation.CandidateDetected::class.java)
        assertThat((first as CorrectionEditObserver.Observation.CandidateDetected).candidate.wrongText)
            .contains("帶師兄")
        assertThat((second as CorrectionEditObserver.Observation.CandidateDetected).candidate.wrongText)
            .contains("都率天")
    }

    @Test
    fun broadRewriteAndNumberEditAreRejected() {
        val broad = observerFor("我明天去台北")
        val numeric = observerFor("今天3點")

        assertThat(broad.observe("我後天下午直接去高雄開會", "com.example.chat"))
            .isEqualTo(CorrectionEditObserver.Observation.Cleared(CorrectionEditObserver.ClearReason.BROAD_REWRITE))
        assertThat(numeric.observe("今天4點", "com.example.chat"))
            .isEqualTo(CorrectionEditObserver.Observation.Cleared(CorrectionEditObserver.ClearReason.NUMBER_EDIT))
    }

    @Test
    fun packageChangeClearsPendingState() {
        val observer = observerFor("今天帶師兄會來")

        val result = observer.observe("今天戴師兄會來", "com.example.mail")

        assertThat(result).isEqualTo(
            CorrectionEditObserver.Observation.Cleared(
                CorrectionEditObserver.ClearReason.PACKAGE_CHANGED,
            ),
        )
        assertThat(observer.currentSnapshot).isNull()
    }

    private fun observerFor(text: String): CorrectionEditObserver =
        CorrectionEditObserver().also {
            it.onCommitted(
                baselineText = text,
                committedStart = 2,
                committedEnd = text.length,
                packageName = "com.example.chat",
                inputType = 1,
            )
        }
}
