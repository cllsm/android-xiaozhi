<template>
  <view class="container">
    <!-- 顶部状态栏 + 工具按钮 -->
    <view class="top-bar">
      <StatusBar />
      <view class="top-actions">
        <ThemePicker />
        <view class="settings-btn" @click="goToSettings">
          <text class="settings-icon">⚙</text>
        </view>
      </view>
    </view>

    <!-- 对话区域 -->
    <scroll-view
      class="chat-area"
      scroll-y
      :scroll-top="scrollTop"
      :scroll-with-animation="true"
      @scroll="onChatScroll"
    >
      <view id="chat-bottom" class="chat-messages">
        <view v-if="chatHistory.length === 0" class="empty-hint">
          <text class="empty-text">点击下方按钮开始对话</text>
          <view class="hint-tags">
            <text class="hint-tag" @click="handleQuickSend('帮我看看屏幕上是什么')">👀 看看屏幕</text>
            <text class="hint-tag" @click="handleQuickSend('帮我读一下屏幕上的文字')">📝 读屏幕文字</text>
            <text class="hint-tag" @click="handleQuickSend('这个怎么操作')">🔧 怎么操作</text>
            <text class="hint-tag" @click="handleQuickSend('聊天记录我该怎么回')">💬 怎么回复</text>
            <text class="hint-tag" @click="handleQuickSend('帮我看看面前有什么')">📷 看看面前</text>
            <text class="hint-tag" @click="handleQuickSend('帮我拍张照看看这是什么东西')">🔍 识别物体</text>
            <text class="hint-tag" @click="handleQuickSend('帮我录一段视频')">📹 录段视频</text>
            <text class="hint-tag" @click="handleQuickSend('帮我打开相机')">🎥 打开相机</text>
            <text class="hint-tag" @click="handleQuickSend('播放一段音乐')">🎵 播放音乐</text>
            <text class="hint-tag" @click="handleQuickSend('今天天气怎么样')">🌤 查天气</text>
          </view>
        </view>
        <ChatBubble
          v-for="(msg, idx) in chatHistory"
          :key="idx"
          :text="msg.text"
          :is-user="msg.isUser"
          :timestamp="msg.timestamp"
        />
      </view>
    </scroll-view>

    <!-- 当前文本显示 -->
    <view v-if="currentText" class="current-text">
      <text class="current-text-content">{{ currentText }}</text>
    </view>

    <!-- 情绪表情 -->
    <EmotionFace :emotion="currentEmotion" />

    <!-- 音频波形 -->
    <AudioWave :active="deviceState === 'LISTENING' || deviceState === 'SPEAKING'" :volume="audioVolume" />

    <!-- 错误提示（3秒自动消失） -->
    <view v-if="errorMessage" class="error-bar">
      <text class="error-text">{{ errorMessage }}</text>
      <text class="error-close" @click="errorMessage = null">✕</text>
    </view>

    <!-- 底部控制栏 -->
    <view class="control-bar">
      <!-- 唤醒词监听中状态 -->
      <view v-if="isWakeWordMonitoring" class="btn-monitoring" @click="handleStopMonitoring">
        <text class="btn-text">正在监听唤醒词... 🎤 点击停止</text>
      </view>

      <!-- 正在启动/连接中 -->
      <view v-else-if="isStarting || isConnecting" class="btn-connecting">
        <text class="btn-text">{{ isConnecting ? '正在连接服务器...' : '正在启动...' }}</text>
      </view>

      <!-- 开始/停止对话按钮 -->
      <view v-else-if="deviceState === 'IDLE'" class="btn-primary" @click="handleStart">
        <text class="btn-text">开始对话</text>
      </view>

      <view v-else-if="deviceState === 'LISTENING'" class="btn-listening" @click="handleStop">
        <text class="btn-text">正在聆听...</text>
      </view>

      <view v-else-if="deviceState === 'SPEAKING'" class="btn-speaking" @click="handleAbort">
        <text class="btn-text">正在回复 (点击打断)</text>
      </view>

      <view v-else class="btn-connecting">
        <text class="btn-text">连接中...</text>
      </view>

      <!-- 唤醒词自动监听开关 -->
      <view v-if="deviceState === 'IDLE' && isBackendConnected && !isWakeWordMonitoring" class="wake-word-toggle">
        <text class="toggle-label">唤醒词监听</text>
        <switch
          :checked="wakeWordAutoMonitor"
          @change="handleToggleWakeWord"
          color="#66bb6a"
        />
      </view>

      <!-- 文本输入 -->
      <view class="input-row">
        <view class="btn-camera" @click="handleTakePhoto">
          <text class="camera-icon">📷</text>
        </view>
        <input
          class="text-input"
          v-model="inputText"
          placeholder="输入消息..."
          placeholder-class="input-placeholder"
          @confirm="handleSendText"
          confirm-type="send"
        />
        <view class="btn-send" @click="handleSendText">
          <text class="btn-send-text">发送</text>
        </view>
      </view>
    </view>
    <!-- renderjs 录音桥接隐藏元素 -->
    <!-- #ifdef APP -->
    <view
      :prop="renderjsProp"
      :change:prop="recorderRenderjs.onPropChange"
      :aecCfg="aecProp"
      :change:aecCfg="recorderRenderjs.onAecChange"
      class="renderjs-bridge"
    ></view>
    <!-- #endif -->
    <!-- 悬浮球 -->
    <FloatingBall />
  </view>
