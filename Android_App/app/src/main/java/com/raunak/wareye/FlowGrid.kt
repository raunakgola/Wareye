package com.raunak.wareye

import android.content.Context
import android.view.View
import android.view.ViewGroup
import kotlin.math.max

/**
 * Lays children out in [columns] equal columns (class tiles), or, when columns == 0,
 * as a wrapping flow (dense class chips). GONE children are skipped, so search can filter.
 */
class FlowGrid(context: Context, private val columns: Int, private val gap: Int) : ViewGroup(context) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val colW = if (columns > 0) (width - gap * (columns - 1)) / columns else 0
        var x = 0; var y = 0; var rowH = 0; var col = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            val ws = if (columns > 0) MeasureSpec.makeMeasureSpec(colW, MeasureSpec.EXACTLY)
            else MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST)
            c.measure(ws, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val wrap = if (columns > 0) col == columns else x > 0 && x + c.measuredWidth > width
            if (wrap) { x = 0; y += rowH + gap; rowH = 0; col = 0 }
            x += c.measuredWidth + gap
            rowH = max(rowH, c.measuredHeight)
            col++
        }
        setMeasuredDimension(width, if (rowH == 0) 0 else y + rowH)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        var x = 0; var y = 0; var rowH = 0; var col = 0
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            val wrap = if (columns > 0) col == columns else x > 0 && x + c.measuredWidth > width
            if (wrap) { x = 0; y += rowH + gap; rowH = 0; col = 0 }
            val left = if (rtl) width - x - c.measuredWidth else x
            c.layout(left, y, left + c.measuredWidth, y + c.measuredHeight)
            x += c.measuredWidth + gap
            rowH = max(rowH, c.measuredHeight)
            col++
        }
    }
}
