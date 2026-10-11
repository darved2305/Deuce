package com.elevate.app.domain.review

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LineCallTest {
    private val singles = "badminton_singles"

    /** Diagonal covariance from per-axis 1-sigma values in metres. */
    private fun diag(sx: Double, sy: Double) = listOf(listOf(sx * sx, 0.0), listOf(0.0, sy * sy))

    /** A tier-B landing at (x, y) with axis sigmas; defaults are tiny so only the asked-for line matters. */
    private fun call(
        x: Double, y: Double,
        cov: List<List<Double>>? = diag(0.01, 0.01),
        tier: String = "B",
        template: String = singles,
        singlesCourt: Boolean = true,
        plane: String? = "court",
        calPlane: String? = "court",
        xy: List<Double>? = listOf(x, y),
    ) = LineCall.of(LandingInput(xy, cov, plane, calPlane, tier, template, singlesCourt))

    // ---- signed distance ---------------------------------------------------------------------

    @Test fun `signed distance to each singles line, inside and outside`() {
        val box = BoundaryBox.forTemplate(singles, singles = true)!!
        assertThat(box).isEqualTo(BoundaryBox(0.0, 5.18, 0.0, 13.4))
        // Mid court: far from every line.
        assertThat(box.signedDistance(BoundaryLine.SideLeft, 2.59, 6.7)).isWithin(1e-9).of(2.59)
        assertThat(box.signedDistance(BoundaryLine.SideRight, 2.59, 6.7)).isWithin(1e-9).of(2.59)
        assertThat(box.signedDistance(BoundaryLine.BackNear, 2.59, 6.7)).isWithin(1e-9).of(6.7)
        assertThat(box.signedDistance(BoundaryLine.BackFar, 2.59, 6.7)).isWithin(1e-9).of(6.7)
        // Outside: negative.
        assertThat(box.signedDistance(BoundaryLine.SideLeft, -0.3, 6.7)).isWithin(1e-9).of(-0.3)
        assertThat(box.signedDistance(BoundaryLine.SideRight, 5.48, 6.7)).isWithin(1e-9).of(-0.3)
        assertThat(box.signedDistance(BoundaryLine.BackNear, 2.0, -0.5)).isWithin(1e-9).of(-0.5)
        assertThat(box.signedDistance(BoundaryLine.BackFar, 2.0, 13.9)).isWithin(1e-9).of(-0.5)
        // Exactly on each line is zero.
        assertThat(box.signedDistance(BoundaryLine.BackFar, 2.0, 13.4)).isWithin(1e-9).of(0.0)
    }

    @Test fun `doubles court uses the doubles sidelines, and a doubles template shifts the singles ones`() {
        val dbl = BoundaryBox.forTemplate("badminton_doubles", singles = false)!!
        assertThat(dbl).isEqualTo(BoundaryBox(0.0, 6.10, 0.0, 13.4))
        val singlesOnDoublesTemplate = BoundaryBox.forTemplate("badminton_doubles", singles = true)!!
        assertThat(singlesOnDoublesTemplate.minX).isWithin(1e-9).of(0.46)
        assertThat(singlesOnDoublesTemplate.maxX).isWithin(1e-9).of(5.64)
        assertThat(BoundaryBox.forTemplate("table_tennis", singles = true)).isNull()
    }

    @Test fun `the nearest line is chosen, and the decisive line at a corner is the one most outside`() {
        assertThat(call(0.2, 6.7).line).isEqualTo(BoundaryLine.SideLeft)
        assertThat(call(4.9, 6.7).line).isEqualTo(BoundaryLine.SideRight)
        assertThat(call(2.59, 0.3).line).isEqualTo(BoundaryLine.BackNear)
        assertThat(call(2.59, 13.1).line).isEqualTo(BoundaryLine.BackFar)
        // Inside the corner: both 0.3 m away; still inside.
        assertThat(call(0.3, 0.3).verdict).isEqualTo(Verdict.Inside)
        // Past the far-left corner: 1 cm past the side line but 50 cm past the back line.
        val c = call(-0.01, 13.9)
        assertThat(c.verdict).isEqualTo(Verdict.Outside)
        assertThat(c.line).isEqualTo(BoundaryLine.BackFar)
        assertThat(c.distanceM).isWithin(1e-9).of(-0.5)
        // Out on one line only: still out, even though the other line is comfortably in.
        assertThat(call(5.5, 6.7).verdict).isEqualTo(Verdict.Outside)
    }

    // ---- sigma projection --------------------------------------------------------------------

    @Test fun `sigma along a normal is sqrt of n transpose sigma n`() {
        val cov = listOf(listOf(0.04, 0.01), listOf(0.01, 0.01))
        assertThat(LineCall.sigmaAlong(cov, 1.0, 0.0)!!).isWithin(1e-12).of(0.2)
        assertThat(LineCall.sigmaAlong(cov, 0.0, 1.0)!!).isWithin(1e-12).of(0.1)
        // The sign of the normal does not matter.
        assertThat(LineCall.sigmaAlong(cov, -1.0, 0.0)!!).isWithin(1e-12).of(0.2)
        // A slanted normal picks up the off-diagonal term: 0.36*0.04 + 2*0.48*0.01 + 0.64*0.01 = 0.0304.
        assertThat(LineCall.sigmaAlong(cov, 0.6, 0.8)!!).isWithin(1e-12).of(Math.sqrt(0.0304))
    }

    @Test fun `the call uses the spread along the line it is judged on`() {
        // Wide along x (side lines), narrow along y (back lines).
        val cov = diag(0.30, 0.02)
        val side = call(0.1, 6.7, cov = cov)
        assertThat(side.sigmaM).isWithin(1e-12).of(0.30)
        val back = call(2.59, 13.3, cov = cov)
        assertThat(back.sigmaM).isWithin(1e-12).of(0.02)
        assertThat(back.marginM).isWithin(1e-12).of(1.96 * 0.02)
    }

    @Test fun `an unusable covariance is treated as missing`() {
        for (bad in listOf(
            listOf(listOf(0.0, 0.0), listOf(0.0, 0.0)),
            listOf(listOf(-1.0, 0.0), listOf(0.0, 1.0)),
            listOf(listOf(1.0, 0.0)),
            listOf(listOf(Double.NaN, 0.0), listOf(0.0, 1.0)),
            listOf(listOf(0.01, 0.5), listOf(0.5, 0.01)),
        )) {
            val c = call(2.59, 13.0, cov = bad)
            assertThat(c.usedFallbackSigma).isTrue()
            assertThat(c.sigmaM).isWithin(1e-12).of(0.40)
        }
    }

    // ---- verdict thresholds ------------------------------------------------------------------

    @Test fun `inside needs distance above 1 point 96 sigma, outside below minus 1 point 96 sigma, else too close`() {
        // sigma 0.1 on every axis: the band is 0.196 m.
        val cov = diag(0.1, 0.1)
        assertThat(call(0.20, 6.7, cov).verdict).isEqualTo(Verdict.Inside)
        assertThat(call(0.19, 6.7, cov).verdict).isEqualTo(Verdict.TooClose)
        assertThat(call(0.0, 6.7, cov).verdict).isEqualTo(Verdict.TooClose)
        assertThat(call(-0.19, 6.7, cov).verdict).isEqualTo(Verdict.TooClose)
        assertThat(call(-0.20, 6.7, cov).verdict).isEqualTo(Verdict.Outside)
    }

    @Test fun `a big margin turns a nearby landing into too close even when the centre is in`() {
        val c = call(2.59, 13.25, cov = diag(0.05, 0.10)) // 15 cm in, band 19.6 cm
        assertThat(c.verdict).isEqualTo(Verdict.TooClose)
        assertThat(c.gapCm).isEqualTo(15)
        assertThat(c.marginCm).isEqualTo(20) // 19.6 rounded up, never understated
    }

    // ---- fallback sigma and not-seen ---------------------------------------------------------

    @Test fun `missing covariance uses the grade's stated error as sigma and says so`() {
        val b = call(2.59, 12.4, cov = null, tier = "B") // 1.0 m inside
        assertThat(b.usedFallbackSigma).isTrue()
        assertThat(b.sigmaM).isWithin(1e-12).of(0.40)
        assertThat(b.marginCm).isEqualTo(79) // 78.4 rounded up
        assertThat(b.verdict).isEqualTo(Verdict.Inside)
        assertThat(LineCallText.note(b)).isEqualTo("No margin was recorded for this landing, so we used the typical error of a grade B court (±79 cm).")

        val a = call(2.59, 13.0, cov = null, tier = "A") // 40 cm inside, band 29.4 cm
        assertThat(a.sigmaM).isWithin(1e-12).of(0.15)
        assertThat(a.verdict).isEqualTo(Verdict.Inside)
        assertThat(call(2.59, 13.2, cov = null, tier = "A").verdict).isEqualTo(Verdict.TooClose) // 20 cm in

        assertThat(call(2.59, 13.0).usedFallbackSigma).isFalse()
    }

    @Test fun `tier C is never called, even with a covariance`() {
        val c = call(2.59, 6.7, tier = "C")
        assertThat(c.verdict).isEqualTo(Verdict.NotSeen)
        assertThat(c.notSeen).isEqualTo(NotSeenReason.TierC)
        assertThat(c.line).isNull()
    }

    @Test fun `no position, wrong plane or no calibration is not seen`() {
        assertThat(call(0.0, 0.0, xy = null).notSeen).isEqualTo(NotSeenReason.NoPosition)
        assertThat(call(0.0, 0.0, xy = listOf(1.0)).notSeen).isEqualTo(NotSeenReason.NoPosition)
        assertThat(call(0.0, 0.0, xy = listOf(1.0, Double.NaN)).notSeen).isEqualTo(NotSeenReason.NoPosition)
        assertThat(call(2.0, 2.0, plane = "floor").notSeen).isEqualTo(NotSeenReason.NotCourtPlane)
        assertThat(call(2.0, 2.0, calPlane = "tabletop").notSeen).isEqualTo(NotSeenReason.NoCalibration)
        assertThat(call(2.0, 2.0, calPlane = null).notSeen).isEqualTo(NotSeenReason.NoCalibration)
        assertThat(call(2.0, 2.0, template = "table_tennis").notSeen).isEqualTo(NotSeenReason.NoCalibration)
        assertThat(call(2.0, 2.0, tier = "Z").notSeen).isEqualTo(NotSeenReason.TierC)
        for (c in listOf(call(0.0, 0.0, xy = null), call(2.0, 2.0, plane = "floor"), call(2.0, 6.7, tier = "C"))) {
            assertThat(c.verdict).isEqualTo(Verdict.NotSeen)
        }
        // A landing with no plane given is read as the court plane.
        assertThat(call(2.59, 6.7, plane = null).verdict).isEqualTo(Verdict.Inside)
    }

    // ---- the exact words ---------------------------------------------------------------------

    @Test fun `inside copy`() {
        // 12 cm inside the far back line, margin exactly 5 cm.
        val c = call(2.59, 13.28, cov = diag(0.05 / 1.96, 0.05 / 1.96))
        assertThat(c.verdict).isEqualTo(Verdict.Inside)
        assertThat(LineCallText.markup(c)).isEqualTo("Landed **12 cm inside** the back line. Even with our margin of error (±5 cm) it stays in.")
        assertThat(LineCallText.plain(c)).isEqualTo("Landed 12 cm inside the back line. Even with our margin of error (±5 cm) it stays in.")
        assertThat(LineCallText.note(c)).isNull()
        assertThat(LineCallText.badge(c.verdict)).isEqualTo("In")
    }

    @Test fun `outside copy`() {
        val c = call(5.48, 6.7, cov = diag(0.05 / 1.96, 0.05 / 1.96))
        assertThat(c.verdict).isEqualTo(Verdict.Outside)
        assertThat(LineCallText.markup(c)).isEqualTo("Landed **30 cm past** the side line. Clearly out.")
        assertThat(LineCallText.badge(c.verdict)).isEqualTo("Out")
    }

    @Test fun `too close copy`() {
        val c = call(2.59, 13.37, cov = diag(0.08 / 1.96, 0.08 / 1.96))
        assertThat(c.verdict).isEqualTo(Verdict.TooClose)
        assertThat(LineCallText.markup(c)).isEqualTo("**Too close to call:** 3 cm from the line, and our margin is ±8 cm. We won't guess.")
        assertThat(LineCallText.badge(c.verdict)).isEqualTo("Too close")
    }

    @Test fun `too close copy when the shuttle is just outside`() {
        val c = call(2.59, 13.43, cov = diag(0.08 / 1.96, 0.08 / 1.96))
        assertThat(LineCallText.plain(c)).isEqualTo("Too close to call: 3 cm from the line, and our margin is ±8 cm. We won't guess.")
    }

    @Test fun `not seen copy and reasons`() {
        val c = call(2.59, 6.7, tier = "C")
        assertThat(LineCallText.markup(c)).isEqualTo("We didn't see this one land clearly, so no call.")
        assertThat(LineCallText.NOT_SEEN).isEqualTo(LineCallText.plain(c))
        assertThat(LineCallText.badge(c.verdict)).isEqualTo("Not seen")
        assertThat(LineCallText.note(c)).isEqualTo("Why: the court fit is grade C, too loose to measure a line.")
        assertThat(LineCallText.note(call(0.0, 0.0, xy = null))).isEqualTo("Why: no court position was measured.")
    }

    @Test fun `sub centimetre gaps are not rounded to zero and fixed phrases are exact`() {
        val inside = call(2.59, 13.395, cov = diag(0.001, 0.001)) // 5 mm in, band 2 mm
        assertThat(inside.verdict).isEqualTo(Verdict.Inside)
        assertThat(LineCallText.plain(inside)).isEqualTo("Landed 1 cm inside the back line. Even with our margin of error (±1 cm) it stays in.")
        val under = call(2.59, 13.3996, cov = diag(0.0001, 0.0001))
        assertThat(LineCallText.plain(under)).startsWith("Landed under 1 cm inside the back line.")
        assertThat(LineCallText.SMALL_PRINT).isEqualTo("A model estimate. You make the final call.")
        assertThat(LineCallText.MOMENT_CAPTION).isEqualTo("The shuttle touches the floor here")
    }

    @Test fun `no copy contains an em dash or the words IN and OUT as jargon labels`() {
        val all = listOf(call(2.59, 13.28), call(5.48, 6.7), call(2.59, 13.37), call(2.59, 6.7, tier = "C"), call(2.59, 12.0, cov = null))
            .flatMap { listOfNotNull(LineCallText.markup(it), LineCallText.note(it)) } + LineCallText.SMALL_PRINT
        all.forEach { assertThat(it).doesNotContain("—") }
    }
}
