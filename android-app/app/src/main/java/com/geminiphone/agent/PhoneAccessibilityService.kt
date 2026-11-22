package com.geminiphone.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

class PhoneAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: PhoneAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }

    @Volatile
    private var lastOriginPackage: String = ""

    @Volatile
    private var lastTargetPackage: String = ""

    @Volatile
    private var lastTargetSetAtMs: Long = 0L

    @Volatile
    private var latestWindowEventPackage: String = ""

    private val riskOverlay by lazy { RiskConfirmOverlay(this) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        RelayForegroundService.notifyAccessibilityStateChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()?.trim().orEmpty()
            if (pkg.isNotEmpty()) {
                latestWindowEventPackage = pkg
            }
        }
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onDestroy() {
        riskOverlay.dismissActive()
        RiskSession.reset()
        if (instance === this) {
            instance = null
            RelayForegroundService.notifyAccessibilityStateChanged()
        }
        super.onDestroy()
    }

    private fun isHostGeminiPackage(pkg: String): Boolean =
        pkg == "com.google.android.apps.bard" ||
            pkg == "com.google.android.googlequicksearchbox" ||
            (lastOriginPackage.isNotEmpty() && pkg == lastOriginPackage)

    private fun getVisibleAppRoots(): List<AccessibilityNodeInfo> {
        val winList = try { windows } catch (_: Exception) { emptyList() }
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels.toFloat().coerceAtLeast(1f)
        val screenH = metrics.heightPixels.toFloat().coerceAtLeast(1f)
        val bounds = Rect()

        data class WindowEntry(
            val root: AccessibilityNodeInfo,
            val layer: Int,
            val isTarget: Boolean,
            val isFocusedOrActive: Boolean
        )

        val topDialogs = mutableListOf<WindowEntry>()
        val targetOrFreeform = mutableListOf<WindowEntry>()
        val otherApps = mutableListOf<WindowEntry>()
        val hostGemini = mutableListOf<WindowEntry>()
        var hasTargetWindow = false

        for (win in winList) {
            val isFocusedOrActive = win.isFocused || win.isActive
            val isSupportedType =
                win.type == AccessibilityWindowInfo.TYPE_APPLICATION ||
                    (win.type == AccessibilityWindowInfo.TYPE_SYSTEM && isFocusedOrActive)
            if (!isSupportedType) continue
            val root = win.root ?: continue
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg == packageName) continue

            win.getBoundsInScreen(bounds)
            if (win.type == AccessibilityWindowInfo.TYPE_SYSTEM) {
                val keepSystemWindow = if (pkg == "com.android.systemui") {
                    bounds.height() >= screenH * 0.5f
                } else {
                    bounds.width() >= screenW * 0.35f && bounds.height() >= screenH * 0.10f
                }
                if (!keepSystemWindow) continue
            }

            val isTarget = lastTargetPackage.isNotEmpty() && pkg == lastTargetPackage
            if (isTarget) hasTargetWindow = true
            val isFreeform = bounds.width() > 100 &&
                bounds.height() > 100 &&
                (bounds.width() < screenW * 0.88f || bounds.height() < screenH * 0.82f)
            val entry = WindowEntry(
                root = root,
                layer = win.layer,
                isTarget = isTarget,
                isFocusedOrActive = isFocusedOrActive
            )

            when {
                win.type == AccessibilityWindowInfo.TYPE_SYSTEM ||
                    (isFocusedOrActive && pkg.contains("permissioncontroller")) -> topDialogs.add(entry)
                isTarget || (isFreeform && !isHostGeminiPackage(pkg)) -> targetOrFreeform.add(entry)
                !isHostGeminiPackage(pkg) -> otherApps.add(entry)
                else -> hostGemini.add(entry)
            }
        }

        val combined = buildList {
            addAll(topDialogs.sortedByDescending { it.layer })
            addAll(
                targetOrFreeform.sortedWith(
                    compareByDescending<WindowEntry> { it.isTarget }
                        .thenByDescending { it.layer }
                        .thenByDescending { it.isFocusedOrActive }
                )
            )
            addAll(
                otherApps.sortedWith(
                    compareByDescending<WindowEntry> { it.layer }
                        .thenByDescending { it.isFocusedOrActive }
                )
            )
            addAll(hostGemini.sortedByDescending { it.layer })
        }.map { it.root }

        if (winList.isNotEmpty() && lastTargetPackage.isNotEmpty() && !hasTargetWindow) {
            if (System.currentTimeMillis() - lastTargetSetAtMs > 2000L) {
                lastTargetPackage = ""
                lastTargetSetAtMs = 0L
            }
        }
        if (combined.isNotEmpty()) return combined
        return listOfNotNull(rootInActiveWindow)
    }

    private fun isPackageBlocked(pkg: String, blocked: Set<String>): Boolean =
        pkg == packageName || blocked.contains(pkg)

    private fun getPrimaryWindowBounds(): Rect {
        val dm = resources.displayMetrics
        val fullRect = Rect(0, 0, dm.widthPixels, dm.heightPixels)
        val screenW = dm.widthPixels.toFloat().coerceAtLeast(1f)
        val screenH = dm.heightPixels.toFloat().coerceAtLeast(1f)
        val winList = try { windows } catch (_: Exception) { emptyList() }

        data class AppWin(
            val pkg: String,
            val bounds: Rect,
            val layer: Int,
            val isFocusedOrActive: Boolean,
            val isFreeform: Boolean
        )

        val appWindows = winList.mapNotNull { win ->
            if (win.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@mapNotNull null
            val pkg = win.root?.packageName?.toString().orEmpty()
            if (pkg == packageName) return@mapNotNull null
            val r = Rect()
            win.getBoundsInScreen(r)
            if (r.width() <= 100 || r.height() <= 100) return@mapNotNull null
            val freeform = r.width() < screenW * 0.88f || r.height() < screenH * 0.82f
            AppWin(
                pkg = pkg,
                bounds = r,
                layer = win.layer,
                isFocusedOrActive = win.isFocused || win.isActive,
                isFreeform = freeform
            )
        }.sortedByDescending { it.layer }

        if (lastTargetPackage.isNotEmpty()) {
            appWindows.firstOrNull { it.pkg == lastTargetPackage }?.let { return it.bounds }
        }
        appWindows.firstOrNull { it.isFreeform && !isHostGeminiPackage(it.pkg) }?.let { return it.bounds }
        appWindows.firstOrNull { it.isFocusedOrActive && !isHostGeminiPackage(it.pkg) }?.let { return it.bounds }
        appWindows.firstOrNull { it.isFocusedOrActive }?.let { return it.bounds }
        return fullRect
    }

    private fun checkSensitiveAppBlocked(): String? {
        val blocked = ConfigStore.getEffectiveBlockedPackages(this)
        val winList = try { windows } catch (_: Exception) { emptyList() }

        for (win in winList) {
            if (win.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
            val pkg = win.root?.packageName?.toString().orEmpty()
            if (pkg.isEmpty()) continue
            // Exclude our own non-focused floating Emergency Stop overlay button
            if (pkg == packageName &&
                win.type != AccessibilityWindowInfo.TYPE_APPLICATION &&
                !win.isActive &&
                !win.isFocused
            ) {
                continue
            }
            if (isPackageBlocked(pkg, blocked)) {
                return "Blocked by local safety policy: active app '$pkg' is in your sensitive app blacklist."
            }
        }

        val activeRoot = try { rootInActiveWindow } catch (_: Exception) { null }
        val activePkg = activeRoot?.packageName?.toString().orEmpty()
        if (activePkg.isNotEmpty() && isPackageBlocked(activePkg, blocked)) {
            return "Blocked by local safety policy: active app '$activePkg' is in your sensitive app blacklist."
        }

        val activeWindowHasNullRoot = winList.any { (it.isActive || it.isFocused) && it.root == null }
        if ((activeRoot == null || activeWindowHasNullRoot) &&
            latestWindowEventPackage.isNotEmpty() &&
            isPackageBlocked(latestWindowEventPackage, blocked)
        ) {
            return "Blocked by local safety policy: active app '$latestWindowEventPackage' is in your sensitive app blacklist."
        }
        return null
    }

    private fun isAllowEscapeAction(action: String, args: JSONObject): Boolean =
        action == "return_to_gemini" ||
            (action == "press_key" && (args.optString("key") == "home" || args.optString("key") == "back"))

    suspend fun executeCommand(cmd: PhoneCommand): CommandResult {
        val allowEscape = isAllowEscapeAction(cmd.action, cmd.args)
        if (!allowEscape) {
            checkSensitiveAppBlocked()?.let { return CommandResult(ok = false, error = it) }
        }

        return when (cmd.action) {
            "get_screen_ui" -> handleGetScreenUi(cmd.args)
            "take_screenshot" -> handleTakeScreenshot(cmd.args)
            "tap" -> handleTap(cmd.args)
            "long_press" -> handleLongPress(cmd.args)
            "swipe" -> handleSwipe(cmd.args)
            "type_text" -> handleTypeText(cmd.args)
            "open_app" -> handleOpenApp(cmd.args)
            "press_key" -> handlePressKey(cmd.args)
            "return_to_gemini" -> handleReturnToGemini(cmd.args)
            else -> CommandResult(ok = false, error = "Unsupported action: ${cmd.action}")
        }
    }

    private fun buildCompactScreenUiPayload(
        maxNodes: Int = 80,
        includeNonInteractive: Boolean = true,
        compact: Boolean = false,
        scope: String = "window"
    ): JSONObject? {
        val roots = getVisibleAppRoots()
        val firstRoot = roots.firstOrNull() ?: return null
        val boundedMax = maxNodes.coerceIn(10, 500)
        val metrics = resources.displayMetrics
        val nodesArray = JSONArray()
        val maxTextLen = if (compact) 60 else 120
        val includeAllWindows = scope == "all"

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null || nodesArray.length() >= boundedMax) return
            if (!node.isVisibleToUser) return

            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val resId = node.viewIdResourceName?.substringAfterLast("/")?.trim().orEmpty()
            val interactive = node.isClickable || node.isEditable || node.isCheckable || node.isScrollable

            val shouldInclude = if (compact) {
                val hasMeaningfulLabel = text.isNotEmpty() || desc.isNotEmpty() || resId.isNotEmpty()
                (interactive && (node.isEditable || hasMeaningfulLabel)) ||
                    (includeNonInteractive && (text.isNotEmpty() || desc.isNotEmpty()))
            } else {
                interactive || (includeNonInteractive && (text.isNotEmpty() || desc.isNotEmpty()))
            }
            if (shouldInclude) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0) {
                    val item = JSONObject()
                    if (text.isNotEmpty()) item.put("text", text.take(maxTextLen))
                    if (desc.isNotEmpty() && desc != text) item.put("contentDesc", desc.take(maxTextLen))
                    if (resId.isNotEmpty()) item.put("resourceId", resId)
                    if (!compact) {
                        item.put("class", node.className?.toString()?.substringAfterLast(".") ?: "")
                    }
                    if (node.isClickable) item.put("clickable", true)
                    if (node.isEditable) item.put("editable", true)
                    if (node.isChecked) item.put("checked", true)
                    if (node.isFocused) item.put("focused", true)
                    item.put("centerX", rect.centerX())
                    item.put("centerY", rect.centerY())
                    if (!compact) {
                        item.put("bounds", "${rect.left},${rect.top},${rect.right},${rect.bottom}")
                    }
                    nodesArray.put(item)
                }
            }

            for (i in 0 until node.childCount) {
                if (nodesArray.length() >= boundedMax) break
                traverse(node.getChild(i))
            }
        }

        val firstPkg = firstRoot.packageName?.toString().orEmpty()
        val firstWinType = try { firstRoot.window?.type } catch (_: Exception) { null }
        val isTopNonSystemUiDialog =
            firstPkg != "com.android.systemui" &&
                (firstWinType == AccessibilityWindowInfo.TYPE_SYSTEM ||
                    firstPkg.contains("permissioncontroller"))

        if (includeAllWindows) {
            var collectedNonHost = false
            for (r in roots) {
                if (nodesArray.length() >= boundedMax) break
                val rPkg = r.packageName?.toString().orEmpty()
                val isHost = isHostGeminiPackage(rPkg)
                if (compact && collectedNonHost && isHost) continue
                traverse(r)
                if (compact && !isHost && nodesArray.length() > 0) {
                    collectedNonHost = true
                }
            }
        } else {
            val primaryRoots = buildList {
                add(firstRoot)
                if (isTopNonSystemUiDialog) {
                    roots.drop(1).firstOrNull { r ->
                        val winType = try { r.window?.type } catch (_: Exception) { null }
                        val rPkg = r.packageName?.toString().orEmpty()
                        winType == AccessibilityWindowInfo.TYPE_APPLICATION &&
                            rPkg.isNotEmpty() &&
                            !rPkg.contains("permissioncontroller")
                    }?.let { add(it) }
                }
            }
            for (r in primaryRoots) {
                if (nodesArray.length() >= boundedMax) break
                traverse(r)
            }
            // Fallback if the top-ranked window yielded zero visible nodes (e.g. transparent overlay)
            if (nodesArray.length() == 0) {
                val visited = primaryRoots.toSet()
                for (r in roots) {
                    if (r in visited) continue
                    traverse(r)
                    if (nodesArray.length() > 0) break
                }
            }
        }

        val root = firstRoot
        val rootPkg = root.packageName?.toString().orEmpty()
        val rootWinType = firstWinType
        val underlyingAppPkg = if (
            rootWinType == AccessibilityWindowInfo.TYPE_SYSTEM &&
            rootPkg != "com.android.systemui"
        ) {
            roots.firstOrNull { r ->
                val winType = try { r.window?.type } catch (_: Exception) { null }
                winType == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    !r.packageName?.toString().isNullOrEmpty()
            }?.packageName?.toString().orEmpty()
        } else {
            ""
        }

        return JSONObject().apply {
            if (underlyingAppPkg.isNotEmpty()) {
                put("packageName", underlyingAppPkg)
                put("dialogPackage", rootPkg)
            } else {
                put("packageName", rootPkg)
            }
            if (compact) {
                put("screen", "${metrics.widthPixels}x${metrics.heightPixels}")
            } else {
                val winBounds = getPrimaryWindowBounds()
                put("windowScope", if (includeAllWindows) "all" else "window")
                put("windowCount", roots.size)
                put("activeWindowBounds", "${winBounds.left},${winBounds.top},${winBounds.right},${winBounds.bottom}")
                put("screenWidth", metrics.widthPixels)
                put("screenHeight", metrics.heightPixels)
            }
            put("nodeCount", nodesArray.length())
            put("nodes", nodesArray)
        }
    }

    private suspend fun attachScreenUi(
        obj: JSONObject,
        settleDelayMs: Long,
        returnUi: Boolean
    ): JSONObject {
        if (!returnUi) return obj
        if (settleDelayMs > 0L) delay(settleDelayMs)
        val blocked = checkSensitiveAppBlocked()
        if (blocked != null) {
            obj.put("screenUiBlocked", blocked)
        } else {
            buildCompactScreenUiPayload(maxNodes = 100, compact = true)?.let { obj.put("screenUi", it) }
        }
        return obj
    }

    private fun handleGetScreenUi(args: JSONObject): CommandResult {
        val includeNonInteractive = args.optBoolean("includeNonInteractive", true)
        val maxNodes = args.optInt("maxNodes", 160).coerceIn(10, 500)
        val scope = args.optString("scope", "window").trim().lowercase()
        val payload = buildCompactScreenUiPayload(
            maxNodes = maxNodes,
            includeNonInteractive = includeNonInteractive,
            scope = scope
        )
            ?: return CommandResult(ok = false, error = "No active window available. Is the screen unlocked?")
        return CommandResult(ok = true, data = payload)
    }

    private suspend fun handleTakeScreenshot(args: JSONObject): CommandResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return CommandResult(
                ok = false,
                error = "take_screenshot requires Android 11 (API 30) or higher. Use get_screen_ui instead."
            )
        }

        if (isDeviceLocked()) {
            return CommandResult(
                ok = false,
                error = "Blocked by local safety policy: the device is locked. Unlock the screen first."
            )
        }

        val quality = args.optInt("quality", 45).coerceIn(20, 90)
        val maxWidth = args.optInt("maxWidth", 540).coerceIn(320, 1440)
        val returnUi = args.optBoolean("returnUi", true)
        val scope = args.optString("scope", "window").trim().lowercase()
        val describe = args.optBoolean("describe", true)
        val prompt = args.optString("prompt", "").trim().take(500).ifEmpty { null }

        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        var cropRect: Rect? = null
        if (scope != "full") {
            val topPkg = getVisibleAppRoots().firstOrNull()?.packageName?.toString().orEmpty()
            if (topPkg != "com.android.systemui") {
                val winBounds = getPrimaryWindowBounds()
                if (winBounds.width() >= 100 &&
                    winBounds.height() >= 100 &&
                    (winBounds.width() < screenW * 0.92f || winBounds.height() < screenH * 0.88f)
                ) {
                    cropRect = Rect(winBounds)
                }
            }
        }

        val result = suspendCancellableCoroutine { cont ->
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                Dispatchers.Default.asExecutor(),
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        var bitmap: Bitmap? = null
                        var softBitmap: Bitmap? = null
                        var croppedBitmap: Bitmap? = null
                        var scaled: Bitmap? = null
                        try {
                            val hwBuffer = screenshot.hardwareBuffer
                            try {
                                bitmap = Bitmap.wrapHardwareBuffer(hwBuffer, screenshot.colorSpace)
                            } finally {
                                try {
                                    hwBuffer.close()
                                } catch (_: Exception) {
                                    // ignore
                                }
                            }
                            if (bitmap == null) {
                                if (cont.isActive) {
                                    cont.resume(CommandResult(ok = false, error = "Failed to wrap screenshot bitmap."))
                                }
                                return
                            }
                            softBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                            bitmap.recycle()
                            bitmap = null

                            if (softBitmap == null) {
                                if (cont.isActive) {
                                    cont.resume(CommandResult(ok = false, error = "Failed to copy screenshot bitmap."))
                                }
                                return
                            }

                            var sourceBitmap: Bitmap = softBitmap
                            var wasCropped = false
                            var cropOffsetX = 0
                            var cropOffsetY = 0
                            var cropWidth = softBitmap.width
                            var cropHeight = softBitmap.height

                            if (cropRect != null) {
                                val left = cropRect.left.coerceIn(0, softBitmap.width)
                                val top = cropRect.top.coerceIn(0, softBitmap.height)
                                val right = cropRect.right.coerceIn(left, softBitmap.width)
                                val bottom = cropRect.bottom.coerceIn(top, softBitmap.height)
                                val cw = right - left
                                val ch = bottom - top
                                if (cw >= 100 && ch >= 100) {
                                    croppedBitmap = Bitmap.createBitmap(softBitmap, left, top, cw, ch)
                                    sourceBitmap = croppedBitmap
                                    wasCropped = true
                                    cropOffsetX = left
                                    cropOffsetY = top
                                    cropWidth = cw
                                    cropHeight = ch
                                }
                            }

                            scaled = if (sourceBitmap.width > maxWidth) {
                                val ratio = maxWidth.toFloat() / sourceBitmap.width.toFloat()
                                val targetH = (sourceBitmap.height * ratio).toInt().coerceAtLeast(1)
                                Bitmap.createScaledBitmap(sourceBitmap, maxWidth, targetH, true)
                            } else {
                                sourceBitmap
                            }

                            val out = ByteArrayOutputStream()
                            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                            val jpegBytes = out.toByteArray()
                            val w = scaled.width
                            val h = scaled.height

                            val b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
                            if (cont.isActive) {
                                cont.resume(
                                    CommandResult(
                                        ok = true,
                                        data = JSONObject()
                                            .put("width", w)
                                            .put("height", h)
                                            .put("imageBytes", jpegBytes.size)
                                            .put("mimeType", "image/jpeg")
                                            .put("cropped", wasCropped)
                                            .put("cropOffsetX", cropOffsetX)
                                            .put("cropOffsetY", cropOffsetY)
                                            .put("cropWidth", cropWidth)
                                            .put("cropHeight", cropHeight)
                                            .put("screenWidth", screenW)
                                            .put("screenHeight", screenH),
                                        imageBase64 = b64,
                                        imageMimeType = "image/jpeg"
                                    )
                                )
                            }
                        } catch (e: Exception) {
                            if (cont.isActive) {
                                cont.resume(CommandResult(ok = false, error = "Screenshot encoding failed: ${e.message}"))
                            }
                        } finally {
                            if (scaled != null && scaled !== croppedBitmap && scaled !== softBitmap) {
                                scaled.recycle()
                            }
                            if (croppedBitmap != null && croppedBitmap !== softBitmap) {
                                croppedBitmap.recycle()
                            }
                            softBitmap?.recycle()
                            bitmap?.recycle()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        if (cont.isActive) {
                            cont.resume(CommandResult(ok = false, error = "takeScreenshot failed with errorCode=$errorCode"))
                        }
                    }
                }
            )
        }
        if (result.ok && result.data is JSONObject) {
            val dataObj = result.data
            if (describe && ConfigStore.getVisionMode(this) == ConfigStore.VISION_MODE_CUSTOM) {
                val b64 = result.imageBase64
                if (!b64.isNullOrEmpty()) {
                    val vr = VisionAnalyzer.analyzeScreenshot(
                        context = this,
                        imageBase64 = b64,
                        mimeType = result.imageMimeType ?: "image/jpeg",
                        cropOffsetX = dataObj.optInt("cropOffsetX", 0),
                        cropOffsetY = dataObj.optInt("cropOffsetY", 0),
                        cropWidth = dataObj.optInt("cropWidth", 0),
                        cropHeight = dataObj.optInt("cropHeight", 0),
                        screenWidth = dataObj.optInt("screenWidth", screenW),
                        screenHeight = dataObj.optInt("screenHeight", screenH),
                        imageWidth = dataObj.optInt("width", 0),
                        imageHeight = dataObj.optInt("height", 0),
                        cropped = dataObj.optBoolean("cropped", false),
                        userPrompt = prompt
                    )
                    dataObj.put("visionProvider", "custom")
                    dataObj.put("visionModel", vr.model)
                    vr.description?.let { dataObj.put("visionDescription", it) }
                    vr.error?.let { dataObj.put("visionError", it) }
                }
            }
            if (returnUi) {
                attachScreenUi(dataObj, settleDelayMs = 0L, returnUi = true)
            }
        }
        return result
    }

    private suspend fun confirmRiskyAction(
        category: RiskCategory,
        actionDesc: String,
        label: String,
        pkgHint: String?
    ): CommandResult? {
        if (!ConfigStore.isConfirmRiskyActions(this) || RiskSession.isAllowAll()) return null

        val pkg = pkgHint?.takeIf { it.isNotEmpty() }
            ?: lastTargetPackage.takeIf { it.isNotEmpty() }
            ?: rootInActiveWindow?.packageName?.toString().orEmpty()
        val catName = RiskClassifier.categoryName(category, false)
        return when (riskOverlay.ask(appLabelOf(pkg), actionDesc, category)) {
            ConfirmDecision.ALLOW -> null
            ConfirmDecision.ALLOW_SESSION -> {
                RiskSession.allowAllForSession()
                null
            }
            ConfirmDecision.DENY -> CommandResult(
                ok = false,
                error = "The user REJECTED this $catName action ('$label') on the phone. Do not retry it; ask the user how to proceed."
            )
            ConfirmDecision.TIMEOUT -> CommandResult(
                ok = false,
                error = "The user did not confirm this $catName action ('$label') on the phone within " +
                    "${RiskConfirmOverlay.DEFAULT_TIMEOUT_MS / 1000}s, so it was cancelled. Ask the user before retrying."
            )
        }
    }

    private fun appLabelOf(pkg: String): String {
        if (pkg.isEmpty()) return "?"
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg
        }
    }

    /** Deepest visible node under (x, y), checking later (top-drawn) children first. */
    private fun findNodeAt(x: Int, y: Int): AccessibilityNodeInfo? {
        val r = Rect()
        fun dfs(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null || !node.isVisibleToUser) return null
            node.getBoundsInScreen(r)
            if (!r.contains(x, y)) return null
            for (i in node.childCount - 1 downTo 0) {
                dfs(node.getChild(i))?.let { return it }
            }
            return node
        }
        for (root in getVisibleAppRoots()) {
            dfs(root)?.let { return it }
        }
        return null
    }

    /**
     * Collects human-readable labels (text, contentDescription) first, then formatted
     * resourceId tokens (splitting '_' and camelCase), across the target clickable element
     * and its near descendants.
     */
    private fun collectActionLabels(node: AccessibilityNodeInfo?): List<String> {
        if (node == null) return emptyList()
        val textLabels = mutableListOf<String>()
        val resIdLabels = mutableListOf<String>()

        fun add(n: AccessibilityNodeInfo) {
            n.text?.toString()?.let(textLabels::add)
            n.contentDescription?.toString()?.let(textLabels::add)
            n.viewIdResourceName
                ?.substringAfterLast('/')
                ?.replace('_', ' ')
                ?.replace(Regex("([a-z])([A-Z])"), "$1 $2")
                ?.let(resIdLabels::add)
        }

        add(node)
        var clickable: AccessibilityNodeInfo? = node
        var depth = 0
        while (clickable != null && !clickable.isClickable && depth < 6) {
            clickable = clickable.parent
            depth++
        }
        val target = if (clickable?.isClickable == true) clickable else node
        if (target !== node) add(target)

        fun addDescendants(n: AccessibilityNodeInfo, level: Int) {
            if (level > 3 || (textLabels.size + resIdLabels.size) > 30) return
            for (i in 0 until n.childCount) {
                val child = n.getChild(i) ?: continue
                add(child)
                addDescendants(child, level + 1)
            }
        }
        addDescendants(target, 1)
        return textLabels + resIdLabels
    }

    /**
     * True when the device keyguard / lock screen is active; unlocked notification shade and
     * quick settings screenshots are intentionally permitted.
     */
    private fun isDeviceLocked(): Boolean {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return km?.isKeyguardLocked == true
    }

    private suspend fun handleTap(args: JSONObject): CommandResult {
        var x = if (args.has("x")) args.optInt("x", -1) else -1
        var y = if (args.has("y")) args.optInt("y", -1) else -1
        var matchedNode: AccessibilityNodeInfo? = null

        if (x < 0 || y < 0) {
            val targetText = args.optString("text", "").trim()
            val targetResId = args.optString("resourceId", "").trim()
            val found = findNode(targetText, targetResId)
                ?: return CommandResult(
                    ok = false,
                    error = "Could not find UI node matching text='$targetText' resourceId='$targetResId'."
                )
            matchedNode = found
            val rect = Rect()
            found.getBoundsInScreen(rect)
            x = rect.centerX()
            y = rect.centerY()
        }

        val hitNode = matchedNode ?: findNodeAt(x, y)
        val labels = collectActionLabels(hitNode) + args.optString("text", "")
        RiskClassifier.classify(labels)?.let { hit ->
            val zh = AppStrings.isZh(this)
            val desc = if (zh) "点击【${hit.label}】" else "tap \"${hit.label}\""
            confirmRiskyAction(hit.category, desc, hit.label, hitNode?.packageName?.toString())
                ?.let { return it }
        }

        var ok = performGesturePath(x.toFloat(), y.toFloat(), x.toFloat(), y.toFloat(), 60L)
        if (!ok && matchedNode != null) {
            ok = performClickOnNodeOrParent(matchedNode)
        }

        return if (ok) {
            val data = attachScreenUi(
                JSONObject().put("tappedX", x).put("tappedY", y),
                settleDelayMs = 320L,
                returnUi = args.optBoolean("returnUi", true)
            )
            CommandResult(ok = true, data = data)
        } else {
            CommandResult(ok = false, error = "Gesture tap cancelled or failed at ($x, $y).")
        }
    }

    private fun performClickOnNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            current = current.parent
            depth++
        }
        return false
    }

    private suspend fun handleLongPress(args: JSONObject): CommandResult {
        val x = args.optInt("x", -1)
        val y = args.optInt("y", -1)
        val duration = args.optLong("durationMs", 800L).coerceIn(300L, 5000L)
        val dm = resources.displayMetrics
        if (x !in 0 until dm.widthPixels || y !in 0 until dm.heightPixels) {
            return CommandResult(
                ok = false,
                error = "long_press requires x in 0..${dm.widthPixels - 1} and y in 0..${dm.heightPixels - 1}."
            )
        }
        val ok = performGesturePath(x.toFloat(), y.toFloat(), x.toFloat(), y.toFloat(), duration)
        return if (ok) {
            val data = attachScreenUi(
                JSONObject().put("x", x).put("y", y).put("durationMs", duration),
                settleDelayMs = 300L,
                returnUi = args.optBoolean("returnUi", true)
            )
            CommandResult(ok = true, data = data)
        } else {
            CommandResult(ok = false, error = "Gesture long_press failed at ($x, $y).")
        }
    }

    private suspend fun handleSwipe(args: JSONObject): CommandResult {
        val wb = getPrimaryWindowBounds()
        val left = wb.left.toFloat()
        val top = wb.top.toFloat()
        val w = wb.width().toFloat()
        val h = wb.height().toFloat()
        val duration = args.optLong("durationMs", 350L).coerceIn(100L, 2000L)
        val dm = resources.displayMetrics

        val (sx, sy, ex, ey) = when (args.optString("direction", "").lowercase()) {
            "up" -> listOf(left + w * 0.5f, top + h * 0.75f, left + w * 0.5f, top + h * 0.25f)
            "down" -> listOf(left + w * 0.5f, top + h * 0.25f, left + w * 0.5f, top + h * 0.75f)
            "left" -> listOf(left + w * 0.8f, top + h * 0.5f, left + w * 0.2f, top + h * 0.5f)
            "right" -> listOf(left + w * 0.2f, top + h * 0.5f, left + w * 0.8f, top + h * 0.5f)
            else -> {
                if (!args.has("startX") || !args.has("startY") || !args.has("endX") || !args.has("endY")) {
                    return CommandResult(
                        ok = false,
                        error = "swipe requires either 'direction' (up/down/left/right) or startX/startY/endX/endY."
                    )
                }
                val rawSx = args.optDouble("startX", Double.NaN)
                val rawSy = args.optDouble("startY", Double.NaN)
                val rawEx = args.optDouble("endX", Double.NaN)
                val rawEy = args.optDouble("endY", Double.NaN)
                if (!rawSx.isFinite() || !rawSy.isFinite() || !rawEx.isFinite() || !rawEy.isFinite() ||
                    rawSx < 0 || rawSy < 0 || rawEx < 0 || rawEy < 0 ||
                    rawSx >= dm.widthPixels || rawSy >= dm.heightPixels ||
                    rawEx >= dm.widthPixels || rawEy >= dm.heightPixels
                ) {
                    return CommandResult(
                        ok = false,
                        error = "swipe coordinates must be finite and within display bounds (0..${dm.widthPixels - 1}, 0..${dm.heightPixels - 1})."
                    )
                }
                listOf(rawSx.toFloat(), rawSy.toFloat(), rawEx.toFloat(), rawEy.toFloat())
            }
        }

        val ok = performGesturePath(sx, sy, ex, ey, duration)
        return if (ok) {
            val msg = "Swiped from (${sx.toInt()},${sy.toInt()}) to (${ex.toInt()},${ey.toInt()})"
            val returnUi = args.optBoolean("returnUi", true)
            val data: Any = if (returnUi) {
                attachScreenUi(JSONObject().put("message", msg), settleDelayMs = 300L, returnUi = true)
            } else {
                msg
            }
            CommandResult(ok = true, data = data)
        } else {
            CommandResult(ok = false, error = "Swipe gesture failed.")
        }
    }

    private suspend fun handleTypeText(args: JSONObject): CommandResult {
        val text = args.optString("text", "")
        val targetText = args.optString("targetText", "").trim()
        val targetResId = args.optString("resourceId", "").trim()
        val clearFirst = args.optBoolean("clearFirst", true)
        val pressEnter = args.optBoolean("pressEnter", false)

        var node: AccessibilityNodeInfo? = null
        val hasTarget = targetText.isNotEmpty() || targetResId.isNotEmpty()
        if (hasTarget) {
            // Never fall back to another input when the caller named a specific target field.
            val targetNode = findNode(targetText, targetResId)
                ?: return CommandResult(
                    ok = false,
                    error = "Target input not found (targetText='$targetText' resourceId='$targetResId'). Call get_screen_ui and retry."
                )
            val rect = Rect()
            targetNode.getBoundsInScreen(rect)
            performGesturePath(
                rect.centerX().toFloat(),
                rect.centerY().toFloat(),
                rect.centerX().toFloat(),
                rect.centerY().toFloat(),
                50L
            )
            // Give expandable search boxes a moment to mount and acquire input focus
            delay(180L)
            node = if (targetNode.isEditable) {
                targetNode
            } else {
                findFocusedEditableField()
            }
            if (node == null) {
                return CommandResult(
                    ok = false,
                    error = "Tapped target '$targetText$targetResId' but no input field received focus."
                )
            }
        }

        if (node == null) {
            node = findActiveEditableField()
        }

        if (node == null) {
            return CommandResult(ok = false, error = "No focused or editable input field found on screen.")
        }

        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        val finalText = if (clearFirst) {
            text
        } else {
            val existing = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && node.isShowingHintText) {
                ""
            } else {
                node.text?.toString().orEmpty()
            }
            existing + text
        }

        val setArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, finalText)
        }
        var inputOk = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)

        if (!inputOk) {
            // Clipboard paste fallback for apps that reject ACTION_SET_TEXT
            inputOk = pasteViaTemporaryClipboard(node, finalText)
        }

        if (!inputOk) {
            return CommandResult(ok = false, error = "Failed to input text into the target field.")
        }

        var enterSubmitted = false
        if (pressEnter) {
            val zh = AppStrings.isZh(this)
            val preview = finalText.take(40) + if (finalText.length > 40) "…" else ""
            val desc = if (zh) "提交 / 发送：“$preview”" else "submit / send: \"$preview\""
            confirmRiskyAction(RiskCategory.SEND, desc, "Enter", node.packageName?.toString())?.let { denied ->
                return denied.copy(error = denied.error + " The text was typed into the field but NOT submitted.")
            }
            delay(100L)
            val focusedNode = getVisibleAppRoots()
                .firstNotNullOfOrNull { it.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
                ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: node
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                enterSubmitted = focusedNode.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                )
            }
            if (!enterSubmitted) {
                // Fallback: look for common submit/search/send button in the same window
                val submitNode = findSubmitButtonNode(node)
                if (submitNode != null) {
                    val rect = Rect()
                    submitNode.getBoundsInScreen(rect)
                    enterSubmitted = performClickOnNodeOrParent(submitNode) ||
                        performGesturePath(
                            rect.centerX().toFloat(),
                            rect.centerY().toFloat(),
                            rect.centerX().toFloat(),
                            rect.centerY().toFloat(),
                            50L
                        )
                }
            }
        }

        val resultJson = JSONObject().apply {
            put("typedLength", finalText.length)
            if (pressEnter) {
                put("pressEnter", true)
                put("enterSubmitted", enterSubmitted)
            }
        }
        attachScreenUi(resultJson, settleDelayMs = 320L, returnUi = args.optBoolean("returnUi", true))
        return CommandResult(ok = true, data = resultJson)
    }

    private fun findActiveEditableField(): AccessibilityNodeInfo? {
        val roots = getVisibleAppRoots()
        for (r in roots) {
            r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { return it }
        }
        for (r in roots) {
            findFirstEditableNode(r)?.let { return it }
        }
        return null
    }

    private fun findFocusedEditableField(): AccessibilityNodeInfo? {
        for (r in getVisibleAppRoots()) {
            r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }?.let { return it }
        }
        return null
    }

    private suspend fun pasteViaTemporaryClipboard(node: AccessibilityNodeInfo, text: String): Boolean {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val previousClip = try {
            clipboard.primaryClip
        } catch (_: Exception) {
            null
        }
        val clip = ClipData.newPlainText("gemini_input", text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        return try {
            clipboard.setPrimaryClip(clip)
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            delay(80L)
            ok
        } finally {
            try {
                if (previousClip != null) {
                    clipboard.setPrimaryClip(previousClip)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    clipboard.clearPrimaryClip()
                } else {
                    clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            } catch (_: Exception) {
                // ignore
            }
        }
    }

    private fun findSubmitButtonNode(inputNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val keywords = listOf("搜索", "发送", "前往", "Search", "Send", "Go")
        fun dfs(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null || !node.isVisibleToUser) return null
            if (!node.isEditable && node.isClickable) {
                val t = node.text?.toString()?.trim().orEmpty()
                val d = node.contentDescription?.toString()?.trim().orEmpty()
                if (keywords.any { kw -> t.equals(kw, ignoreCase = true) || d.equals(kw, ignoreCase = true) }) {
                    return node
                }
            }
            for (i in 0 until node.childCount) {
                val found = dfs(node.getChild(i))
                if (found != null) return found
            }
            return null
        }
        val windowRoot = inputNode.window?.root ?: return null
        return dfs(windowRoot)
    }

    private suspend fun handleOpenApp(args: JSONObject): CommandResult {
        val query = args.optString("app", "").trim()
        if (query.isEmpty()) {
            return CommandResult(ok = false, error = "open_app requires 'app' parameter.")
        }

        val blocked = ConfigStore.getEffectiveBlockedPackages(this)
        if (isPackageBlocked(query, blocked)) {
            return CommandResult(ok = false, error = "Blocked by local safety policy: '$query' is in your blacklist.")
        }

        val pm = packageManager
        var launchIntent = pm.getLaunchIntentForPackage(query)
        var resolvedPkg = query

        if (launchIntent == null) {
            val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = pm.queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)
            val match = apps.firstOrNull { info ->
                info.loadLabel(pm)?.toString()?.trim()?.equals(query, ignoreCase = true) == true
            } ?: apps.firstOrNull { info ->
                info.loadLabel(pm)?.toString()?.contains(query, ignoreCase = true) == true
            }

            if (match != null) {
                resolvedPkg = match.activityInfo.packageName
                if (isPackageBlocked(resolvedPkg, blocked)) {
                    return CommandResult(ok = false, error = "Blocked by local safety policy: '$resolvedPkg' is in your blacklist.")
                }
                launchIntent = pm.getLaunchIntentForPackage(resolvedPkg) ?: Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    component = ComponentName(resolvedPkg, match.activityInfo.name)
                }
            }
        }

        if (launchIntent == null) {
            return CommandResult(ok = false, error = "App not found: '$query'")
        }

        val browserPackages = setOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary",
            "org.mozilla.firefox",
            "com.microsoft.emmx",
            "com.brave.browser",
            "com.sec.android.app.sbrowser",
            "com.opera.browser"
        )
        val currentForeground = rootInActiveWindow?.packageName?.toString().orEmpty()
        if (currentForeground.isNotEmpty() &&
            currentForeground != resolvedPkg &&
            currentForeground != packageName &&
            lastTargetPackage.isEmpty() &&
            !(lastOriginPackage in setOf("com.google.android.apps.bard", "com.google.android.googlequicksearchbox") &&
                currentForeground in browserPackages)
        ) {
            lastOriginPackage = currentForeground
        }
        lastTargetPackage = resolvedPkg
        lastTargetSetAtMs = System.currentTimeMillis()

        val argMode = args.optString("windowMode", "default").lowercase()
        val effectiveMode = when (argMode) {
            ConfigStore.MODE_FREEFORM, ConfigStore.MODE_FULLSCREEN -> argMode
            else -> ConfigStore.getWindowMode(this)
        }

        var usedSmallWindow = false
        if (effectiveMode == ConfigStore.MODE_FREEFORM) {
            val dm = resources.displayMetrics
            val bounds = Rect(
                (dm.widthPixels * 0.08f).toInt(),
                (dm.heightPixels * 0.10f).toInt(),
                (dm.widthPixels * 0.92f).toInt(),
                (dm.heightPixels * 0.74f).toInt()
            )
            try {
                val freeformIntent = Intent(launchIntent).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                }
                val optsBundle = ActivityOptions.makeBasic()
                    .setLaunchBounds(bounds)
                    .toBundle()
                    .apply {
                        putInt("android.activity.windowingMode", 5)
                    }
                startActivity(freeformIntent, optsBundle)
                usedSmallWindow = true
            } catch (_: Throwable) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
            }
        } else {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
        }

        delay(600L)
        val actualWindowMode = detectActualWindowMode(resolvedPkg)
        val data = JSONObject()
            .put("openedPackage", resolvedPkg)
            .put("windowMode", effectiveMode)
            .put("actualWindowMode", actualWindowMode)
            .put("smallWindowRequested", usedSmallWindow)
            .put("autoReturnGemini", ConfigStore.isAutoReturnGemini(this))
        attachScreenUi(data, settleDelayMs = 0L, returnUi = args.optBoolean("returnUi", true))
        return CommandResult(ok = true, data = data)
    }

    private fun detectActualWindowMode(pkg: String): String {
        val dm = resources.displayMetrics
        val winList = try { windows } catch (_: Exception) { emptyList() }
        val r = Rect()
        for (win in winList) {
            if (win.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val winPkg = win.root?.packageName?.toString().orEmpty()
            if (winPkg == pkg) {
                win.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0) {
                    return if (r.width() < dm.widthPixels * 0.88f || r.height() < dm.heightPixels * 0.82f) {
                        ConfigStore.MODE_FREEFORM
                    } else {
                        ConfigStore.MODE_FULLSCREEN
                    }
                }
            }
        }
        return ConfigStore.MODE_FULLSCREEN
    }

    fun isExternalAppInForeground(): Boolean {
        val activePkg = try {
            rootInActiveWindow?.packageName?.toString().orEmpty()
        } catch (_: Exception) {
            ""
        }.ifEmpty {
            getVisibleAppRoots().firstOrNull()?.packageName?.toString().orEmpty()
        }
        if (activePkg.isEmpty()) return false
        return activePkg != packageName &&
            activePkg != "com.google.android.apps.bard" &&
            activePkg != "com.google.android.googlequicksearchbox"
    }

    private fun launchGeminiPackage(): String? {
        val pm = packageManager
        val browserPackages = setOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary",
            "org.mozilla.firefox",
            "com.microsoft.emmx",
            "com.brave.browser",
            "com.sec.android.app.sbrowser",
            "com.opera.browser"
        )

        val candidates = mutableListOf<Pair<String, Intent>>()

        // 1. Standalone Gemini app package
        pm.getLaunchIntentForPackage("com.google.android.apps.bard")?.let {
            candidates.add("com.google.android.apps.bard" to it)
        }

        // 2. Any launcher activity explicitly labeled "Gemini" (excluding web browsers / PWAs)
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launcherApps = try {
            pm.queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)
        } catch (_: Exception) {
            emptyList()
        }
        for (info in launcherApps) {
            val pkg = info.activityInfo?.packageName ?: continue
            if (pkg in browserPackages || pkg == packageName) continue
            val label = info.loadLabel(pm)?.toString()?.trim().orEmpty()
            if (label.equals("Gemini", ignoreCase = true)) {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    component = ComponentName(pkg, info.activityInfo.name)
                }
                candidates.add(pkg to intent)
            }
        }

        // 3. Google App assistant/Robin entry or Google App launcher
        val robinIntent = Intent().setComponent(
            ComponentName(
                "com.google.android.googlequicksearchbox",
                "com.google.android.apps.search.assistant.surfaces.voice.robin.main.MainActivity"
            )
        )
        if (pm.resolveActivity(robinIntent, PackageManager.MATCH_DEFAULT_ONLY) != null) {
            candidates.add("com.google.android.googlequicksearchbox" to robinIntent)
        }
        pm.getLaunchIntentForPackage("com.google.android.googlequicksearchbox")?.let {
            candidates.add("com.google.android.googlequicksearchbox" to it)
        }

        // 4. Last-resort fallback to recorded origin app if no Gemini/Google app is installed
        if (lastOriginPackage.isNotEmpty() && lastOriginPackage != packageName) {
            pm.getLaunchIntentForPackage(lastOriginPackage)?.let {
                candidates.add(lastOriginPackage to it)
            }
        }

        for ((pkg, intent) in candidates) {
            val launch = Intent(intent).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
            }
            try {
                startActivity(launch)
                return pkg
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    fun bringGeminiToFront(): Boolean {
        lastTargetPackage = ""
        lastTargetSetAtMs = 0L
        return launchGeminiPackage() != null
    }

    private fun handleReturnToGemini(args: JSONObject): CommandResult {
        val force = args.optBoolean("force", false)
        val autoEnabled = ConfigStore.isAutoReturnGemini(this)
        lastTargetPackage = ""
        lastTargetSetAtMs = 0L

        if (!force && !autoEnabled) {
            return CommandResult(
                ok = true,
                data = JSONObject()
                    .put("returned", false)
                    .put("reason", "autoReturnGemini is turned off in phone settings")
            )
        }

        val launchedPkg = launchGeminiPackage()
        return if (launchedPkg != null) {
            CommandResult(
                ok = true,
                data = JSONObject().put("returned", true).put("package", launchedPkg)
            )
        } else {
            CommandResult(
                ok = false,
                error = "Could not locate installed Gemini app package on device."
            )
        }
    }

    private suspend fun handlePressKey(args: JSONObject): CommandResult {
        val key = args.optString("key", "").lowercase()
        val action = when (key) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> return CommandResult(ok = false, error = "Unknown key: $key")
        }
        val ok = performGlobalAction(action)
        return if (ok) {
            val msg = "Pressed $key"
            val returnUi = args.optBoolean("returnUi", true)
            val data: Any = if (returnUi) {
                attachScreenUi(JSONObject().put("message", msg), settleDelayMs = 320L, returnUi = true)
            } else {
                msg
            }
            CommandResult(ok = true, data = data)
        } else {
            CommandResult(ok = false, error = "performGlobalAction failed for $key")
        }
    }

    private fun clampToDisplay(x: Float, y: Float): Pair<Float, Float> {
        val dm = resources.displayMetrics
        val maxX = (dm.widthPixels - 1).coerceAtLeast(0).toFloat()
        val maxY = (dm.heightPixels - 1).coerceAtLeast(0).toFloat()
        return x.coerceIn(0f, maxX) to y.coerceIn(0f, maxY)
    }

    private suspend fun performGesturePath(
        sx: Float,
        sy: Float,
        ex: Float,
        ey: Float,
        durationMs: Long
    ): Boolean {
        if (!sx.isFinite() || !sy.isFinite() || !ex.isFinite() || !ey.isFinite()) {
            return false
        }
        val (csx, csy) = clampToDisplay(sx, sy)
        val (cex, cey) = clampToDisplay(ex, ey)
        val safeDuration = durationMs.coerceAtLeast(1L)

        return suspendCancellableCoroutine { cont ->
            val path = Path().apply {
                moveTo(csx, csy)
                if (csx != cex || csy != cey) {
                    lineTo(cex, cey)
                }
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, safeDuration)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null
            )
            if (!dispatched && cont.isActive) {
                cont.resume(false)
            }
        }
    }

    private fun findNode(targetText: String, targetResId: String): AccessibilityNodeInfo? {
        fun dfs(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null || !node.isVisibleToUser) return null
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val resId = node.viewIdResourceName.orEmpty()

            val textMatched = targetText.isNotEmpty() &&
                (text.contains(targetText, ignoreCase = true) || desc.contains(targetText, ignoreCase = true))
            val resMatched = targetResId.isNotEmpty() && resId.contains(targetResId, ignoreCase = true)

            if (textMatched || resMatched) return node
            for (i in 0 until node.childCount) {
                val found = dfs(node.getChild(i))
                if (found != null) return found
            }
            return null
        }
        for (root in getVisibleAppRoots()) {
            dfs(root)?.let { return it }
        }
        return null
    }

    private fun findFirstEditableNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        if (root.isEditable && root.isVisibleToUser) return root
        for (i in 0 until root.childCount) {
            val found = findFirstEditableNode(root.getChild(i))
            if (found != null) return found
        }
        return null
    }
}