</template>

<!-- ★ Options API 普通脚本 — renderjs 的 $ownerInstance.callMethod 需要这里的 methods -->
<script lang="ts">
import { audioRecorder } from '@/utils/audio-recorder'

export default {
  methods: {
    /** renderjs PCM 帧回调 */
    onPcmFrameFromRenderjs(data: { hex: string, sampleCount: number }) {
      audioRecorder.onPcmFrameHex(data.hex)
    },
    /** renderjs 录音就绪回调 */
    onRenderjsReady(_data: any) {
      console.log('[Home] renderjs 录音已就绪')
    },
    /** renderjs 错误回调 */
    onRenderjsError(data: { msg: string }) {
      console.error('[Home] renderjs 录音错误:', data.msg)
      // renderjs getUserMedia 失败，立即触发降级
      audioRecorder.handleRenderjsError(data.msg)
    },
  },
}
</script>

<script setup lang="ts">
import { useAppStore } from '@/store'
import { useSettingsStore } from '@/store'
import { useAudioStore } from '@/store'
import FloatingBall from '@/components/floating-ball/index.vue'
import { AudioRecorder } from '@/utils/audio-recorder'
import { audioRecorder } from '@/utils/audio-recorder'
import { callNative } from '@/utils/native-bridge'
import { backendService } from '@/api/backend'

const appStore = useAppStore()
const settingsStore = useSettingsStore()
const audioStore = useAudioStore()
const { deviceState, currentText, currentEmotion, chatHistory, errorMessage, isWakeWordMonitoring, isBackendConnected, wakeWordAutoMonitor, isConnecting, isStarting } = storeToRefs(appStore)
const { wakeWordEnabled, aecEnabled } = storeToRefs(settingsStore)
const { volumeLevel: audioVolume } = storeToRefs(audioStore)

const inputText = ref('')
const scrollTop = ref(0)
let _scrollCounter = 0

/** 滚动到底部（递增数值确保每次都触发） */
function scrollToBottom() {
  nextTick(() => {
    _scrollCounter++
    scrollTop.value = _scrollCounter * 100
  })
}

/** scroll-view 滚动事件（未使用，预留） */
function onChatScroll(_e: any) {}

// renderjs 录音桥接 — 通过改变 prop 触发 renderjs 开始/停止录音
const renderjsProp = ref({ action: 'none', timestamp: 0 })

// AEC 配置传递给 renderjs
const aecProp = computed(() => ({ enabled: aecEnabled.value }))

// 绑定 renderjs 控制方法到 audioRecorder（让 audioRecorder 可以触发 renderjs）
onMounted(() => {
  // #ifdef APP
  audioRecorder.bindRenderjsControl({
    startRecording: () => {
      renderjsProp.value = { action: 'start', timestamp: Date.now() }
    },
    stopRecording: () => {
      renderjsProp.value = { action: 'stop', timestamp: Date.now() }
    },
  })
  // #endif
})

