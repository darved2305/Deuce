package com.elevate.app.domain.calibration

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs

/**
 * The app draws court lines with its own homography from the four setup taps; the engine computes
 * `match.v1.calibration.H` (image px -> court m, row-major, h33 = 1, contracts 1.8.0) from the same
 * taps. Both must place every image pixel at the same court point, or the lines on the video and
 * the engine's landings disagree. Real data: the A55 session s_3df758f2 taps (#61) and the engine's
 * calibration cal_5ce8b39cabd7a746 for them (engine/eval/runs/s3df758_calibration on
 * p1/court-calibration).
 */
class EngineCalibrationAgreementTest {
    private val taps = listOf(
        ImagePoint(83.6, 1014.7), // near_left
        ImagePoint(1831.1, 1014.7), // near_right
        ImagePoint(1331.6, 664.4), // far_right
        ImagePoint(624.0, 664.4), // far_left
    )

    private val engineH = doubleArrayOf(
        -0.004095713340634894, -0.006318365893968458, 6.753647578765794,
        -2.510446377319843e-20, 0.021401897533259107, -21.71650568825165,
        -5.220766575329499e-20, -0.002347206310057368, 1.0,
    )

    private fun engineToCourt(px: Double, py: Double): Pair<Double, Double> {
        val h = engineH
        val w = h[6] * px + h[7] * py + h[8]
        return Pair((h[0] * px + h[1] * py + h[2]) / w, (h[3] * px + h[4] * py + h[5]) / w)
    }

    @Test fun appAndEngineAgreeWithinOneCentimetreAcrossTheCourt() {
        val app = Homography.fromFourPoints(CourtModel.outerCorners("badminton_singles")!!, taps)!!
        // The four taps, the image centre, and pixels on the net and service lines (via the app's
        // court -> image map), so the check covers the whole visible court, not only the corners.
        val probes = taps + ImagePoint(960.0, 840.0) + listOf(
            0.0 to 6.7, 5.18 to 6.7, 2.59 to 6.7, // net
            0.0 to 4.72, 5.18 to 8.68, 2.59 to 1.0, 2.59 to 12.4,
        ).map { (x, y) -> app.toImage(x, y) }
        for (p in probes) {
            val (ax, ay) = app.toCourt(p.x, p.y)
            val (ex, ey) = engineToCourt(p.x, p.y)
            assertThat(abs(ax - ex)).isLessThan(0.01)
            assertThat(abs(ay - ey)).isLessThan(0.01)
        }
    }

    @Test fun bothUseTheContractCourtFrame() {
        // near_left is the origin, x runs across to near_right, y runs to the far baseline (metres).
        val (nx, ny) = engineToCourt(83.6, 1014.7)
        val (fx, fy) = engineToCourt(1331.6, 664.4)
        assertThat(abs(nx)).isLessThan(0.001)
        assertThat(abs(ny)).isLessThan(0.001)
        assertThat(abs(fx - 5.18)).isLessThan(0.001)
        assertThat(abs(fy - 13.4)).isLessThan(0.001)
    }
}
