package com.raunak.wareye

import android.app.Activity
import android.app.Dialog
import android.os.Build
import android.view.Gravity
import android.widget.FrameLayout
import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun View.dp(v: Float): Int = context.dp(v)

/** Draw edge to edge; status/navigation bar icons dark on cream screens, light on the camera screen. */
fun Activity.edgeToEdge(lightBars: Boolean) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = lightBars
        isAppearanceLightNavigationBars = lightBars
    }
}

/** Adds system bar + display cutout insets to the view's original padding. */
fun View.padForSystemBars(top: Boolean = true, bottom: Boolean = true) {
    val l = paddingLeft; val t = paddingTop; val r = paddingRight; val b = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val i = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        v.setPadding(l + i.left, t + if (top) i.top else 0, r + i.right, b + if (bottom) i.bottom else 0)
        insets
    }
}

fun ViewGroup.MarginLayoutParams.setMarginsDp(view: View, l: Float, t: Float, r: Float, b: Float) {
    setMargins(view.dp(l), view.dp(t), view.dp(r), view.dp(b))
}

/**
 * Turns a full-screen Dialog into a bottom sheet: edge to edge, max 480 dp wide, nav-bar padding,
 * tap outside to close, slide-in on show.
 */
fun Dialog.asBottomSheet(activity: Activity, scrim: View, sheet: View) {
    window?.let { w ->
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        WindowCompat.setDecorFitsSystemWindows(w, false)
        WindowCompat.getInsetsController(w, w.decorView).isAppearanceLightNavigationBars = true
        if (Build.VERSION.SDK_INT >= 29) w.isNavigationBarContrastEnforced = false
    }
    sheet.layoutParams = (sheet.layoutParams as FrameLayout.LayoutParams).apply {
        width = minOf(activity.resources.displayMetrics.widthPixels, activity.dp(480f))
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
    }
    val padBottom = sheet.paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(sheet) { v, insets ->
        val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
        v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, padBottom + nav.bottom)
        insets
    }
    scrim.setOnClickListener { dismiss() }
    setOnShowListener {
        sheet.translationY = activity.dp(400f).toFloat()
        sheet.animate().translationY(0f).setDuration(200).start()
    }
}

fun String.fmt2(v: Float) = String.format(java.util.Locale.US, this, v)