// 同步 AEC 配置到 AudioRecorder 静态属性
watch(aecEnabled, (val) => {
  AudioRecorder.aecEnabled = val
}, { immediate: true })

watch(() => chatHistory.value.length, () => {
  scrollToBottom()
})

// 监听 currentText 变化也滚动到底部（实时识别中）
watch(currentText, () => {
  scrollToBottom()
})

function handleStart() {
  appStore.startListening()
}
function goToSettings() {
  uni.navigateTo({ url: '/pages/tab/settings/index' })
}
function handleStop() {
  appStore.stopListening()
}
function handleAbort() {
  appStore.abortSpeaking()
}
function handleStartMonitoring() {
  appStore.startWakeWordMonitoring()
}
function handleStopMonitoring() {
  appStore.stopWakeWordMonitoring()
}
function handleToggleWakeWord(e: any) {
  const checked = e.detail.value
  if (checked) {
    appStore.startWakeWordMonitoring()
  }
  else {
    appStore.stopWakeWordMonitoring()
  }
}
function handleSendText() {
  const text = inputText.value.trim()
  if (!text)
    return
  appStore.sendText(text)
  inputText.value = ''
}

/** 快捷发送（空状态引导标签点击） */
function handleQuickSend(text: string) {
  appStore.sendText(text)
}

/** 拍照并分析 */
async function handleTakePhoto() {
  try {
    const result = await callNative('take_photo') as any
    if (!result?.image_data) {
      appStore.errorMessage = '拍照失败：未获取到图片'
      return
    }

    // 发送到后端分析
    const response = await backendService.httpPost('/api/analyze_photo', {
      image_data: result.image_data,
      question: '描述这张照片的内容',
    })

    if (response?.success !== false) {
      const analysisText = response?.result
        ? (typeof response.result === 'string' ? response.result : JSON.stringify(response.result))
        : '分析完成'
      // 将分析结果显示为 AI 消息（chatHistory 已在 setup 顶层解构）
      chatHistory.value = [...chatHistory.value, {
        text: '📷 ' + analysisText,
        isUser: false,
        timestamp: Date.now(),
      }]
    }
    else {
      appStore.errorMessage = response?.message || '分析失败'
    }
  }
  catch (e: any) {
    console.warn('[Home] 拍照分析失败:', e)
    appStore.errorMessage = `拍照失败: ${e.message || '未知错误'}`
  }
}
</script>

<!-- #ifdef APP -->
<script module="recorderRenderjs" lang="renderjs">
/**
 * renderjs 录音模块 — 在 WebView 中直接调用 getUserMedia + recorder-core
 *
 * 通信方式:
 *   逻辑层改变 renderjsProp → 触发 onPropChange → 开始/停止录音
 *   录音数据通过 $ownerInstance.callMethod 传回逻辑层
 */
import Recorder from 'recorder-core'

let rec = null
let stream = null
let aecEnabled = true  // AEC 配置，由逻辑层传入

