package com.raunak.wareye

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Draws detection boxes over a FILL_CENTER preview. Boxes ease toward new positions (~80 ms),
 * labels are re-placed every frame with the design's collision rules.
 */
class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private class Track(val cls: Int, var score: Float, val cur: RectF, val target: RectF)

    private val tracks = ArrayList<Track>()
    private var lastDrawMs = 0L

    var paused = false
        set(v) { field = v; invalidate() }

    private val dp = resources.displayMetrics.density
    private val strokeW = 2 * dp
    private val keylineW = 1 * dp
    private val labelH = 20 * dp
    private val labelPad = 6 * dp
    private val labelStep = 22 * dp
    private val labelInset = 2 * dp
    private val labelRadius = 4 * dp
    private val minBoxForInsideLabel = 44 * dp

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = strokeW }
    private val keylinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = keylineW; color = 0x73000000 // 45% black
    }
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF121210.toInt()
        typeface = Typeface.DEFAULT_BOLD
        // overlayLabel: 12sp, capped at 1.3x font scale to protect the preview.
        val scale = min(resources.configuration.fontScale, 1.3f)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f * scale, resources.displayMetrics)
    }

    private val placed = ArrayList<RectF>()
    private val labelRect = RectF()
    private val boxRect = RectF()

    fun clear() {
        tracks.clear()
        invalidate()
    }

    /** Maps normalized upright-frame boxes into view pixels (same math as PreviewView FILL_CENTER). */
    fun setResults(r: FrameResult) {
        if (width == 0 || height == 0) return
        val scale = max(width.toFloat() / r.frameW, height.toFloat() / r.frameH)
        val ox = (width - r.frameW * scale) / 2f
        val oy = (height - r.frameH * scale) / 2f
        val sw = r.frameW * scale
        val sh = r.frameH * scale

        val old = ArrayList(tracks)
        tracks.clear()
        for (d in r.detections.sortedByDescending { it.score }) {
            val target = RectF(ox + d.x1 * sw, oy + d.y1 * sh, ox + d.x2 * sw, oy + d.y2 * sh)
            // Continue the best-overlapping track of the same class, so the box glides instead of jumping.
            var best: Track? = null
            var bestIou = 0.3f
            for (t in old) {
                if (t.cls != d.cls) continue
                val v = iou(t.target, target)
                if (v > bestIou) { bestIou = v; best = t }
            }
            if (best != null) {
                old.remove(best)
                best.target.set(target)
                best.score = d.score
                tracks.add(best)
            } else {
                tracks.add(Track(d.cls, d.score, RectF(target), target))
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastDrawMs == 0L) 16L else min(now - lastDrawMs, 100L)
        lastDrawMs = now
        val k = 1f - exp(-dt / SMOOTH_MS)
        var moving = false

        val boxAlpha = if (paused) 153 else 255 // paused: frozen, 60% stroke
        for (t in tracks) {
            if (!paused) moving = ease(t.cur, t.target, k) || moving
            keylinePaint.alpha = if (paused) 70 else 115
            val o = strokeW / 2 + keylineW / 2
            canvas.drawRect(t.cur.left - o, t.cur.top - o, t.cur.right + o, t.cur.bottom + o, keylinePaint)
            boxPaint.color = ClassRegistry.color(t.cls)
            boxPaint.alpha = boxAlpha
            canvas.drawRect(t.cur, boxPaint)
        }
        drawLabels(canvas)
        if (moving) postInvalidateOnAnimation()
    }

    private fun drawLabels(canvas: Canvas) {
        placed.clear()
        // tracks are already in confidence order; past 12 boxes the lowest-confidence labels are hidden.
        for (i in 0 until min(tracks.size, MAX_LABELS)) {
            val t = tracks[i]
            val text = "${ClassRegistry.label(t.cls)} ${(t.score * 100).toInt()}%"
            val w = textPaint.measureText(text) + 2 * labelPad
            if (!place(t.cur, w)) continue
            placed.add(RectF(labelRect))
            chipPaint.color = ClassRegistry.color(t.cls)
            canvas.drawRoundRect(labelRect, labelRadius, labelRadius, chipPaint)
            val fm = textPaint.fontMetrics
            val baseline = labelRect.centerY() - (fm.ascent + fm.descent) / 2
            canvas.drawText(text, labelRect.left + labelPad, baseline, textPaint)
        }
    }

    /** Inside-top, then 22 dp steps down, then below, then above. First free slot wins. */
    private fun place(box: RectF, w: Float): Boolean {
        boxRect.set(box)
        // Right-align when the box touches the right edge (or the label would overflow it).
        val rightAligned = box.right >= width - strokeW || box.left + labelInset + w > width
        val x = if (rightAligned) box.right - labelInset - w else box.left + labelInset
        val below = box.bottom + labelInset
        val above = box.top - labelInset - labelH

        if (box.height() >= minBoxForInsideLabel) {
            var y = box.top + labelInset
            while (y + labelH <= box.bottom - labelInset) {
                if (tryAt(x, y, w)) return true
                y += labelStep
            }
        }
        return tryAt(x, below, w) || tryAt(x, above, w)
    }

    private fun tryAt(x: Float, y: Float, w: Float): Boolean {
        val cx = x.coerceIn(0f, max(0f, width - w))
        val cy = y.coerceIn(0f, max(0f, height - labelH))
        labelRect.set(cx, cy, cx + w, cy + labelH)
        for (p in placed) if (RectF.intersects(p, labelRect)) return false
        return true
    }

    private fun ease(cur: RectF, target: RectF, k: Float): Boolean {
        cur.left += (target.left - cur.left) * k
        cur.top += (target.top - cur.top) * k
        cur.right += (target.right - cur.right) * k
        cur.bottom += (target.bottom - cur.bottom) * k
        val still = abs(cur.left - target.left) < 0.5f && abs(cur.top - target.top) < 0.5f &&
            abs(cur.right - target.right) < 0.5f && abs(cur.bottom - target.bottom) < 0.5f
        if (still) cur.set(target)
        return !still
    }

    private fun iou(a: RectF, b: RectF): Float {
        val ix = min(a.right, b.right) - max(a.left, b.left)
        val iy = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (ix <= 0 || iy <= 0) return 0f
        val inter = ix * iy
        return inter / (a.width() * a.height() + b.width() * b.height() - inter)
    }

    companion object {
        private const val SMOOTH_MS = 80f
        private const val MAX_LABELS = 12
    }
}
