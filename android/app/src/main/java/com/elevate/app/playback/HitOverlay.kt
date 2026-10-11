package com.elevate.app.playback

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PorterDuff
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.CanvasOverlay
import androidx.media3.effect.StaticOverlaySettings
import com.elevate.app.domain.analysis.LiveHit
import com.elevate.app.domain.analysis.LiveHits
import kotlin.math.max

/**
 * Draws engine hit candidates into the video frame inside Media3's effect pipeline, the same way
 * as [TrailOverlay]: the only clock is the processed frame's `presentationTimeUs`, so the marks
 * are right at any speed, paused, after seeks and on replay.
 *
 * Every hit within ±[WINDOW_MS] of the frame's source time that has an `image_xy` gets a dashed
 * ring there, labelled "HIT?". Dashed, never solid: these are candidates, not confirmed hits. No
 * pulsing or animation (nothing is driven by a timer). Hits without `image_xy` draw nothing on the
 * video (they still show on the strip below it).
 */
@UnstableApi
class HitOverlay : CanvasOverlay(/* useInputFrameSize = */ false) {

    private class Prepared(val tMs: LongArray, val hits: List<LiveHit>, val imageW: Int?, val imageH: Int?)

    @Volatile private var prepared: Prepared? = null
    @Volatile var visible: Boolean = true

    /** Source time of the played file's first frame (0 for the gallery original). */
    @Volatile var clipStartInSourceUs: Long = 0

    private var downscale = 1
    private var frameWidth = 0
    private var frameHeight = 0
    private var canvasDirty = false
    private var overlaySettings: OverlaySettings = StaticOverlaySettings.Builder().build()

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LIME; style = Paint.Style.STROKE }
    private val ringShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SHADOW; style = Paint.Style.STROKE }
    private val landRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ORANGE; style = Paint.Style.STROKE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LIME
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val labelBack = Paint().apply { color = SHADOW }
    private var dashUnit = -1f

    /**
     * New hit candidates (sorted or not), or empty to draw nothing. [imageW] x [imageH] is the space
     * of `image_xy` (source pixels after rotation); null = the decoded frame's own size.
     */
    fun setHits(hits: List<LiveHit>, imageW: Int? = null, imageH: Int? = null) {
        val sorted = hits.sortedBy { it.tMs }
        prepared = if (sorted.isEmpty()) null else Prepared(LongArray(sorted.size) { sorted[it].tMs }, sorted, imageW, imageH)
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
        val p = prepared
        val draw = visible && p != null
        // An untouched bitmap is not re-uploaded, so skip clearing when the canvas is already empty.
        if (!draw && !canvasDirty) return
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvasDirty = false
        if (!draw || p == null) return

        val sourceMs = Math.floorDiv(TimeMapping.presentationToSourceUs(presentationTimeUs, clipStartInSourceUs), 1000L)
        val near = LiveHits.indicesNear(p.tMs, sourceMs, WINDOW_MS)
        if (near.isEmpty()) return

        val spaceW = (p.imageW ?: frameWidth).takeIf { it > 0 } ?: return
        val spaceH = (p.imageH ?: frameHeight).takeIf { it > 0 } ?: return
        val sx = canvas.width.toFloat() / spaceW
        val sy = canvas.height.toFloat() / spaceH
        // Never smaller than on 1080p (same rule as the trail).
        val unit = max(canvas.height / 1080f, 1f)
        ring.strokeWidth = 3f * unit
        landRing.strokeWidth = 3f * unit
        ringShadow.strokeWidth = 5f * unit
        if (dashUnit != unit) {
            val dash = DashPathEffect(floatArrayOf(7f * unit, 5f * unit), 0f)
            ring.pathEffect = dash
            landRing.pathEffect = dash
            ringShadow.pathEffect = dash
            dashUnit = unit
        }
        label.textSize = 18f * unit
        val radius = 22f * unit
        for (i in near) {
            val hit = p.hits[i]
            val xy = hit.imageXy ?: continue
            val x = xy.first * sx
            val y = xy.second * sy
            val text = if (hit.landing) LANDING_LABEL else LABEL
            label.color = if (hit.landing) ORANGE else LIME
            canvas.drawCircle(x, y, radius, ringShadow)
            canvas.drawCircle(x, y, radius, if (hit.landing) landRing else ring)
            val pad = 3f * unit
            val tw = label.measureText(text)
            val left = x + radius * 0.75f
            val bottom = y - radius * 0.75f
            canvas.drawRect(left, bottom - label.textSize - pad, left + tw + 2 * pad, bottom + pad, labelBack)
            canvas.drawText(text, left + pad, bottom - label.descent() + pad / 2, label)
            canvasDirty = true
        }
    }

    companion object {
        const val MAX_CANVAS_LONG_SIDE = 1920
        /** A hit candidate is marked on frames within this many ms of its time. */
        const val WINDOW_MS = 150L
        const val LABEL = "Hit"
        /** Landing candidates from `match.v1` (contracts §7): dashed, never a verdict. */
        const val LANDING_LABEL = "Lands"
        private const val ORANGE = 0xFFFF7A1A.toInt()
        private const val LIME = 0xFFB6FF3B.toInt() // lime: not the trail orange, candidate cyan or net yellow
        private const val SHADOW = 0x99000000.toInt()
    }
}
