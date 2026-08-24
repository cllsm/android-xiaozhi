package com.xiaozhi.android.mcp

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

object VisionService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
    private val jpegMediaType = "image/jpeg".toMediaType()

    @Volatile
    private var analyzeUrl: String = ""

    @Volatile
    private var token: String = ""

    fun configure(capabilities: JSONObject) {
        val vision = capabilities.optJSONObject("vision") ?: return
        analyzeUrl = vision.optString("url").orEmpty()
        token = vision.optString("token").orEmpty()
    }

    fun isConfigured(): Boolean {
        return analyzeUrl.isNotBlank()
    }

    suspend fun awaitConfigured(timeoutMs: Long = 8_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!isConfigured()) {
            if (System.currentTimeMillis() >= deadline) return false
            delay(200L)
        }
        return true
    }

    fun analyze(question: String, image: ByteArray, fileName: String): JSONObject {
        if (analyzeUrl.isBlank()) {
            return failure("视觉分析服务 URL 未配置")
        }

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("question", question)
            .addFormDataPart("file", fileName, image.toRequestBody(jpegMediaType))
            .build()
        val request = Request.Builder()
            .url(analyzeUrl)
            .post(body)
            .apply {
                if (token.isNotBlank()) {
                    header("Authorization", "Bearer $token")
                }
            }
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return failure("上传图片失败，HTTP ${response.code}")
                }
                parseResponse(text)
            }
        } catch (error: Exception) {
            failure("连接视觉分析服务失败：${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun parseResponse(text: String): JSONObject {
        return try {
            JSONObject(text)
        } catch (_: Exception) {
            JSONObject().put("success", true).put("response", text)
        }
    }

    private fun failure(message: String): JSONObject {
        return JSONObject().put("success", false).put("message", message)
    }
}
