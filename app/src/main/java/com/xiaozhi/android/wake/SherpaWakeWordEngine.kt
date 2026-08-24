package com.xiaozhi.android.wake

import android.content.Context
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.getFeatureConfig
import com.xiaozhi.android.core.SettingsState
import java.util.concurrent.atomic.AtomicBoolean

class SherpaWakeWordEngine(
    context: Context,
    settings: SettingsState,
    private val onDetected: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val closed = AtomicBoolean(false)
    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null

    init {
        try {
            val model = OnlineModelConfig().apply {
                transducer = OnlineTransducerModelConfig(
                    encoder = "$MODEL_DIR/encoder.onnx",
                    decoder = "$MODEL_DIR/decoder.onnx",
                    joiner = "$MODEL_DIR/joiner.onnx"
                )
                tokens = "$MODEL_DIR/tokens.txt"
                numThreads = 1
                modelType = MODEL_TYPE
            }
            val config = KeywordSpotterConfig().apply {
                featConfig = getFeatureConfig(
                    sampleRate = SAMPLE_RATE,
                    featureDim = FEATURE_DIM
                )
                modelConfig = model
                keywordsFile = "$MODEL_DIR/keywords.txt"
                keywordsScore = settings.keywordsScore
                // Sherpa exposes one threshold; combine the two legacy controls so
                // changing either sensitivity or threshold affects detection.
                keywordsThreshold = (
                    settings.wakeWordSensitivity + settings.keywordsThreshold
                    ) / 2f
            }
            spotter = KeywordSpotter(context.assets, config)
            val keywordLine = runCatching {
                WakeWordKeywordBuilder.build(settings.wakeWordText)
            }.getOrElse {
                onError("唤醒词转换失败，已回退默认关键词：${it.message}")
                null
            }
            stream = if (keywordLine == null) {
                spotter?.createStream()
            } else {
                spotter?.createStream(keywordLine)
            }
        } catch (error: Exception) {
            close()
            onError(error.message ?: "唤醒词模型初始化失败")
        }
    }

    fun process(samples: ShortArray) {
        if (closed.get()) return
        val engine = spotter ?: return
        val currentStream = stream ?: return
        try {
            currentStream.acceptWaveform(
                samples.map { it / Short.MAX_VALUE.toFloat() }.toFloatArray(),
                SAMPLE_RATE
            )
            while (engine.isReady(currentStream)) {
                engine.decode(currentStream)
            }
            val keyword = engine.getResult(currentStream).keyword
            if (keyword.isNotBlank()) {
                engine.reset(currentStream)
                onDetected(keyword)
            }
        } catch (error: Exception) {
            onError(error.message ?: "唤醒词检测失败")
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { stream?.release() }
        runCatching { spotter?.release() }
        stream = null
        spotter = null
    }

    private companion object {
        const val MODEL_DIR = "models/zh"
        const val MODEL_TYPE = "zipformer2"
        const val SAMPLE_RATE = 16_000
        const val FEATURE_DIM = 80
    }
}
