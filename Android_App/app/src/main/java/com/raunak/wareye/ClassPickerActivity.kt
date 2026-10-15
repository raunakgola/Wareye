package com.raunak.wareye

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.raunak.wareye.databinding.ActivityPickerBinding

/** Class picker driven entirely by [ClassRegistry]: categories, per-category select all, recent, search. */
class ClassPickerActivity : ComponentActivity() {
    private lateinit var b: ActivityPickerBinding

    private class CategoryViews(
        val name: String,
        val classes: List<DetClass>,
        val header: View,
        val chevron: ImageView,
        val count: TextView,
        val action: TextView,
        val grid: FlowGrid,
    )

    private val selected = LinkedHashSet<Int>()
    private val expanded = HashSet<String>()
    private val tiles = HashMap<Int, TextView>()
    private val categories = ArrayList<CategoryViews>()
    private val total = ClassRegistry.all.size
    /** Dense mode: chips + search once the list grows past 12 classes. */
    private val dense = total > 12
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(this)
        edgeToEdge(lightBars = true)
        b = ActivityPickerBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.root.padForSystemBars()

        selected.addAll(Settings.selected)
        ClassRegistry.categories.forEach { (name, classes) ->
            if (!dense || classes.any { it.id in selected }) expanded.add(name)
        }
        if (dense) {
            b.title.setText(R.string.choose_classes)
            b.subtitle.visibility = View.GONE
            b.search.visibility = View.VISIBLE
            b.search.hint = getString(R.string.search_hint, total)
            b.search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
                override fun afterTextChanged(s: Editable?) { query = s.toString().trim().lowercase(); refresh() }
            })
        }

        buildCategories()

        b.back.setOnClickListener { finish() }
        b.selectAll.setOnClickListener { selected.addAll(ClassRegistry.ids); refresh() }
        b.clear.setOnClickListener { selected.clear(); refresh() }
        b.useAgain.setOnClickListener {
            selected.clear(); selected.addAll(Settings.recent); refresh()
        }
        b.start.setOnClickListener {
            if (selected.isEmpty()) {
                b.start.announceForAccessibility(getString(R.string.pick_one_hint))
                return@setOnClickListener
            }
            val ordered = ClassRegistry.all.map { it.id }.filter { it in selected }
            Settings.selected = selected
            Settings.recent = ordered
            Engine.setSelected(selected)
            startActivity(Intent(this, ScanActivity::class.java))
        }
        refresh()
    }

    private fun buildCategories() {
        val gap = dp(if (dense) 8f else 12f)
        for ((name, classes) in ClassRegistry.categories) {
            val header = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48f)
                isClickable = true
                isFocusable = true
                background = getDrawable(R.drawable.ripple_rect)
            }
            val chevron = ImageView(this).apply {
                setImageResource(R.drawable.ic_chevron)
                imageTintList = getColorStateList(R.color.ink)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val title = TextView(this).apply {
                text = name
                setTextAppearance(R.style.T_TitleMedium)
                setPadding(dp(6f), 0, dp(8f), 0)
            }
            val count = TextView(this).apply {
                setTextAppearance(R.style.T_LabelSmall)
                gravity = Gravity.CENTER_VERTICAL
                compoundDrawablePadding = dp(2f)
            }
            val spacer = View(this)
            val action = TextView(this).apply { setTextAppearance(R.style.T_Label) }
            action.minHeight = dp(48f)
            action.gravity = Gravity.CENTER
            action.setPadding(dp(8f), 0, dp(8f), 0)
            action.background = getDrawable(R.drawable.ripple_rect)
            action.setTextColor(getColorStateList(R.color.text_button_red))
            header.addView(chevron, LinearLayout.LayoutParams(dp(24f), dp(24f)))
            header.addView(title)
            header.addView(count)
            header.addView(spacer, LinearLayout.LayoutParams(0, 0, 1f))
            header.addView(action)

            val grid = FlowGrid(this, if (dense) 0 else 2, gap)
            for (c in classes) grid.addView(makeTile(c))

            b.categories.addView(header, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12f) })
            b.categories.addView(grid, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4f); bottomMargin = dp(4f) })
            if (dense) b.categories.addView(View(this).apply { background = getDrawable(R.drawable.divider_line) },
                LinearLayout.LayoutParams(-1, dp(1f)).apply { topMargin = dp(8f) })

            val cv = CategoryViews(name, classes, header, chevron, count, action, grid)
            categories.add(cv)
            header.setOnClickListener {
                if (!expanded.remove(name)) expanded.add(name)
                refresh()
            }
            action.setOnClickListener {
                val ids = classes.map { it.id }
                if (selected.containsAll(ids)) selected.removeAll(ids.toSet()) else selected.addAll(ids)
                refresh()
            }
        }
        // Move the "no results" chip to the end.
        b.categories.removeView(b.noResults)
        b.categories.addView(b.noResults)
    }

    private fun makeTile(c: DetClass): TextView {
        val t = TextView(this).apply {
            text = c.title
            setTextAppearance(R.style.T_BodyLarge)
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            if (dense) {
                background = getDrawable(R.drawable.class_chip)
                minHeight = dp(44f)
                setPadding(dp(14f), 0, dp(14f), 0)
                compoundDrawablePadding = dp(4f)
            } else {
                background = getDrawable(R.drawable.class_tile)
                minHeight = dp(64f)
                setPadding(dp(16f), 0, dp(14f), 0)
            }
            setOnClickListener {
                if (!selected.remove(c.id)) selected.add(c.id)
                refresh()
            }
        }
        // Announced as a checkbox with its checked state (aria-pressed equivalent).
        ViewCompat.setAccessibilityDelegate(t, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.CheckBox"
                info.isCheckable = true
                info.isChecked = c.id in selected
            }
        })
        tiles[c.id] = t
        return t
    }

    private fun refresh() {
        val searching = query.isNotEmpty()
        var anyVisible = false
        for (cv in categories) {
            var visibleInCat = 0
            for (c in cv.classes) {
                val tile = tiles.getValue(c.id)
                val on = c.id in selected
                val matches = !searching || c.label.contains(query) || cv.name.lowercase().contains(query)
                tile.visibility = if (matches) View.VISIBLE else View.GONE
                if (matches) visibleInCat++
                tile.isActivated = on
                tile.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL), if (on) Typeface.BOLD else Typeface.NORMAL)
                val icon = when {
                    !on -> null
                    dense -> getDrawable(R.drawable.ic_check)?.mutate()?.also { it.setTint(getColor(R.color.forest)) }
                    else -> getDrawable(R.drawable.ic_check_badge)
                }
                if (dense) tile.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
                else tile.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, icon, null)
            }
            val n = cv.classes.count { it.id in selected }
            // Searching auto-expands matching groups.
            val open = if (searching) visibleInCat > 0 else cv.name in expanded
            cv.header.visibility = if (!searching || visibleInCat > 0) View.VISIBLE else View.GONE
            cv.grid.visibility = if (open && cv.header.visibility == View.VISIBLE) View.VISIBLE else View.GONE
            cv.chevron.rotation = if (open) 0f else -90f
            cv.count.text = "$n / ${cv.classes.size}"
            val badge = !open && n > 0
            cv.count.setCompoundDrawablesRelativeWithIntrinsicBounds(
                if (badge) getDrawable(R.drawable.ic_check)?.mutate()?.also { it.setTint(getColor(R.color.forest)) } else null,
                null, null, null)
            cv.count.background = if (badge) getDrawable(R.drawable.class_chip) else null
            cv.count.isActivated = badge
            cv.count.setPadding(if (badge) dp(6f) else 0, dp(2f), if (badge) dp(8f) else 0, dp(2f))
            val all = n == cv.classes.size
            cv.action.text = getString(if (all) R.string.clear else R.string.select_all)
            ViewCompat.setStateDescription(cv.header, if (open) "Expanded" else "Collapsed")
            cv.header.contentDescription = "${cv.name}, $n of ${cv.classes.size} selected"
            if (cv.header.visibility == View.VISIBLE) anyVisible = true
        }
        b.noResults.visibility = if (searching && !anyVisible) View.VISIBLE else View.GONE
        b.noResults.text = getString(R.string.no_results, query)

        val n = selected.size
        b.counter.text = if (dense) getString(R.string.selected_n, n) else getString(R.string.selected_of, n, total)
        b.selectAll.text = if (dense) getString(R.string.select_all_n, total) else getString(R.string.select_all)
        b.selectAll.isEnabled = n < total
        b.clear.isEnabled = n > 0

        val recent = Settings.recent
        b.recentRow.visibility = if (recent.isEmpty()) View.GONE else View.VISIBLE
        b.recentNames.text = recent.joinToString(" · ") { ClassRegistry[it]?.title ?: "" }
        val applied = recent.isNotEmpty() && selected == recent.toSet()
        b.useAgain.text = getString(if (applied) R.string.applied else R.string.use_again)
        b.useAgain.setCompoundDrawablesRelativeWithIntrinsicBounds(if (applied) getDrawable(R.drawable.ic_check) else null, null, null, null)

        // Disabled look at 0, but stays focusable and announces why.
        b.start.isActivated = n > 0
        b.start.text = if (n > 0) getString(R.string.start_scanning_n, n) else getString(R.string.start_scanning)
        b.start.contentDescription = if (n > 0) null else getString(R.string.cd_start_disabled)
        b.startHint.visibility = if (n > 0) View.GONE else View.VISIBLE
    }
}
