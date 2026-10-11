package com.elevate.app.domain.coach

import com.elevate.app.domain.calibration.CourtModel

/**
 * What the shareable match card says and where the landings sit on it. Pure: the bitmap drawing
 * lives in the UI layer and only follows these lines and this mapping.
 */
object ShareCard {

    const val WIDTH_PX = 1080
    const val HEIGHT_PX = 1350
    const val BRAND = "Elevate"
    const val FOOTER = "Analysed from one phone video"

    data class Stat(val label: String, val value: String)

    data class Lines(
        val brand: String,
        val sport: String,
        val stats: List<Stat>,
        /** The first suggestion with enough data behind it; null when none has. */
        val topSuggestion: String?,
        val footer: String,
    )

    /** True when the report has anything real to put on a card. */
    fun hasData(report: CoachReport, hitCount: Int): Boolean =
        hitCount > 0 || report.rhythm.rallies > 0 || report.heat.zoned > 0

    /** "doubles" for the doubles template, otherwise "singles" (the app's default format). */
    fun format(template: String?): String = if (template == "badminton_doubles") "doubles" else "singles"

    fun lines(report: CoachReport, hitCount: Int, format: String = "singles"): Lines {
        val longest = report.rhythm.longestHits
        return Lines(
            brand = BRAND,
            sport = "Badminton · $format",
            stats = listOf(
                Stat("Hits", hitCount.toString()),
                Stat("Rallies", report.rhythm.rallies.toString()),
                Stat("Longest rally", if (longest == null) "none" else if (longest == 1) "1 hit" else "$longest hits"),
            ),
            topSuggestion = report.suggestions.firstOrNull { it.enoughData }?.text,
            footer = FOOTER,
        )
    }

    /**
     * The court drawn from above inside a pixel box: true proportions, centred, near baseline at the
     * bottom, the x-range [minX]..[minX] + [spanX] across. [px] and [py] map court metres to pixels.
     */
    data class CourtFrame(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
        val minX: Double,
        val spanX: Double,
    ) {
        fun px(xM: Double): Float = left + ((xM - minX) / spanX * width).toFloat()
        fun py(yM: Double): Float = top + ((1.0 - yM / CourtModel.LENGTH_M) * height).toFloat()

        /** True when the point is on the court or within [marginM] metres of it. */
        fun contains(xM: Double, yM: Double, marginM: Double = 0.5): Boolean =
            xM >= minX - marginM && xM <= minX + spanX + marginM && yM >= -marginM && yM <= CourtModel.LENGTH_M + marginM
    }

    /** Fits the court (x-range [xBounds], 13.4 m long) into the box, centred, keeping its proportions. */
    fun courtFrame(boxLeft: Float, boxTop: Float, boxWidth: Float, boxHeight: Float, xBounds: Pair<Double, Double>): CourtFrame {
        val span = xBounds.second - xBounds.first
        val scale = minOf(boxWidth / span, boxHeight / CourtModel.LENGTH_M).toFloat()
        val w = (span * scale).toFloat()
        val h = (CourtModel.LENGTH_M * scale).toFloat()
        return CourtFrame(boxLeft + (boxWidth - w) / 2, boxTop + (boxHeight - h) / 2, w, h, xBounds.first, span)
    }
}
