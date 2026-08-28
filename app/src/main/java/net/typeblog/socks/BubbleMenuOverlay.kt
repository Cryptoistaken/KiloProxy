package net.typeblog.socks

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
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
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
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
    private var connectedDotAnimator: ObjectAnimator? = null

    private val globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        val root = rootView ?: return@OnGlobalLayoutListener
        if (!root.isAttachedToWindow) return@OnGlobalLayoutListener
        val search = root.findViewById<EditText>(R.id.menu_search) ?: return@OnGlobalLayoutListener
        if (!search.hasFocus()) return@OnGlobalLayoutListener
        repositionPanelAboveIme(root)
    }

    fun show(bubbleCenterX: Int, bubbleCenterY: Int, bubbleSizePx: Int, connectedCountryCode: String?, supportsCountrySwitch: Boolean) {
        if (isShowing()) return
        countrySwitchEnabled = supportsCountrySwitch
        val root = rootView ?: LayoutInflater.from(context)
            .inflate(R.layout.bubble_menu, null) as FrameLayout
        rootView = root

        val panel = root.findViewById<LinearLayout>(R.id.menu_panel)
        val scroll = root.findViewById<ScrollView>(R.id.menu_scroll)
        val list = root.findViewById<LinearLayout>(R.id.menu_list)
        val searchInput = root.findViewById<EditText>(R.id.menu_search)
        menuList = list

        // Clamp against the inset-aware content area (display minus system bars /
        // cutouts), not the raw screen, so the panel never sits under the status
        // bar, nav bar, or a cutout.
        val bounds = contentBounds()
        val screenW = bounds.width()
        val screenH = bounds.height()

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
            list.addView(sectionLabel(context.getString(R.string.bubble_section_recent)))
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
            list.addView(sectionLabel(context.getString(R.string.bubble_section_all)))
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
                    list.addView(sectionLabel(context.getString(R.string.bubble_section_no_results)))
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

        // Search tap: the popup window is already focusable (no FLAG_NOT_FOCUSABLE),
        // so the EditText can receive input immediately — request focus and show IME.
        searchInput.setOnClickListener {
            searchInput.requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        // Also handle the case where focus arrived programmatically
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
        val right  = bounds.right - (bx + bubbleSizePx) - margin8
        val left   = bx - bounds.left - margin8
        val bottom = bounds.bottom - (by + bubbleSizePx) - margin8
        val top    = by - bounds.top - margin8

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
        panelX = panelX.coerceIn(bounds.left + margin8, bounds.right - panelWidth - margin8)
        panelY = panelY.coerceIn(bounds.top + margin8, bounds.bottom - maxHeightPx - margin8)

        panelLp.leftMargin = panelX
        panelLp.topMargin = panelY

        val type = overlayType()
        // No FLAG_NOT_FOCUSABLE: a non-focusable window cannot receive text input,
        // so toggling the flag at runtime to open the search keyboard is unreliable
        // (the keyboard can lag for seconds). A plain focusable window lets the
        // EditText connect to the IME immediately; the keyboard only appears when
        // the search field is tapped.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            0,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        // Resize the window to sit above the keyboard when the search field opens,
        // so the panel can be re-positioned on top of it (see globalLayoutListener).
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE

        // Set alpha BEFORE addView to prevent 1-2 frame flash of the scrim
        root.alpha = 0f

        try {
            windowManager.addView(root, params)
        } catch (_: Exception) {
            rootView = null
            menuList = null
            return
        }

        // Track IME appearance so the panel can be pushed above the keyboard while
        // the search field is focused.
        root.viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)

        root.setOnClickListener { hide() }
        panel.isClickable = true
        list.isClickable = true

        // scrim fade: 100ms (fast, just enough to avoid hard pop-in)
        root.animate().alpha(1f).setDuration(100).start()

        // Panel: keep hidden until geometry is finalized below (prevents flash)
        panel.scaleX = 0.55f
        panel.scaleY = 0.55f
        panel.alpha = 0f

        // Single post: clamp scroll height, refine panel Y using the TRUE clamped
        // height, set per-side pivots, then start the grow-in animation.
        panel.post {
            val listHeight = list.measuredHeight
            val targetHeight = if (listHeight > 0) listHeight.coerceAtMost(maxHeightPx) else maxHeightPx
            val scrollLp = scroll.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    targetHeight
                )
            if (scrollLp.height != targetHeight) {
                scrollLp.height = targetHeight
                scroll.layoutParams = scrollLp
            }
            // panel.height and scroll.height are read from the same (stale) layout pass,
            // so their difference is the fixed header height (search bar + divider).
            val headerHeight = (panel.height - scroll.height).coerceAtLeast(0)
            val trueHeight = headerHeight + targetHeight
            val refinedY = when (side) {
                "top"    -> (by - trueHeight - margin8).coerceIn(bounds.top + margin8, (bounds.bottom - trueHeight - margin8).coerceAtLeast(bounds.top + margin8))
                "bottom" -> (by + bubbleSizePx + margin8).coerceIn(bounds.top + margin8, (bounds.bottom - trueHeight - margin8).coerceAtLeast(bounds.top + margin8))
                else     -> (bubbleCenterY - trueHeight / 2).coerceIn(bounds.top + margin8, (bounds.bottom - trueHeight - margin8).coerceAtLeast(bounds.top + margin8))
            }
            if (refinedY != panelLp.topMargin) {
                panelLp.topMargin = refinedY
                panel.requestLayout()
            }
            panel.pivotX = when (side) {
                "left" -> panelWidth.toFloat()
                "top", "bottom" -> panelWidth / 2f
                else -> 0f
            }
            panel.pivotY = when (side) {
                "top" -> trueHeight.toFloat()
                "bottom" -> 0f
                else -> trueHeight / 2f
            }
            panel.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(120)
                .setInterpolator(FastOutSlowInInterpolator())
                .start()
        }
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        messageHandler.removeCallbacksAndMessages(null)
        // Cancel the pulsing dot animator to prevent leak
        connectedDotAnimator?.cancel()
        connectedDotAnimator = null
        // Remove any lingering snackbar-style message overlay
        messageView?.let {
            if (it.isAttachedToWindow) {
                try {
                    windowManager.removeView(it)
                } catch (_: Exception) {
                }
            }
            messageView = null
        }
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
        // Instant dismiss — no fade animation (avoids flash of underlying screen)
        root.alpha = 0f
        cleanup()
    }

    fun isShowing(): Boolean = rootView?.isAttachedToWindow == true

    private fun makeRow(country: Countries.Country, isConnected: Boolean): View {
        val row = LayoutInflater.from(context).inflate(R.layout.bubble_country_row, menuList, false)
        row.findViewById<TextView>(R.id.row_flag).text = country.flag
        row.findViewById<TextView>(R.id.row_name).text = country.name
        row.findViewById<TextView>(R.id.row_code).text = country.code
        val dialView = row.findViewById<TextView>(R.id.row_dial)
        val dot = row.findViewById<View>(R.id.row_dot)
        if (isConnected) {
            // Connected: hide dial, show pulsing green dot
            dialView.visibility = View.GONE
            dot.visibility = View.VISIBLE
            connectedDotAnimator?.cancel()
            connectedDotAnimator = ObjectAnimator.ofPropertyValuesHolder(
                dot,
                PropertyValuesHolder.ofFloat("alpha", 0.6f, 1f),
                PropertyValuesHolder.ofFloat("scaleX", 1f, 1.08f),
                PropertyValuesHolder.ofFloat("scaleY", 1f, 1.08f)
            ).apply {
                duration = 900
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                start()
            }
        } else {
            // Not connected: show dial code hint
            dialView.text = "+${country.phone}"
            dialView.visibility = View.VISIBLE
            dot.visibility = View.GONE
        }
        row.setOnClickListener {
            if (countrySwitchEnabled) {
                onCountrySelected(country.code)
                hide()
            } else {
                showMessage(context.getString(R.string.bubble_country_switch_unavailable))
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
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
        connectedDotAnimator?.cancel()
        connectedDotAnimator = null
        val root = rootView
        if (root != null) {
            try {
                root.viewTreeObserver.removeOnGlobalLayoutListener(globalLayoutListener)
            } catch (_: Exception) {
            }
            if (root.isAttachedToWindow) {
                try {
                    windowManager.removeView(root)
                } catch (_: Exception) {
                }
            }
        }
        rootView = null
        menuList = null
        onDismissed()
    }

    private fun displayBounds(): Rect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val b = windowManager.currentWindowMetrics.bounds
                return Rect(0, 0, b.width(), b.height())
            } catch (e: Exception) {
            }
        }
        val dm = context.resources.displayMetrics
        return Rect(0, 0, dm.widthPixels, dm.heightPixels)
    }

    private fun systemBarInsets(): Rect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val insets = windowManager.currentWindowMetrics.windowInsets
                    .getInsets(WindowInsets.Type.systemBars())
                return Rect(insets.left, insets.top, insets.right, insets.bottom)
            } catch (e: Exception) {
            }
        }
        val statusBar = try {
            context.resources.getDimensionPixelSize(
                context.resources.getIdentifier("status_bar_height", "dimen", "android")
            )
        } catch (e: Exception) {
            0
        }
        val navBar = try {
            context.resources.getDimensionPixelSize(
                context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
            )
        } catch (e: Exception) {
            0
        }
        return Rect(0, statusBar, 0, navBar)
    }

    /** Display bounds minus ALL four system-bar/cutout insets. */
    private fun contentBounds(): Rect {
        val b = displayBounds()
        val i = systemBarInsets()
        return Rect(i.left, i.top, b.width() - i.right, b.height() - i.bottom)
    }

    /**
     * When the search field is focused the IME covers the lower part of the
     * overlay (SOFT_INPUT_ADJUST_RESIZE shrinks the window), so push the panel up
     * so its bottom stays above the top of the keyboard.
     */
    private fun repositionPanelAboveIme(root: View) {
        val panel = root.findViewById<LinearLayout>(R.id.menu_panel) ?: return
        val displayH = displayBounds().height()
        val imeHeight = (displayH - root.height).coerceAtLeast(0)
        if (imeHeight <= 0) return
        val bounds = contentBounds()
        val panelBottom = panel.top + panel.height
        val limitBottom = displayH - imeHeight
        if (panelBottom > limitBottom) {
            val delta = panelBottom - limitBottom
            val lp = panel.layoutParams as? FrameLayout.LayoutParams ?: return
            lp.topMargin = (lp.topMargin - delta).coerceAtLeast(bounds.top)
            panel.requestLayout()
        }
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