package com.geminiphone.agent

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_POST_NOTIFICATIONS = 1001
        private const val ID_ET_WORKER_URL = 10001
        private const val ID_ET_ACCESS_KEY = 10002
        private const val ID_ET_VISION_URL = 10003
        private const val ID_ET_VISION_KEY = 10004
        private const val ID_ET_VISION_MODEL = 10005
        private const val STATE_VISION_MODE = "state_vision_mode"
    }

    private data class Palette(
        val isDark: Boolean,
        val canvas: Int,
        val card: Int,
        val cardStroke: Int,
        val inputBg: Int,
        val inputStroke: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val textMuted: Int,
        val accent: Int,
        val accentSoft: Int,
        val dangerText: Int,
        val dangerBg: Int,
        val dangerStroke: Int,
        val successBg: Int,
        val successText: Int,
        val warnBg: Int,
        val warnText: Int,
        val divider: Int,
        val segTrack: Int
    )

    private data class InstalledAppItem(
        val label: String,
        val packageName: String,
        val icon: Drawable?
    )

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val stateListener: () -> Unit = { refreshStatus() }
    private var activeDialog: AlertDialog? = null
    private var isTokenVisible = false
    private var isVisionKeyVisible = false
    private var selectedVisionMode = ConfigStore.VISION_MODE_WORKERS_AI

    private lateinit var palette: Palette

    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var btnLang: Button

    private lateinit var statusDot: View
    private lateinit var tvStatusRelay: TextView
    private lateinit var tvStatusPerms: TextView

    private lateinit var tvConnTitle: TextView
    private lateinit var tvUrlLabel: TextView
    private lateinit var etWorkerUrl: EditText
    private lateinit var tvTokenLabel: TextView
    private lateinit var etAccessKey: EditText
    private lateinit var btnToggleToken: Button
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button

    private lateinit var tvVisionTitle: TextView
    private lateinit var btnVisionWorkers: Button
    private lateinit var btnVisionCustom: Button
    private lateinit var visionWorkersGroup: LinearLayout
    private lateinit var tvVisionWorkersTip: TextView
    private lateinit var visionCustomGroup: LinearLayout
    private lateinit var tvVisionUrlLabel: TextView
    private lateinit var etVisionUrl: EditText
    private lateinit var tvVisionKeyLabel: TextView
    private lateinit var etVisionKey: EditText
    private lateinit var btnToggleVisionKey: Button
    private lateinit var tvVisionModelLabel: TextView
    private lateinit var etVisionModel: EditText
    private lateinit var btnSaveVision: Button

    private lateinit var tvExecModeTitle: TextView
    private lateinit var tvWinModeLabel: TextView
    private lateinit var btnModeFreeform: Button
    private lateinit var btnModeFullscreen: Button
    private lateinit var tvWinModeTip: TextView
    private lateinit var swAutoReturn: SwitchMaterial
    private lateinit var swConfirmRisky: SwitchMaterial
    private lateinit var tvConfirmRiskyTip: TextView

    private lateinit var tvBlacklistTitle: TextView
    private lateinit var tvBlacklistSummary: TextView
    private lateinit var btnPickApps: Button
    private lateinit var btnEditRaw: Button

    private lateinit var tvShortcutsTitle: TextView
    private lateinit var tvAccLabel: TextView
    private lateinit var tvAccBadge: TextView
    private lateinit var tvOverlayLabel: TextView
    private lateinit var tvOverlayBadge: TextView
    private lateinit var tvBatteryLabel: TextView
    private lateinit var tvBatteryBadge: TextView

    private lateinit var tvLog: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        palette = buildPalette()
        applySystemBars()

        selectedVisionMode = savedInstanceState?.getString(STATE_VISION_MODE)
            ?: ConfigStore.getVisionMode(this)

        setContentView(buildUi())

        if (savedInstanceState == null) {
            etWorkerUrl.setText(ConfigStore.getWorkerUrl(this))
            etAccessKey.setText(ConfigStore.getToken(this))
            etVisionUrl.setText(ConfigStore.getVisionApiUrl(this))
            etVisionKey.setText(ConfigStore.getVisionApiKey(this))
            etVisionModel.setText(ConfigStore.getVisionModel(this))
        }

        requestNotificationPermissionIfNeeded()

        RelayForegroundService.registerStateListener(stateListener)
        applyLanguageAndRefresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_VISION_MODE, selectedVisionMode)
    }

    override fun onResume() {
        super.onResume()
        applyLanguageAndRefresh()
    }

    override fun onDestroy() {
        RelayForegroundService.unregisterStateListener(stateListener)
        activeDialog?.dismiss()
        activeDialog = null
        uiScope.cancel()
        super.onDestroy()
    }

    private fun buildPalette(): Palette {
        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return if (isDark) {
            Palette(
                isDark = true,
                canvas = Color.parseColor("#0F1117"),
                card = Color.parseColor("#181B23"),
                cardStroke = Color.parseColor("#262B38"),
                inputBg = Color.parseColor("#11141B"),
                inputStroke = Color.parseColor("#2A3040"),
                textPrimary = Color.parseColor("#F3F4F6"),
                textSecondary = Color.parseColor("#9CA3AF"),
                textMuted = Color.parseColor("#6B7280"),
                accent = Color.parseColor("#3B82F6"),
                accentSoft = Color.parseColor("#172554"),
                dangerText = Color.parseColor("#F87171"),
                dangerBg = Color.parseColor("#2A1619"),
                dangerStroke = Color.parseColor("#5C242A"),
                successBg = Color.parseColor("#0D2B22"),
                successText = Color.parseColor("#34D399"),
                warnBg = Color.parseColor("#2B2110"),
                warnText = Color.parseColor("#FBBF24"),
                divider = Color.parseColor("#242936"),
                segTrack = Color.parseColor("#11141B")
            )
        } else {
            Palette(
                isDark = false,
                canvas = Color.parseColor("#F5F6FA"),
                card = Color.parseColor("#FFFFFF"),
                cardStroke = Color.parseColor("#E5E7EB"),
                inputBg = Color.parseColor("#F3F5F9"),
                inputStroke = Color.parseColor("#DCE1EB"),
                textPrimary = Color.parseColor("#111827"),
                textSecondary = Color.parseColor("#5B6475"),
                textMuted = Color.parseColor("#8A94A6"),
                accent = Color.parseColor("#2563EB"),
                accentSoft = Color.parseColor("#EFF6FF"),
                dangerText = Color.parseColor("#DC2626"),
                dangerBg = Color.parseColor("#FEF2F2"),
                dangerStroke = Color.parseColor("#FECACA"),
                successBg = Color.parseColor("#ECFDF5"),
                successText = Color.parseColor("#047857"),
                warnBg = Color.parseColor("#FFFBEB"),
                warnText = Color.parseColor("#B45309"),
                divider = Color.parseColor("#EEF0F5"),
                segTrack = Color.parseColor("#EEF1F6")
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun applySystemBars() {
        window.statusBarColor = palette.canvas
        window.navigationBarColor = palette.canvas
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !palette.isDark
            isAppearanceLightNavigationBars = !palette.isDark
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun makeRoundedBg(fillColor: Int, strokeColor: Int? = null, cornerDp: Int = 12): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(cornerDp).toFloat()
            setColor(fillColor)
            if (strokeColor != null) {
                setStroke(dp(1), strokeColor)
            }
        }

    private fun makeCard(padVerticalDp: Int = 16, bottomMarginDp: Int = 14): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeRoundedBg(palette.card, palette.cardStroke, 16)
            setPadding(dp(16), dp(padVerticalDp), dp(16), dp(padVerticalDp))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(bottomMarginDp)
            }
        }

    private fun makeSectionTitle(bottomPadDp: Int = 10): TextView =
        TextView(this).apply {
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(palette.textPrimary)
            setPadding(0, 0, 0, dp(bottomPadDp))
        }

    private fun makeInputLabel(topPadDp: Int = 0): TextView =
        TextView(this).apply {
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(palette.textSecondary)
            setPadding(0, dp(topPadDp), 0, dp(6))
        }

    private fun makeStyledEditText(viewId: Int, inputTypeFlags: Int): EditText =
        EditText(this).apply {
            id = viewId
            inputType = inputTypeFlags
            isSingleLine = true
            textSize = 14f
            setTextColor(palette.textPrimary)
            setHintTextColor(palette.textMuted)
            background = makeRoundedBg(palette.inputBg, palette.inputStroke, 12)
            setPadding(dp(14), dp(11), dp(14), dp(11))
        }

    private fun makePasswordInputRow(
        viewId: Int,
        getVisible: () -> Boolean,
        setVisible: (Boolean) -> Unit
    ): Triple<LinearLayout, EditText, Button> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = makeRoundedBg(palette.inputBg, palette.inputStroke, 12)
            setPadding(dp(14), dp(4), dp(6), dp(4))
        }
        val editText = EditText(this).apply {
            id = viewId
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
            textSize = 14f
            setTextColor(palette.textPrimary)
            setHintTextColor(palette.textMuted)
            background = null
            setPadding(0, dp(7), dp(8), dp(7))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val toggleBtn = Button(this).apply {
            textSize = 11.5f
            isAllCaps = false
            minimumWidth = 0
            minWidth = 0
            minimumHeight = 0
            minHeight = 0
            backgroundTintList = null
            background = makeRoundedBg(palette.card, palette.cardStroke, 14)
            setTextColor(palette.textSecondary)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            setOnClickListener {
                val nextVisible = !getVisible()
                setVisible(nextVisible)
                val selStart = editText.selectionStart
                val selEnd = editText.selectionEnd
                val savedTypeface = editText.typeface
                editText.inputType = if (nextVisible) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                } else {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                editText.typeface = savedTypeface
                if (selStart >= 0 && selEnd >= 0) {
                    editText.setSelection(selStart, selEnd)
                }
                text = AppStrings.tokenToggleLabel(AppStrings.isZh(this@MainActivity), nextVisible)
            }
        }
        row.addView(editText)
        row.addView(toggleBtn)
        return Triple(row, editText, toggleBtn)
    }

    private fun makeSegmentButton(onClick: () -> Unit): Button =
        Button(this).apply {
            isAllCaps = false
            textSize = 12.5f
            minimumWidth = 0
            minWidth = 0
            minimumHeight = 0
            minHeight = 0
            backgroundTintList = null
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        }

    private fun makeSegmentedTrack(left: Button, right: Button): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = makeRoundedBg(palette.segTrack, null, 12)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            (left.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(4)
            addView(left)
            addView(right)
        }

    private fun styleSegmentButton(btn: Button, selected: Boolean) {
        btn.backgroundTintList = null
        if (selected) {
            btn.background = makeRoundedBg(palette.accent, null, 9)
            btn.setTextColor(Color.WHITE)
            btn.setTypeface(null, Typeface.BOLD)
        } else {
            btn.background = makeRoundedBg(Color.TRANSPARENT, null, 9)
            btn.setTextColor(palette.textSecondary)
            btn.setTypeface(null, Typeface.NORMAL)
        }
    }

    private fun makeDivider(verticalMarginDp: Int = 0): View =
        View(this).apply {
            setBackgroundColor(palette.divider)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                topMargin = dp(verticalMarginDp)
                bottomMargin = dp(verticalMarginDp)
            }
        }

    private fun makeSwitch(initialChecked: Boolean, onToggle: (Boolean) -> Unit): SwitchMaterial {
        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked)
        )
        val thumbColors = ColorStateList(
            states,
            intArrayOf(Color.WHITE, if (palette.isDark) palette.textSecondary else Color.WHITE)
        )
        val trackColors = ColorStateList(
            states,
            intArrayOf(palette.accent, palette.inputStroke)
        )
        return SwitchMaterial(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            textSize = 13.5f
            setTextColor(palette.textPrimary)
            thumbTintList = thumbColors
            trackTintList = trackColors
            isChecked = initialChecked
            setOnCheckedChangeListener { _, isChecked -> onToggle(isChecked) }
        }
    }

    private fun makePermissionRow(onClick: () -> Unit): Triple<LinearLayout, TextView, TextView> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { onClick() }
        }
        val label = TextView(this).apply {
            textSize = 13.5f
            setTextColor(palette.textPrimary)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(10)
            }
        }
        val badge = TextView(this).apply {
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        val chevron = TextView(this).apply {
            text = "›"
            textSize = 17f
            setTextColor(palette.textMuted)
            setPadding(dp(8), 0, 0, 0)
        }
        row.addView(label)
        row.addView(badge)
        row.addView(chevron)
        return Triple(row, label, badge)
    }

    private fun updatePermissionBadge(badge: TextView, enabled: Boolean) {
        if (enabled) {
            badge.text = AppStrings.get(this, R.string.ui_perm_on)
            badge.background = makeRoundedBg(palette.successBg, null, 12)
            badge.setTextColor(palette.successText)
        } else {
            badge.text = AppStrings.get(this, R.string.ui_perm_off)
            badge.background = makeRoundedBg(palette.warnBg, null, 12)
            badge.setTextColor(palette.warnText)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQ_POST_NOTIFICATIONS
                )
            }
        }
    }

    @SuppressLint("BatteryLife")
    private fun buildUi(): ScrollView {
        val hPad = dp(18)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.canvas)
            setPadding(hPad, dp(16), hPad, dp(24))
            isFocusableInTouchMode = true
        }

        // Minimal Header
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(2), dp(16))
        }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(12)
            }
        }
        tvTitle = TextView(this).apply {
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(palette.textPrimary)
        }
        tvSubtitle = TextView(this).apply {
            textSize = 12.5f
            setTextColor(palette.textMuted)
            setPadding(0, dp(2), 0, 0)
        }
        titleCol.addView(tvTitle)
        titleCol.addView(tvSubtitle)

        btnLang = Button(this).apply {
            textSize = 12f
            isAllCaps = false
            setTypeface(null, Typeface.BOLD)
            minimumWidth = 0
            minWidth = 0
            minimumHeight = 0
            minHeight = 0
            backgroundTintList = null
            background = makeRoundedBg(palette.card, palette.cardStroke, 18)
            setTextColor(palette.textSecondary)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setOnClickListener {
                ConfigStore.toggleLanguage(this@MainActivity)
                RelayForegroundService.notifyLanguageChanged()
                applyLanguageAndRefresh()
            }
        }
        headerRow.addView(titleCol)
        headerRow.addView(btnLang)
        root.addView(headerRow)

        // Hero Status Card
        val heroCard = makeCard(padVerticalDp = 14, bottomMarginDp = 14)
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                marginEnd = dp(9)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(palette.textMuted)
            }
        }
        tvStatusRelay = TextView(this).apply {
            textSize = 14.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(palette.textPrimary)
        }
        statusRow.addView(statusDot)
        statusRow.addView(tvStatusRelay)
        heroCard.addView(statusRow)

        tvStatusPerms = TextView(this).apply {
            textSize = 12f
            setTextColor(palette.textSecondary)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, dp(6), 0, 0)
        }
        heroCard.addView(tvStatusPerms)
        root.addView(heroCard)

        // Card 1 — Relay Connection
        val connCard = makeCard()
        tvConnTitle = makeSectionTitle(bottomPadDp = 10)
        connCard.addView(tvConnTitle)

        tvUrlLabel = makeInputLabel()
        connCard.addView(tvUrlLabel)
        etWorkerUrl = makeStyledEditText(
            ID_ET_WORKER_URL,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        )
        connCard.addView(etWorkerUrl)

        tvTokenLabel = makeInputLabel(topPadDp = 12)
        connCard.addView(tvTokenLabel)
        val (tokenRow, accessInput, tokenToggle) = makePasswordInputRow(
            viewId = ID_ET_ACCESS_KEY,
            getVisible = { isTokenVisible },
            setVisible = { isTokenVisible = it }
        )
        etAccessKey = accessInput
        btnToggleToken = tokenToggle
        connCard.addView(tokenRow)

        val connBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        }
        btnConnect = Button(this).apply {
            isAllCaps = false
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            minimumHeight = 0
            minHeight = 0
            backgroundTintList = null
            background = makeRoundedBg(palette.accent, null, 12)
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(11), dp(12), dp(11))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.25f).apply {
                marginEnd = dp(10)
            }
            setOnClickListener { handleConnectClick() }
        }
        btnDisconnect = Button(this).apply {
            isAllCaps = false
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            minimumHeight = 0
            minHeight = 0
            backgroundTintList = null
            background = makeRoundedBg(palette.dangerBg, palette.dangerStroke, 12)
            setTextColor(palette.dangerText)
            setPadding(dp(12), dp(11), dp(12), dp(11))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                RelayForegroundService.stop(this@MainActivity)
                Toast.makeText(
                    this@MainActivity,
                    AppStrings.get(this@MainActivity, R.string.ui_toast_stopped),
                    Toast.LENGTH_SHORT
                ).show()
                refreshStatus()
            }
        }
        connBtnRow.addView(btnConnect)
        connBtnRow.addView(btnDisconnect)
        connCard.addView(connBtnRow)
        root.addView(connCard)

        // Card 2 — Screenshot Vision
        val visionCard = makeCard()
        tvVisionTitle = makeSectionTitle(bottomPadDp = 10)
        visionCard.addView(tvVisionTitle)

        btnVisionWorkers = makeSegmentButton {
            selectedVisionMode = ConfigStore.VISION_MODE_WORKERS_AI
            ConfigStore.setVisionMode(this@MainActivity, ConfigStore.VISION_MODE_WORKERS_AI)
            refreshVisionModeUi()
        }
        btnVisionCustom = makeSegmentButton {
            selectedVisionMode = ConfigStore.VISION_MODE_CUSTOM
            refreshVisionModeUi()
        }
        visionCard.addView(makeSegmentedTrack(btnVisionWorkers, btnVisionCustom))

        visionWorkersGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        tvVisionWorkersTip = TextView(this).apply {
            textSize = 12.5f
            setTextColor(palette.textSecondary)
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        visionWorkersGroup.addView(tvVisionWorkersTip)
        visionCard.addView(visionWorkersGroup)

        visionCustomGroup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        tvVisionUrlLabel = makeInputLabel()
        visionCustomGroup.addView(tvVisionUrlLabel)
        etVisionUrl = makeStyledEditText(
            ID_ET_VISION_URL,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        )
        visionCustomGroup.addView(etVisionUrl)

        tvVisionKeyLabel = makeInputLabel(topPadDp = 12)
        visionCustomGroup.addView(tvVisionKeyLabel)
        val (visionKeyRow, visionKeyInput, visionKeyToggle) = makePasswordInputRow(
            viewId = ID_ET_VISION_KEY,
            getVisible = { isVisionKeyVisible },
            setVisible = { isVisionKeyVisible = it }
        )
        etVisionKey = visionKeyInput
        btnToggleVisionKey = visionKeyToggle
        visionCustomGroup.addView(visionKeyRow)

        tvVisionModelLabel = makeInputLabel(topPadDp = 12)
        visionCustomGroup.addView(tvVisionModelLabel)
        etVisionModel = makeStyledEditText(ID_ET_VISION_MODEL, InputType.TYPE_CLASS_TEXT)
        visionCustomGroup.addView(etVisionModel)

        btnSaveVision = Button(this).apply {
            isAllCaps = false
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            minimumHeight = 0
            minHeight = 0
            backgroundTintList = null
            background = makeRoundedBg(palette.accent, null, 12)
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(14)
            }
            setOnClickListener { handleSaveVisionClick() }
        }
        visionCustomGroup.addView(btnSaveVision)
        visionCard.addView(visionCustomGroup)
        root.addView(visionCard)

        // Card 3 — Control & Safety
        val execCard = makeCard()
        tvExecModeTitle = makeSectionTitle(bottomPadDp = 8)
        execCard.addView(tvExecModeTitle)

        tvWinModeLabel = makeInputLabel()
        execCard.addView(tvWinModeLabel)

        btnModeFreeform = makeSegmentButton {
            ConfigStore.setWindowMode(this@MainActivity, ConfigStore.MODE_FREEFORM)
            RelayForegroundService.notifySettingsChanged()
            refreshExecModeUi()
        }
        btnModeFullscreen = makeSegmentButton {
            ConfigStore.setWindowMode(this@MainActivity, ConfigStore.MODE_FULLSCREEN)
            RelayForegroundService.notifySettingsChanged()
            refreshExecModeUi()
        }
        execCard.addView(makeSegmentedTrack(btnModeFreeform, btnModeFullscreen))

        tvWinModeTip = TextView(this).apply {
            textSize = 11.5f
            setTextColor(palette.textMuted)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, dp(8), 0, 0)
        }
        execCard.addView(tvWinModeTip)
        execCard.addView(makeDivider(verticalMarginDp = 12))

        swAutoReturn = makeSwitch(ConfigStore.isAutoReturnGemini(this)) { isChecked ->
            ConfigStore.setAutoReturnGemini(this@MainActivity, isChecked)
            RelayForegroundService.notifySettingsChanged()
        }.apply {
            setPadding(0, 0, 0, dp(10))
        }
        execCard.addView(swAutoReturn)

        swConfirmRisky = makeSwitch(ConfigStore.isConfirmRiskyActions(this)) { isChecked ->
            ConfigStore.setConfirmRiskyActions(this@MainActivity, isChecked)
            if (!isChecked) RiskSession.reset()
        }
        execCard.addView(swConfirmRisky)

        tvConfirmRiskyTip = TextView(this).apply {
            textSize = 11.5f
            setTextColor(palette.textMuted)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, dp(4), 0, 0)
        }
        execCard.addView(tvConfirmRiskyTip)
        root.addView(execCard)

        // Card 4 — Sensitive App Blacklist
        val blacklistCard = makeCard()
        tvBlacklistTitle = makeSectionTitle(bottomPadDp = 8)
        blacklistCard.addView(tvBlacklistTitle)

        tvBlacklistSummary = TextView(this).apply {
            textSize = 12.5f
            setTextColor(palette.textSecondary)
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        blacklistCard.addView(tvBlacklistSummary)

        val blacklistBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        btnPickApps = Button(this).apply {
            isAllCaps = false
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            minimumHeight = 0
            minHeight = 0
            backgroundTintList = null
            background = makeRoundedBg(palette.accentSoft, palette.accent, 20)
            setTextColor(palette.accent)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f).apply {
                marginEnd = dp(10)
            }
            setOnClickListener { showInstalledAppPickerDialog() }
        }
        btnEditRaw = Button(this).apply {
            isAllCaps = false
            textSize = 12.5f
            minimumHeight = 0
            minHeight = 0
            backgroundTintList = null
            background = makeRoundedBg(palette.inputBg, palette.inputStroke, 20)
            setTextColor(palette.textPrimary)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { showRawPackagesDialog() }
        }
        blacklistBtnRow.addView(btnPickApps)
        blacklistBtnRow.addView(btnEditRaw)
        blacklistCard.addView(blacklistBtnRow)
        root.addView(blacklistCard)

        // Card 5 — System Permissions
        val permCard = makeCard(padVerticalDp = 12, bottomMarginDp = 14)
        tvShortcutsTitle = makeSectionTitle(bottomPadDp = 4)
        permCard.addView(tvShortcutsTitle)

        val (accRow, accLabel, accBadge) = makePermissionRow {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        tvAccLabel = accLabel
        tvAccBadge = accBadge
        permCard.addView(accRow)
        permCard.addView(makeDivider())

        val (overlayRow, overlayLabel, overlayBadge) = makePermissionRow {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
        tvOverlayLabel = overlayLabel
        tvOverlayBadge = overlayBadge
        permCard.addView(overlayRow)
        permCard.addView(makeDivider())

        val (batteryRow, batteryLabel, batteryBadge) = makePermissionRow {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        tvBatteryLabel = batteryLabel
        tvBatteryBadge = batteryBadge
        permCard.addView(batteryRow)
        root.addView(permCard)

        // Footer Log Card
        val logCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeRoundedBg(palette.card, palette.cardStroke, 16)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        tvLog = TextView(this).apply {
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setTextColor(palette.textSecondary)
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        logCard.addView(tvLog)
        root.addView(logCard)

        return ScrollView(this).apply {
            setBackgroundColor(palette.canvas)
            isFillViewport = true
            addView(root)
        }
    }

    private fun handleConnectClick() {
        val rawUrl = etWorkerUrl.text.toString().trim()
        val rawToken = etAccessKey.text.toString().trim()
        val normalizedUrl = ConfigStore.normalizeWorkerUrl(rawUrl)
        if (normalizedUrl.isEmpty() || rawToken.isEmpty()) {
            Toast.makeText(
                this,
                AppStrings.toastMissingConfig(AppStrings.isZh(this)),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (!ConfigStore.isValidWorkerUrl(normalizedUrl)) {
            Toast.makeText(
                this,
                AppStrings.get(this, R.string.ui_toast_invalid_url),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        requestNotificationPermissionIfNeeded()
        ConfigStore.saveConnectionConfig(this, normalizedUrl, rawToken)

        if (selectedVisionMode == ConfigStore.VISION_MODE_WORKERS_AI) {
            ConfigStore.setVisionMode(this, ConfigStore.VISION_MODE_WORKERS_AI)
        } else {
            val vUrl = etVisionUrl.text.toString().trim()
            val vKey = etVisionKey.text.toString().trim()
            val vModel = etVisionModel.text.toString().trim()
            if (ConfigStore.isValidVisionApiUrl(vUrl) && vModel.isNotEmpty()) {
                ConfigStore.saveVisionConfig(this, ConfigStore.VISION_MODE_CUSTOM, vUrl, vKey, vModel)
            }
        }

        etWorkerUrl.setText(ConfigStore.getWorkerUrl(this))
        RelayForegroundService.start(this)
        Toast.makeText(this, AppStrings.get(this, R.string.ui_toast_connecting), Toast.LENGTH_SHORT).show()
        refreshStatus()
    }

    private fun handleSaveVisionClick() {
        val apiUrl = etVisionUrl.text.toString().trim()
        val apiKey = etVisionKey.text.toString().trim()
        val model = etVisionModel.text.toString().trim()
        if (!ConfigStore.isValidVisionApiUrl(apiUrl) || model.isEmpty()) {
            Toast.makeText(
                this,
                AppStrings.get(this, R.string.ui_toast_vision_invalid),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        ConfigStore.saveVisionConfig(this, ConfigStore.VISION_MODE_CUSTOM, apiUrl, apiKey, model)
        selectedVisionMode = ConfigStore.VISION_MODE_CUSTOM
        etVisionUrl.setText(ConfigStore.getVisionApiUrl(this))
        etVisionModel.setText(ConfigStore.getVisionModel(this))
        Toast.makeText(
            this,
            AppStrings.get(this, R.string.ui_toast_vision_saved),
            Toast.LENGTH_SHORT
        ).show()
        refreshVisionModeUi()
    }

    private fun applyLanguageAndRefresh() {
        val zh = AppStrings.isZh(this)
        title = AppStrings.get(this, R.string.ui_title)
        tvTitle.text = AppStrings.get(this, R.string.ui_title)
        tvSubtitle.text = AppStrings.get(this, R.string.ui_subtitle)
        btnLang.text = AppStrings.langTogglePill(zh)

        tvConnTitle.text = AppStrings.get(this, R.string.ui_conn_title)
        tvUrlLabel.text = AppStrings.get(this, R.string.ui_url_label)
        etWorkerUrl.hint = AppStrings.get(this, R.string.ui_url_hint)
        tvTokenLabel.text = AppStrings.get(this, R.string.ui_token_label)
        etAccessKey.hint = AppStrings.get(this, R.string.ui_token_hint)
        btnToggleToken.text = AppStrings.tokenToggleLabel(zh, isTokenVisible)
        btnConnect.text = AppStrings.get(this, R.string.ui_save_connect)
        btnDisconnect.text = AppStrings.get(this, R.string.ui_disconnect)

        tvVisionTitle.text = AppStrings.get(this, R.string.ui_vision_title)
        btnVisionWorkers.text = AppStrings.get(this, R.string.ui_vision_mode_workers)
        btnVisionCustom.text = AppStrings.get(this, R.string.ui_vision_mode_custom)
        tvVisionWorkersTip.text = AppStrings.get(this, R.string.ui_vision_workers_tip)
        tvVisionUrlLabel.text = AppStrings.get(this, R.string.ui_vision_url_label)
        etVisionUrl.hint = AppStrings.get(this, R.string.ui_vision_url_hint)
        tvVisionKeyLabel.text = AppStrings.get(this, R.string.ui_vision_key_label)
        etVisionKey.hint = AppStrings.get(this, R.string.ui_vision_key_hint)
        btnToggleVisionKey.text = AppStrings.tokenToggleLabel(zh, isVisionKeyVisible)
        tvVisionModelLabel.text = AppStrings.get(this, R.string.ui_vision_model_label)
        etVisionModel.hint = AppStrings.get(this, R.string.ui_vision_model_hint)
        btnSaveVision.text = AppStrings.get(this, R.string.ui_vision_save)
        refreshVisionModeUi()

        tvExecModeTitle.text = AppStrings.get(this, R.string.ui_exec_mode_title)
        tvWinModeLabel.text = AppStrings.get(this, R.string.ui_win_mode_label)
        btnModeFreeform.text = AppStrings.get(this, R.string.ui_win_mode_freeform)
        btnModeFullscreen.text = AppStrings.get(this, R.string.ui_win_mode_fullscreen)
        tvWinModeTip.text = AppStrings.get(this, R.string.ui_win_mode_tip)
        swAutoReturn.text = AppStrings.get(this, R.string.ui_auto_return_check)
        swConfirmRisky.text = AppStrings.get(this, R.string.ui_confirm_risky_check)
        tvConfirmRiskyTip.text = AppStrings.get(this, R.string.ui_confirm_risky_tip)
        refreshExecModeUi()

        tvBlacklistTitle.text = AppStrings.get(this, R.string.ui_blacklist_title)
        btnPickApps.text = AppStrings.get(this, R.string.ui_pick_apps)
        btnEditRaw.text = AppStrings.get(this, R.string.ui_edit_raw)

        tvShortcutsTitle.text = AppStrings.get(this, R.string.ui_shortcuts_title)
        tvAccLabel.text = AppStrings.get(this, R.string.ui_btn_acc)
        tvOverlayLabel.text = AppStrings.get(this, R.string.ui_btn_overlay)
        tvBatteryLabel.text = AppStrings.get(this, R.string.ui_btn_battery)

        refreshBlacklistSummary()
        refreshStatus()
    }

    private fun refreshVisionModeUi() {
        val isCustom = selectedVisionMode == ConfigStore.VISION_MODE_CUSTOM
        styleSegmentButton(btnVisionWorkers, !isCustom)
        styleSegmentButton(btnVisionCustom, isCustom)
        visionWorkersGroup.visibility = if (isCustom) View.GONE else View.VISIBLE
        visionCustomGroup.visibility = if (isCustom) View.VISIBLE else View.GONE
    }

    private fun refreshExecModeUi() {
        val isFreeform = ConfigStore.getWindowMode(this) == ConfigStore.MODE_FREEFORM
        styleSegmentButton(btnModeFreeform, isFreeform)
        styleSegmentButton(btnModeFullscreen, !isFreeform)

        val autoReturn = ConfigStore.isAutoReturnGemini(this)
        if (swAutoReturn.isChecked != autoReturn) {
            swAutoReturn.isChecked = autoReturn
        }
        val confirmRisky = ConfigStore.isConfirmRiskyActions(this)
        if (swConfirmRisky.isChecked != confirmRisky) {
            swConfirmRisky.isChecked = confirmRisky
        }
    }

    private fun refreshBlacklistSummary() {
        val zh = AppStrings.isZh(this)
        val blocked = ConfigStore.getBlockedPackages(this)
        val pm = packageManager
        val displayNames = blocked.map { pkg ->
            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                val label = pm.getApplicationLabel(appInfo)?.toString()?.trim()
                if (!label.isNullOrEmpty() && label != pkg) label else pkg.substringAfterLast('.')
            } catch (_: Exception) {
                friendlyFallbackName(pkg, zh)
            }
        }.sorted()

        tvBlacklistSummary.text = AppStrings.formatBlacklistSummary(zh, blocked.size, displayNames)
    }

    private fun friendlyFallbackName(pkg: String, zh: Boolean): String =
        when (pkg) {
            "com.eg.android.AlipayGphone" -> if (zh) "支付宝" else "Alipay"
            "com.unionpay" -> if (zh) "云闪付" else "UnionPay"
            "com.icbc" -> if (zh) "工商银行" else "ICBC"
            "com.chinamworld.main" -> if (zh) "建设银行" else "CCB"
            "com.chinamworld.bocmbci" -> if (zh) "中国银行" else "Bank of China"
            "cmb.pb" -> if (zh) "招商银行" else "CMB"
            "com.android.bankabc" -> if (zh) "农业银行" else "ABC Bank"
            "com.onepassword.android" -> "1Password"
            "com.x8bit.bitwarden" -> "Bitwarden"
            "com.google.android.apps.walletnfcrel" -> "Google Wallet"
            "com.google.android.apps.authenticator2" -> "Authenticator"
            else -> pkg
        }

    private fun showInstalledAppPickerDialog() {
        val zh = AppStrings.isZh(this)
        val selected = ConfigStore.getBlockedPackages(this).toMutableSet()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }

        val etSearch = makeStyledEditText(View.generateViewId(), InputType.TYPE_CLASS_TEXT).apply {
            hint = AppStrings.get(this@MainActivity, R.string.ui_picker_search)
        }
        container.addView(etSearch)

        val tvLoading = TextView(this).apply {
            text = AppStrings.get(this@MainActivity, R.string.ui_picker_loading)
            textSize = 13f
            setTextColor(palette.textSecondary)
            setPadding(dp(4), dp(16), dp(4), dp(16))
        }
        container.addView(tvLoading)

        val listView = ListView(this).apply {
            visibility = View.GONE
            dividerHeight = dp(1)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(360)
            )
        }
        container.addView(listView)

        val dialog = AlertDialog.Builder(this)
            .setTitle(AppStrings.get(this, R.string.ui_picker_title))
            .setView(container)
            .setPositiveButton(AppStrings.get(this, R.string.ui_save)) { _, _ ->
                ConfigStore.saveBlockedPackages(this, selected)
                refreshBlacklistSummary()
                Toast.makeText(
                    this,
                    AppStrings.toastBlacklistSaved(zh, ConfigStore.getBlockedPackages(this).size),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(AppStrings.get(this, R.string.ui_cancel), null)
            .create()

        activeDialog?.dismiss()
        activeDialog = dialog
        dialog.setOnDismissListener {
            if (activeDialog === dialog) activeDialog = null
        }
        dialog.show()

        uiScope.launch {
            val allApps = withContext(Dispatchers.IO) {
                loadInstalledLauncherApps(selected)
            }
            if (!dialog.isShowing) return@launch

            tvLoading.visibility = View.GONE
            listView.visibility = View.VISIBLE

            var filteredApps: List<InstalledAppItem> = allApps

            val adapter = object : BaseAdapter() {
                override fun getCount(): Int = filteredApps.size
                override fun getItem(position: Int): InstalledAppItem = filteredApps[position]
                override fun getItemId(position: Int): Long = position.toLong()

                override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                    val holder: AppRowHolder
                    val rowView: LinearLayout
                    if (convertView == null) {
                        rowView = LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(dp(4), dp(8), dp(4), dp(8))
                        }
                        val ivIcon = ImageView(this@MainActivity).apply {
                            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                                marginEnd = dp(12)
                            }
                        }
                        val textCol = LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        }
                        val tvName = TextView(this@MainActivity).apply {
                            textSize = 14f
                            setTypeface(null, Typeface.BOLD)
                            setTextColor(palette.textPrimary)
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                        }
                        val tvPkg = TextView(this@MainActivity).apply {
                            textSize = 11f
                            setTextColor(palette.textSecondary)
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                        }
                        textCol.addView(tvName)
                        textCol.addView(tvPkg)

                        val cb = CheckBox(this@MainActivity).apply {
                            isFocusable = false
                            isClickable = false
                        }
                        rowView.addView(ivIcon)
                        rowView.addView(textCol)
                        rowView.addView(cb)

                        holder = AppRowHolder(ivIcon, tvName, tvPkg, cb)
                        rowView.tag = holder
                    } else {
                        rowView = convertView as LinearLayout
                        holder = rowView.tag as AppRowHolder
                    }

                    val item = getItem(position)
                    if (item.icon != null) {
                        holder.icon.setImageDrawable(item.icon)
                    } else {
                        holder.icon.setImageResource(android.R.drawable.sym_def_app_icon)
                    }
                    holder.name.text = item.label
                    holder.pkg.text = item.packageName
                    holder.checkBox.isChecked = selected.contains(item.packageName)

                    rowView.setOnClickListener {
                        if (selected.contains(item.packageName)) {
                            selected.remove(item.packageName)
                            holder.checkBox.isChecked = false
                        } else {
                            selected.add(item.packageName)
                            holder.checkBox.isChecked = true
                        }
                    }
                    return rowView
                }
            }

            listView.adapter = adapter

            etSearch.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val q = s?.toString()?.trim().orEmpty()
                    filteredApps = if (q.isEmpty()) {
                        allApps
                    } else {
                        allApps.filter {
                            it.label.contains(q, ignoreCase = true) ||
                                it.packageName.contains(q, ignoreCase = true)
                        }
                    }
                    adapter.notifyDataSetChanged()
                }
            })
        }
    }

    private class AppRowHolder(
        val icon: ImageView,
        val name: TextView,
        val pkg: TextView,
        val checkBox: CheckBox
    )

    private fun loadInstalledLauncherApps(currentlySelected: Set<String>): List<InstalledAppItem> {
        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = pm.queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)
        val map = linkedMapOf<String, InstalledAppItem>()

        for (ri in resolveInfos) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (pkg == packageName || map.containsKey(pkg)) continue
            val label = ri.loadLabel(pm)?.toString()?.trim().takeUnless { it.isNullOrEmpty() } ?: pkg
            val icon = try {
                ri.loadIcon(pm)
            } catch (_: Exception) {
                null
            }
            map[pkg] = InstalledAppItem(label = label, packageName = pkg, icon = icon)
        }

        return map.values.sortedWith(
            compareByDescending<InstalledAppItem> { currentlySelected.contains(it.packageName) }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }
        )
    }

    private fun showRawPackagesDialog() {
        val zh = AppStrings.isZh(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), 0)
        }
        val etRaw = EditText(this).apply {
            setText(ConfigStore.getBlockedPackagesText(this@MainActivity))
            minLines = 7
            maxLines = 12
            gravity = Gravity.TOP or Gravity.START
            textSize = 12.5f
            typeface = Typeface.MONOSPACE
            setTextColor(palette.textPrimary)
            background = makeRoundedBg(palette.inputBg, palette.inputStroke, 12)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        container.addView(etRaw)

        val dialog = AlertDialog.Builder(this)
            .setTitle(AppStrings.get(this, R.string.ui_raw_title))
            .setView(container)
            .setPositiveButton(AppStrings.get(this, R.string.ui_save)) { _, _ ->
                val updated = etRaw.text.toString()
                    .lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                ConfigStore.saveBlockedPackages(this, updated)
                refreshBlacklistSummary()
                Toast.makeText(
                    this,
                    AppStrings.toastBlacklistSaved(zh, ConfigStore.getBlockedPackages(this).size),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNeutralButton(AppStrings.get(this, R.string.ui_reset_default)) { _, _ ->
                ConfigStore.saveBlockedPackages(this, ConfigStore.DEFAULT_BLOCKED_PACKAGES)
                refreshBlacklistSummary()
                Toast.makeText(
                    this,
                    AppStrings.toastBlacklistSaved(zh, ConfigStore.DEFAULT_BLOCKED_PACKAGES.size),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(AppStrings.get(this, R.string.ui_cancel), null)
            .create()

        activeDialog?.dismiss()
        activeDialog = dialog
        dialog.setOnDismissListener {
            if (activeDialog === dialog) activeDialog = null
        }
        dialog.show()
    }

    private fun refreshStatus() {
        val zh = AppStrings.isZh(this)
        val accOn = PhoneAccessibilityService.isRunning()
        val overlayOn = Settings.canDrawOverlays(this)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryOn = pm.isIgnoringBatteryOptimizations(packageName)

        val statusEn = RelayForegroundService.getLocalizedStatus(false)
        val dotColor = when {
            statusEn == "Connected" -> palette.successText
            RelayForegroundService.isServiceRunning -> palette.warnText
            else -> palette.textMuted
        }
        (statusDot.background as? GradientDrawable)?.setColor(dotColor)

        tvStatusRelay.text =
            "${AppStrings.get(this, R.string.ui_relay_prefix)}${RelayForegroundService.getLocalizedStatus(zh)}"
        tvStatusPerms.text = AppStrings.formatPermLine(zh, accOn, overlayOn, batteryOn)

        updatePermissionBadge(tvAccBadge, accOn)
        updatePermissionBadge(tvOverlayBadge, overlayOn)
        updatePermissionBadge(tvBatteryBadge, batteryOn)

        tvLog.text = "${AppStrings.get(this, R.string.ui_log_prefix)}${RelayForegroundService.getLocalizedLog(zh)}"
    }
}
