package com.elevate.app.domain.review

import com.elevate.app.domain.analysis.BallTrack
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LineFrameTest {
    private fun call(x: Double, y: Double, xx: Double, xy: Double, yy: Double) = LineCall.of(
        LandingInput(listOf(x, y), listOf(listOf(xx, xy), listOf(xy, yy)), "court", "court", "B", "badminton_singles", true),
    )

    @Test fun `a back line keeps along-line and across-line spreads apart`() {
        val f = LineFrame.of(call(2.59, 13.3, xx = 0.04, xy = 0.0, yy = 0.0004))!!
        assertThat(f.uu).isWithin(1e-12).of(0.04)
        assertThat(f.vv).isWithin(1e-12).of(0.0004)
        assertThat(f.landingV).isWithin(1e-9).of(0.10)
        assertThat(f.bandV).isWithin(1e-12).of(1.96 * 0.02)
    }

    @Test fun `a side line swaps the axes`() {
        val f = LineFrame.of(call(0.10, 6.7, xx = 0.04, xy = 0.0, yy = 0.0004))!!
        assertThat(f.uu).isWithin(1e-12).of(0.0004)
        assertThat(f.vv).isWithin(1e-12).of(0.04)
    }

    @Test fun `the ellipse reaches exactly 1 point 96 sigma across the line and is centred on the landing`() {
        val f = LineFrame.of(call(2.59, 13.3, xx = 0.0009, xy = 0.0003, yy = 0.0004))!!
        val pts = f.ellipse(720)
        assertThat(pts.maxOf { it.second } - f.landingV).isWithin(1e-3).of(f.bandV)
        assertThat(f.landingV - pts.minOf { it.second }).isWithin(1e-3).of(f.bandV)
        assertThat(pts.map { it.first }.average()).isWithin(1e-6).of(0.0)
    }

    @Test fun `the off-diagonal term follows the rotation, so the picture is never mirrored`() {
        // Both back lines: tangent (+-1, 0), normal (0, -+1): u*v covariance is -xy on each.
        val far = LineFrame.of(call(2.59, 13.3, 0.0009, 0.0003, 0.0004))!!
        val near = LineFrame.of(call(2.59, 0.1, 0.0009, 0.0003, 0.0004))!!
        assertThat(far.uv).isWithin(1e-12).of(-0.0003)
        assertThat(near.uv).isWithin(1e-12).of(-0.0003)
        // Left side line: tangent (0, 1), normal (1, 0): +xy.
        assertThat(LineFrame.of(call(0.1, 6.7, 0.0009, 0.0003, 0.0004))!!.uv).isWithin(1e-12).of(0.0003)
    }

    @Test fun `zoomed range holds the line, the landing and the band, and is never tighter than 30 cm`() {
        val f = LineFrame.of(call(2.59, 13.37, 0.0004, 0.0, 0.0004))!! // 3 cm in, band 3.9 cm
        val (lo, hi) = f.zoomedRange()
        assertThat(hi - lo).isAtLeast(LineFrame.MIN_SPAN_M - 1e-9)
        assertThat(lo).isAtMost(-0.02)
        assertThat(hi).isAtLeast(f.landingV + f.bandV)
        val wide = LineFrame.of(call(2.59, 12.0, 0.01, 0.0, 0.01))!!
        val (wlo, whi) = wide.zoomedRange()
        assertThat(wlo).isAtMost(wide.landingV - wide.bandV)
        assertThat(whi).isAtLeast(wide.landingV + wide.bandV)
    }

    @Test fun `ruler steps keep ticks apart and no frame for not seen`() {
        assertThat(LineFrame.rulerStepCm(20.0)).isEqualTo(1)
        assertThat(LineFrame.rulerStepCm(4.0)).isEqualTo(2)
        assertThat(LineFrame.rulerStepCm(1.0)).isEqualTo(10)
        assertThat(LineFrame.rulerStepCm(0.1)).isEqualTo(100)
        val notSeen = LineCall.of(LandingInput(null, null, null, "court", "B", "badminton_singles", true))
        assertThat(LineFrame.of(notSeen)).isNull()
    }

    @Test fun `flight trail keeps observed rows just before the landing and needs at least three`() {
        val t = longArrayOf(1_000_000, 1_900_000, 2_000_000, 2_100_000, 2_200_000, 2_990_000, 3_000_000)
        val ball = BallTrack(
            tUs = t, x = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f), y = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f),
            conf = FloatArray(7) { 0.9f }, state = byteArrayOf(0, 0, 0, 1, 0, 0, 0), engineIndex = ByteArray(7), engineVersions = listOf("t"),
        )
        // window for 3000 ms: 2300..3040 ms -> rows at 2990 and 3000 only (2 rows): not enough.
        assertThat(FlightTrail.before(ball, 3_000)).isEmpty()
        // window for 2200 ms: 1500..2240 ms -> rows at 1900, 2000, (2100 candidate dropped), 2200.
        assertThat(FlightTrail.before(ball, 2_200)).containsExactly(2f to 2f, 3f to 3f, 5f to 5f).inOrder()
        assertThat(FlightTrail.before(null, 2_200)).isEmpty()
        val n = FlightTrail.normalised(listOf(0f to 0f, 10f to 5f))
        assertThat(n.first()).isEqualTo(0f to 0.25f)
        assertThat(n.last()).isEqualTo(1f to 0.75f)
    }
}
