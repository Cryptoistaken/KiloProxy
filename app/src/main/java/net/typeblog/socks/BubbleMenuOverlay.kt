package net.typeblog.socks

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import net.typeblog.socks.util.Countries
import net.typeblog.socks.util.Utility
import java.util.Locale

/**
 * Popup menu overlay for the floating control bubble.
 * Shows a scrollable country list (pinned connected country, then RECENT, then ALL).
 * Handles its own scrim click-to-close, bubble-edge scale animation, and a
 * Snackbar-style bottom overlay message for non-OwlProxy taps.
 */
class BubbleMenuOverlay(
    private val context: Context,
    private val onCountrySelected: (String) -> Unit,
    private val onDismissed: () -> Unit = {}
) {
    // Use display context so the menu overlay is a system-level window
    private val windowManager: WindowManager = run {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        val display = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val displayCtx = context.createDisplayContext(display)
        displayCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }
    private val handler = Handler(Looper.getMainLooper())
    private val messageHandler = Handler(Looper.getMainLooper())
    private var rootView: FrameLayout? = null
    private var menuList: LinearLayout? = null
    private var messageView: TextView? = null
    private var countrySwitchEnabled = false

    fun show(bubbleCenterX: Int, bubbleCenterY: Int, bubbleSizePx: Int, connectedCountryCode: String?, supportsCountrySwitch: Boolean) {
        if (isShowing()) return
        countrySwitchEnabled = supportsCountrySwitch
        val root = rootView ?: LayoutInflater.from(context)
            .inflate(R.layout.bubble_menu, null) as FrameLayout
        rootView = root

        val metrics = context.resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        val panel = root.findViewById<LinearLayout>(R.id.menu_panel)
        val scroll = root.findViewById<ScrollView>(R.id.menu_scroll)
        val list = root.findViewById<LinearLayout>(R.id.menu_list)
        val searchInput = root.findViewById<EditText>(R.id.menu_search)
        menuList = list

        val panelWidth = minOf(
            dp(230f),
            (screenW - bubbleSizePx - dp(16f)).coerceAtLeast(1)
        )
        val maxHeightPx = minOf(dp(200f), screenH - dp(32f)).coerceAtLeast(1)

        val panelLp = panel.layoutParams as? FrameLayout.LayoutParams
            ?: FrameLayout.LayoutParams(panelWidth, FrameLayout.LayoutParams.WRAP_CONTENT)
        panelLp.width = panelWidth
        panelLp.height = FrameLayout.LayoutParams.WRAP_CONTENT
        panel.layoutParams = panelLp

        list.removeAllViews()
        val shown = HashSet<String>()

        val recents = Utility.getRecentCountries(context)
        val connectedCode = connectedCountryCode?.uppercase(Locale.ROOT)
        val connectedInRecents = !connectedCode.isNullOrBlank() && recents.contains(connectedCode)

        if (!connectedCode.isNullOrBlank() && !connectedInRecents) {
            val c = Countries.fromCode(connectedCode)
            if (c != null) {
                list.addView(makeRow(c, isConnected = true))
                shown.add(c.code)
            }
        }

        val recentRows = recents.mapNotNull { code ->
            if (shown.contains(code)) null else Countries.fromCode(code)
        }
        if (recentRows.isNotEmpty()) {
            list.addView(sectionLabel("RECENT"))
            recentRows.forEach {
                list.addView(makeRow(it, isConnected = it.code == connectedCode))
                shown.add(it.code)
            }
        }

        val allRows = Countries.ALL.filter { !shown.contains(it.code) }
        if (recentRows.isNotEmpty() && allRows.isNotEmpty()) {
            list.addView(separatorView())
        }
        if (allRows.isNotEmpty()) {
            list.addView(sectionLabel("ALL"))
            allRows.forEach { list.addView(makeRow(it, isConnected = false)) }
        }

        // Store all countries for search filtering
        val allCountryItems = mutableListOf<Pair<Countries.Country, Boolean>>()
        if (!connectedCode.isNullOrBlank() && !connectedInRecents) {
            Countries.fromCode(connectedCode)?.let { allCountryItems.add(it to true) }
        }
        recents.forEach { code ->
            if (!allCountryItems.any { it.first.code == code }) {
                Countries.fromCode(code)?.let { allCountryItems.add(it to (it.code == connectedCode)) }
            }
        }
        Countries.ALL.forEach { c ->
            if (!allCountryItems.any { it.first.code == c.code }) {
                allCountryItems.add(c to false)
            }
        }

        // Search filtering
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim()?.lowercase(Locale.ROOT) ?: ""
                list.removeAllViews()
                val filtered = if (query.isEmpty()) {
                    allCountryItems
                } else {
                    val digits = query.filter { it.isDigit() }
                    allCountryItems.filter { (country, _) ->
                        country.name.lowercase(Locale.ROOT).contains(query) ||
                            country.code.lowercase(Locale.ROOT).contains(query) ||
                            (digits.isNotEmpty() && country.phone.startsWith(digits)) ||
                            (digits.isNotEmpty() && digits.startsWith(country.phone))
                    }
                }
                if (filtered.isEmpty()) {
                    list.addView(sectionLabel("NO RESULTS"))
                } else {
                    var lastWasSection = false
                    val connectedItems = filtered.filter { it.second }
                    val otherItems = filtered.filter { !it.second }

                    if (connectedItems.isNotEmpty()) {
                        connectedItems.forEach { list.addView(makeRow(it.first, isConnected = true)) }
                        lastWasSection = false
                    }
                    if (connectedItems.isNotEmpty() && otherItems.isNotEmpty()) {
                        list.addView(separatorView())
                        lastWasSection = true
                    }
                    if (otherItems.isNotEmpty()) {
                        if (!lastWasSection && connectedItems.isEmpty()) {
                            // Don't add "ALL" label during search
                        }
                        otherItems.forEach { list.addView(makeRow(it.first, isConnected = false)) }
                    }
                }
                scroll.post { scroll.fullScroll(View.FOCUS_UP) }
            }
        })

        // When search is tapped, make the overlay focusable so keyboard works
        searchInput.setOnClickListener {
            val winParams = root.layoutParams as? WindowManager.LayoutParams ?: return@setOnClickListener
            if (winParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0) {
                winParams.flags = winParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                try {
                    windowManager.updateViewLayout(root, winParams)
                } catch (_: Exception) {}
                searchInput.requestFocus()
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }
        // Also handle the case where focus already arrived (e.g. flag was cleared)
        searchInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }

        // Smart 4-side positioning: pick the side with most free space
        val margin8 = dp(8f)
        val bx = bubbleCenterX - bubbleSizePx / 2
        val by = bubbleCenterY - bubbleSizePx / 2
        val right  = screenW - (bx + bubbleSizePx) - margin8
        val left   = bx - margin8
        val bottom = screenH - (by + bubbleSizePx) - margin8
        val top    = by - margin8

        fun fitsH(s: Int) = s >= panelWidth
        fun fitsV(s: Int) = s >= maxHeightPx

        val hOptions = listOf("right" to right, "left" to left).filter { fitsH(it.second) }
        val vOptions = listOf("bottom" to bottom, "top" to top).filter { fitsV(it.second) }

        val side = when {
            hOptions.isNotEmpty() -> hOptions.maxByOrNull { it.second }!!.first
            vOptions.isNotEmpty() -> vOptions.maxByOrNull { it.second }!!.first
            else -> listOf("right" to right, "left" to left, "bottom" to bottom, "top" to top)
                .maxByOrNull { it.second }!!.first
        }

        var panelX = when (side) {
            "right" -> bx + bubbleSizePx + margin8
            "left"  -> bx - panelWidth - margin8
            else    -> bx + bubbleSizePx / 2 - panelWidth / 2
        }
        var panelY = when (side) {
            "bottom" -> by + bubbleSizePx + margin8
            "top"    -> by - maxHeightPx - margin8
            else     -> by + bubbleSizePx / 2 - maxHeightPx / 2
        }
        panelX = panelX.coerceIn(margin8, screenW - panelWidth - margin8)
        panelY = panelY.coerceIn(margin8, screenH - maxHeightPx - margin8)

        panelLp.leftMargin = panelX
        panelLp.topMargin = panelY

        val type = overlayType()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        try {
            windowManager.addView(root, params)
        } catch (_: Exception) {
            rootView = null
            menuList = null
            return
        }

        root.setOnClickListener { hide() }
        panel.isClickable = true
        list.isClickable = true

        root.alpha = 0f
        root.animate().alpha(1f).setDuration(180).start()

        panel.pivotX = if (side == "left") panelWidth.toFloat() else 0f
        panel.pivotY = if (side == "top") maxHeightPx.toFloat() else 0f
        panel.scaleX = 0.55f
        panel.scaleY = 0.55f
        panel.alpha = 0f
        handler.postDelayed({
            panel.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(200)
                .setInterpolator(OvershootInterpolator(1.2f))
                .start()
        }, 60)

        panel.post {
            // Clamp the scrollable height so the menu never overflows the screen
            // (View.maxHeight needs API 26+, so we set explicit layout heights).
            val listHeight = list.measuredHeight
            if (listHeight > 0) {
                val targetHeight = listHeight.coerceAtMost(maxHeightPx)
                val scrollLp = scroll.layoutParams as? LinearLayout.LayoutParams
                    ?: LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        targetHeight
                    )
                if (scrollLp.height != targetHeight) {
                    scrollLp.height = targetHeight
                    scroll.layoutParams = scrollLp
                    panel.requestLayout()
                }
            }
            val h = panel.height
            if (h > 0) {
                // Refine vertical position based on actual panel height
                val refinedY = when (side) {
                    "top"    -> (by - h - margin8).coerceIn(margin8, (screenH - h - margin8).coerceAtLeast(margin8))
                    "bottom" -> (by + bubbleSizePx + margin8).coerceIn(margin8, (screenH - h - margin8).coerceAtLeast(margin8))
                    else     -> (bubbleCenterY - h / 2).coerceIn(margin8, (screenH - h - margin8).coerceAtLeast(margin8))
                }
                if (refinedY != panelLp.topMargin) {
                    panelLp.topMargin = refinedY
                    panel.requestLayout()
                }
                panel.pivotY = h / 2f
            }
        }
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        // Hide keyboard if search was open
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            rootView?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
        } catch (_: Exception) {}
        val root = rootView ?: return
        if (!root.isAttachedToWindow) {
            cleanup()
            return
        }
        val panel = root.findViewById<LinearLayout>(R.id.menu_panel)
        panel.animate().scaleX(0.6f).scaleY(0.6f).alpha(0f).setDuration(150).start()
        root.animate().alpha(0f).setDuration(150).withEndAction {
            cleanup()
        }.start()
    }

    fun isShowing(): Boolean = rootView?.isAttachedToWindow == true

    private fun makeRow(country: Countries.Country, isConnected: Boolean): View {
        val row = LayoutInflater.from(context).inflate(R.layout.bubble_country_row, menuList, false)
        row.findViewById<TextView>(R.id.row_flag).text = country.flag
        row.findViewById<TextView>(R.id.row_name).text = country.name
        row.findViewById<TextView>(R.id.row_code).text = country.code
        if (isConnected) {
            row.findViewById<TextView>(R.id.row_status).visibility = View.VISIBLE
            val dot = row.findViewById<View>(R.id.row_dot)
            dot.visibility = View.VISIBLE
            ObjectAnimator.ofPropertyValuesHolder(
                dot,
                PropertyValuesHolder.ofFloat("alpha", 0.6f, 1f),
                PropertyValuesHolder.ofFloat("scaleX", 1f, 1.15f),
                PropertyValuesHolder.ofFloat("scaleY", 1f, 1.15f)
            ).apply {
                duration = 900
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                start()
            }
        }
        row.setOnClickListener {
            if (countrySwitchEnabled) {
                onCountrySelected(country.code)
                hide()
            } else {
                showMessage("Country switching is not available for this profile")
            }
        }
        return row
    }

    private fun sectionLabel(text: String): TextView = TextView(context).apply {
        this.text = text.uppercase(Locale.ROOT)
        textSize = 9f
        setTypeface(typeface, Typeface.BOLD)
        letterSpacing = 0.08f
        setTextColor(Color.BLACK)
        setPadding(dp(5f), dp(1f), 0, dp(2f))
    }

    private fun separatorView(): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(1f)
        ).apply {
            setMargins(dp(6f), 0, dp(6f), 0)
        }
        setBackgroundColor(Color.parseColor("#E4E4E7"))
    }

    private fun showMessage(msg: String) {
        messageHandler.removeCallbacksAndMessages(null)
        messageView?.let {
            if (it.isAttachedToWindow) {
                try {
                    windowManager.removeView(it)
                } catch (_: Exception) {
                }
            }
            messageView = null
        }

        val metrics = context.resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        val tv = TextView(context).apply {
            text = msg
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
            background = GradientDrawable().apply {
                cornerRadius = dp(22f).toFloat()
                setColor(Color.parseColor("#CC111111"))
            }
            measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = ((screenW - tv.measuredWidth) / 2).coerceAtLeast(0)
        params.y = (screenH - dp(120f)).coerceAtLeast(0)

        try {
            windowManager.addView(tv, params)
        } catch (_: Exception) {
            return
        }
        messageView = tv

        tv.translationY = dp(60f).toFloat()
        tv.alpha = 0f
        tv.animate().translationY(0f).alpha(1f).setDuration(250).start()

        messageHandler.postDelayed({
            if (messageView != tv || !tv.isAttachedToWindow) return@postDelayed
            tv.animate()
                .translationY(dp(60f).toFloat())
                .alpha(0f)
                .setDuration(200)
                .withEndAction {
                    if (messageView == tv) {
                        if (tv.isAttachedToWindow) {
                            try {
                                windowManager.removeView(tv)
                            } catch (_: Exception) {
                            }
                        }
                        messageView = null
                    }
                }
                .start()
        }, 2600)
    }

    private fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        val root = rootView
        if (root != null && root.isAttachedToWindow) {
            try {
                windowManager.removeView(root)
            } catch (_: Exception) {
            }
        }
        rootView = null
        menuList = null
        onDismissed()
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun dp(value: Float): Int = (value * context.resources.displayMetrics.density).toInt()
}