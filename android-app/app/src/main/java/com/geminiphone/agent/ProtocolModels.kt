package com.geminiphone.agent

import org.json.JSONObject

data class PhoneCommand(
    val requestId: String,
    val action: String,
    val args: JSONObject
)

data class CommandResult(
    val ok: Boolean,
    val data: Any? = null,
    val imageBase64: String? = null,
    val imageMimeType: String? = null,
    val error: String? = null
) {
    fun toResponseJson(requestId: String): String {
        val obj = JSONObject()
        obj.put("type", "response")
        obj.put("requestId", requestId)
        obj.put("ok", ok)
        if (data != null) obj.put("data", data)
        if (imageBase64 != null) obj.put("imageBase64", imageBase64)
        if (imageMimeType != null) obj.put("imageMimeType", imageMimeType)
        if (error != null) obj.put("error", error)
        return obj.toString()
    }
}
