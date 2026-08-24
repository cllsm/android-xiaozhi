package com.xiaozhi.android.data

import android.content.Context
import android.media.MediaDrm
import android.provider.Settings
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xiaozhi.android.core.DeviceIdentity
import java.security.MessageDigest
import java.util.UUID

private val Context.identityStore by preferencesDataStore(name = "xiaozhi_identity")

class DeviceIdentityRepository(private val context: Context) {

    suspend fun ensureIdentity(): DeviceIdentity {
        var identity: DeviceIdentity? = null
        val stableSeed = stableDeviceSeed()
        context.identityStore.edit { prefs ->
            val existingClientId = prefs[ClientId]
            val clientId = existingClientId ?: stableClientId(stableSeed)
            if (existingClientId == null) {
                prefs[ClientId] = clientId
            }
            val savedDeviceId = prefs[DeviceId]
            val deviceId = when {
                savedDeviceId == null -> defaultDeviceId(stableSeed)
                isValidMac(savedDeviceId) -> savedDeviceId.lowercase()
                else -> {
                    prefs.remove(SerialNumber)
                    prefs.remove(HmacKey)
                    prefs.remove(Activated)
                    defaultDeviceId(stableSeed)
                }
            }
            val serialNumber = prefs[SerialNumber] ?: generateSerialNumber(deviceId)
            val hmacKey = prefs[HmacKey] ?: generateHmacKey(deviceId, clientId)
            identity = DeviceIdentity(
                deviceId = deviceId,
                clientId = clientId,
                serialNumber = serialNumber,
                hmacKey = hmacKey,
                activated = prefs[Activated] ?: false
            )
            prefs[DeviceId] = deviceId
            prefs[SerialNumber] = serialNumber
            prefs[HmacKey] = hmacKey
            prefs[Activated] = identity!!.activated
        }
        return requireNotNull(identity)
    }

    suspend fun setActivated(activated: Boolean) {
        context.identityStore.edit { prefs ->
            prefs[Activated] = activated
        }
    }

    suspend fun restore(identity: DeviceIdentity) {
        val deviceId = identity.deviceId.lowercase()
        require(deviceId.matches(MAC_PATTERN)) { "恢复的 Device-Id 不合法" }
        require(identity.clientId.length in 8..64) { "恢复的 Client-Id 不合法" }
        require(identity.serialNumber.length in 4..80) { "恢复的序列号不合法" }
        require(identity.hmacKey.matches(HMAC_KEY_PATTERN)) { "恢复的设备密钥不合法" }

        context.identityStore.edit { prefs ->
            prefs[DeviceId] = deviceId
            prefs[ClientId] = identity.clientId
            prefs[SerialNumber] = identity.serialNumber
            prefs[HmacKey] = identity.hmacKey
            prefs[Activated] = identity.activated
        }
    }

    private fun defaultDeviceId(stableSeed: String): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty()
        return formatAsMac(digest("SHA-256", listOf(stableSeed, androidId).joinToString("||")).take(MAC_HEX_LENGTH))
    }

    private fun stableDeviceSeed(): String {
        val hardwareSeed = runCatching {
            MediaDrm(WIDEVINE_UUID).use { drm ->
                drm.getPropertyString(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID)
            }
        }.getOrNull().orEmpty()
        return digest("SHA-256", "xiaozhi-android-device-v1||$hardwareSeed")
    }

    private fun stableClientId(stableSeed: String): String {
        val value = digest("SHA-256", "xiaozhi-android-client-v1||$stableSeed").take(32)
        return "${value.substring(0, 8)}-${value.substring(8, 12)}-" +
            "${value.substring(12, 16)}-${value.substring(16, 20)}-${value.substring(20, 32)}"
    }

    private fun formatAsMac(value: String): String {
        return value.chunked(2).joinToString(":") { part -> part.lowercase() }
    }

    private fun isValidMac(value: String): Boolean {
        val normalized = value.replace(":", "")
        return normalized.length == MAC_HEX_LENGTH &&
            normalized.all { character -> character.isDigit() || character in 'a'..'f' || character in 'A'..'F' }
    }

    private fun generateSerialNumber(deviceId: String): String {
        return if (deviceId.contains(':')) {
            val mac = deviceId.replace(":", "").lowercase()
            "SN-${digest("MD5", mac).take(8).uppercase()}-$mac"
        } else {
            val identifier = deviceId.take(12)
            "SN-${digest("MD5", identifier).take(8).uppercase()}-$identifier"
        }
    }

    private fun generateHmacKey(deviceId: String, clientId: String): String {
        return digest("SHA-256", listOf("Android", deviceId, clientId, STABLE_IDENTITY_NAMESPACE).joinToString("||"))
    }

    private fun digest(algorithm: String, value: String): String {
        return MessageDigest.getInstance(algorithm)
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        val DeviceId = stringPreferencesKey("device_id")
        val ClientId = stringPreferencesKey("client_id")
        val SerialNumber = stringPreferencesKey("serial_number")
        val HmacKey = stringPreferencesKey("hmac_key")
        val Activated = booleanPreferencesKey("activated")
        const val MAC_HEX_LENGTH = 12
        const val STABLE_IDENTITY_NAMESPACE = "xiaozhi-android-stable-v1"
        val WIDEVINE_UUID = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")
        val MAC_PATTERN = Regex("^([0-9a-f]{2}:){5}[0-9a-f]{2}$")
        val HMAC_KEY_PATTERN = Regex("^[0-9a-f]{64}$")
    }
}
