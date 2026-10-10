package com.elevate.app.playback

import com.elevate.app.TestVectors
import com.elevate.app.data.fixture.TrailLabelsParser
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TimeMappingTest {

    private val track = TrailLabelsParser.parse(TestVectors.doc(TestVectors.FRAMES_50FPS))
    private val tol = TimeMapping.matchToleranceUs(track.tUs)

    @Test fun `us and ms convert both ways`() {
        assertThat(TimeMapping.usToMs(40_020_000)).isEqualTo(40_020)
        assertThat(TimeMapping.usToMs(16_667)).isEqualTo(17)
        assertThat(TimeMapping.usToMs(16_499)).isEqualTo(16)
        assertThat(TimeMapping.msToUs(21_430)).isEqualTo(21_430_000)
    }

    @Test fun `clip-relative time maps to source time with the clip offset`() {
        assertThat(TimeMapping.presentationToSourceUs(1_500_000)).isEqualTo(1_500_000)
        assertThat(TimeMapping.presentationToSourceUs(1_500_000, clipStartInSourceUs = 19_930_000)).isEqualTo(21_430_000)
    }

    @Test fun `tolerance comes from the labels' own spacing, not an assumed fps`() {
        assertThat(tol).isEqualTo(9_999)
        // Irregular (VFR-like) spacing: the smallest gap decides.
        assertThat(TimeMapping.matchToleranceUs(longArrayOf(0, 33_366, 66_366, 100_100))).isEqualTo(16_499)
        assertThat(TimeMapping.matchToleranceUs(longArrayOf(5))).isEqualTo(0)
    }

    @Test fun `exact frame lookup`() {
        assertThat(TimeMapping.labelIndexAt(track, 40_020_000, tol)).isEqualTo(1)
        // A decoder timestamp a few µs off still lands on the same frame, never the neighbour.
        assertThat(TimeMapping.labelIndexAt(track, 40_020_003, tol)).isEqualTo(1)
        assertThat(TimeMapping.labelIndexAt(track, 40_029_999, tol)).isEqualTo(1)
        assertThat(TimeMapping.labelIndexAt(track, 40_030_001, tol)).isEqualTo(2)
    }

    @Test fun `unlabelled and out-of-range frames have no label`() {
        assertThat(TimeMapping.labelIndexAt(track, 40_080_000, tol)).isEqualTo(-1)
        assertThat(TimeMapping.labelIndexAt(track, 39_980_000, tol)).isEqualTo(-1)
        assertThat(TimeMapping.labelIndexAt(track, 41_000_000, tol)).isEqualTo(-1)
    }

    @Test fun `not-visible frame is labelled but not observed`() {
        val i = TimeMapping.labelIndexAt(track, 40_060_000, tol)
        assertThat(i).isEqualTo(3)
        assertThat(track.isObserved(i)).isFalse()
        assertThat(track.observedFrames).isEqualTo(4)
    }

    @Test fun `trail window holds the current frame and earlier ones, never later`() {
        val w = TimeMapping.trailWindow(track, 40_040_000, windowUs = 40_000, toleranceUs = tol)
        assertThat(w.toList()).containsExactly(1, 2).inOrder()
        val atStart = TimeMapping.trailWindow(track, 40_000_000, windowUs = 400_000, toleranceUs = tol)
        assertThat(atStart.toList()).containsExactly(0)
        val before = TimeMapping.trailWindow(track, 39_000_000, windowUs = 400_000, toleranceUs = tol)
        assertThat(before.isEmpty()).isTrue()
    }

    @Test fun `seeking backwards gives the same answer as playing forwards`() {
        val forward = (0..5).map { TimeMapping.labelIndexAt(track, 40_000_000L + it * 20_000, tol) }
        val backward = (5 downTo 0).map { TimeMapping.labelIndexAt(track, 40_000_000L + it * 20_000, tol) }.reversed()
        assertThat(backward).isEqualTo(forward)
    }
}
