package com.raunak.wareye

import android.content.Context
import android.content.SharedPreferences

/** App settings in SharedPreferences, with in-memory copies the inference thread can read. */
object Settings {
    const val DEFAULT_CONF = 0.25f
    const val DEFAULT_IOU = 0.70f

    private lateinit var sp: SharedPreferences

    @Volatile var conf = DEFAULT_CONF
        set(v) { field = v; sp.edit().putFloat("conf", v).apply() }
    @Volatile var iou = DEFAULT_IOU
        set(v) { field = v; sp.edit().putFloat("iou", v).apply() }
    @Volatile var useGpu = true
        set(v) { field = v; sp.edit().putBoolean("gpu", v).apply() }
    @Volatile var showMetrics = true
        set(v) { field = v; sp.edit().putBoolean("metrics", v).apply() }

    /** Selected class ids. Unknown ids from older saves are dropped silently. */
    @Volatile var selected: Set<Int> = emptySet()
        set(v) { field = v.filter { it in ClassRegistry.ids }.toSet(); sp.edit().putString("sel", encode(field)).apply() }
    var recent: List<Int> = emptyList()
        set(v) { field = v.filter { it in ClassRegistry.ids }; sp.edit().putString("recent", encode(field)).apply() }

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.applicationContext.getSharedPreferences("wareye", Context.MODE_PRIVATE)
        conf = sp.getFloat("conf", DEFAULT_CONF)
        iou = sp.getFloat("iou", DEFAULT_IOU)
        useGpu = sp.getBoolean("gpu", true)
        showMetrics = sp.getBoolean("metrics", true)
        selected = decode(sp.getString("sel", null)).toSet()
        recent = decode(sp.getString("recent", null))
    }

    fun resetTuning() {
        conf = DEFAULT_CONF
        iou = DEFAULT_IOU
        showMetrics = true
    }

    private fun encode(ids: Collection<Int>) = ids.joinToString(",")

    private fun decode(s: String?): List<Int> =
        s.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in ClassRegistry.ids }.distinct()
}
