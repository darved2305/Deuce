package com.elevate.app.playback

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.CanvasOverlay
import androidx.media3.common.OverlaySettings
import androidx.media3.effect.StaticOverlaySettings
import com.elevate.app.domain.model.TrailTrack
import kotlin.math.max

/**
 * Draws the shuttle trail into the video frame inside Media3's effect pipeline (GL thread).
 *
 * The only clock is the `presentationTimeUs` of the frame being processed, so the drawing is
 * correct at any speed, while paused, after seeks and on replay. Only labelled positions are drawn:
 * the current frame's (large, orange) and those within [trailWindowUs] before it (small, fading);
 * a line joins two positions only when they are on consecutive labelled frames, so a missing or
 * not-visible frame breaks the line. Nothing is interpolated.
 *
 * The canvas is in frame pixels (source pixels after rotation, the labels' space), downscaled for
 * frames larger than [MAX_CANVAS_LONG_SIDE] and scaled back up by [getOverlaySettings].
 */
@UnstableApi
class TrailOverlay(private val trailWindowUs: Long = 400_000) : CanvasOverlay(/* useInputFrameSize= */ false) {

    private class Prepared(val track: TrailTrack, val toleranceUs: Long, val frameStepUs: Long)

    @Volatile private var prepared: Prepared? = null
    @Volatile var visible: Boolean = true

    /** Source time of the played file's first frame (0 for the gallery original). */
    @Volatile var clipStartInSourceUs: Long = 0

    /**
     * Debug builds: stamp each frame with its own presentationTimeUs, drawn into that frame. A
     * screenshot then shows a frame together with the time the trail lookup used for it.
     */
    @Volatile var stampPresentationTime: Boolean = false

    /** Called on the GL thread with each processed frame's presentationTimeUs (instrumented tests). */
    @Volatile var onFrame: ((Long) -> Unit)? = null

    private var downscale = 1
    private var frameWidth = 0
    private var frameHeight = 0
    private var canvasDirty = false

    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ORANGE; style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ORANGE; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val currentFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ORANGE; style = Paint.Style.FILL }
    private val currentRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE }
    private val stampText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = android.graphics.Typeface.MONOSPACE }
    private val stampBack = Paint().apply { color = 0xB0000000.toInt() }
    private var overlaySettings: OverlaySettings = StaticOverlaySettings.Builder().build()

    fun setTrack(track: TrailTrack?) {
        prepared = track?.let {
            val tol = TimeMapping.matchToleranceUs(it.tUs)
            Prepared(it, tol, max(1, 2 * tol + 1))
        }
    }

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        frameWidth = videoSize.width
        frameHeight = videoSize.height
        downscale = max(1, (max(frameWidth, frameHeight) + MAX_CANVAS_LONG_SIDE - 1) / MAX_CANVAS_LONG_SIDE)
        setCanvasSize(max(1, frameWidth / downscale), max(1, frameHeight / downscale))
        overlaySettings = StaticOverlaySettings.Builder().setScale(downscale.toFloat(), downscale.toFloat()).build()
        canvasDirty = true
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = overlaySettings

    override fun onDraw(canvas: Canvas, presentationTimeUs: Long) {
        onFrame?.invoke(presentationTimeUs)
        val p = prepared
        val drawTrail = visible && p != null
        val stamp = stampPresentationTime
        // An untouched bitmap is not re-uploaded (BitmapOverlay compares generation IDs), so skip
        // clearing when the canvas is already empty.
        if (!drawTrail && !stamp && !canvasDirty) return
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvasDirty = false
        if (stamp) drawStamp(canvas, presentationTimeUs)
        if (!drawTrail) return

        val track = p.track
        val sourceUs = TimeMapping.presentationToSourceUs(presentationTimeUs, clipStartInSourceUs)
        if (sourceUs < track.rangeStartUs - p.toleranceUs || sourceUs > track.rangeEndUs + p.toleranceUs) {
            canvasDirty = true
            return
        }

        // Labels are in clip.width x clip.height; the canvas is the decoded frame / downscale.
        val sx = canvas.width.toFloat() / track.clip.width
        val sy = canvas.height.toFloat() / track.clip.height
        val unit = canvas.height / 1080f
        linePaint.strokeWidth = 2.5f * unit
        currentRing.strokeWidth = 3f * unit

        val window = TimeMapping.trailWindow(track, sourceUs, trailWindowUs, p.toleranceUs)
        val current = TimeMapping.labelIndexAt(track, sourceUs, p.toleranceUs)
        for (i in window) {
            if (!track.isObserved(i) || i == current) continue
            val age = (sourceUs - track.tUs[i]).coerceAtLeast(0)
            val alpha = (255 * (1f - age.toFloat() / trailWindowUs)).toInt().coerceIn(60, 230)
            val prev = i - 1
            if (prev >= window.first && track.isObserved(prev) && track.tUs[i] - track.tUs[prev] <= p.frameStepUs + p.toleranceUs) {
                linePaint.alpha = alpha / 2
                canvas.drawLine(track.x[prev] * sx, track.y[prev] * sy, track.x[i] * sx, track.y[i] * sy, linePaint)
            }
            trailPaint.alpha = alpha
            canvas.drawCircle(track.x[i] * sx, track.y[i] * sy, 4.5f * unit, trailPaint)
        }
        if (current >= 0 && track.isObserved(current)) {
            val prev = current - 1
            if (prev >= 0 && track.isObserved(prev) && track.tUs[current] - track.tUs[prev] <= p.frameStepUs + p.toleranceUs) {
                linePaint.alpha = 140
                canvas.drawLine(track.x[prev] * sx, track.y[prev] * sy, track.x[current] * sx, track.y[current] * sy, linePaint)
            }
            canvas.drawCircle(track.x[current] * sx, track.y[current] * sy, 9f * unit, currentFill)
            canvas.drawCircle(track.x[current] * sx, track.y[current] * sy, 9f * unit, currentRing)
        }
        canvasDirty = true
    }

    private fun drawStamp(canvas: Canvas, presentationTimeUs: Long) {
        val unit = canvas.height / 1080f
        stampText.textSize = 28f * unit
        val text = "PTS %d.%06d s".format(presentationTimeUs / 1_000_000, presentationTimeUs % 1_000_000)
        val w = stampText.measureText(text)
        val pad = 10f * unit
        val right = canvas.width - 16f * unit
        val bottom = canvas.height - 16f * unit
        canvas.drawRect(right - w - 2 * pad, bottom - stampText.textSize - 2 * pad, right, bottom, stampBack)
        canvas.drawText(text, right - w - pad, bottom - pad - stampText.descent(), stampText)
        canvasDirty = true
    }

    companion object {
        const val MAX_CANVAS_LONG_SIDE = 1920
        private const val ORANGE = 0xFFE8622A.toInt()
    }
}
