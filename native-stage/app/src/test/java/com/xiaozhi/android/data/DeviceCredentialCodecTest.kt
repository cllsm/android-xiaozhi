package com.xiaozhi.android.data

import com.xiaozhi.android.core.DeviceIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCredentialCodecTest {

    @Test
    fun encryptDecryptRoundTrip() {
        val identity = identity(activated = true)
        val raw = codec.encrypt(identity, PASSWORD)

        assertFalse(raw.contains(identity.clientId))
        assertFalse(raw.contains(identity.hmacKey))

        val restored = codec.decrypt(raw, PASSWORD)
        assertEquals(identity, restored)
    }

    @Test
    fun decryptRejectsWrongPassword() {
        val raw = codec.encrypt(identity(activated = true), PASSWORD)

        val error = runCatching { codec.decrypt(raw, "wrong-password") }
            .exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals("恢复口令不正确或凭证文件已损坏", error?.message)
    }

    @Test
    fun decryptRejectsInvalidEnvelope() {
        val error = runCatching { codec.decrypt("{\"type\":\"wrong\"}", PASSWORD) }
            .exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals("不支持的激活凭证文件", error?.message)
    }

    private fun identity(activated: Boolean): DeviceIdentity {
        return DeviceIdentity(
            deviceId = "aa:bb:cc:dd:ee:ff",
            clientId = "01234567-89ab-cdef-0123-456789abcdef",
            serialNumber = "SN-TEST-001",
            hmacKey = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            activated = activated
        )
    }

    private companion object {
        const val PASSWORD = "restore-password"
        val codec = DeviceCredentialCodec()
    }
}
