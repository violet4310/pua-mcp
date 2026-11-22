package com.geminiphone.agent

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

object AppStrings {
    @Volatile
    private var cachedZhRes: Resources? = null

    @Volatile
    private var cachedEnRes: Resources? = null

    fun isZh(context: Context): Boolean = ConfigStore.getLanguage(context) != "en"

    private fun res(context: Context, zh: Boolean): Resources {
        val existing = if (zh) cachedZhRes else cachedEnRes
        if (existing != null) return existing
        val appCtx = context.applicationContext ?: context
        val created = appCtx.createConfigurationContext(
            Configuration(appCtx.resources.configuration).apply {
                setLocale(if (zh) Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH)
            }
        ).resources
        if (zh) cachedZhRes = created else cachedEnRes = created
        return created
    }

    fun get(context: Context, resId: Int): String =
        res(context, isZh(context)).getString(resId)

    fun toastMissingConfig(zh: Boolean): String =
        if (zh) "请先填写 Worker 服务地址和连接密钥" else "Please enter both Relay Server URL and RELAY_TOKEN"

    fun tokenToggleLabel(zh: Boolean, visible: Boolean): String =
        if (zh) {
            if (visible) "隐藏" else "显示"
        } else {
            if (visible) "Hide" else "Show"
        }

    fun langTogglePill(zh: Boolean): String =
        if (zh) "EN" else "中文"

    fun toastBlacklistSaved(zh: Boolean, count: Int): String =
        if (zh) "黑名单已更新（共保护 $count 个应用）" else "Blacklist updated ($count apps protected)"

    fun formatBlacklistSummary(
        zh: Boolean,
        totalCount: Int,
        resolvedNames: List<String>
    ): String {
        if (totalCount == 0) {
            return if (zh) "当前未设置保护应用（点下方「＋ 选择本机应用」勾选）" else "No apps selected yet (tap ＋ below to add)"
        }
        val preview = resolvedNames.take(6)
        return if (zh) {
            val joined = preview.joinToString("、")
            val tail = if (totalCount > 6) " 等共 $totalCount 个应用" else "（共 $totalCount 个应用）"
            "已保护：$joined$tail"
        } else {
            val joined = preview.joinToString(", ")
            val tail = if (totalCount > 6) " (+${totalCount - 6} more, $totalCount total)" else " ($totalCount total)"
            "Protected: $joined$tail"
        }
    }

    fun formatPermLine(
        zh: Boolean,
        accOn: Boolean,
        overlayOn: Boolean,
        batteryOn: Boolean
    ): String {
        return if (zh) {
            val a = if (accOn) "已开启" else "未开启"
            val o = if (overlayOn) "已开启" else "未开启"
            val b = if (batteryOn) "已开启" else "未开启"
            "无障碍: $a  |  悬浮急停: $o  |  后台保活: $b"
        } else {
            val a = if (accOn) "ON" else "OFF"
            val o = if (overlayOn) "ON" else "OFF"
            val b = if (batteryOn) "ON" else "OFF"
            "Accessibility: $a  |  Overlay: $o  |  KeepAlive: $b"
        }
    }
}
