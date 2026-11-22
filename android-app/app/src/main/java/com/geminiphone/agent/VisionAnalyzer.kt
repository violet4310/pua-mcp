package com.geminiphone.agent

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class VisionResult(
    val description: String? = null,
    val error: String? = null,
    val model: String
)

object VisionAnalyzer {
    private const val MAX_ERROR_BODY_LEN = 240
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .build()

    suspend fun analyzeScreenshot(
        context: Context,
        imageBase64: String,
        mimeType: String,
        cropOffsetX: Int,
        cropOffsetY: Int,
        cropWidth: Int,
        cropHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
        imageWidth: Int,
        imageHeight: Int,
        cropped: Boolean,
        userPrompt: String?
    ): VisionResult = withContext(Dispatchers.IO) {
        val apiUrl = ConfigStore.getVisionApiUrl(context)
        val apiKey = ConfigStore.getVisionApiKey(context)
        val model = ConfigStore.getVisionModel(context)

        if (!ConfigStore.isValidVisionApiUrl(apiUrl) || model.isEmpty()) {
            return@withContext VisionResult(
                error = "Custom vision API URL or model is not configured in the Android app.",
                model = model
            )
        }

        val prompt = buildPrompt(
            cropOffsetX = cropOffsetX,
            cropOffsetY = cropOffsetY,
            cropWidth = cropWidth,
            cropHeight = cropHeight,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            cropped = cropped,
            userPrompt = userPrompt
        )

        val isNativeGemini = apiUrl.contains("generativelanguage.googleapis.com", ignoreCase = true) &&
            !apiUrl.contains("/openai", ignoreCase = true)

        var jobHandle: kotlinx.coroutines.DisposableHandle? = null
        try {
            val request = if (isNativeGemini) {
                buildGeminiRequest(apiUrl, apiKey, model, prompt, mimeType, imageBase64)
            } else {
                buildOpenAiRequest(apiUrl, apiKey, model, prompt, mimeType, imageBase64)
            }

            val call = client.newCall(request)
            jobHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
                if (cause != null) call.cancel()
            }

            call.execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val snippet = sanitizeError(rawBody, apiKey)
                    val msg = if (snippet.isNotEmpty()) {
                        "Vision API HTTP ${response.code}: $snippet"
                    } else {
                        "Vision API HTTP ${response.code}"
                    }
                    return@withContext VisionResult(error = msg, model = model)
                }

                val parsedText = if (isNativeGemini) {
                    parseGeminiResponse(rawBody)
                } else {
                    parseOpenAiResponse(rawBody)
                }