export default {
  data() {
    return { pcmCount: 0 }
  },
  mounted() {
    console.log('[renderjs] 录音模块已挂载, Recorder=' + typeof Recorder)
  },
  methods: {
    /** AEC 配置变化回调 */
    onAecChange(newVal) {
      if (newVal && typeof newVal.enabled === 'boolean') {
        aecEnabled = newVal.enabled
        console.log('[renderjs] AEC 配置更新:', aecEnabled)
      }
    },
    /**
     * prop 变化回调 — 逻辑层通过改变 renderjsProp 触发
     * @param newVal { action: 'start' | 'stop' | 'none', timestamp: number }
     */
    onPropChange(newVal) {
      if (!newVal || !newVal.action) return

      console.log('[renderjs] 收到指令:', JSON.stringify(newVal))

      if (newVal.action === 'start') {
        this.startRecording()
      }
      else if (newVal.action === 'stop') {
        this.stopRecording()
      }
    },

    /** 开始录音 */
    async startRecording() {
      // 如果已在录音先停止
      this.stopRecording()

      console.log('[renderjs] 开始录音...')
      this.pcmCount = 0

      try {
        // 请求麦克风权限
        stream = await navigator.mediaDevices.getUserMedia({
          audio: {
            sampleRate: 16000,
            channelCount: 1,
            echoCancellation: aecEnabled,
            noiseSuppression: aecEnabled,
          },
        })
        console.log('[renderjs] getUserMedia 成功, tracks=' + stream.getAudioTracks().length)

        // 使用 recorder-core 创建录音器
        // Recorder 构造函数来自 import (recorder-core 挂载到 window.Recorder)
        var RecClass = Recorder || window.Recorder
        if (!RecClass) {
          throw new Error('Recorder 未加载')
        }

        var self = this
        rec = RecClass({
          type: 'pcm',
          sampleRate: 16000,
          bitRate: 16,
          channelCount: 1,
          stream: stream,
          onProcess: function(buffers, powerLevel, duration, sampleRate, newBufferIdx) {
            // 取最新一帧 PCM 数据 (Float32 buffer)
            if (newBufferIdx <= 0) return

            var buf = buffers[newBufferIdx - 1]
            if (!buf || buf.length === 0) return

            // Float32 → 降采样 48kHz→16kHz → Int16 → 十六进制字符串
            // ★ recorder-core onProcess 提供的是 48kHz 原始数据（非降采样后）
            // ★ WebM.PCM 模式输出的值是 Int16 量级，不是 [-1, 1]
            // ★ 用十六进制编码代替 Base64，彻底避免 btoa/atob 跨引擎兼容问题
            var float32 = buf instanceof Float32Array ? buf : new Float32Array(buf)

            // 动态降采样：基于 onProcess 传入的实际采样率
            var targetRate = 16000
            var step = Math.max(1, Math.round(sampleRate / targetRate))
            var outLen = Math.floor(float32.length / step)
            var int16 = new Int16Array(outLen)
            for (var i = 0; i < outLen; i++) {
              var s = float32[i * step]
              int16[i] = s < -32768 ? -32768 : (s > 32767 ? 32767 : Math.round(s))
            }

            // Int16 → 十六进制字符串（每个样本 4 个 hex 字符）
            var hex = ''
            for (var h = 0; h < int16.length; h++) {
              var val = int16[h] & 0xFFFF
              hex += (val < 16 ? '000' : val < 256 ? '00' : val < 4096 ? '0' : '') + val.toString(16)
            }

            self.pcmCount++
            if (self.pcmCount <= 3) {
              var dbg = ''
              for (var d = 0; d < Math.min(5, int16.length); d++) {
                dbg += int16[d] + ' '
              }
              console.log('[renderjs] PCM #' + self.pcmCount + ', outSamples=' + outLen + ', int16前5=[' + dbg + ']')
            }

            // 十六进制字符串传回逻辑层
            self.$ownerInstance.callMethod('onPcmFrameFromRenderjs', {
              hex: hex,
              sampleCount: outLen,
            })
          },
        })

        rec.open(function() {
          rec.start()
          console.log('[renderjs] 录音已启动')
          self.$ownerInstance.callMethod('onRenderjsReady', {})
        }, function(msg) {
          console.error('[renderjs] 录音打开失败: ' + msg)
          self.$ownerInstance.callMethod('onRenderjsError', { msg: String(msg) })
        })
      }
      catch (err) {
        var errMsg = err && err.message ? err.message : String(err)
        console.error('[renderjs] 启动失败: ' + errMsg)
        this.$ownerInstance.callMethod('onRenderjsError', { msg: errMsg })
      }
    },

    /** 停止录音 */
    stopRecording() {
      console.log('[renderjs] 停止录音')
      if (rec) {
        try { rec.stop(); rec.close() } catch (_) {}
        rec = null
      }
      if (stream) {
        stream.getTracks().forEach(function(t) { t.stop() })
        stream = null
      }
    },
  },
}
</script>
<!-- #endif -->

