package com.elevate.app.ui.overview

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.FileProvider
import com.elevate.app.domain.calibration.CourtModel
import com.elevate.app.domain.coach.LandingEvidence
import com.elevate.app.domain.coach.ShareCard
import com.elevate.app.ui.theme.ElevateColors
import java.io.File

/** Draws the match card with `android.graphics.Canvas` and hands it to the share sheet. */
internal object ShareCardRenderer {

    private const val PAD = 72f
    private const val STATS_X = 560f
    private const val COURT_TOP = 230f
    private const val COURT_HEIGHT = 700f
    private const val COURT_WIDTH = 440f

    fun render(lines: ShareCard.Lines, landings: LandingEvidence, c: ElevateColors = ElevateColors()): Bitmap {
        val bmp = Bitmap.createBitmap(ShareCard.WIDTH_PX, ShareCard.HEIGHT_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(c.cream.toArgb())

        text(canvas, lines.brand, PAD, 130f, 72f, c.orange, bold = true)
        text(canvas, lines.sport, PAD, 190f, 38f, c.inkMuted)

        val bounds = landings.template?.let(CourtModel::courtBounds)
        if (bounds != null) drawCourt(canvas, bounds, landings, c)

        var y = COURT_TOP + 70f
        val statsX = if (bounds != null) STATS_X else PAD
        for (stat in lines.stats) {
            text(canvas, stat.label, statsX, y, 34f, c.inkMuted)
            text(canvas, stat.value, statsX, y + 78f, 72f, c.ink, bold = true)
            y += 220f
        }
        if (bounds != null && landings.points.isNotEmpty()) {
            val ring = if (landings.allCandidates) "Rings: where the shuttle likely landed" else "Dots: where the shuttle landed"
            text(canvas, ring, statsX, COURT_TOP + COURT_HEIGHT - 10f, 28f, c.inkMuted)
        }

        lines.topSuggestion?.let { paragraph(canvas, it, PAD, 1000f, ShareCard.WIDTH_PX - 2 * PAD, 40f, c.ink, maxLines = 5) }
        text(canvas, lines.footer, PAD, 1290f, 32f, c.inkMuted)
        return bmp
    }

    private fun drawCourt(canvas: Canvas, bounds: Pair<Double, Double>, landings: LandingEvidence, c: ElevateColors) {
        val f = ShareCard.courtFrame(PAD, COURT_TOP, COURT_WIDTH, COURT_HEIGHT, bounds)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c.court.toArgb() }
        canvas.drawRoundRect(RectF(f.left, f.top, f.left + f.width, f.top + f.height), 12f, 12f, fill)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.White.toArgb(); style = Paint.Style.STROKE; strokeWidth = 3f }
        for (seg in CourtModel.lines(landings.template ?: return)) {
            line.strokeWidth = if (seg.name == "net") 8f else 3f
            canvas.drawLine(f.px(seg.start.first), f.py(seg.start.second), f.px(seg.end.first), f.py(seg.end.second), line)
        }
        // Candidates are hollow rings (never drawn as certain); anything else is a filled dot.
        val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.White.toArgb(); style = Paint.Style.STROKE; strokeWidth = 10f }
        val haloFill = Paint(halo).apply { style = Paint.Style.FILL }
        val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c.orangeDeep.toArgb() }
        val ringPaint = Paint(mark).apply { style = Paint.Style.STROKE; strokeWidth = 5f }
        for (p in landings.points) {
            if (!f.contains(p.xM, p.yM)) continue
            val x = f.px(p.xM)
            val y = f.py(p.yM)
            if (landings.allCandidates) {
                canvas.drawCircle(x, y, 11f, halo)
                canvas.drawCircle(x, y, 11f, ringPaint)
            } else {
                canvas.drawCircle(x, y, 12f, haloFill)
                canvas.drawCircle(x, y, 9f, mark)
            }
        }
    }

    private fun text(canvas: Canvas, s: String, x: Float, baseline: Float, size: Float, color: Color, bold: Boolean = false) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.toArgb()
            textSize = size
            typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
        canvas.drawText(s, x, baseline, p)
    }

    private fun paragraph(canvas: Canvas, s: String, x: Float, top: Float, width: Float, size: Float, color: Color, maxLines: Int) {
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.toArgb()
            textSize = size
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val layout = StaticLayout.Builder.obtain(s, 0, s.length, p, width.toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.15f)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        canvas.save()
        canvas.translate(x, top)
        layout.draw(canvas)
        canvas.restore()
    }

    /** Writes the card to cache/share/ (file work: call off the main thread). */
    fun writePng(context: Context, bitmap: Bitmap): File {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "elevate-match.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    fun shareIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share your match card").apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
}
