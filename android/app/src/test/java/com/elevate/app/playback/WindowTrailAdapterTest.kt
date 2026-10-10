package com.elevate.app.playback

import com.elevate.app.domain.analysis.BallTrack
import com.elevate.app.domain.model.ClipIdentity
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/** Test vectors only; not engine output for any video. */
class WindowTrailAdapterTest {

    private val clip = ClipIdentity("test_clip", setOf("sha"), 1920, 1080)

    private fun ball(vararg rows: Pair<Long, Byte>) = BallTrack(
        tUs = LongArray(rows.size) { rows[it].first },
        x = FloatArray(rows.size) { 100f + it },
        y = FloatArray(rows.size) { 200f + it },
        conf = FloatArray(rows.size) { Float.NaN },
        state = ByteArray(rows.size) { rows[it].second },
        engineIndex = ByteArray(rows.size),
        engineVersions = listOf("0.3.0"),
    )

    @Test fun `observed and inferred stay separate tracks on one time axis`() {
        val b = ball(20_000L to BallTrack.OBSERVED, 40_000L to BallTrack.INFERRED, 60_000L to BallTrack.CANDIDATE, 80_000L to BallTrack.OBSERVED)
        val t = WindowTrailAdapter.toTrail(b, clip, "0.3.0", listOf(0L..100_000L))!!
        assertThat(t.observed.tUs.toList()).containsExactly(20_000L, 40_000L, 60_000L, 80_000L).inOrder()
        assertThat(t.observed.observedFrames).isEqualTo(2)
        assertThat(t.observed.x[1].isNaN()).isTrue()
        assertThat(t.inferred.observedFrames).isEqualTo(1)
        assertThat(t.inferred.x[1]).isEqualTo(101f)
        assertThat(t.candidate.observedFrames).isEqualTo(1)
        assertThat(t.observedRows).isEqualTo(2)
        assertThat(t.inferredRows).isEqualTo(1)
        assertThat(t.candidateRows).isEqualTo(1)
    }

    @Test fun `provenance is the engine, never hand-labelled`() {
        val t = WindowTrailAdapter.toTrail(ball(20_000L to BallTrack.OBSERVED), clip, "0.3.0", listOf(0L..100_000L))!!
        assertThat(t.observed.provenance.isHandLabelled).isFalse()
        assertThat(t.observed.provenance.engineVersion).isEqualTo("0.3.0")
        assertThat(t.observed.provenance.state).isEqualTo("observed")
        assertThat(t.inferred.provenance.state).isEqualTo("inferred")
        assertThrows(IllegalArgumentException::class.java) {
            WindowTrailAdapter.toTrail(ball(20_000L to BallTrack.OBSERVED), clip, "hand-labelled", listOf(0L..100_000L))
        }
    }

    @Test fun `user rows from the engine are excluded and counted`() {
        val t = WindowTrailAdapter.toTrail(ball(20_000L to BallTrack.USER, 40_000L to BallTrack.OBSERVED), clip, "0.3.0", listOf(0L..100_000L))!!
        assertThat(t.userRowsExcluded).isEqualTo(1)
        assertThat(t.observed.tUs.toList()).containsExactly(40_000L)
    }

    @Test fun `rows snap to real frame times within tolerance, others are dropped`() {
        // Engine rows are whole ms; real frames at 29.97 fps fall between them (one frame missing).
        val frames = longArrayOf(0, 33_367, 66_733, 200_200)
        val b = ball(33_000L to BallTrack.OBSERVED, 67_000L to BallTrack.OBSERVED, 133_000L to BallTrack.OBSERVED)
        val t = WindowTrailAdapter.toTrail(b, clip, "0.3.0", listOf(0L..200_200L), frames)!!
        assertThat(t.observed.tUs.toList()).containsExactly(33_367L, 66_733L).inOrder()
        assertThat(t.unmatchedRows).isEqualTo(1)
        assertThat(t.observed.framesInRange).isEqualTo(4)
    }

    @Test fun `rows outside downloaded coverage are not credited`() {
        val t = WindowTrailAdapter.toTrail(ball(20_000L to BallTrack.OBSERVED, 500_000L to BallTrack.OBSERVED), clip, "0.3.0", listOf(0L..100_000L))!!
        assertThat(t.observed.tUs.toList()).containsExactly(20_000L)
        assertThat(t.unmatchedRows).isEqualTo(1)
    }

    @Test fun `nothing to draw gives null`() {
        assertThat(WindowTrailAdapter.toTrail(BallTrack.EMPTY, clip, "0.3.0", listOf(0L..1L))).isNull()
        assertThat(WindowTrailAdapter.toTrail(ball(20_000L to BallTrack.OBSERVED), clip, "0.3.0", emptyList())).isNull()
    }
}