<style scoped lang="scss">
.container {
  display: flex;
  flex-direction: column;
  height: 100%;
  width: 100%;
  overflow: hidden;
  background-color: var(--theme-bg-color);
}

.top-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
  flex-shrink: 0;
}

.top-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.settings-btn {
  padding: 8px;
}

.settings-icon {
  font-size: 24px;
  color: var(--theme-content-color);
}

.chat-area {
  flex: 1;
  padding: 16px;
  overflow: hidden;
  width: 100%;
}

.chat-messages {
  display: flex;
  flex-direction: column;
}

.empty-hint {
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  padding-top: 80px;
}

.empty-text {
  color: var(--theme-tips-color);
  font-size: 16px;
  margin-bottom: 24px;
}

.hint-tags {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 10px;
  padding: 0 16px;
}

.hint-tag {
  padding: 8px 14px;
  background-color: var(--theme-bg-card);
  border-radius: 20px;
  color: var(--theme-content-color);
  font-size: 13px;
  border: 1px solid var(--theme-border-color);
}

.current-text {
  padding: 8px 16px;
  background-color: var(--theme-bg-card);
}

.current-text-content {
  color: var(--theme-main-color);
  font-size: 14px;
}

.error-bar {
  display: flex;
  align-items: center;
  padding: 8px 16px;
  background-color: rgba(239, 83, 80, 0.15);
}

.error-text {
  flex: 1;
  color: var(--theme-error);
  font-size: 13px;
}

.error-close {
  color: var(--theme-error);
  padding: 4px 8px;
  font-size: 16px;
}

.control-bar {
  padding: 12px 16px;
  padding-bottom: calc(12px + env(safe-area-inset-bottom, 12px));
  background-color: var(--theme-bg-color-secondary);
  border-top: 1px solid var(--theme-border-color);
  width: 100%;
  flex-shrink: 0;
}

.btn-primary,
.btn-listening,
.btn-speaking,
.btn-connecting,
.btn-monitoring,
.btn-wake-word {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 48px;
  border-radius: 24px;
  margin-bottom: 12px;
}

.btn-primary {
  background-color: var(--theme-primary);
}

.btn-listening {
  background-color: var(--theme-success);
}

.btn-speaking {
  background-color: var(--theme-warning);
}

.btn-connecting {
  background-color: var(--theme-tips-color);
  animation: pulse-green 2s infinite;
}

.btn-monitoring {
  background-color: var(--theme-success);
  animation: pulse-green 2s infinite;
}

.btn-wake-word {
  background-color: var(--theme-success);
}

.wake-word-toggle {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 8px;
  margin-bottom: 12px;
  height: 40px;
  background-color: var(--theme-bg-card);
  border-radius: 20px;
}

.toggle-label {
  color: var(--theme-content-color);
  font-size: 14px;
  padding-left: 8px;
}

@keyframes pulse-green {
  0% {
    opacity: 1;
  }
  50% {
    opacity: 0.7;
  }
  100% {
    opacity: 1;
  }
}

.btn-text {
  color: #fff;
  font-size: 16px;
  font-weight: 600;
}

.input-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.text-input {
  flex: 1;
  height: 40px;
  padding: 0 12px;
  background-color: var(--theme-border-color);
  border-radius: 20px;
  color: var(--theme-main-color);
  font-size: 14px;
}

.input-placeholder {
  color: var(--theme-tips-color);
}

.btn-camera {
  display: flex;
  justify-content: center;
  align-items: center;
  width: 40px;
  height: 40px;
  background-color: var(--theme-bg-card);
  border-radius: 20px;
  border: 1px solid var(--theme-border-color);
  flex-shrink: 0;
}

.camera-icon {
  font-size: 20px;
}

.btn-send {
  display: flex;
  justify-content: center;
  align-items: center;
  width: 60px;
  height: 40px;
  background-color: var(--theme-primary);
  border-radius: 20px;
}

.btn-send-text {
  color: #fff;
  font-size: 14px;
  font-weight: 600;
}

.renderjs-bridge {
  height: 0;
  width: 0;
  overflow: hidden;
}
</style>
