package com.xiaozhi.android.audio

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer

class OpusMediaEncoder(
    private val sampleRate: Int,
    private val channels: Int,
    private val bitrate: Int = 32_000
) {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    private var presentationTimeUs = 0L

    init {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_OPUS,
            sampleRate,
            channels
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun encode(samples: ShortArray): List<ByteArray> {
        val inputIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (inputIndex >= 0) {
            val input = codec.getInputBuffer(inputIndex) ?: return emptyList()
            input.clear()
            input.asShortBuffer().put(samples)
            codec.queueInputBuffer(
                inputIndex,
                0,
                samples.size * SHORT_BYTES,
                presentationTimeUs,
                0
            )
            presentationTimeUs += samples.size * 1_000_000L / sampleRate
        }
        return drainEncoder()
    }

    private fun drainEncoder(): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US)
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
            if (outputIndex < 0) continue

            val output = codec.getOutputBuffer(outputIndex)
            if (output != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                output.position(info.offset)
                output.limit(info.offset + info.size)
                packets.add(ByteArray(output.remaining()).also(output::get))
            }
            codec.releaseOutputBuffer(outputIndex, false)
        }
        return packets
    }

    fun close() {
        codec.stop()
        codec.release()
    }

    private companion object {
        const val SHORT_BYTES = 2
        const val MAX_INPUT_SIZE = 8192
        const val INPUT_TIMEOUT_US = 10_000L
        const val OUTPUT_TIMEOUT_US = 10_000L
    }
}

class OpusMediaDecoder(
    private val sampleRate: Int,
    private val channels: Int,
    private val onOutputFormatChanged: (sampleRate: Int, channels: Int) -> Unit = { _, _ -> }
) {
    private val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    private var outputSampleRate = sampleRate
    private var outputChannels = channels
    private var presentationTimeUs = 0L
    private var loggedFirstDecode = false

    init {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_OPUS,
            sampleRate,
            channels
        ).apply {
            setByteBuffer(CODEC_CONFIG_KEY, buildOpusHead())
            setByteBuffer(CODEC_DELAY_KEY, emptyCsdValue())
            setByteBuffer(SEEK_PRE_ROLL_KEY, emptyCsdValue())
        }
        codec.configure(format, null, null, 0)
        codec.start()
    }

    fun decode(packet: ByteArray): List<ByteArray> {
        val inputIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (inputIndex >= 0) {
            val input = codec.getInputBuffer(inputIndex) ?: return emptyList()
            input.clear()
            input.put(packet)
            codec.queueInputBuffer(inputIndex, 0, packet.size, presentationTimeUs, 0)
            presentationTimeUs += FRAME_DURATION_US
        }
        val chunks = drainDecoder()
        if (!loggedFirstDecode) {
            loggedFirstDecode = true
            Log.i(
                TAG,
                "first decode inputIndex=$inputIndex chunks=${chunks.size} " +
                    "bytes=${chunks.sumOf { it.size }} format=$outputSampleRate/$outputChannels"
            )
        }
        return chunks
    }

    private fun drainDecoder(): List<ByteArray> {
        val chunks = mutableListOf<ByteArray>()
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US)
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val format = codec.outputFormat
                outputSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                outputChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                onOutputFormatChanged(outputSampleRate, outputChannels)
                continue
            }
            if (outputIndex < 0) continue

            val output = codec.getOutputBuffer(outputIndex)
            if (output != null && info.size > 0) {
                output.position(info.offset)
                output.limit(info.offset + info.size)
                chunks.add(ByteArray(output.remaining()).also(output::get))
            }
            codec.releaseOutputBuffer(outputIndex, false)
        }
        return chunks
    }

    fun close() {
        codec.stop()
        codec.release()
    }

    private fun buildOpusHead(): ByteBuffer {
        val bytes = ByteArray(OPUS_HEAD_SIZE)
        val header = "OpusHead".toByteArray(Charsets.US_ASCII)
        System.arraycopy(header, 0, bytes, 0, header.size)
        bytes[8] = 1
        bytes[9] = channels.toByte()
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            position(PRE_SKIP_OFFSET)
            putShort(0)
            putInt(sampleRate)
            putShort(0)
            put(MAPPING_FAMILY_MONO.toByte())
            rewind()
        }
    }

    private fun emptyCsdValue(): ByteBuffer {
        return ByteBuffer.wrap(ByteArray(CSD_VALUE_BYTES)).order(ByteOrder.LITTLE_ENDIAN)
    }

    private companion object {
        const val TAG = "XiaozhiOpus"
        const val INPUT_TIMEOUT_US = 10_000L
        const val OUTPUT_TIMEOUT_US = 10_000L
        const val FRAME_DURATION_US = 20_000L
        const val OPUS_HEAD_SIZE = 19
        const val PRE_SKIP_OFFSET = 10
        const val MAPPING_FAMILY_MONO = 0
        const val CODEC_CONFIG_KEY = "csd-0"
        const val CODEC_DELAY_KEY = "csd-1"
        const val SEEK_PRE_ROLL_KEY = "csd-2"
        const val CSD_VALUE_BYTES = 8
    }
}
