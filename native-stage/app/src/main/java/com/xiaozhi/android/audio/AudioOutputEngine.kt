package com.xiaozhi.android.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.sqrt

class AudioOutputEngine(
    private val sampleRate: Int,
    private val onError: (String) -> Unit = {},
    private val onLevel: (Float) -> Unit = {}
) {
    private val running = AtomicBoolean(false)
    private val queue = LinkedBlockingQueue<ByteArray>()
    private var track: AudioTrack? = null
    private var decoder: OpusMediaDecoder? = null
    private var worker: Thread? = null
    private var trackSampleRate = sampleRate
    private var trackChannels = 1
    private var packetCount = 0L
    private var pcmByteCount = 0L
    private var writeErrorCount = 0L

    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            // Android's software Opus decoder renders PCM at 48 kHz regardless of the
            // requested decoder rate. Configure that rate explicitly and let the
            // output-format callback remain authoritative for vendor-specific decoders.
            decoder = OpusMediaDecoder(ANDROID_OPUS_SAMPLE_RATE, 1, ::updateTrackFormat)
            worker = thread(name = "xiaozhi-audio-output") {
                var levelFrame = 0
                while (running.get()) {
                    val packet = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    try {
                        packetCount += 1
                        if (packetCount == 1L) {
                            Log.i(
                                TAG,
                                "first packet size=${packet.size} prefix=${packet.toHexStringPrefix()}"
                            )
                        }
                        val chunks = decoder?.decode(packet).orEmpty()
                        if (track == null) createTrack(ANDROID_OPUS_SAMPLE_RATE, 1)
                        chunks.forEach { pcm ->
                            val written = track?.write(pcm, 0, pcm.size) ?: -1
                            if (written < 0) {
                                writeErrorCount += 1
                                onError("AudioTrack 写入失败：$written")
                            } else {
                                pcmByteCount += written
                            }
                            if (++levelFrame % LEVEL_INTERVAL == 0) {
                                onLevel(rmsLevel(pcm))
                            }
                        }
                        if (packetCount == 1L || packetCount % LOG_INTERVAL == 0L) {
                            logStats("progress")
                        }
                    } catch (error: Exception) {
                        onError(error.message ?: "Opus 解码失败")
                    }
                }
            }
        } catch (error: Exception) {
            stop()
            onError(error.message ?: "音频输出初始化失败")
        }
    }

    fun enqueue(packet: ByteArray) {
        if (running.get() && packet.isNotEmpty()) {
            queue.offer(packet)
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        queue.clear()
        worker?.join(1_000)
        runCatching { track?.stop() }
        runCatching { track?.release() }
        runCatching { decoder?.close() }
        logStats("stop")
        track = null
        decoder = null
        worker = null
    }

    private fun updateTrackFormat(actualSampleRate: Int, actualChannels: Int) {
        if (track == null) {
            createTrack(actualSampleRate, actualChannels)
            return
        }
        if (actualSampleRate == trackSampleRate && actualChannels == trackChannels) return

        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
        createTrack(actualSampleRate, actualChannels)
    }

    private fun createTrack(newSampleRate: Int, newChannels: Int) {
        val channelMask = when (newChannels) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> {
                onError("不支持的播放声道数：$newChannels")
                return
            }
        }
        val minBuffer = AudioTrack.getMinBufferSize(
            newSampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT
        )
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(newSampleRate)
                    .setChannelMask(channelMask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(
                maxOf(minBuffer, FRAME_SAMPLES * newChannels * SHORT_BYTES * BUFFER_FRAMES)
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        trackSampleRate = newSampleRate
        trackChannels = newChannels
        track?.play()
        Log.i(
            TAG,
            "AudioTrack ready: rate=$newSampleRate, channels=$newChannels, buffer=$minBuffer"
        )
    }

    private fun logStats(stage: String) {
        Log.i(
            TAG,
            "$stage packets=$packetCount pcmBytes=$pcmByteCount " +
                "writeErrors=$writeErrorCount track=${trackSampleRate}Hz/${trackChannels}ch"
        )
    }

    private companion object {
        const val TAG = "XiaozhiAudioOut"
        const val ANDROID_OPUS_SAMPLE_RATE = 48_000
        const val SHORT_BYTES = 2
        const val BUFFER_FRAMES = 4
        const val FRAME_SAMPLES = 960
        const val LEVEL_INTERVAL = 5
        const val LOG_INTERVAL = 25
        const val HEX_PREFIX_BYTES = 16
    }

    private fun ByteArray.toHexStringPrefix(): String {
        return take(HEX_PREFIX_BYTES).joinToString(" ") { byte ->
            String.format("%02x", byte)
        }
    }

    private fun rmsLevel(pcm: ByteArray): Float {
        var sum = 0.0
        var count = 0
        var index = 0
        while (index + 1 < pcm.size) {
            val sample = (((pcm[index + 1].toInt()) shl 8) or
                (pcm[index].toInt() and 0xff)).toShort()
            sum += sample.toDouble() * sample.toDouble()
            count += 1
            index += 2
        }
        if (count == 0) return 0f
        return (sqrt(sum / count) / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
    }
}
