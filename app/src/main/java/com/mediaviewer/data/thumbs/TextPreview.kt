package com.mediaviewer.data.thumbs

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.StyleSpan
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Renders the first lines of a text file into a tile-sized bitmap (README §3.10). The bitmap is
 * `ALPHA_8` (glyph coverage only): the tile tints it with the theme colour, so one cached bitmap
 * serves light and dark themes at a quarter of the memory. Blocking: decode dispatcher only.
 */
object TextPreview {
    /** Fewer whole lines than this fit: the tile shows the icon instead. */
    const val MIN_LINES = 3
    private const val LINE_HEIGHT = 1.2f

    /**
     * @param textPx font size in px (10 sp, scaled with the system font size up to 13 sp; README §3.10).
     * @param padPx padding on each side.
     * @return null when fewer than [MIN_LINES] lines fit or there is no text.
     */
    fun render(text: String, truncated: Boolean, width: Int, height: Int, textPx: Float, padPx: Int, monospace: Boolean, markdown: Boolean): Bitmap? {
        val lineH = textPx * LINE_HEIGHT
        val innerW = width - 2 * padPx
        val innerH = height - 2 * padPx
        val maxLines = floor(innerH / lineH).toInt()
        if (maxLines < MIN_LINES || innerW <= textPx * 4) return null
        val lines = TextDecode.lines(text, maxLines, truncated)
        if (lines.isEmpty()) return null

        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG or TextPaint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = textPx
            typeface = if (monospace) Typeface.MONOSPACE else Typeface.DEFAULT
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(bitmap)
        canvas.clipRect(padPx, padPx, width - padPx, height - padPx)
        if (monospace) {
            // Code and logs: one line per line, the end cut off at the edge, never wrapped.
            val fm = paint.fontMetrics
            val baseline = (lineH - (fm.descent - fm.ascent)) / 2 - fm.ascent
            lines.forEachIndexed { i, line -> canvas.drawText(line, padPx.toFloat(), padPx + i * lineH + baseline, paint) }
        } else {
            // Prose: soft-wrapped; only lines that fit completely are drawn.
            val sb = SpannableStringBuilder()
            lines.forEachIndexed { i, line ->
                if (i > 0) sb.append('\n')
                val heading = markdown && line.startsWith("#")
                val shown = if (heading) line.trimStart('#').trimStart() else line
                val start = sb.length
                sb.append(shown)
                if (heading) sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val fm = paint.fontMetrics
            val extra = lineH - (fm.descent - fm.ascent)
            val layout = StaticLayout.Builder.obtain(sb, 0, sb.length, paint, innerW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(extra, 1f)
                .setIncludePad(false)
                .setMaxLines(maxLines)
                .build()
            var shown = 0
            while (shown < layout.lineCount && ceil(layout.getLineBottom(shown).toFloat()) <= innerH) shown++
            if (shown == 0) return null
            canvas.save()
            canvas.translate(padPx.toFloat(), padPx.toFloat())
            canvas.clipRect(0f, 0f, innerW.toFloat(), layout.getLineBottom(shown - 1).toFloat())
            layout.draw(canvas)
            canvas.restore()
        }
        return bitmap
    }
}
