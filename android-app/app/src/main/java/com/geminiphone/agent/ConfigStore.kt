package com.geminiphone.agent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object ConfigStore {
    private const val PREF_NAME = "gemini_phone_agent_prefs"
    private const val KEY_WORKER_URL = "worker_url"
    private const val KEY_TOKEN = "relay_token"
    private const val KEY_BLOCKED_PACKAGES = "blocked_packages"
    private const val KEY_LANGUAGE = "app_language"
    private const val KEY_WINDOW_MODE = "window_mode"
    private const val KEY_AUTO_RETURN_GEMINI = "auto_return_gemini"
    private const val KEY_CONFIRM_RISKY = "confirm_risky_actions"
    private const val KEY_VISION_MODE = "vision_mode"
    private const val KEY_VISION_API_URL = "vision_api_url"
    private const val KEY_VISION_API_KEY = "vision_api_key"
    private const val KEY_VISION_MODEL = "vision_model"

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEYSTORE_ALIAS = "gemini_phone_agent_token_key"
    private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val ENC_PREFIX = "enc:"
    private const val GCM_IV_LEN = 12
    private const val GCM_TAG_BITS = 128

    private val keystoreLock = Any()
    private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")

    const val MODE_FREEFORM = "freeform"
    const val MODE_FULLSCREEN = "fullscreen"

    const val VISION_MODE_WORKERS_AI = "workers_ai"
    const val VISION_MODE_CUSTOM = "custom"

    // Default sensitive finance/payment/password manager packages blocked from remote automation
    val DEFAULT_BLOCKED_PACKAGES: List<String> = listOf(
        "com.geminiphone.agent",
        "com.eg.android.AlipayGphone",
        "com.unionpay",
        "com.icbc",
        "com.chinamworld.main",
        "com.chinamworld.bocmbci",
        "cmb.pb",
        "com.android.bankabc",
        "com.onepassword.android",
        "com.x8bit.bitwarden",
        "com.google.android.apps.walletnfcrel",
        "com.google.android.apps.authenticator2"
    )

    // Always enforced regardless of the user's blacklist: installing APKs remotely would
    // let a compromised session sideload software; runtime permission dialogs are allowed.
    val ALWAYS_BLOCKED_PACKAGES: Set<String> = setOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller"
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun getLanguage(context: Context): String =
        prefs(context).getString(KEY_LANGUAGE, "zh")?.trim()?.lowercase().takeIf {
            it == "zh" || it == "en"
        } ?: "zh"

    fun setLanguage(context: Context, lang: String) {
        val normalized = if (lang.lowercase() == "en") "en" else "zh"
        prefs(context).edit().putString(KEY_LANGUAGE, normalized).apply()
    }

    fun toggleLanguage(context: Context): String {
        val next = if (getLanguage(context) == "zh") "en" else "zh"
        setLanguage(context, next)
        return next
    }

    fun getWindowMode(context: Context): String =
        prefs(context).getString(KEY_WINDOW_MODE, MODE_FREEFORM)?.trim()?.lowercase().takeIf {
            it == MODE_FREEFORM || it == MODE_FULLSCREEN
        } ?: MODE_FREEFORM

    fun setWindowMode(context: Context, mode: String) {
        val normalized = if (mode.lowercase() == MODE_FULLSCREEN) MODE_FULLSCREEN else MODE_FREEFORM
        prefs(context).edit().putString(KEY_WINDOW_MODE, normalized).apply()
    }

    fun isAutoReturnGemini(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_RETURN_GEMINI, true)

    fun setAutoReturnGemini(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_RETURN_GEMINI, enabled).apply()
    }

    fun isConfirmRiskyActions(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CONFIRM_RISKY, true)

    fun setConfirmRiskyActions(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CONFIRM_RISKY, enabled).apply()
    }

    fun getVisionMode(context: Context): String =
        prefs(context).getString(KEY_VISION_MODE, VISION_MODE_WORKERS_AI)?.trim()?.lowercase().takeIf {
            it == VISION_MODE_WORKERS_AI || it == VISION_MODE_CUSTOM
        } ?: VISION_MODE_WORKERS_AI

    fun setVisionMode(context: Context, mode: String) {
        val normalized = if (mode.trim().lowercase() == VISION_MODE_CUSTOM) {
            VISION_MODE_CUSTOM
        } else {
            VISION_MODE_WORKERS_AI
        }
        prefs(context).edit().putString(KEY_VISION_MODE, normalized).apply()
    }

    private fun normalizeVisionApiUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        return if ('?' in trimmed || '#' in trimmed) trimmed else trimmed.trimEnd('/')
    }

    private fun isPrivateLanIpv4(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it < 0 || it > 255 }) return false
        val a = octets[0]
        val b = octets[1]
        return a == 10 ||
            (a == 192 && b == 168) ||
            (a == 172 && b in 16..31)
    }

    /** True when the vision API URL uses https:// or loopback/private-LAN http://. */
    fun isValidVisionApiUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        val parsed = trimmed.toHttpUrlOrNull() ?: return false
        val host = parsed.host.lowercase()
        if (host.isEmpty()) return false
        if (parsed.isHttps) return true
        if (parsed.scheme.equals("http", ignoreCase = true)) {
            return LOOPBACK_HOSTS.contains(host) || isPrivateLanIpv4(host)
        }
        return false
    }

    fun getVisionApiUrl(context: Context): String =
        normalizeVisionApiUrl(prefs(context).getString(KEY_VISION_API_URL, "").orEmpty())

    fun getVisionApiKey(context: Context): String =
        readEncryptedPref(context, KEY_VISION_API_KEY)

    fun getVisionModel(context: Context): String =
        prefs(context).getString(KEY_VISION_MODEL, "").orEmpty().trim()

    fun saveVisionConfig(
        context: Context,
        mode: String,
        apiUrl: String,
        apiKey: String,
        model: String
    ) {
        val normalizedMode = if (mode.trim().lowercase() == VISION_MODE_CUSTOM) {
            VISION_MODE_CUSTOM
        } else {
            VISION_MODE_WORKERS_AI
        }
        prefs(context).edit()
            .putString(KEY_VISION_MODE, normalizedMode)
            .putString(KEY_VISION_API_URL, normalizeVisionApiUrl(apiUrl))
            .putString(KEY_VISION_API_KEY, prepareTokenForStorage(apiKey))
            .putString(KEY_VISION_MODEL, model.trim())
            .apply()
    }

    fun isLoopbackHost(url: String): Boolean {
        val host = try {
            URI(url).host?.lowercase()
        } catch (_: Exception) {
            null
        } ?: return false
        return LOOPBACK_HOSTS.contains(host)
    }

    fun normalizeWorkerUrl(raw: String): String {
        var url = raw.trim()
        if (url.isEmpty()) return ""

        url = url.substringBefore("?").substringBefore("#").trim()

        url = when {
            url.startsWith("wss://", ignoreCase = true) -> "https://" + url.substring(6)
            url.startsWith("ws://", ignoreCase = true) -> "http://" + url.substring(5)
            url.startsWith("https://", ignoreCase = true) -> "https://" + url.substring(8)
            url.startsWith("http://", ignoreCase = true) -> "http://" + url.substring(7)
            else -> "https://$url"
        }

        if (url.startsWith("http://", ignoreCase = true) && !isLoopbackHost(url)) {
            url = "https://" + url.substring(7)
        }

        url = url.trimEnd('/')
        for (suffix in listOf("/mcp", "/ws", "/health")) {
            if (url.endsWith(suffix, ignoreCase = true)) {
                url = url.dropLast(suffix.length).trimEnd('/')
            }
        }
        return url
    }

    /** True when the normalized URL is a well-formed https:// (or loopback http://) URL OkHttp can use. */
    fun isValidWorkerUrl(url: String): Boolean {
        if (url.isEmpty()) return false
        val parsed = url.toHttpUrlOrNull() ?: return false
        if (parsed.host.isEmpty()) return false
        return parsed.isHttps || isLoopbackHost(url)
    }

    fun getWorkerUrl(context: Context): String =
        normalizeWorkerUrl(prefs(context).getString(KEY_WORKER_URL, "").orEmpty())

    fun getToken(context: Context): String =
        readEncryptedPref(context, KEY_TOKEN)

    private fun readEncryptedPref(context: Context, key: String): String {
        val stored = prefs(context).getString(key, "")?.trim().orEmpty()
        if (stored.isEmpty()) return ""
        if (stored.startsWith(ENC_PREFIX)) {
            return decryptToken(stored)?.trim().orEmpty()
        }
        // Transparently migrate legacy plaintext tokens into Android Keystore ciphertext
        val encrypted = encryptToken(stored)
        if (encrypted.startsWith(ENC_PREFIX)) {
            prefs(context).edit().putString(key, encrypted).apply()
        }
        return stored
    }

    private fun getOrCreateSecretKey(): SecretKey? {
        synchronized(keystoreLock) {
            return try {
                val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
                val existing = ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey
                if (existing != null) return existing

                val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
                val spec = KeyGenParameterSpec.Builder(
                    KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGen.init(spec)
                keyGen.generateKey()
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun encryptToken(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val key = getOrCreateSecretKey() ?: return plain
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            if (iv == null || iv.size != GCM_IV_LEN) return plain
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + encrypted.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)
            ENC_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (_: Throwable) {
            plain
        }
    }

    private fun decryptToken(stored: String): String? {
        return try {
            val combined = Base64.decode(stored.removePrefix(ENC_PREFIX), Base64.NO_WRAP)
            if (combined.size <= GCM_IV_LEN) return ""
            val iv = combined.copyOfRange(0, GCM_IV_LEN)
            val ciphertext = combined.copyOfRange(GCM_IV_LEN, combined.size)
            val key = getOrCreateSecretKey() ?: return null
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    fun getBlockedPackages(context: Context): Set<String> {
        val raw = prefs(context).getString(KEY_BLOCKED_PACKAGES, null)
            ?: return DEFAULT_BLOCKED_PACKAGES.toSet()
        val parsed = raw.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        // An emptied list falls back to the defaults instead of silently disabling all protection
        return parsed.ifEmpty { DEFAULT_BLOCKED_PACKAGES.toSet() }
    }

    /** Packages the automation layer must refuse: user blacklist plus the non-removable set. */
    fun getEffectiveBlockedPackages(context: Context): Set<String> =
        getBlockedPackages(context) + ALWAYS_BLOCKED_PACKAGES

    fun getBlockedPackagesText(context: Context): String =
        getBlockedPackages(context).sorted().joinToString("\n")

    fun saveBlockedPackages(context: Context, packages: Collection<String>) {
        val cleaned = packages
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSortedSet()
            .joinToString("\n")
        prefs(context).edit().apply {
            if (cleaned.isEmpty()) remove(KEY_BLOCKED_PACKAGES) else putString(KEY_BLOCKED_PACKAGES, cleaned)
        }.apply()
    }

    private fun prepareTokenForStorage(token: String): String {
        val cleanToken = token.trim()
        return if (cleanToken.isEmpty()) "" else encryptToken(cleanToken)
    }

    fun saveConnectionConfig(context: Context, workerUrl: String, token: String) {
        prefs(context).edit()
            .putString(KEY_WORKER_URL, normalizeWorkerUrl(workerUrl))
            .putString(KEY_TOKEN, prepareTokenForStorage(token))
            .apply()
    }

    fun save(context: Context, workerUrl: String, token: String, blockedPackagesText: String) {
        prefs(context).edit()
            .putString(KEY_WORKER_URL, normalizeWorkerUrl(workerUrl))
            .putString(KEY_TOKEN, prepareTokenForStorage(token))
            .putString(KEY_BLOCKED_PACKAGES, blockedPackagesText.trim())
            .apply()
    }
}
