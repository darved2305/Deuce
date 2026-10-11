package com.elevate.app.data.record

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Tilt of the phone about the camera's viewing axis, from the accelerometer (no permission needed),
 * for a phone held landscape. The court's baselines should be horizontal in the frame: a tilted
 * camera skews the court and costs the court fit some accuracy.
 */
object Level {
    const val LEVEL_DEG = 1.5
    const val OK_DEG = 3.0

    enum class Quality { Level, Close, Tilted }

    /**
     * Roll in degrees (positive = right side down when looking at the screen), from the device
     * axes in m/s² (x to the right of a portrait phone, y up). Landscape: gravity runs along x, so
     * the roll is the angle of the y component against it. Returns null when the phone is flat
     * (gravity mostly through the screen), where roll means nothing.
     */
    fun rollDegrees(ax: Float, ay: Float, az: Float): Double? {
        val inPlane = hypot(ax.toDouble(), ay.toDouble())
        if (inPlane < abs(az) * 0.5) return null
        // In landscape, "up" for the picture is along +x or -x. Fold both landscapes into one sign.
        val roll = Math.toDegrees(atan2(ay.toDouble(), abs(ax).toDouble()))
        return if (ax >= 0) roll else -roll
    }

    fun quality(rollDeg: Double): Quality = when {
        abs(rollDeg) <= LEVEL_DEG -> Quality.Level
        abs(rollDeg) <= OK_DEG -> Quality.Close
        else -> Quality.Tilted
    }

    /** One step of a low-pass filter so a shaky hand does not make the bubble jitter. */
    fun smooth(previous: Double?, sample: Double, alpha: Double = 0.15): Double =
        if (previous == null) sample else previous + alpha * (sample - previous)
}

/** The framing guide: where a whole badminton court sits in the frame from behind a baseline. */
object FramingGuide {
    /** `x, y` as fractions of the frame, clockwise from the far-left corner. */
    data class Quad(val farLeft: Pair<Float, Float>, val farRight: Pair<Float, Float>, val nearRight: Pair<Float, Float>, val nearLeft: Pair<Float, Float>)

    /**
     * A trapezoid: the far baseline is shorter and higher (perspective), the near baseline spans most
     * of the width near the bottom. A guide for placing the phone, not a measurement: the four
     * corners are tapped on the real picture afterwards.
     */
    val trapezoid = Quad(
        farLeft = 0.33f to 0.34f,
        farRight = 0.67f to 0.34f,
        nearRight = 0.94f to 0.90f,
        nearLeft = 0.06f to 0.90f,
    )

    const val INSTRUCTION = "Stand behind the baseline. Whole court in view."
}
