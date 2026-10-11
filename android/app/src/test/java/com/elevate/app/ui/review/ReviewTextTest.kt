package com.elevate.app.ui.review

import com.elevate.app.domain.review.Verdict
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReviewTextTest {
    @Test fun `bold markers split into runs and a stray marker stays plain`() {
        assertThat(ReviewSteps.runs("Landed **12 cm inside** the back line."))
            .containsExactly("Landed " to false, "12 cm inside" to true, " the back line." to false).inOrder()
        assertThat(ReviewSteps.runs("**Too close to call:** 3 cm")).containsExactly("Too close to call:" to true, " 3 cm" to false).inOrder()
        assertThat(ReviewSteps.runs("no markers")).containsExactly("no markers" to false)
        assertThat(ReviewSteps.runs("one ** marker")).containsExactly("one ** marker" to false)
    }

    @Test fun `the three how-we-know steps say what the brief says`() {
        assertThat(ReviewSteps.corners("B")).isEqualTo("Your 4 corner taps turned the video into a measured court (quality grade B).")
        assertThat(ReviewSteps.corners(null)).isEqualTo("Your 4 corner taps turned the video into a measured court.")
        assertThat(ReviewSteps.FLIGHT).isEqualTo("We followed the shuttle's flight frame by frame to where it stopped.")
        assertThat(ReviewSteps.MEASURE).isEqualTo("We mapped that spot onto the court and measured the distance to the line, with an honest margin of error.")
        assertThat(ReviewSteps.SMALL_PRINT).isEqualTo("A model estimate. You make the final call.")
    }

    @Test fun `every explanation and line of copy is filled in and has no em dash`() {
        val all = ReviewExplanations.all.flatMap { listOf(it.title, it.text) } +
            listOf(ReviewSteps.corners("A"), ReviewSteps.FLIGHT, ReviewSteps.MEASURE, ReviewSteps.TITLE, ReviewSteps.CALL_SAVED, ReviewSteps.CALL_QUESTION)
        all.forEach {
            assertThat(it).isNotEmpty()
            assertThat(it).doesNotContain("—")
        }
        assertThat(ReviewExplanations.all.map { it.title }).containsNoDuplicates()
    }

    @Test fun `the saved answer is local, keyed by match and landing, and round trips`() {
        assertThat(ReviewCalls.key("m_1", "e_9")).isEqualTo("call:m_1:e_9")
        assertThat(ReviewCalls.key(null, "e_9")).isEqualTo("call:-:e_9")
        assertThat(YourCall.decode("agree")).isEqualTo(YourCall.Agree)
        assertThat(YourCall.decode("disagree")).isEqualTo(YourCall.Disagree)
        assertThat(YourCall.decode("official")).isNull()
        assertThat(YourCall.decode(null)).isNull()
    }

    @Test fun `beats skip what cannot be shown`() {
        assertThat(beatsFor(Verdict.Inside, hasVideo = true)).containsExactly(Beat.Moment, Beat.Line, Beat.Result).inOrder()
        assertThat(beatsFor(Verdict.TooClose, hasVideo = false)).containsExactly(Beat.Line, Beat.Result).inOrder()
        assertThat(beatsFor(Verdict.NotSeen, hasVideo = true)).containsExactly(Beat.Moment, Beat.Result).inOrder()
        assertThat(beatsFor(Verdict.NotSeen, hasVideo = false)).containsExactly(Beat.Result)
    }

    @Test fun `each verdict has its own icon and colour`() {
        val icons = Verdict.entries.map { ReviewIcons.forVerdict(it) }
        assertThat(icons.map { it.name }.toSet()).hasSize(4)
        assertThat(Verdict.entries.map { VerdictPalette.of(it).solid }.toSet()).hasSize(4)
    }

    @Test fun `clock formats minutes and tenths`() {
        assertThat(ReviewSteps.clock(27_300)).isEqualTo("0:27.3")
        assertThat(ReviewSteps.clock(62_400)).isEqualTo("1:02.4")
        assertThat(ReviewSteps.clock(-5)).isEqualTo("0:00.0")
    }
}
