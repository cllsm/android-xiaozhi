/**
 * 应用全局状态
 */
import { backendService } from '@/api/backend'
import { AudioPlayer } from '@/utils/audio-player'
import { audioRecorder } from '@/utils/audio-recorder'
import { useSettingsStore } from '@/store'

export const useAppStore = defineStore('app', () => {
  // ========== 状态 ==========
  const deviceState = ref<string>('IDLE')
  const listeningMode = ref<string>('REALTIME')
  const isConnected = ref(false)
  const isBackendConnected = ref(false)
  const errorMessage = ref<string | null>(null)
  const currentText = ref<string>('')
  const currentEmotion = ref<string>('neutral')
  const chatHistory = ref<Array<{ text: string; isUser: boolean; timestamp: number }>>([])

  // ========== 唤醒词持续监听 ==========
  const isWakeWordMonitoring = ref(false)
  /** 开关：开启后对话结束自动恢复唤醒词监听 */
  const wakeWordAutoMonitor = ref(false)

  // ========== 音频实例 ==========
  const audioPlayer = new AudioPlayer()
  // audioRecorder 来自全局单例，页面 renderjs 绑定在同一个实例上

  // ========== 连接 ==========

  /** 连接后端 */
  async function connectBackend() {
    try {
      // 同步 settings 中的后端地址到 BackendService
      try {
        const settingsStore = useSettingsStore()
        if (settingsStore.backendHost) {
          backendService.setAppBackendHost(settingsStore.backendHost)
        }
      }
      catch {
        // settings store 未初始化，使用默认地址
      }

      _registerEventListeners()
      await backendService.connect()
    }
    catch (e) {
      console.error('[AppStore] 连接后端失败:', e)
      errorMessage.value = '无法连接到后端服务'
    }
  }

  /** 注册所有后端事件监听 */
  function _registerEventListeners() {
    backendService.on('backend_connected', () => {
      isBackendConnected.value = true
      errorMessage.value = null
      _fetchStatus()
    })

    backendService.on('backend_disconnected', () => {
      isBackendConnected.value = false
    })

    backendService.on('state_change', (data: any) => {
      const newState = (data.state || '').toUpperCase()
      deviceState.value = newState

      // IDLE 时停止音频播放
      if (newState === 'IDLE') {
        audioPlayer.stop()

        // ★ 自动恢复唤醒词监听：对话结束后如果开关开启，自动重新开始监听
        if (wakeWordAutoMonitor.value && !isWakeWordMonitoring.value && isBackendConnected.value) {
          console.log('[AppStore] 对话结束，自动恢复唤醒词监听')
          startWakeWordMonitoring()
        }
      }
    })

    backendService.on('connection_status', (data: any) => {
      isConnected.value = data.connected
    })

    backendService.on('text_response', (data: any) => {
      if (!data.text)
        return
      currentText.value = data.text
      // STT 最终识别结果 → 显示为用户消息
      if (data.source === 'stt') {
        if (data.is_final) {
          chatHistory.value.push({
            text: data.text,
            isUser: true,
            timestamp: Date.now(),
          })
        }
        return
      }
      const isFinal = data.is_final !== false
      if (isFinal) {
        chatHistory.value.push({
          text: data.text,
          isUser: false,
          timestamp: Date.now(),
        })
      }
    })

    backendService.on('emotion', (data: any) => {
      currentEmotion.value = data.emotion || 'neutral'
    })

    backendService.on('error', (data: any) => {
      errorMessage.value = data.message
    })

    // 唤醒词检测事件: 后端检测到唤醒词后自动开始对话
    backendService.on('wake_word_detected', (_data: any) => {
      isWakeWordMonitoring.value = false
      // 后端已自动连接服务器并开始对话，刷新状态即可
      _fetchStatus()
    })

    // 后端播放模式：音频由后端 sounddevice 播放，前端不播放
    // 保留监听用于调试
    backendService.on('audio', (_pcmData: ArrayBuffer) => {
      // no-op: 音频由后端统一播放
    })
  }

  /** 拉取后端状态 */
  async function _fetchStatus() {
    try {
      const status = await backendService.httpGet('/api/status')
      deviceState.value = (status.device_state || 'IDLE').toUpperCase()
      listeningMode.value = (status.listening_mode || 'REALTIME').toUpperCase()
      isConnected.value = status.connected
    }
    catch (e) {
      console.warn('[AppStore] 获取状态失败:', e)
    }
  }

  // ========== 操作 ==========

  async function startListening(mode?: string) {
    try {
      errorMessage.value = null

      // 先通知后端进入 LISTENING 状态，再启动麦克风
      // 避免音频在后端 LISTENING 之前发送导致丢帧
      await backendService.sendCommand('start_listening', { mode: mode || listeningMode.value })

      // 启动麦克风录音
      try {
        await audioRecorder.start((pcmData: ArrayBuffer) => {
          backendService.sendAudio(pcmData)
        })
        console.log(`[AppStore] 录音方案: ${audioRecorder.method}`)
      }
      catch (e: any) {
        console.warn('[AppStore] 麦克风录音启动失败:', e)
        errorMessage.value = `录音失败: ${e.message || '请检查权限'}`
      }
    }
    catch (e: any) {
      errorMessage.value = e.message || '启动监听失败'
    }
  }

  async function stopListening() {
    try {
      audioRecorder.stop()
      await backendService.sendCommand('stop_listening')
    }
    catch (e: any) {
      errorMessage.value = e.message || '停止监听失败'
    }
  }

  async function abortSpeaking() {
    try {
      audioPlayer.stop()
      audioRecorder.stop()
      await backendService.sendCommand('abort_speaking')
    }
    catch (e: any) {
      errorMessage.value = e.message || '中止失败'
    }
  }

  async function sendText(text: string) {
    if (!text.trim())
      return
    chatHistory.value.push({
      text: text.trim(),
      isUser: true,
      timestamp: Date.now(),
    })
    try {
      await backendService.sendCommand('send_text', { text: text.trim() })
    }
    catch (e: any) {
      errorMessage.value = e.message || '发送失败'
    }
  }

  async function connectServer() {
    try {
      await backendService.sendCommand('connect_server')
    }
    catch (e: any) {
      errorMessage.value = e.message || '连接服务器失败'
    }
  }

  async function disconnectServer() {
    try {
      await backendService.sendCommand('disconnect_server')
    }
    catch (e: any) {
      errorMessage.value = e.message || '断开失败'
    }
  }

  // ========== 唤醒词持续监听 ==========

  /** 启动唤醒词持续监听模式 */
  async function startWakeWordMonitoring() {
    try {
      errorMessage.value = null
      audioRecorder.stop()
      await backendService.sendCommand('start_wake_word_monitoring')
      try {
        await audioRecorder.start((pcmData: ArrayBuffer) => {
          backendService.sendAudio(pcmData)
        })
        console.log(`[AppStore] 录音方案: ${audioRecorder.method}`)
      }
      catch (e: any) {
        console.warn('[AppStore] 唤醒词监听录音启动失败:', e)
        errorMessage.value = `麦克风启动失败: ${e.message || '请检查权限'}`
        await backendService.sendCommand('stop_wake_word_monitoring')
        return
      }
      isWakeWordMonitoring.value = true
      wakeWordAutoMonitor.value = true
    }
    catch (e: any) {
      errorMessage.value = e.message || '启动唤醒词监听失败'
    }
  }

  /** 停止唤醒词持续监听模式 */
  async function stopWakeWordMonitoring() {
    // 用户手动停止 → 同时关闭自动监听开关
    wakeWordAutoMonitor.value = false
    try {
      audioRecorder.stop()
      await backendService.sendCommand('stop_wake_word_monitoring')
    }
    catch (e: any) {
      errorMessage.value = e.message || '停止监听失败'
    }
    finally {
      isWakeWordMonitoring.value = false
    }
  }

  return {
    deviceState,
    listeningMode,
    isConnected,
    isBackendConnected,
    errorMessage,
    currentText,
    currentEmotion,
    chatHistory,
    isWakeWordMonitoring,
    wakeWordAutoMonitor,
    connectBackend,
    startListening,
    stopListening,
    abortSpeaking,
    sendText,
    connectServer,
    disconnectServer,
    startWakeWordMonitoring,
    stopWakeWordMonitoring,
  }
})
