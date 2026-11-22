package com.geminiphone.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class RelayForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "gemini_phone_agent_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_RECONNECT = "com.geminiphone.agent.ACTION_RECONNECT"

        @Volatile
        private var activeInstance: RelayForegroundService? = null

        @Volatile
        private var statusZh: String = "未连接"

        @Volatile
        private var statusEn: String = "Disconnected"

        @Volatile
        private var logZh: String = "等待启动..."

        @Volatile
        private var logEn: String = "Ready"

        val currentStatus: String
            get() = statusZh

        val lastLog: String
            get() = logZh

        fun getLocalizedStatus(zh: Boolean): String = if (zh) statusZh else statusEn
        fun getLocalizedLog(zh: Boolean): String = if (zh) logZh else logEn

        @Volatile
        var isServiceRunning: Boolean = false
            private set

        var onStateChanged: (() -> Unit)? = null

        fun registerStateListener(listener: () -> Unit) {
            onStateChanged = listener
        }

        fun unregisterStateListener(listener: () -> Unit) {
            if (onStateChanged === listener) {
                onStateChanged = null
            }
        }

        fun start(context: Context) {
            val intent = Intent(context, RelayForegroundService::class.java).apply {
                action = ACTION_RECONNECT
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RelayForegroundService::class.java))
        }

        fun notifyAccessibilityStateChanged() {
            activeInstance?.pushHelloIfConnected()
            activeInstance?.mainHandler?.post { onStateChanged?.invoke() }
        }

        fun notifyLanguageChanged() {
            val inst = activeInstance ?: return
            inst.mainHandler.post {
                inst.overlayManager?.refreshLanguage()
                inst.refreshNotification()
                onStateChanged?.invoke()
            }
        }

        fun notifySettingsChanged() {
            activeInstance?.pushHelloIfConnected()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    private val socketLock = Any()
    private val wsGeneration = AtomicLong(0)
    private val commandMutex = Mutex()

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var connectingSocket: WebSocket? = null

    @Volatile
    private var isConnecting: Boolean = false

    @Volatile
    private var shouldReconnect = true

    @Volatile
    private var isDestroying = false

    private var connectJob: Job? = null
    private var pingJob: Job? = null

    @Volatile
    private var activeCommandJob: Job? = null

    private var stopReason: QuadString? = null
    private var overlayManager: EmergencyOverlayManager? = null

    private val idleHintRunnable = Runnable {
        if (isServiceRunning && !isDestroying && activeCommandJob?.isActive != true) {
            if (PhoneAccessibilityService.instance?.isExternalAppInForeground() == true) {
                overlayManager?.updateStatus("回Gemini", "To Gemini", isReturnHint = true, isRunning = false)
            } else {
                overlayManager?.resetToIdle()
            }
        }
    }

    private fun scheduleIdleHintCheck() {
        mainHandler.removeCallbacks(idleHintRunnable)
        if (isServiceRunning && !isDestroying) {
            mainHandler.postDelayed(idleHintRunnable, 5000L)
        }
    }

    private data class QuadString(
        val sZh: String,
        val sEn: String,
        val lZh: String,
        val lEn: String
    )

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        isServiceRunning = true
        isDestroying = false
        createNotificationChannel()
        val zh = AppStrings.isZh(this)
        val initialNotification = buildNotification(
            if (zh) "正在连接中转服务..." else "Connecting to relay..."
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        overlayManager = EmergencyOverlayManager(
            context = this,
            onStopClicked = {
                isDestroying = true
                shouldReconnect = false
                isServiceRunning = false
                connectJob?.cancel()
                pingJob?.cancel()
                activeCommandJob?.cancel()
                activeCommandJob = null
                scope.coroutineContext.cancelChildren()
                mainHandler.removeCallbacks(idleHintRunnable)
                closeCurrentSockets("Emergency stopped")
                stopReason = QuadString(
                    "已急停并断开",
                    "Emergency Stopped",
                    "已点击悬浮急停按钮，远程连接已终止",
                    "Emergency stop pressed; remote connection terminated"
                )
                stopSelf()
            },
            onReturnGeminiClicked = {
                val moved = PhoneAccessibilityService.instance?.bringGeminiToFront() == true
                if (!moved) {
                    packageManager.getLaunchIntentForPackage("com.google.android.apps.bard")?.let { intent ->
                        intent.addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        )
                        try {
                            startActivity(intent)
                        } catch (_: Exception) {
                            // ignore
                        }
                    }
                }
                overlayManager?.resetToIdle()
                scheduleIdleHintCheck()
            }
        )
        mainHandler.post {
            overlayManager?.show()
            scheduleIdleHintCheck()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        mainHandler.post { overlayManager?.show() }
        restartConnectionLoop()
        return START_STICKY
    }

    override fun onTimeout(startId: Int) {
        handleForegroundTimeout(startId)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        handleForegroundTimeout(startId)
    }

    private fun handleForegroundTimeout(startId: Int) {
        stopReason = QuadString(
            "后台时长已达系统上限",
            "Background Time Limit Reached",
            "Android 前台服务达到单日时长上限，请重新点击「保存并连接」",
            "Android foreground service limit reached; tap Save & Connect again"
        )
        shouldReconnect = false
        stopSelf(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isDestroying = true
        shouldReconnect = false
        isServiceRunning = false
        if (activeInstance === this) {
            activeInstance = null
        }

        connectJob?.cancel()
        pingJob?.cancel()
        activeCommandJob?.cancel()
        activeCommandJob = null
        mainHandler.removeCallbacks(idleHintRunnable)
        closeCurrentSockets("Service stopped")
        RiskSession.reset()

        if (Looper.myLooper() == Looper.getMainLooper()) {
            overlayManager?.hide()
        } else {
            mainHandler.post { overlayManager?.hide() }
        }
        scope.cancel()
        try {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        } catch (_: Exception) {
            // ignore
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)

        val reason = stopReason ?: QuadString(
            "已停止",
            "Stopped",
            "前台服务已关闭",
            "Foreground service stopped"
        )
        statusZh = reason.sZh
        statusEn = reason.sEn
        logZh = reason.lZh
        logEn = reason.lEn
        mainHandler.post { onStateChanged?.invoke() }

        super.onDestroy()
    }

    private fun closeCurrentSockets(reason: String) {
        val openWs: WebSocket?
        val pendingWs: WebSocket?
        synchronized(socketLock) {
            wsGeneration.incrementAndGet()
            openWs = webSocket
            pendingWs = connectingSocket
            webSocket = null
            connectingSocket = null
            isConnecting = false
        }
        try {
            openWs?.close(1000, reason)
        } catch (_: Exception) {
            // ignore
        }
        if (pendingWs != null && pendingWs !== openWs) {
            try {
                pendingWs.close(1000, reason)
            } catch (_: Exception) {
                // ignore
            }
        }
    }

    private fun restartConnectionLoop() {
        stopReason = null
        shouldReconnect = true
        RiskSession.reset()

        connectJob?.cancel()
        pingJob?.cancel()
        closeCurrentSockets("Reconfiguring connection")

        val workerUrl = ConfigStore.getWorkerUrl(this)
        val token = ConfigStore.getToken(this)
        if (workerUrl.isNotEmpty() && !ConfigStore.isValidWorkerUrl(workerUrl)) {
            stopReason = QuadString(
                "地址无效",
                "Invalid URL",
                "中转服务地址格式无效，请填写正确的 https:// 地址",
                "Relay URL is invalid; enter a valid https:// URL"
            )
            shouldReconnect = false
            stopSelf()
            return
        }
        if (workerUrl.isEmpty() || token.isEmpty()) {
            stopReason = QuadString(
                "配置缺失",
                "Missing Config",
                "请先填写 Cloudflare Worker 地址和 RELAY_TOKEN",
                "Please enter both Relay Server URL and RELAY_TOKEN"
            )
            shouldReconnect = false
            stopSelf()
            return
        }

        val wsBase = workerUrl
            .replaceFirst("^https://".toRegex(RegexOption.IGNORE_CASE), "wss://")
            .replaceFirst("^http://".toRegex(RegexOption.IGNORE_CASE), "ws://")

        if (wsBase.startsWith("ws://", ignoreCase = true) && !ConfigStore.isLoopbackHost(wsBase)) {
            stopReason = QuadString(
                "连接地址不安全",
                "Insecure Relay URL",
                "仅本地调试地址允许 ws://，请改用 https://",
                "Non-loopback ws:// is blocked; use https://"
            )
            shouldReconnect = false
            stopSelf()
            return
        }

        connectJob = scope.launch {
            while (isActive && shouldReconnect) {
                val needsConnect = synchronized(socketLock) {
                    webSocket == null && connectingSocket == null && !isConnecting
                }
                if (needsConnect) {
                    val metrics = resources.displayMetrics
                    val encodedName = URLEncoder.encode("${Build.MANUFACTURER} ${Build.MODEL}".trim(), "UTF-8")
                    val encodedVer = URLEncoder.encode(Build.VERSION.RELEASE ?: "Android", "UTF-8")
                    val accFlag = if (PhoneAccessibilityService.isRunning()) "1" else "0"
                    val winMode = ConfigStore.getWindowMode(this@RelayForegroundService)
                    val autoRet = if (ConfigStore.isAutoReturnGemini(this@RelayForegroundService)) "1" else "0"
                    val wsUrl = "$wsBase/ws?deviceName=$encodedName&androidVersion=$encodedVer&w=${metrics.widthPixels}&h=${metrics.heightPixels}&acc=$accFlag&winMode=$winMode&autoReturn=$autoRet"

                    updateBilingualState(
                        "连接中...",
                        "Connecting...",
                        "正在连接 $wsBase/ws",
                        "Connecting to $wsBase/ws"
                    )
                    connectOnce(wsUrl, token)
                }
                delay(5000)
            }
        }

        pingJob = scope.launch {
            while (isActive && shouldReconnect) {
                delay(25000)
                try {
                    val activeWs = synchronized(socketLock) { webSocket }
                    activeWs?.send("ping")
                } catch (_: Exception) {
                    // ignore
                }
            }
        }
    }

    private fun connectOnce(wsUrl: String, token: String) {
        val gen: Long
        synchronized(socketLock) {
            if (isConnecting || webSocket != null || connectingSocket != null) return
            gen = wsGeneration.incrementAndGet()
            isConnecting = true
        }

        val request = try {
            Request.Builder()
                .url(wsUrl)
                .header("Authorization", "Bearer $token")
                .build()
        } catch (e: Exception) {
            synchronized(socketLock) {
                if (gen == wsGeneration.get()) {
                    isConnecting = false
                }
            }
            updateBilingualState(
                "连接地址无效",
                "Invalid Relay URL",
                e.message ?: "Worker 地址格式错误",
                e.message ?: "Invalid Relay Server URL"
            )
            return
        }

        val wsInstance = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    var accepted = false
                    synchronized(socketLock) {
                        if (gen == wsGeneration.get() && shouldReconnect && !isDestroying) {
                            connectingSocket = null
                            webSocket = ws
                            isConnecting = false
                            accepted = true
                        }
                    }
                    if (!accepted) {
                        try {
                            ws.close(1000, "Stale connection")
                        } catch (_: Exception) {
                            // ignore
                        }
                        return
                    }
                    updateBilingualState(
                        "已连接",
                        "Connected",
                        "已连上中转层，等待 Gemini MCP 指令",
                        "Connected to relay; awaiting Gemini MCP commands"
                    )
                    sendHello(ws)
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    if (gen != wsGeneration.get()) return
                    if (text == "pong") return
                    handleIncomingMessage(ws, text)
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    try {
                        ws.close(1000, null)
                    } catch (_: Exception) {
                        // ignore
                    }
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    var isCurrent = false
                    synchronized(socketLock) {
                        if (gen == wsGeneration.get()) {
                            if (webSocket === ws) webSocket = null
                            if (connectingSocket === ws) connectingSocket = null
                            isConnecting = false
                            isCurrent = true
                        }
                    }
                    if (isCurrent && shouldReconnect && !isDestroying) {
                        updateBilingualState(
                            "连接断开（准备重连）",
                            "Disconnected (Reconnecting)",
                            "关闭码=$code 原因=$reason",
                            "Code=$code Reason=$reason"
                        )
                    }
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    var isCurrent = false
                    synchronized(socketLock) {
                        if (gen == wsGeneration.get()) {
                            if (webSocket === ws) webSocket = null
                            if (connectingSocket === ws) connectingSocket = null
                            isConnecting = false
                            isCurrent = true
                        }
                    }
                    if (isCurrent && shouldReconnect && !isDestroying) {
                        val httpHint = if (response != null) " (HTTP ${response.code})" else ""
                        updateBilingualState(
                            "连接失败（5秒后重试）",
                            "Connect Failed (Retrying in 5s)",
                            "${t.message ?: "网络异常"}$httpHint",
                            "${t.message ?: "Network error"}$httpHint"
                        )
                    }
                }
            }
        )

        var shouldCloseCreated = false
        synchronized(socketLock) {
            if (gen == wsGeneration.get() && shouldReconnect && !isDestroying) {
                if (webSocket == null && isConnecting) {
                    connectingSocket = wsInstance
                }
            } else {
                shouldCloseCreated = true
            }
        }
        if (shouldCloseCreated) {
            try {
                wsInstance.close(1000, "Superseded")
            } catch (_: Exception) {
                // ignore
            }
        }
    }

    fun pushHelloIfConnected() {
        val ws = synchronized(socketLock) { webSocket } ?: return
        try {
            sendHello(ws)
        } catch (_: Exception) {
            // ignore
        }
    }

    private fun buildDeviceMetaJson(): JSONObject {
        val metrics = resources.displayMetrics
        return JSONObject().apply {
            put("deviceName", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            put("androidVersion", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            put("screenWidth", metrics.widthPixels)
            put("screenHeight", metrics.heightPixels)
            put("accessibilityEnabled", PhoneAccessibilityService.isRunning())
            put("windowMode", ConfigStore.getWindowMode(this@RelayForegroundService))
            put("autoReturnGemini", ConfigStore.isAutoReturnGemini(this@RelayForegroundService))
        }
    }

    private fun sendHello(ws: WebSocket) {
        val hello = buildDeviceMetaJson().apply {
            put("type", "hello")
        }
        ws.send(hello.toString())
    }

    private fun handleIncomingMessage(ws: WebSocket, text: String) {
        val json = try {
            JSONObject(text)
        } catch (_: Exception) {
            return
        }
        if (json.optString("type") != "command") return

        val requestId = json.optString("requestId")
        val action = json.optString("action")
        val args = json.optJSONObject("args") ?: JSONObject()

        if (action == "get_status") {
            val statusResult = CommandResult(ok = true, data = buildDeviceMetaJson())
            ws.send(statusResult.toResponseJson(requestId))
            return
        }

        updateBilingualState(
            "执行中：$action",
            "Running: $action",
            "收到指令 $action ($requestId)",
            "Received $action ($requestId)"
        )

        if (isDestroying || !shouldReconnect || !isServiceRunning) return

        val launched = scope.launch {
            commandMutex.withLock {
                if (isDestroying || !shouldReconnect || !isServiceRunning) return@withLock
                val myJob = this@launch.coroutineContext[Job]
                activeCommandJob = myJob
                try {
                    mainHandler.post {
                        mainHandler.removeCallbacks(idleHintRunnable)
                        overlayManager?.updateStatus(
                            "执行: $action",
                            "Run: $action",
                            isReturnHint = false,
                            isRunning = true
                        )
                    }
                    val service = PhoneAccessibilityService.instance
                    val result = if (service == null) {
                        val serviceLabel = AppStrings.get(
                            this@RelayForegroundService,
                            R.string.accessibility_service_label
                        )
                        CommandResult(
                            ok = false,
                            error = "AccessibilityService is not enabled. Please enable '$serviceLabel' in Android Accessibility settings."
                        )
                    } else {
                        try {
                            service.executeCommand(PhoneCommand(requestId, action, args))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            CommandResult(ok = false, error = "Execution exception: ${e.message}")
                        }
                    }

                    ws.send(result.toResponseJson(requestId))
                    val summaryZh = if (result.ok) "完成：$action" else "失败：$action (${result.error})"
                    val summaryEn = if (result.ok) "Done: $action" else "Failed: $action (${result.error})"
                    updateBilingualState("已连接", "Connected", summaryZh, summaryEn)
                } finally {
                    if (activeCommandJob === myJob) {
                        activeCommandJob = null
                    }
                    mainHandler.post {
                        if (isServiceRunning && !isDestroying) {
                            overlayManager?.resetToIdle()
                            scheduleIdleHintCheck()
                        }
                    }
                }
            }
        }
        if (activeCommandJob == null) {
            activeCommandJob = launched
        }
    }

    private fun updateBilingualState(sZh: String, sEn: String, lZh: String, lEn: String) {
        statusZh = sZh
        statusEn = sEn
        logZh = lZh
        logEn = lEn
        refreshNotification()
        mainHandler.post { onStateChanged?.invoke() }
    }

    private fun refreshNotification() {
        if (isServiceRunning && !isDestroying) {
            val zh = AppStrings.isZh(this)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(getLocalizedStatus(zh)))
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Gemini Phone Agent",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(AppStrings.get(this, R.string.ui_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_stat_relay)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
