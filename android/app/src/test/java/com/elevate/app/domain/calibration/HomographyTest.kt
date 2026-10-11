package com.elevate.app.domain.calibration

import com.google.common.truth.Truth.assertThat
import kotlin.random.Random
import org.junit.Test

class HomographyTest {

    private fun pt(x: Number, y: Number) = ImagePoint(x.toDouble(), y.toDouble())

    private val court = CourtModel.outerCorners("badminton_doubles")!!

    /** A court seen from behind the near baseline on 1920×1080: a trapezoid narrowing upwards. */
    private val taps = listOf(pt(300, 1000), pt(1620, 1000), pt(1250, 300), pt(670, 300))

    @Test fun tappedCornersMapExactly() {
        val h = Homography.fromFourPoints(court, taps)!!
        court.forEachIndexed { i, (x, y) ->
            val p = h.toImage(x, y)
            assertThat(p.x).isWithin(1e-6).of(taps[i].x)
            assertThat(p.y).isWithin(1e-6).of(taps[i].y)
        }
    }

    @Test fun interiorPointsRoundTrip() {
        val h = Homography.fromFourPoints(court, taps)!!
        val rnd = Random(7)
        repeat(20) {
            val x = rnd.nextDouble(0.0, CourtModel.WIDTH_DOUBLES_M)
            val y = rnd.nextDouble(0.0, CourtModel.LENGTH_M)
            val p = h.toImage(x, y)
            val (bx, by) = h.toCourt(p.x, p.y)
            assertThat(bx).isWithin(1e-6).of(x)
            assertThat(by).isWithin(1e-6).of(y)
        }
    }

    @Test fun degenerateQuadsGiveNull() {
        // Three tapped corners on one line.
        assertThat(
            Homography.fromFourPoints(court, listOf(pt(0, 0), pt(100, 0), pt(200, 0), pt(50, 50))),
        ).isNull()
        // Three court corners on one line.
        assertThat(
            Homography.fromFourPoints(
                listOf(Pair(0.0, 0.0), Pair(1.0, 0.0), Pair(2.0, 0.0), Pair(0.5, 0.5)),
                taps,
            ),
        ).isNull()
        // Fewer than four correspondences.
        assertThat(Homography.fromFourPoints(court.dropLast(1), taps.dropLast(1))).isNull()
    }

    /**
     * The clip's real shape: `h13g_singles` has no recorded `points_px` in the repo (the slice-2
     * proof tapped corners but recorded no values — android/PHASE2_STATUS.md), so this uses the
     * task's stand-in quad: near baseline fills the bottom edge, far corners converge at mid-frame.
     */
    @Test fun perspectiveQuadKeepsLineEndsInOrNearTheFrame() {
        val image = listOf(pt(0, 1080), pt(1920, 1080), pt(1250, 520), pt(670, 520))
        for (template in listOf("badminton_doubles", "badminton_singles")) {
            val h = Homography.fromFourPoints(CourtModel.outerCorners(template)!!, image)!!
            for (seg in CourtModel.lines(template)) {
                for ((cx, cy) in listOf(seg.start, seg.end)) {
                    val p = h.toImage(cx, cy)
                    // In or near the frame: within a quarter of each side outside it.
                    assertThat(p.x).isAtLeast(-480.0)
                    assertThat(p.x).isAtMost(2400.0)
                    assertThat(p.y).isAtLeast(-270.0)
                    assertThat(p.y).isAtMost(1350.0)
                }
            }
        }
    }
}
