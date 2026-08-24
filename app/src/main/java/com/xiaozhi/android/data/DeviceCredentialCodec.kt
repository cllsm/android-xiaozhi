package com.xiaozhi.android.data

import com.xiaozhi.android.core.DeviceIdentity
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

class DeviceCredentialCodec {

    fun encrypt(identity: DeviceIdentity, password: String): String {
        validateIdentity(identity)
        validatePassword(password)

        val salt = ByteArray(SALT_LENGTH_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_LENGTH_BYTES).also(random::nextBytes)
        val plaintext = payload(identity).toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            deriveKey(password, salt),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        )
        cipher.updateAAD(AUTHENTICATED_HEADER.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(plaintext)

        return JSONObject()
            .put("type", CREDENTIAL_TYPE)
            .put("version", FORMAT_VERSION)
            .put(
                "encryption",
                JSONObject()
                    .put("kdf", KDF_ALGORITHM)
                    .put("iterations", KDF_ITERATIONS)
                    .put("salt", encode(salt))
                    .put("cipher", CIPHER_ALGORITHM)
                    .put("iv", encode(iv))
            )
            .put("payload", encode(ciphertext))
            .toString()
    }

    fun decrypt(raw: String, password: String): DeviceIdentity {
        validatePassword(password)
        val source = runCatching { JSONObject(raw) }.getOrElse {
            error("凭证文件格式不正确")
        }
        if (source.optString("type") != CREDENTIAL_TYPE ||
            source.optInt("version") != FORMAT_VERSION
        ) {
            error("不支持的激活凭证文件")
        }

        val encryption = source.optJSONObject("encryption")
            ?: error("凭证文件缺少加密信息")
        if (encryption.optString("kdf") != KDF_ALGORITHM ||
            encryption.optString("cipher") != CIPHER_ALGORITHM ||
            encryption.optInt("iterations") != KDF_ITERATIONS
        ) {
            error("凭证加密参数不兼容")
        }

        val salt = decode(encryption.optString("salt"), "凭证盐值")
        val iv = decode(encryption.optString("iv"), "凭证初始向量")
        val ciphertext = decode(source.optString("payload"), "凭证内容")
        if (salt.size != SALT_LENGTH_BYTES || iv.size != IV_LENGTH_BYTES) {
            error("凭证加密参数长度不正确")
        }

        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            deriveKey(password, salt),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        )
        cipher.updateAAD(AUTHENTICATED_HEADER.toByteArray(Charsets.UTF_8))
        val plaintext = try {
            cipher.doFinal(ciphertext)
        } catch (_: Exception) {
            error("恢复口令不正确或凭证文件已损坏")
        }

        return parsePayload(String(plaintext, Charsets.UTF_8))
    }

    private fun payload(identity: DeviceIdentity): JSONObject {
        return JSONObject()
            .put("payload_version", PAYLOAD_VERSION)
            .put("device_id", identity.deviceId)
            .put("client_id", identity.clientId)
            .put("serial_number", identity.serialNumber)
            .put("hmac_key", identity.hmacKey)
            .put("activated", identity.activated)
            .put("created_at", System.currentTimeMillis())
    }

    private fun parsePayload(raw: String): DeviceIdentity {
        val source = runCatching { JSONObject(raw) }.getOrElse {
            error("凭证内容格式不正确")
        }
        if (source.optInt("payload_version") != PAYLOAD_VERSION) {
            error("凭证内容版本不支持")
        }
        val identity = DeviceIdentity(
            deviceId = source.optString("device_id"),
            clientId = source.optString("client_id"),
            serialNumber = source.optString("serial_number"),
            hmacKey = source.optString("hmac_key"),
            activated = source.optBoolean("activated", false)
        )
        validateIdentity(identity)
        return identity
    }

    private fun validateIdentity(identity: DeviceIdentity) {
        if (!DEVICE_ID_PATTERN.matches(identity.deviceId)) error("凭证 Device-Id 不合法")
        if (identity.clientId.length !in CLIENT_ID_LENGTH_RANGE) error("凭证 Client-Id 不合法")
        if (identity.serialNumber.length !in SERIAL_LENGTH_RANGE) error("凭证序列号不合法")
        if (!HMAC_KEY_PATTERN.matches(identity.hmacKey)) error("凭证设备密钥不合法")
    }

    private fun validatePassword(password: String) {
        if (password.length !in PASSWORD_LENGTH_RANGE) {
            error("恢复口令需要 8-64 个字符")
        }
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance(KDF_ALGORITHM)
        val key = factory.generateSecret(
            PBEKeySpec(password.toCharArray(), salt, KDF_ITERATIONS, KEY_LENGTH_BITS)
        ).encoded
        return SecretKeySpec(key, "AES")
    }

    private fun encode(value: ByteArray): String {
        return Base64.getEncoder().withoutPadding().encodeToString(value)
    }

    private fun decode(value: String, name: String): ByteArray {
        if (value.isBlank()) error("$name 不能为空")
        return try {
            Base64.getDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            error("$name 不是有效的 Base64")
        }
    }

    private companion object {
        const val CREDENTIAL_TYPE = "com.xiaozhi.android.device-credential"
        const val FORMAT_VERSION = 1
        const val PAYLOAD_VERSION = 1
        const val AUTHENTICATED_HEADER = "xiaozhi-device-credential-v1"
        const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
        const val CIPHER_ALGORITHM = "AES/GCM/NoPadding"
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val KDF_ITERATIONS = 120_000
        const val KEY_LENGTH_BITS = 256
        const val GCM_TAG_LENGTH_BITS = 128
        const val SALT_LENGTH_BYTES = 16
        const val IV_LENGTH_BYTES = 12
        val PASSWORD_LENGTH_RANGE = 8..64
        val CLIENT_ID_LENGTH_RANGE = 8..64
        val SERIAL_LENGTH_RANGE = 4..80
        val DEVICE_ID_PATTERN = Regex("^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$")
        val HMAC_KEY_PATTERN = Regex("^[0-9a-f]{64}$")
        val random = SecureRandom()
    }
}
