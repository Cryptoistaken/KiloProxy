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
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
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
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
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
        menuList = list

        val panelWidth = minOf(
            dp(260f),
            (screenW - bubbleSizePx - dp(16f)).coerceAtLeast(1)
        )
        val maxHeightPx = minOf(dp(340f), screenH - dp(32f)).coerceAtLeast(1)

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

        val margin8 = dp(8f)
        val bubbleRadius = bubbleSizePx / 2
        val openRight = (bubbleCenterX + bubbleRadius + panelWidth + margin8) <= screenW
        var panelX = if (openRight) {
            bubbleCenterX + bubbleRadius + margin8
        } else {
            bubbleCenterX - bubbleRadius - panelWidth - margin8
        }
        panelX = panelX.coerceIn(margin8, screenW - panelWidth - margin8)
        var panelY = bubbleCenterY - dp(180f)
        panelY = panelY.coerceIn(margin8, screenH - margin8)

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

        panel.pivotX = if (openRight) 0f else panelWidth.toFloat()
        panel.pivotY = 0f
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
                val refined = (bubbleCenterY - h / 2)
                    .coerceIn(margin8, (screenH - h - margin8).coerceAtLeast(margin8))
                if (refined != panelLp.topMargin) {
                    panelLp.topMargin = refined
                    panel.requestLayout()
                }
                panel.pivotY = h / 2f
            }
        }
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
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