package com.elevate.app.domain.shotrewind

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RealExamplesTest {
    private fun c(
        shot: String,
        role: String = "similar_moment",
        provenance: String = "own",
        distance: Double = 1.0,
        won: Int = 0,
        of: Int = 0,
        attribution: String? = null,
    ) = ComparisonCandidate(shot, "c_$shot", distance, listOf("zone"), role, won, of, provenance, attribution)

    @Test fun `simulated is never a real example`() {
        val e = RealExamples.from("sh_q", listOf(c("sh_1", role = "simulated")))
        assertThat(e.real).isEmpty()
        assertThat(e.simulatedOnly).isTrue()
        assertThat(RealExamples.from("sh_q", emptyList()).simulatedOnly).isFalse()
    }

    @Test fun `labels carry source and role`() {
        val e = RealExamples.from("sh_q", listOf(
            c("sh_1", role = "worked_for_you", won = 4, of = 5),
            c("sh_2", provenance = "shared", attribution = "Arjun"),
            c("sh_3", provenance = "team_reference", role = "coach_pick"),
        )).real
        assertThat(e.map { it.sourceLabel to it.roleLabel }).containsExactly(
            "YOUR MATCH" to "WORKED FOR YOU · 4/5",
            "SHARED BY Arjun" to "SIMILAR MOMENT",
            "TEAM REFERENCE" to "COACH PICK",
        ).inOrder()
    }

    @Test fun `candidates without an honest label are dropped`() {
        val e = RealExamples.from("sh_q", listOf(
            c("sh_q"),
            c("sh_1", provenance = "shared"),
            c("sh_2", provenance = "public_cc", attribution = " "),
            c("sh_3", role = "worked_for_you", won = 3, of = 0),
            c("sh_4", role = "invented"),
            c("sh_5", provenance = "somewhere"),
        ))
        assertThat(e.real).isEmpty()
        assertThat(e.simulatedOnly).isFalse()
    }

    @Test fun `own first then nearest`() {
        val e = RealExamples.from("sh_q", listOf(
            c("sh_pub", provenance = "public_cc", attribution = "CC-BY club", distance = 0.1),
            c("sh_far", distance = 2.0),
            c("sh_near", distance = 0.5),
        )).real
        assertThat(e.map { it.candidate.shotId }).containsExactly("sh_near", "sh_far", "sh_pub").inOrder()
    }
}
