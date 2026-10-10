package com.elevate.app.domain.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Algorithm unit tests for the grouping of onsets into provisional rally candidates (onset times are hand-written). */
class RallyCandidatesTest {

    private fun on(vararg t: Long) = t.map { Onset(it, 0.5f) }

    @Test fun emptyGivesNothing() {
        assertThat(RallyCandidates.group(emptyList())).isEmpty()
    }

    @Test fun groupsByGapAndPads() {
        val r = RallyCandidates.group(on(10_000, 11_000, 12_200, 13_500, 30_000, 31_500, 33_000, 34_000))
        assertThat(r).containsExactly(
            RallyCandidate(9_500, 14_500, 4),
            RallyCandidate(29_500, 35_000, 4),
        ).inOrder()
        assertThat(r.all { it.state == "candidate" }).isTrue()
    }

    @Test fun gapExactlyAtLimitStaysTogether() {
        val r = RallyCandidates.group(on(5_000, 7_500, 10_000))
        assertThat(r).containsExactly(RallyCandidate(4_500, 11_000, 3))
    }

    @Test fun gapJustOverLimitSplits() {
        val r = RallyCandidates.group(on(5_000, 6_000, 7_000, 9_501, 10_000))
        assertThat(r).containsExactly(RallyCandidate(4_500, 8_000, 3))
    }

    @Test fun tooFewOnsetsIsNotACandidate() {
        assertThat(RallyCandidates.group(on(1_000, 2_000))).isEmpty()
        assertThat(RallyCandidates.group(on(1_000))).isEmpty()
    }

    @Test fun unsortedInputIsSorted() {
        assertThat(RallyCandidates.group(on(3_000, 1_000, 2_000))).containsExactly(RallyCandidate(500, 4_000, 3))
    }

    @Test fun clampedToClipBounds() {
        val r = RallyCandidates.group(on(100, 900, 1_700), durationMs = 2_000)
        assertThat(r).containsExactly(RallyCandidate(0, 2_000, 3))
    }

    @Test fun neverOverlapsPreviousWhenPadsAreLarge() {
        val p = RallyParams(maxGapMs = 1_000, minOnsets = 2, padBeforeMs = 2_000, padAfterMs = 2_000)
        val r = RallyCandidates.group(on(10_000, 11_000, 12_500, 13_000), params = p)
        assertThat(r).hasSize(2)
        assertThat(r[1].startMs).isAtLeast(r[0].endMs)
    }

    @Test fun customMinimum() {
        val r = RallyCandidates.group(on(1_000, 2_000), params = RallyParams(minOnsets = 2))
        assertThat(r).containsExactly(RallyCandidate(500, 3_000, 2))
    }

    @Test fun endToEndFromSyntheticImpulses() {
        // Seven built-in-code clicks at shot-like spacing, then silence, then a lone click: one candidate.
        val sr = 48_000
        val hits = listOf(1_000, 2_100, 3_000, 4_200, 5_000, 6_300, 7_100, 15_000)
        val x = FloatArray(sr * 17)
        val rnd = java.util.Random(1)
        for (i in x.indices) x[i] = (rnd.nextGaussian() * 0.002).toFloat()
        for (h in hits) for (k in 0 until sr / 10) {
            val t = k.toDouble() / sr
            x[sr * h / 1000 + k] += (0.4 * kotlin.math.exp(-t / 0.015) * kotlin.math.sin(2 * Math.PI * 4_000 * t + Math.PI / 2)).toFloat()
        }
        val d = OnsetDetector(sr)
        d.accept(x, x.size, 0)
        val r = RallyCandidates.group(d.finish(), durationMs = 17_000)
        assertThat(r).hasSize(1)
        assertThat(r[0].onsets).isEqualTo(7)
        assertThat(r[0].startMs.toDouble()).isWithin(10.0).of(500.0)
        assertThat(r[0].endMs.toDouble()).isWithin(10.0).of(8_100.0)
    }
}
