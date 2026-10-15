package com.raunak.wareye

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Latency of the last 100 frames with dashed P50 / P95 guides.
 * The axis is fixed at 50..100 ms and widens when a frame falls outside it.
 */
class SparklineView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val values = FloatArray(Metrics.WINDOW)
    private var count = 0
    private var p50 = 0f
    private var p95 = 0f

    private val d = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.4f * d; strokeJoin = Paint.Join.ROUND
        color = context.getColor(R.color.forest)
    }
    private val p95Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.2f * d; color = context.getColor(R.color.red)
        pathEffect = DashPathEffect(floatArrayOf(5 * d, 4 * d), 0f)
    }
    private val p50Paint = Paint(p95Paint).apply { color = context.getColor(R.color.ink_muted) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE; textSize = 11 * resources.configuration.fontScale.coerceAtMost(1.3f) * d
        color = context.getColor(R.color.ink_muted)
    }
    private val path = Path()

    fun set(src: FloatArray, n: Int, p50: Float, p95: Float) {
        System.arraycopy(src, 0, values, 0, n)
        count = n
        this.p50 = p50
        this.p95 = p95
        contentDescription = "Latency, last $n frames. P50 %.0f ms, P95 %.0f ms.".format(p50, p95)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val pad = 12 * d
        val labelH = 18 * d
        val left = pad
        val right = width - pad
        val top = pad + labelH
        val bottom = height - pad - labelH
        var lo = 50f
        var hi = 100f
        for (i in 0 until count) { lo = min(lo, values[i]); hi = max(hi, values[i]) }
        hi += (hi - lo) * 0.05f
        fun y(v: Float) = bottom - (v - lo) / (hi - lo) * (bottom - top)

        if (count > 0) {
            text.color = context.getColor(R.color.red)
            canvas.drawLine(left, y(p95), right, y(p95), p95Paint)
            canvas.drawText("P95 %.0f".format(p95), left, y(p95) - 4 * d, text)
            text.color = context.getColor(R.color.ink_muted)
            canvas.drawLine(left, y(p50), right, y(p50), p50Paint)
            canvas.drawText("P50 %.0f".format(p50), left, y(p50) + 13 * d, text)

            path.reset()
            val step = (right - left) / (Metrics.WINDOW - 1)
            // Right-aligned: the newest frame is always at "now".
            val x0 = right - (count - 1) * step
            for (i in 0 until count) {
                val x = x0 + i * step
                if (i == 0) path.moveTo(x, y(values[i])) else path.lineTo(x, y(values[i]))
            }
            canvas.drawPath(path, line)
        }
        text.color = context.getColor(R.color.ink_muted)
        val base = height - pad
        canvas.drawText("${Metrics.WINDOW} frames ago", left, base, text)
        canvas.drawText("now", right - text.measureText("now"), base, text)
    }
}
