package com.xiaozhi.android.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.sqrt

class AudioInputEngine(
    private val onPacket: (ByteArray) -> Unit,
    private val onSamples: (ShortArray) -> Unit = {},
    private val initialSendingEnabled: Boolean = true,
    private val aecEnabled: Boolean = true,
    private val onError: (String) -> Unit = {},
    private val onLevel: (Float) -> Unit = {}
) {
    private val running = AtomicBoolean(false)
    private val sendingEnabled = AtomicBoolean(initialSendingEnabled)
    private var record: AudioRecord? = null
    private var encoder: OpusMediaEncoder? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var worker: Thread? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (!running.compareAndSet(false, true)) return

        try {
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            record = AudioRecord.Builder()
                .setAudioSource(
                    if (aecEnabled) {
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION
                    } else {
                        MediaRecorder.AudioSource.MIC
                    }
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuffer, FRAME_BYTES * 4))
                .build()
            encoder = OpusMediaEncoder(SAMPLE_RATE, CHANNELS)

            if (record?.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("麦克风初始化失败")
            }
            if (aecEnabled) {
                echoCanceler = AcousticEchoCanceler.create(record!!.audioSessionId)?.apply {
                    enabled = true
                }
            }
            record?.startRecording()
            worker = thread(name = "xiaozhi-audio-input") {
                val samples = ShortArray(FRAME_SAMPLES)
                var levelFrame = 0
                while (running.get()) {
                    val read = try {
                        record?.read(samples, 0, samples.size) ?: break
                    } catch (_: Exception) {
                        break
                    }
                    if (read == FRAME_SAMPLES) {
                        if (++levelFrame % LEVEL_INTERVAL == 0) {
                            onLevel(rmsLevel(samples))
                        }
                        onSamples(samples.copyOf())
                        if (sendingEnabled.get()) {
                            try {
                                encoder?.encode(samples)?.forEach(onPacket)
                            } catch (error: Exception) {
                                onError(error.message ?: "Opus 编码失败")
                                break
                            }
                        }
                    }
                }
            }
        } catch (error: Exception) {
            stop()
            onError(error.message ?: "音频输入初始化失败")
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { record?.stop() }
        runCatching { record?.release() }
        runCatching { echoCanceler?.enabled = false }
        runCatching { echoCanceler?.release() }
        runCatching { encoder?.close() }
        record = null
        echoCanceler = null
        encoder = null
        worker = null
    }

    fun startSending() {
        sendingEnabled.set(true)
    }

    fun stopSending() {
        sendingEnabled.set(false)
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNELS = 1
        const val FRAME_SAMPLES = 320
        const val FRAME_BYTES = FRAME_SAMPLES * 2
        const val LEVEL_INTERVAL = 5
    }

    private fun rmsLevel(samples: ShortArray): Float {
        var sum = 0.0
        for (sample in samples) {
            sum += sample.toDouble() * sample.toDouble()
        }
        return (sqrt(sum / samples.size) / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
    }
}
