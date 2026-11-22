package com.geminiphone.agent

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

class EmergencyOverlayManager(
    private val context: Context,
    private val onStopClicked: () -> Unit,
    private val onReturnGeminiClicked: () -> Unit = {}
) {
    private var windowManager: WindowManager? = null
    private var pillContainer: DraggablePillLayout? = null
    private var statusChip: TextView? = null
    private var stopButton: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var lastZhText: String = "待命"
    private var lastEnText: String = "Ready"
    private var lastIsReturnHint: Boolean = false
    private var lastIsRunning: Boolean = false

    private fun createOverlayContext(): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
                if (display != null) {
                    return context
                        .createDisplayContext(display)
                        .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                }
            } catch (_: Exception) {
                // Fall back to base context on OEM ROMs where WindowContext creation fails
            }
        }
        return context
    }

    private fun dp(uiContext: Context, value: Int): Int =
        (value * uiContext.resources.displayMetrics.density).toInt()

    private fun makePillBg(uiContext: Context, fillHex: String, strokeHex: String?, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(uiContext, radiusDp).toFloat()
            setColor(Color.parseColor(fillHex))
            if (strokeHex != null) {
                setStroke(dp(uiContext, 1), Color.parseColor(strokeHex))
            }
        }

    fun show() {
        if (pillContainer != null) {
            refreshLanguage()
            return
        }
        if (!Settings.canDrawOverlays(context)) return

        val uiContext = createOverlayContext()
        val wm = uiContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(uiContext, 12)
            y = dp(uiContext, 88)
        }

        val pill = DraggablePillLayout(uiContext, params, wm).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = makePillBg(uiContext, "#E61F2937", "#475569", 20)
            setPadding(dp(uiContext, 5), dp(uiContext, 4), dp(uiContext, 5), dp(uiContext, 4))
            elevation = dp(uiContext, 6).toFloat()
        }

        val chip = TextView(uiContext).apply {
            textSize = 11.5f
            setTextColor(Color.WHITE)
            setPadding(dp(uiContext, 9), dp(uiContext, 5), dp(uiContext, 9), dp(uiContext, 5))
            setOnClickListener { onReturnGeminiClicked() }
        }

        val stopBtn = TextView(uiContext).apply {
            text = AppStrings.get(context, R.string.ui_stop_overlay)
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = makePillBg(uiContext, "#DC2626", null, 16)
            setPadding(dp(uiContext, 10), dp(uiContext, 5), dp(uiContext, 10), dp(uiContext, 5))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(uiContext, 4)
            }
            setOnClickListener { onStopClicked() }
        }

        pill.addView(chip)
        pill.addView(stopBtn)

        try {
            wm.addView(pill, params)
            windowManager = wm
            layoutParams = params
            pillContainer = pill
            statusChip = chip
            stopButton = stopBtn
            applyStatusAppearance()
        } catch (_: Exception) {
            // Overlay permission may not be active
        }
    }

    fun updateStatus(
        zhText: String,
        enText: String,
        isReturnHint: Boolean = false,
        isRunning: Boolean = false
    ) {
        lastZhText = zhText
        lastEnText = enText
        lastIsReturnHint = isReturnHint
        lastIsRunning = isRunning
        applyStatusAppearance()
    }

    fun resetToIdle() {
        updateStatus("待命", "Ready", isReturnHint = false, isRunning = false)
    }

    fun refreshLanguage() {
        stopButton?.text = AppStrings.get(context, R.string.ui_stop_overlay)
        applyStatusAppearance()
    }

    private fun applyStatusAppearance() {
        val chip = statusChip ?: return
        val zh = AppStrings.isZh(context)
        chip.text = if (zh) lastZhText else lastEnText
        val fillHex = when {
            lastIsReturnHint -> "#D97706"
            lastIsRunning -> "#1D4ED8"
            else -> "#334155"
        }
        chip.setTypeface(null, if (lastIsReturnHint || lastIsRunning) Typeface.BOLD else Typeface.NORMAL)
        chip.background = makePillBg(chip.context, fillHex, null, 16)
    }

    fun hide() {
        val view = pillContainer ?: return
        try {
            windowManager?.removeView(view)
        } catch (_: Exception) {
            // ignore
        } finally {
            pillContainer = null
            statusChip = null
            stopButton = null
            layoutParams = null
            windowManager = null
        }
    }

    private class DraggablePillLayout(
        context: Context,
        private val params: WindowManager.LayoutParams,
        private val wm: WindowManager
    ) : LinearLayout(context) {

        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var downRawX = 0f
        private var downRawY = 0f
        private var startParamX = 0
        private var startParamY = 0
        private var isDragging = false

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    startParamX = params.x
                    startParamY = params.y
                    isDragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        isDragging = true
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                }
            }
            return false
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if (isDragging) {
                        val dx = event.rawX - downRawX
                        val dy = event.rawY - downRawY
                        val dm = resources.displayMetrics
                        val maxX = (dm.widthPixels - width).coerceAtLeast(0)
                        val maxY = (dm.heightPixels - height).coerceAtLeast(0)
                        // Gravity.TOP or Gravity.END: moving right (+dx) reduces x distance from END
                        params.x = (startParamX - dx.toInt()).coerceIn(0, maxX)
                        params.y = (startParamY + dy.toInt()).coerceIn(0, maxY)
                        try {
                            wm.updateViewLayout(this, params)
                        } catch (_: Exception) {
                            // ignore if window already detached
                        }
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        isDragging = false
                        return true
                    }
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
