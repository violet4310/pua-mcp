package com.geminiphone.agent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

enum class RiskCategory { PAYMENT, DELETE, SEND }

enum class ConfirmDecision { ALLOW, ALLOW_SESSION, DENY, TIMEOUT }

/**
 * Keyword-based detection of irreversible actions (pay / delete / send) from the labels of the
 * UI element about to be tapped. Icon-only buttons without text, contentDescription or a telling
 * resourceId are not detected; this is a safety net, not a guarantee.
 */
object RiskClassifier {
    // Longer strings are usually content (chat messages, article text), not button labels
    private const val MAX_LABEL_LEN = 16

    data class Hit(val category: RiskCategory, val label: String)

    private val zhKeywords: Map<RiskCategory, List<String>> = mapOf(
        RiskCategory.PAYMENT to listOf(
            "支付", "付款", "转账", "立即购买", "提交订单", "下单", "充值", "红包", "确认收款"
        ),
        RiskCategory.DELETE to listOf(
            "删除", "清空", "移除", "卸载", "注销", "退出群聊", "解除绑定", "解绑"
        ),
        RiskCategory.SEND to listOf(
            "发送", "发布", "发表", "分享", "转发", "回复", "评论"
        )
    )

    private val enKeywords: Map<RiskCategory, List<String>> = mapOf(
        RiskCategory.PAYMENT to listOf(
            "pay", "pay now", "checkout", "place order", "buy now", "purchase", "transfer", "top up"
        ),
        RiskCategory.DELETE to listOf(
            "delete", "remove", "clear all", "uninstall", "erase", "unlink"
        ),
        RiskCategory.SEND to listOf(
            "send", "post", "publish", "share", "forward", "reply", "comment"
        )
    )

    // Whole-word match: "Post" / "Send now" hit, but "Postcode" / "Sender info" don't
    private val enRegex: Map<RiskCategory, Regex> = enKeywords.mapValues { (_, words) ->
        Regex(
            "\\b(" + words.joinToString("|") { Regex.escape(it) } + ")\\b",
            RegexOption.IGNORE_CASE
        )
    }

    fun classify(labels: Collection<String>): Hit? {
        val cleaned = labels
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= MAX_LABEL_LEN }
            .distinct()
        for (category in RiskCategory.values()) {
            for (label in cleaned) {
                val zhHit = zhKeywords.getValue(category).any { label.contains(it) }
                if (zhHit || enRegex.getValue(category).containsMatchIn(label)) {
                    return Hit(category, label)
                }
            }
        }
        return null
    }

    fun categoryName(category: RiskCategory, zh: Boolean): String = when (category) {
        RiskCategory.PAYMENT -> if (zh) "支付 / 转账" else "Payment"
        RiskCategory.DELETE -> if (zh) "删除 / 清空" else "Delete"
        RiskCategory.SEND -> if (zh) "对外发送" else "Send / Post"
    }
}

/**
 * "Allow everything this session": lasts until the relay connection is restarted or stopped,
 * and at most [MAX_SESSION_MS] so a forgotten approval doesn't stay open for days.
 */
object RiskSession {
    const val MAX_SESSION_MS = 30 * 60 * 1000L

    @Volatile
    private var allowAllUntil = 0L

    fun isAllowAll(): Boolean = System.currentTimeMillis() < allowAllUntil

    fun allowAllForSession() {
        allowAllUntil = System.currentTimeMillis() + MAX_SESSION_MS
    }

    fun reset() {
        allowAllUntil = 0L
    }
}

/**
 * Full-screen confirmation dialog drawn as an accessibility overlay (needs no extra permission).
 * Commands are serialized, so Gemini can't tap this dialog itself while it is waiting.
 */
class RiskConfirmOverlay(private val service: AccessibilityService) {

    @Volatile
    private var activeDismiss: (() -> Unit)? = null

    fun dismissActive() {
        activeDismiss?.invoke()
    }

