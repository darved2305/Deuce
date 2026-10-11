package com.elevate.app.domain.calibration

/**
 * One marked line on a badminton court between [start] and [end], in court metres (contracts §0:
 * x across from the camera's left sideline, y along from the near baseline). [name] is stable, so
 * overlays and tests can address a line.
 */
data class Segment(val name: String, val start: Pair<Double, Double>, val end: Pair<Double, Double>)

/**
 * The whole-court geometry from one tap calibration: which template's corners were tapped, the
 * solved court↔image homography, and the image size the taps were made in (source pixels, §0 —
 * the probe's display size). Everything the court overlay and the mini-court need.
 */
data class CourtCalibration(
    val template: String,
    val homography: Homography,
    val imageW: Int,
    val imageH: Int,
) {
    /** Every painted line for [template]'s court, in metres. */
    val lines: List<Segment> by lazy { CourtModel.lines(template) }
}

/**
 * The lines a real badminton court carries, fixed by the BWF Laws — never measured, never guessed:
 * length 13.40 m; doubles width 6.10 m; singles width 5.18 m, each singles sideline 0.46 m inside
 * the doubles one; net at mid-court 6.70 m; short service lines 1.98 m from the net (4.72, 8.68);
 * doubles long service lines 0.76 m inside each back boundary (0.76, 12.64); centre line from each
 * short service line to its back boundary at mid-width.
 *
 * The four tapped corners are the outer corners of the chosen template's rectangle (the setup
 * screen's court hint names those lines): `badminton_doubles` taps the doubles quad 6.10 × 13.40,
 * `badminton_singles` taps the singles quad 5.18 × 13.40 — for singles the painted doubles lines
 * then sit 0.46 m outside the tapped quad on each side.
 */
object CourtModel {
    const val LENGTH_M = 13.40
    const val WIDTH_DOUBLES_M = 6.10
    const val WIDTH_SINGLES_M = 5.18
    const val SINGLES_INSET_M = 0.46
    const val NET_Y_M = 6.70
    const val SHORT_SERVICE_M = 1.98
    const val LONG_SERVICE_DOUBLES_M = 0.76
    /** The net is strung on posts outside the sidelines, so it is drawn past them. */
    const val NET_OVERHANG_M = 0.30

    /**
     * The tapped quad for [template] in contract corner order (near_left, near_right, far_right,
     * far_left), or null for a sport this build has no court for.
     */
    fun outerCorners(template: String): List<Pair<Double, Double>>? {
        val w = when (template) {
            "badminton_doubles" -> WIDTH_DOUBLES_M
            "badminton_singles" -> WIDTH_SINGLES_M
            else -> return null
        }
        return listOf(
            Pair(0.0, 0.0),        // near_left
            Pair(w, 0.0),          // near_right
            Pair(w, LENGTH_M),     // far_right
            Pair(0.0, LENGTH_M),   // far_left
        )
    }

    /** The painted court's x-extent in [template]'s frame — always the doubles rectangle. */
    fun courtBounds(template: String): Pair<Double, Double>? = when (template) {
        "badminton_doubles" -> Pair(0.0, WIDTH_DOUBLES_M)
        "badminton_singles" -> Pair(-SINGLES_INSET_M, WIDTH_SINGLES_M + SINGLES_INSET_M)
        else -> null
    }

    /** Every marked line in [template]'s frame, or empty for a template with no court. */
    fun lines(template: String): List<Segment> {
        val (l, r) = courtBounds(template) ?: return emptyList()
        val mid = (l + r) / 2
        val sl = l + SINGLES_INSET_M
        val sr = r - SINGLES_INSET_M
        val ssn = NET_Y_M - SHORT_SERVICE_M              // 4.72
        val ssf = NET_Y_M + SHORT_SERVICE_M              // 8.68
        val ldn = LONG_SERVICE_DOUBLES_M                 // 0.76
        val ldf = LENGTH_M - LONG_SERVICE_DOUBLES_M      // 12.64
        fun seg(name: String, x0: Double, y0: Double, x1: Double, y1: Double) =
            Segment(name, Pair(x0, y0), Pair(x1, y1))
        return listOf(
            seg("baseline_near", l, 0.0, r, 0.0),
            seg("baseline_far", l, LENGTH_M, r, LENGTH_M),
            seg("sideline_doubles_left", l, 0.0, l, LENGTH_M),
            seg("sideline_doubles_right", r, 0.0, r, LENGTH_M),
            seg("sideline_singles_left", sl, 0.0, sl, LENGTH_M),
            seg("sideline_singles_right", sr, 0.0, sr, LENGTH_M),
            seg("short_service_near", l, ssn, r, ssn),
            seg("short_service_far", l, ssf, r, ssf),
            seg("long_service_doubles_near", l, ldn, r, ldn),
            seg("long_service_doubles_far", l, ldf, r, ldf),
            seg("centre_near", mid, ssn, mid, 0.0),
            seg("centre_far", mid, ssf, mid, LENGTH_M),
            seg("net", l - NET_OVERHANG_M, NET_Y_M, r + NET_OVERHANG_M, NET_Y_M),
        )
    }
}
