package com.xiaozhi.android.network

import com.xiaozhi.android.core.DeviceIdentity
import com.xiaozhi.android.core.SettingsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.TimeUnit

data class OtaConfig(
    val websocketUrl: String,
    val websocketToken: String,
    val activationCode: String? = null,
    val activationMessage: String? = null,
    val activationChallenge: String? = null
)

class OtaClient(
    private val client: OkHttpClient = defaultClient()
) {

    suspend fun fetch(
        settings: SettingsState,
        identity: DeviceIdentity,
        localIpAddress: String
    ): OtaConfig = withContext(Dispatchers.IO) {
        val payload = JSONObject()
                    .put(
                        "application",
                        JSONObject()
                            .put("version", APP_VERSION)
                            .put("elf_sha256", identity.hmacKey)
            )
            .put(
                "board",
                JSONObject()
                    .put("type", BOARD_TYPE)
                    .put("name", APP_NAME)
                    .put("ip", localIpAddress)
                    .put("mac", identity.deviceId)
            )

        val request = Request.Builder()
            .url(settings.otaUrl)
            .header("Device-Id", identity.deviceId)
            .header("Client-Id", identity.clientId)
            .header("Content-Type", JSON_MEDIA_TYPE.toString())
            .header("Accept-Language", "zh-CN")
            .header("User-Agent", "$BOARD_TYPE/$APP_NAME-$APP_VERSION")
            .header("Activation-Version", APP_VERSION)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("OTA 服务器返回 HTTP ${response.code}")
            }
            val json = JSONObject(body)
            val websocket = json.optJSONObject("websocket") ?: JSONObject()
            val activation = json.optJSONObject("activation")
            OtaConfig(
                websocketUrl = websocket.optString("url"),
                websocketToken = websocket.optString("token"),
                activationCode = activation?.optString("code")?.takeIf { it.isNotBlank() },
                activationMessage = activation?.optString("message")?.takeIf { it.isNotBlank() },
                activationChallenge = activation?.optString("challenge")?.takeIf { it.isNotBlank() }
            )
        }
    }

    suspend fun activate(
        settings: SettingsState,
        identity: DeviceIdentity,
        config: OtaConfig
    ): Boolean = withContext(Dispatchers.IO) {
        val challenge = config.activationChallenge
        val code = config.activationCode
        if (challenge.isNullOrBlank() || code.isNullOrBlank()) return@withContext false

        val payload = JSONObject().put(
            "Payload",
            JSONObject()
                .put("algorithm", "hmac-sha256")
                .put("serial_number", identity.serialNumber)
                .put("challenge", challenge)
                .put("hmac", hmacSha256(identity.hmacKey, challenge))
        )
        val activateUrl = "${settings.otaUrl.trimEnd('/')}/activate"

        repeat(ACTIVATION_MAX_ATTEMPTS) { attempt ->
            val request = Request.Builder()
                .url(activateUrl)
                .header("Activation-Version", "2")
                .header("Device-Id", identity.deviceId)
                .header("Client-Id", identity.clientId)
                .header("Content-Type", JSON_MEDIA_TYPE.toString())
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (response.code == HTTP_OK) return@withContext true
                }
            } catch (_: IOException) {
                // Keep polling; the user may not have entered the code yet.
            }
            if (attempt < ACTIVATION_MAX_ATTEMPTS - 1) {
                delay(ACTIVATION_RETRY_INTERVAL_MS)
            }
        }
        false
    }

    private fun hmacSha256(key: String, challenge: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(challenge.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    companion object {
        private const val APP_VERSION = "1.0.0"
        private const val APP_NAME = "android-xiaozhi"
        private const val BOARD_TYPE = "bread-compact-wifi"
        private const val HTTP_OK = 200
        private const val ACTIVATION_MAX_ATTEMPTS = 60
        private const val ACTIVATION_RETRY_INTERVAL_MS = 5_000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