                if (parsedText.isNullOrEmpty()) {
                    VisionResult(error = "Vision API returned an empty description.", model = model)
                } else {
                    VisionResult(description = parsedText, model = model)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = sanitizeError(e.message ?: e.javaClass.simpleName, apiKey)
            VisionResult(error = "Vision API error: $msg", model = model)
        } finally {
            jobHandle?.dispose()
        }
    }

    private fun buildPrompt(
        cropOffsetX: Int,
        cropOffsetY: Int,
        cropWidth: Int,
        cropHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
        imageWidth: Int,
        imageHeight: Int,
        cropped: Boolean,
        userPrompt: String?
    ): String {
        val regionDesc = if (cropped) {
            "cropped Android screenshot (${imageWidth}x${imageHeight}px) covering screen region " +
                "[$cropOffsetX, $cropOffsetY, ${cropOffsetX + cropWidth}, ${cropOffsetY + cropHeight}] " +
                "within ${screenWidth}x${screenHeight} display"
        } else {
            "full-screen Android screenshot (${imageWidth}x${imageHeight}px) of a ${screenWidth}x${screenHeight} display"
        }
        val base = "Analyze this $regionDesc. Provide: " +
            "1) a concise layout summary, " +
            "2) visible text, icons, buttons, or images likely absent from the accessibility tree, and " +
            "3) estimated full-screen (x, y) coordinates in ${screenWidth}x${screenHeight} space for key visual targets."
        val extra = userPrompt?.trim().orEmpty()
        return if (extra.isNotEmpty()) "$base\nFocus: $extra" else base
    }

    private fun buildGeminiEndpoint(apiUrl: String, model: String): String {
        val parsed = apiUrl.toHttpUrlOrNull()
        val path = parsed?.encodedPath?.trimEnd('/') ?: apiUrl.trimEnd('/')
        if (path.endsWith(":generateContent", ignoreCase = true)) {
            return apiUrl
        }
        val cleanUrl = apiUrl.trimEnd('/')
        if (parsed != null && parsed.query != null) {
            val builder = parsed.newBuilder()
            when {
                path.endsWith("/models", ignoreCase = true) -> builder.addPathSegment("$model:generateContent")
                path.contains("/models/", ignoreCase = true) -> {
                    val segs = parsed.pathSegments
                    val last = segs.lastOrNull().orEmpty()
                    if (last.isNotEmpty()) {
                        builder.setPathSegment(segs.lastIndex, "$last:generateContent")
                    }
                }
                path.isEmpty() -> builder.addPathSegments("v1beta/models/$model:generateContent")
                else -> builder.addPathSegments("models/$model:generateContent")
            }
            return builder.build().toString()
        }
        return when {
            path.endsWith("/models", ignoreCase = true) -> "$cleanUrl/$model:generateContent"
            path.contains("/models/", ignoreCase = true) -> "$cleanUrl:generateContent"
            path.isEmpty() -> "$cleanUrl/v1beta/models/$model:generateContent"
            else -> "$cleanUrl/models/$model:generateContent"
        }
    }

    private fun buildOpenAiEndpoint(apiUrl: String): String {
        val parsed = apiUrl.toHttpUrlOrNull()
        val path = parsed?.encodedPath?.trimEnd('/') ?: apiUrl.trimEnd('/')
        if (path.endsWith("/chat/completions", ignoreCase = true)) {
            return apiUrl
        }
        if (parsed != null && parsed.query != null) {
            return parsed.newBuilder()
                .addPathSegments("chat/completions")
                .build()
                .toString()
        }
        return "${apiUrl.trimEnd('/')}/chat/completions"
    }

    private fun buildGeminiRequest(
        apiUrl: String,
        apiKey: String,
        model: String,
        prompt: String,
        mimeType: String,
        imageBase64: String
    ): Request {
        val endpoint = buildGeminiEndpoint(apiUrl, model)
        val parts = JSONArray()
            .put(JSONObject().put("text", prompt))
            .put(
                JSONObject().put(
                    "inline_data",
                    JSONObject()
                        .put("mime_type", mimeType)
                        .put("data", imageBase64)
                )
            )
        val payload = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put(
                "generationConfig",
                JSONObject()
                    .put("maxOutputTokens", 600)
                    .put("temperature", 0.2)
            )

        val builder = Request.Builder()
            .url(endpoint)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
        if (apiKey.isNotEmpty()) {
            builder.header("x-goog-api-key", apiKey)
        }
        return builder.build()
    }

    private fun buildOpenAiRequest(
        apiUrl: String,
        apiKey: String,
        model: String,
        prompt: String,
        mimeType: String,
        imageBase64: String
    ): Request {
        val endpoint = buildOpenAiEndpoint(apiUrl)
        val contentArray = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:$mimeType;base64,$imageBase64"))
            )
        val payload = JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", contentArray)
                )
            )
            .put("max_tokens", 600)
            .put("temperature", 0.2)

        val builder = Request.Builder()
            .url(endpoint)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
        if (apiKey.isNotEmpty()) {
            builder.header("Authorization", "Bearer $apiKey")
        }
        return builder.build()
    }

    private fun parseGeminiResponse(rawBody: String): String? {
        val json = JSONObject(rawBody)
        val parts = json.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?: return null

        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val text = parts.optJSONObject(i)?.optString("text", "")?.trim().orEmpty()
            if (text.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(text)
            }
        }
        return sb.toString().ifEmpty { null }
    }

    private fun parseOpenAiResponse(rawBody: String): String? {
        val json = JSONObject(rawBody)
        val rawContent = json.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.opt("content")
            ?: return null

        val text = when (rawContent) {
            is String -> rawContent.trim()
            is JSONArray -> {
                val sb = StringBuilder()
                for (i in 0 until rawContent.length()) {
                    val part = rawContent.opt(i)
                    val t = when (part) {
                        is JSONObject -> part.optString("text", "").trim()
                        is String -> part.trim()
                        else -> ""
                    }
                    if (t.isNotEmpty()) {
                        if (sb.isNotEmpty()) sb.append('\n')
                        sb.append(t)
                    }
                }
                sb.toString()
            }
            else -> ""
        }
        return text.ifEmpty { null }
    }

    private fun sanitizeError(text: String, apiKey: String): String {
        val trimmed = text.trim()
        val redacted = if (apiKey.length >= 4 && trimmed.contains(apiKey)) {
            trimmed.replace(apiKey, "[REDACTED]")
        } else {
            trimmed
        }
        return redacted.take(MAX_ERROR_BODY_LEN)
    }
}
