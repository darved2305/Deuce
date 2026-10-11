package com.elevate.app.playback

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.CanvasOverlay
import androidx.media3.effect.StaticOverlaySettings
import com.elevate.app.domain.calibration.CourtCalibration
import com.elevate.app.domain.calibration.CourtModel
import kotlin.math.max

/**
 * Draws every painted court line into the video frame inside Media3's effect pipeline — same
 * pattern as [TrailOverlay]: the canvas is the decoded frame (downscaled past
 * [MAX_CANVAS_LONG_SIDE]), so lines track the picture at any speed, paused or seeking.
 *
 * The lines are real geometry, not guesses: [CourtModel] gives the BWF court in metres and the
 * corner-tap homography maps each line, sampled densely, into frame pixels. The homography is the
 * still frame the user tapped — if the camera moves afterwards the lines stay where the taps put
 * them, which is the honest limit of four taps.
 */
@UnstableApi
class CourtOverlay : CanvasOverlay(/* useInputFrameSize = */ false) {

    private class Prepared(val calibration: CourtCalibration, val lines: List<com.elevate.app.domain.calibration.Segment>)

    @Volatile private var prepared: Prepared? = null
    @Volatile var visible: Boolean = true

    private var downscale = 1
    private var canvasDirty = false
    private var overlaySettings: OverlaySettings = StaticOverlaySettings.Builder().build()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LINE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val netPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = NET; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    /** New calibration, or null to draw nothing. Takes effect on the next drawn frame. */
    fun setCalibration(calibration: CourtCalibration?) {
        prepared = calibration?.let { Prepared(it, CourtModel.lines(it.template)) }
        canvasDirty = true
    }

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        downscale = max(1, (max(videoSize.width, videoSize.height) + MAX_CANVAS_LONG_SIDE - 1) / MAX_CANVAS_LONG_SIDE)
        setCanvasSize(max(1, videoSize.width / downscale), max(1, videoSize.height / downscale))
        overlaySettings = StaticOverlaySettings.Builder().setScale(downscale.toFloat(), downscale.toFloat()).build()
        canvasDirty = true
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = overlaySettings

    override fun onDraw(canvas: Canvas, presentationTimeUs: Long) {
        val p = prepared
        val draw = visible && p != null
        // An untouched bitmap is not re-uploaded, so skip clearing when the canvas is already empty.
        if (!draw && !canvasDirty) return
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvasDirty = false
        if (!draw || p == null) return

        // Taps live in the probe's display size; the canvas is the decoded frame / downscale.
        val sx = canvas.width.toFloat() / p.calibration.imageW
        val sy = canvas.height.toFloat() / p.calibration.imageH
        val unit = canvas.height / 1080f
        linePaint.strokeWidth = 3f * unit
        netPaint.strokeWidth = 3f * unit
        for (seg in p.lines) {
            val paint = if (seg.name == "net") netPaint else linePaint
            var prevX = 0f
            var prevY = 0f
            var havePrev = false
            for (i in 0 until SAMPLES_PER_LINE) {
                val t = i / (SAMPLES_PER_LINE - 1.0)
                val im = p.calibration.homography.toImage(
                    seg.start.first + (seg.end.first - seg.start.first) * t,
                    seg.start.second + (seg.end.second - seg.start.second) * t,
                )
                // A point on the vanishing line is not finite: the line breaks there.
                if (!im.x.isFinite() || !im.y.isFinite()) {
                    havePrev = false
                    continue
                }
                val x = (im.x * sx).toFloat().coerceIn(-1e6f, 1e6f)
                val y = (im.y * sy).toFloat().coerceIn(-1e6f, 1e6f)
                if (havePrev) canvas.drawLine(prevX, prevY, x, y, paint)
                prevX = x
                prevY = y
                havePrev = true
            }
        }
        canvasDirty = true
    }

    companion object {
        const val MAX_CANVAS_LONG_SIDE = 1920
        /** Points sampled along each court line before mapping — dense enough for any warp. */
        private const val SAMPLES_PER_LINE = 40
        private const val LINE = 0xB3E9EEF6.toInt()   // #E9EEF6 at 70% alpha
        private const val NET = 0xFFFFD23F.toInt()    // #FFD23F
    }
}
