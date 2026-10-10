package com.elevate.app.domain.calibration

import com.elevate.app.ui.setup.SetupAction
import com.elevate.app.ui.setup.SetupState
import com.elevate.app.ui.setup.Step
import com.elevate.app.ui.setup.reduce
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CourtGeometryExtendedTest {

    private fun pt(x: Number, y: Number) = ImagePoint(x.toDouble(), y.toDouble())

    /** Near baseline below the frame (h13g_singles style), 1920×1080. */
    private val offFrameCourt = mapOf(
        Corner.NearLeft to pt(-150, 1260),
        Corner.NearRight to pt(2070, 1260),
        Corner.FarRight to pt(1250, 520),
        Corner.FarLeft to pt(670, 520),
    )

    @Test fun extendedReturnsPointsBeyondTheFrame() {
        val t = FitTransform(1080.0, 1920.0, 1920.0, 1080.0)
        // View y 1400 is 743.75 view px below the picture top: source y 1322 > srcH.
        val p = t.toSourceExtended(540.0, 1400.0)
        assertThat(p.x).isWithin(1e-6).of(960.0)
        assertThat(p.y).isWithin(1e-6).of((1400 - 656.25) / 0.5625)
        assertThat(p.y).isGreaterThan(1080.0)
        assertThat(t.toSourceExtended(-100.0, 700.0).x).isLessThan(0.0)
    }

    @Test fun extendedStopsAtTheMarginLimit() {
        val t = FitTransform(1080.0, 1920.0, 1920.0, 1080.0)
        assertThat(t.toSourceExtended(-50000.0, -50000.0)).isEqualTo(pt(-960, -540))
        assertThat(t.toSourceExtended(50000.0, 50000.0)).isEqualTo(pt(2880, 1620))
        assertThat(t.toSourceExtended(50000.0, 50000.0, margin = 0.1)).isEqualTo(pt(2112, 1188))
    }

    @Test fun extendedAndViewRoundTripForAnOffFramePoint() {
        for (padding in listOf(0.0, 40.0)) {
            val t = FitTransform(1080.0, 1500.0, 1920.0, 1080.0, padding)
            for (p in offFrameCourt.values) {
                val (vx, vy) = t.toView(p)
                val back = t.toSourceExtended(vx, vy)
                assertThat(back.x).isWithin(1e-6).of(p.x)
                assertThat(back.y).isWithin(1e-6).of(p.y)
            }
        }
    }

    @Test fun paddingKeepsThePictureInsideTheCanvasWithRoomAround() {
        val t = FitTransform(1000.0, 1000.0, 1000.0, 500.0, padding = 40.0)
        assertThat(t.scale).isWithin(1e-9).of(0.92)
        assertThat(t.toView(pt(0, 0)).first).isWithin(1e-9).of(40.0)
        assertThat(t.toView(pt(1000, 500)).first).isWithin(1e-9).of(960.0)
        assertThat(t.toSource(20.0, 500.0)).isNull() // in the padding: not on the picture
    }

    @Test fun tapStillHasToLandOnThePicture() {
        val t = FitTransform(1080.0, 1920.0, 1920.0, 1080.0)
        assertThat(t.toSource(540.0, 1400.0)).isNull()
    }

    @Test fun aCourtWithBothNearCornersBelowTheFramePasses() {
        assertThat(CourtGeometry.check(offFrameCourt, 1920.0, 1080.0)).isEqualTo(CourtCheck.Ok)
        assertThat(offFrameCourt.values.count { CourtGeometry.isOffFrame(it, 1920.0, 1080.0) }).isEqualTo(2)
    }

    @Test fun degenerateQuadsAreStillRejected() {
        fun code(m: Map<Corner, ImagePoint>) = (CourtGeometry.check(m, 1920.0, 1080.0) as CourtCheck.Problem).code
        // All four on one line (some off-frame): zero turn, neither convex nor area.
        val collinear = mapOf(
            Corner.NearLeft to pt(-100, 1200), Corner.NearRight to pt(500, 1000),
            Corner.FarRight to pt(1100, 800), Corner.FarLeft to pt(1700, 600),
        )
        assertThat(code(collinear)).isEqualTo("crossed")
        assertThat(code(offFrameCourt + (Corner.FarLeft to pt(-140, 1250)))).isEqualTo("too_close")
        assertThat(code(offFrameCourt + mapOf(Corner.FarRight to pt(670, 520), Corner.FarLeft to pt(1250, 520)))).isEqualTo("crossed")
    }

    @Test fun reviewCountsOffFrameCornersAndPointsKeepExtendedCoordinates() {
        val s = SetupState(srcW = 1920.0, srcH = 1080.0, frameMs = 500, step = Step.Court)
            .let { start -> Corner.entries.fold(start) { st, c -> reduce(st, SetupAction.Tap(pt(960, 540))).copyCorner(c, offFrameCourt.getValue(c)) } }
        assertThat(s.offFrameCorners).isEqualTo(2)
        val r = s.copy(step = Step.Review, me = pt(900, 700)).result()!!
        val pts = r.calibrationInput!!.pointsPx
        assertThat(pts.nearLeft).containsExactly(-150.0, 1260.0).inOrder()
        assertThat(pts.nearRight).containsExactly(2070.0, 1260.0).inOrder()
    }

    private fun SetupState.copyCorner(c: Corner, p: ImagePoint) = copy(corners = corners + (c to p))
}
