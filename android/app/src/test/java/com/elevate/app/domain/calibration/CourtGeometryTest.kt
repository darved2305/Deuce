package com.elevate.app.domain.calibration

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CourtGeometryTest {

    private fun pt(x: Number, y: Number) = ImagePoint(x.toDouble(), y.toDouble())

    /** A court seen from behind the near baseline, 1920×1080: a trapezoid narrowing upwards. */
    private val court = mapOf(
        Corner.NearLeft to pt(300, 1000),
        Corner.NearRight to pt(1620, 1000),
        Corner.FarRight to pt(1250, 300),
        Corner.FarLeft to pt(670, 300),
    )

    @Test fun contractOrderAndKeys() {
        assertThat(Corner.entries.map { it.key }).containsExactly("near_left", "near_right", "far_right", "far_left").inOrder()
    }

    @Test fun landscapeVideoInPortraitViewIsLetterboxedTopAndBottom() {
        // 1080 px wide view, 1920×1080 frame: scale 0.5625, picture 607.5 px tall, bars of 656.25 px.
        val t = FitTransform(1080.0, 1920.0, 1920.0, 1080.0)
        assertThat(t.scale).isWithin(1e-9).of(0.5625)
        assertThat(t.offsetX).isWithin(1e-9).of(0.0)
        assertThat(t.offsetY).isWithin(1e-9).of(656.25)
        assertThat(t.toSource(540.0, 656.25 + 303.75)).isEqualTo(pt(960, 540))
        assertThat(t.toSource(540.0, 100.0)).isNull() // in the bar above the picture
    }

    @Test fun portraitVideoInLandscapeViewIsPillarboxed() {
        // Upright 1080×1920 source (a rotated phone clip after the decoder's rotation) in a 2340×1080 view.
        val t = FitTransform(2340.0, 1080.0, 1080.0, 1920.0)
        assertThat(t.scale).isWithin(1e-9).of(0.5625)
        assertThat(t.offsetX).isWithin(1e-9).of((2340 - 607.5) / 2)
        assertThat(t.toSource(t.offsetX, 0.0)).isEqualTo(pt(0, 0))
        assertThat(t.toSource(t.offsetX + 607.5, 1080.0)).isEqualTo(pt(1080, 1920))
        assertThat(t.toSource(10.0, 500.0)).isNull()
    }

    @Test fun viewAndSourceRoundTrip() {
        val t = FitTransform(1080.0, 1500.0, 3840.0, 2160.0)
        for (p in court.values) {
            val (vx, vy) = t.toView(p)
            val back = t.toSource(vx, vy)!!
            assertThat(back.x).isWithin(1e-6).of(p.x)
            assertThat(back.y).isWithin(1e-6).of(p.y)
        }
    }

    @Test fun clampedDragStaysInsideTheFrame() {
        val t = FitTransform(1080.0, 1920.0, 1920.0, 1080.0)
        assertThat(t.toSourceClamped(-50.0, 0.0)).isEqualTo(pt(0, 0))
        assertThat(t.toSourceClamped(5000.0, 5000.0)).isEqualTo(pt(1920, 1080))
    }

    @Test fun aValidCourtPasses() {
        assertThat(CourtGeometry.check(court, 1920.0, 1080.0)).isEqualTo(CourtCheck.Ok)
    }

    @Test fun eachProblemIsNamed() {
        fun code(m: Map<Corner, ImagePoint>) = (CourtGeometry.check(m, 1920.0, 1080.0) as CourtCheck.Problem).code
        assertThat(code(court - Corner.FarLeft)).isEqualTo("incomplete")
        assertThat(code(court + (Corner.FarLeft to pt(1240, 305)))).isEqualTo("too_close")
        // Far corners swapped: the quad crosses itself.
        assertThat(code(court + mapOf(Corner.FarRight to pt(670, 300), Corner.FarLeft to pt(1250, 300)))).isEqualTo("crossed")
        // Whole court mirrored left-right: still a clean quad, but left is on the right.
        assertThat(code(court.mapValues { (_, p) -> pt(1920 - p.x, p.y) })).isEqualTo("left_right")
        // Flipped top-bottom: left/right still correct, but the near baseline is above the far one.
        assertThat(code(court.mapValues { (_, p) -> pt(p.x, 1080 - p.y) })).isEqualTo("near_far")
        val tiny = mapOf(Corner.NearLeft to pt(0, 100), Corner.NearRight to pt(100, 100), Corner.FarRight to pt(90, 50), Corner.FarLeft to pt(10, 50))
        assertThat(code(tiny)).isEqualTo("too_small")
    }
}