    private fun createOverlayContext(): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val dm = service.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
                if (display != null) {
                    return service
                        .createDisplayContext(display)
                        .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
                }
            } catch (_: Exception) {
                // Fall back to service context on OEM ROMs where WindowContext creation fails
            }
        }
        return service
    }

    suspend fun ask(
        appLabel: String,
        actionDesc: String,
        category: RiskCategory,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): ConfirmDecision = withContext(Dispatchers.Main) {
        dismissActive()
        suspendCancellableCoroutine { cont ->
            val uiContext = createOverlayContext()
            val wm = uiContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val zh = AppStrings.isZh(service)
            val handler = Handler(Looper.getMainLooper())
            var rootView: View? = null
            var finished = false

            fun finishOnMain(decision: ConfirmDecision) {
                if (finished) return
                finished = true
                activeDismiss = null
                handler.removeCallbacksAndMessages(null)
                val viewToRemove = rootView
                rootView = null
                if (viewToRemove != null) {
                    try {
                        wm.removeView(viewToRemove)
                    } catch (_: Exception) {
                        // ignore
                    }
                }
                if (cont.isActive) cont.resume(decision)
            }

            fun finish(decision: ConfirmDecision) {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    finishOnMain(decision)
                } else {
                    handler.post { finishOnMain(decision) }
                }
            }

            val density = uiContext.resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()
            fun rounded(fill: String, radiusDp: Int) = GradientDrawable().apply {
                setColor(Color.parseColor(fill))
                cornerRadius = dp(radiusDp).toFloat()
            }

            val countdown = TextView(uiContext).apply {
                textSize = 12f
                setTextColor(Color.parseColor("#5F6368"))
                setPadding(0, dp(8), 0, dp(12))
            }

            fun makeButton(label: String, fill: String, textColor: Int, onClick: () -> Unit) =
                Button(uiContext).apply {
                    text = label
                    isAllCaps = false
                    textSize = 14f
                    setTextColor(textColor)
                    background = rounded(fill, 18)
                    setOnClickListener { onClick() }
                }

            val btnDeny = makeButton(
                if (zh) "拒绝" else "Deny", "#E8EAED", Color.parseColor("#1F1F1F")
            ) { finish(ConfirmDecision.DENY) }
            val btnAllow = makeButton(
                if (zh) "允许" else "Allow", "#D93025", Color.WHITE
            ) { finish(ConfirmDecision.ALLOW) }
            val btnSession = makeButton(
                if (zh) "本次会话都允许（最长 30 分钟）" else "Allow all this session (max 30 min)",
                "#FFFFFF",
                Color.parseColor("#1A73E8")
            ) { finish(ConfirmDecision.ALLOW_SESSION) }

            val btnRow = LinearLayout(uiContext).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(btnDeny, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(6)
                })
                addView(btnAllow, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(6)
                })
            }

            val card = LinearLayout(uiContext).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded("#FFFFFF", 16)
                setPadding(dp(20), dp(18), dp(20), dp(14))
                addView(TextView(uiContext).apply {
                    text = if (zh) "⚠️ 需要你确认" else "⚠️ Confirmation needed"
                    textSize = 17f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#1F1F1F"))
                })
                addView(TextView(uiContext).apply {
                    text = if (zh) {
                        "Gemini 想在【$appLabel】$actionDesc\n类别：${RiskClassifier.categoryName(category, true)}"
                    } else {
                        "Gemini wants to $actionDesc in [$appLabel]\nCategory: ${RiskClassifier.categoryName(category, false)}"
                    }
                    textSize = 15f
                    setTextColor(Color.parseColor("#3C4043"))
                    setPadding(0, dp(10), 0, 0)
                })
                addView(countdown)
                addView(btnRow)
                addView(btnSession, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) })
            }

            val root = FrameLayout(uiContext).apply {
                setBackgroundColor(Color.parseColor("#99000000"))
                isClickable = true // swallow touches outside the card
                val cardWidth = (uiContext.resources.displayMetrics.widthPixels * 0.86f).toInt()
                addView(card, FrameLayout.LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            }
            rootView = root

            val deadline = System.currentTimeMillis() + timeoutMs
            val tick = object : Runnable {
                override fun run() {
                    val left = ((deadline - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0)
                    if (left <= 0) {
                        finish(ConfirmDecision.TIMEOUT)
                        return
                    }
                    countdown.text = if (zh) "$left 秒后自动拒绝" else "Auto-deny in ${left}s"
                    handler.postDelayed(this, 1000L)
                }
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // Not focusable so an open keyboard / input focus in the target app is left untouched
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )

            activeDismiss = { finish(ConfirmDecision.DENY) }
            try {
                wm.addView(root, params)
                tick.run()
            } catch (_: Exception) {
                // Can't show the dialog: fail closed
                rootView = null
                finish(ConfirmDecision.DENY)
            }

            cont.invokeOnCancellation { finish(ConfirmDecision.DENY) }
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000L
    }
}
