/**
 * 应用全局状态
 */
import { backendService } from '@/api/backend'
import { AudioPlayer } from '@/utils/audio-player'
import { audioRecorder } from '@/utils/audio-recorder'
import { initNativeBridge } from '@/utils/native-bridge'
import { useSettingsStore } from '@/store'
import { useAudioStore } from '@/store'

/** 聊天历史默认最大条数（会被 settings store 覆盖） */
const DEFAULT_CHAT_HISTORY_LIMIT = 200
/** 持久化存储 key */
const CHAT_HISTORY_KEY = 'xiaozhi_chat_history'

export const useAppStore = defineStore('app', () => {
  // ========== 状态 ==========
  const deviceState = ref<string>('IDLE')
  const listeningMode = ref<string>('REALTIME')
  const isConnected = ref(false)
  const isBackendConnected = ref(false)
  const errorMessage = ref<string | null>(null)
  let _errorTimer: ReturnType<typeof setTimeout> | null = null
  /** 设置错误消息（5秒自动消失） */
  function showError(msg: string) {
    errorMessage.value = msg
    if (_errorTimer) clearTimeout(_errorTimer)
    _errorTimer = setTimeout(() => {
      errorMessage.value = null
    }, 5000)
  }
  const currentText = ref<string>('')
  const currentEmotion = ref<string>('neutral')
  const chatHistory = ref<Array<{ text: string; isUser: boolean; timestamp: number }>>([])

  // ========== 加载状态 ==========
  /** 是否正在连接服务器 */
  const isConnecting = ref(false)
  /** 是否正在启动对话 */
  const isStarting = ref(false)

  // ========== 唤醒词持续监听 ==========
  const isWakeWordMonitoring = ref(false)
  /** 开关：开启后对话结束自动恢复唤醒词监听 */
  const wakeWordAutoMonitor = ref(false)

  // ========== 播放模式 ==========
  const audioPlaybackMode = ref<string>('backend')

  // ========== 音频实例 ==========
  const audioPlayer = new AudioPlayer()
  // audioRecorder 来自全局单例，页面 renderjs 绑定在同一个实例上

  // 将 AudioPlayer 的音量数据同步到 audioStore（供 AudioWave 消费）
  audioPlayer.onVolume((volume: number) => {
    try {
      const audioStore = useAudioStore()
      audioStore.setVolume(volume)
      audioStore.setPlaying(volume > 0 || audioPlayer.isPlaying)
    }
    catch (_) {}
  })

  /** 获取当前聊天历史限制条数（从 settings 读取） */
  function getChatLimit(): number {
    try {
      const settingsStore = useSettingsStore()
      return settingsStore.chatHistoryLimit || DEFAULT_CHAT_HISTORY_LIMIT
    }
    catch (_) {
      return DEFAULT_CHAT_HISTORY_LIMIT
    }
  }

  // ========== 持久化：加载聊天历史 ==========
  try {
    const saved = uni.getStorageSync(CHAT_HISTORY_KEY)
    if (saved) {
      const parsed = JSON.parse(saved)
      if (Array.isArray(parsed)) {
        chatHistory.value = parsed.slice(-getChatLimit())
      }
    }
  }
  catch (_) {
    // 存储读取失败，使用空历史
  }

  /** 持久化聊天历史到本地存储（防抖） */
  let _saveTimer: ReturnType<typeof setTimeout> | null = null
  function persistChatHistory() {
    if (_saveTimer) clearTimeout(_saveTimer)
    _saveTimer = setTimeout(() => {
      try {
        const data = chatHistory.value.slice(-getChatLimit())
        uni.setStorageSync(CHAT_HISTORY_KEY, JSON.stringify(data))
      }
      catch (_) {
        // 存储写入失败（空间不足等），静默忽略
      }
    }, 500)
  }

  /** 清除聊天历史 */
  function clearChatHistory() {
    chatHistory.value = []
    try {
      uni.removeStorageSync(CHAT_HISTORY_KEY)
    }
    catch (_) {}
  }

  // 监听 chatHistory 变化自动持久化（防抖 500ms）
  watch(() => chatHistory.value.length, () => {
    persistChatHistory()
  })

  /** 播放模式变更时同步 AudioPlayer */
  function updatePlaybackMode(mode: string) {
    audioPlaybackMode.value = mode
    if (mode === 'frontend') {
      audioPlayer.ensureContext()
    }
  }

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
        // 同步播放模式
        if (settingsStore.audioPlaybackMode) {
          updatePlaybackMode(settingsStore.audioPlaybackMode)
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
      showError('无法连接到后端服务')
    }
  }

  /** 注册所有后端事件监听 */
  function _registerEventListeners() {
    // ★ 注册原生桥接：监听后端下发的 native_call_request 事件
    initNativeBridge()

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

      // IDLE 时停止音频播放并重置状态
      if (newState === 'IDLE') {
        audioPlayer.stop()
        currentEmotion.value = 'neutral'
        currentText.value = ''

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
      console.log('[AppStore] text_response:', JSON.stringify(data))

      // STT 语音识别结果 → 添加为用户消息
      if (data.source === 'stt') {
        // 小智协议 STT 不区分中间/最终，收到即添加
        // 避免重复：如果最后一条用户消息文字相同则跳过
        const last = chatHistory.value[chatHistory.value.length - 1]
        if (last && last.isUser && last.text === data.text) {
          return
        }
        const msg = { text: data.text, isUser: true, timestamp: Date.now() }
        chatHistory.value = [...chatHistory.value, msg]
        // 限制聊天历史长度
        if (chatHistory.value.length > getChatLimit()) {
          chatHistory.value = chatHistory.value.slice(-getChatLimit())
        }
        return
      }

      // TTS / LLM 回复 → 添加为 AI 消息
      const isFinal = data.is_final !== false
      if (isFinal) {
        const msg = { text: data.text, isUser: false, timestamp: Date.now() }
        chatHistory.value = [...chatHistory.value, msg]
      }
      // 限制聊天历史长度
      if (chatHistory.value.length > getChatLimit()) {
        chatHistory.value = chatHistory.value.slice(-getChatLimit())
      }
    })

    backendService.on('emotion', (data: any) => {
      currentEmotion.value = data.emotion || 'neutral'
    })

    backendService.on('error', (data: any) => {
      showError(data.message || '未知错误')
    })

    // 唤醒词检测事件: 后端检测到唤醒词后自动开始对话
    backendService.on('wake_word_detected', (_data: any) => {
      isWakeWordMonitoring.value = false
      // 后端已自动连接服务器并开始对话，刷新状态即可
      _fetchStatus()
    })

    // ★ 前端播放模式：接收后端推送的 PCM 音频并播放
    backendService.on('audio', (pcmData: ArrayBuffer) => {
      if (audioPlaybackMode.value === 'frontend') {
        audioPlayer.play(pcmData)
      }
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
    if (isStarting.value) return
    isStarting.value = true
    errorMessage.value = null
    try {
      // 前端播放模式：在用户点击时初始化 AudioContext（浏览器自动播放策略）
      if (audioPlaybackMode.value === 'frontend') {
        audioPlayer.ensureContext()
      }

      // 未连接服务器时自动连接（超时 15 秒）
      if (!isConnected.value) {
        isConnecting.value = true
        try {
          await backendService.sendCommand('connect')
          isConnecting.value = false
        }
        catch (connErr: any) {
          isConnecting.value = false
          showError('连接服务器失败，请检查网络后重试')
          return
        }
      }

      // 先通知后端进入 LISTENING 状态，再启动麦克风
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
        showError(`录音失败: ${e.message || '请检查权限'}`)
      }
    }
    catch (e: any) {
      showError(e.message || '启动监听失败')
    }
    finally {
      isStarting.value = false
    }
  }

  async function stopListening() {
    try {
      audioRecorder.stop()
      await backendService.sendCommand('stop_listening')
    }
    catch (e: any) {
      showError(e.message || '停止监听失败')
    }
  }

  async function abortSpeaking() {
    try {
      audioPlayer.stop()
      audioRecorder.stop()
      await backendService.sendCommand('abort_speaking')
    }
    catch (e: any) {
      showError(e.message || '中止失败')
    }
  }

  async function sendText(text: string) {
    if (!text.trim())
      return
    const msg = {
      text: text.trim(),
      isUser: true,
      timestamp: Date.now(),
    }
    // 重新赋值数组，确保 uni-app 响应式更新（push 可能不触发渲染）
    chatHistory.value = [...chatHistory.value, msg]
    // 限制聊天历史长度
    if (chatHistory.value.length > getChatLimit()) {
      chatHistory.value = chatHistory.value.slice(-getChatLimit())
    }
    try {
      // 未连接服务器时自动连接
      if (!isConnected.value) {
        isConnecting.value = true
        try {
          await backendService.sendCommand('connect')
        }
        catch (connErr: any) {
          isConnecting.value = false
          showError('连接服务器失败，请检查网络后重试')
          return
        }
        isConnecting.value = false
      }
      await backendService.sendCommand('send_text', { text: text.trim() })
    }
    catch (e: any) {
      showError(e.message || '发送失败')
    }
  }

  async function connectServer() {
    try {
      await backendService.sendCommand('connect')
    }
    catch (e: any) {
      showError(e.message || '连接服务器失败')
    }
  }

  async function disconnectServer() {
    try {
      await backendService.sendCommand('disconnect_server')
    }
    catch (e: any) {
      showError(e.message || '断开失败')
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
        showError(`麦克风启动失败: ${e.message || '请检查权限'}`)
        await backendService.sendCommand('stop_wake_word_monitoring')
        return
      }
      isWakeWordMonitoring.value = true
      wakeWordAutoMonitor.value = true
    }
    catch (e: any) {
      showError(e.message || '启动唤醒词监听失败')
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
      showError(e.message || '停止监听失败')
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
    clearChatHistory,
    isConnecting,
    isStarting,
    isWakeWordMonitoring,
    wakeWordAutoMonitor,
    audioPlaybackMode,
    updatePlaybackMode,
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
