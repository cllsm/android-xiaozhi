package com.xiaozhi.android.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DeviceCredentialRepository(
    private val context: Context,
    private val codec: DeviceCredentialCodec = DeviceCredentialCodec()
) {
    private val identityRepository = DeviceIdentityRepository(context)

    suspend fun export(uri: Uri, password: String): Unit = withContext(Dispatchers.IO) {
        val identity = identityRepository.ensureIdentity()
        if (!identity.activated) error("设备还未激活，暂不能备份凭证")

        val content = codec.encrypt(identity, password)
        val output = context.contentResolver.openOutputStream(uri, "wt")
            ?: error("无法打开凭证文件")
        output.use { stream ->
            stream.write(content.toByteArray(Charsets.UTF_8))
        }
    }

    suspend fun import(uri: Uri, password: String): Unit = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri) ?: error("无法打开凭证文件")
        val bytes = input.use { stream -> stream.readBytes() }
        if (bytes.size > MAX_CREDENTIAL_BYTES) error("凭证文件不能超过 64KB")

        val identity = codec.decrypt(String(bytes, Charsets.UTF_8), password)
        identityRepository.restore(identity)
    }

    private companion object {
        const val MAX_CREDENTIAL_BYTES = 64 * 1024
    }
}
