/**
 * 应用全局状态
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { backendService } from '@/services/backend'

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

  // ========== 连接 ==========

  /** 连接后端 */
  async function connectBackend() {
    try {
      // 注册事件监听
      _registerEventListeners()
      await backendService.connect()
    } catch (e) {
      console.error('[AppStore] 连接后端失败:', e)
      errorMessage.value = '无法连接到后端服务'
    }
  }

  /** 注册所有后端事件监听 */
  function _registerEventListeners() {
    // 后端连接状态
    backendService.on('backend_connected', () => {
      isBackendConnected.value = true
      errorMessage.value = null
      // 连接后拉取状态
      _fetchStatus()
    })

    backendService.on('backend_disconnected', () => {
      isBackendConnected.value = false
    })

    // 设备状态变更
    backendService.on('state_change', (data: any) => {
      deviceState.value = (data.state || '').toUpperCase()
    })

    // 服务器连接状态
    backendService.on('connection_status', (data: any) => {
      isConnected.value = data.connected
    })

    // 文本回复（仅 TTS/AI 回复加入聊天记录）
    backendService.on('text_response', (data: any) => {
      if (!data.text) return
      currentText.value = data.text
      // STT 来源不加入聊天记录（用户输入已由 sendText 处理）
      if (data.source === 'stt') return
      const isFinal = data.is_final !== false
      if (isFinal) {
        chatHistory.value.push({
          text: data.text,
          isUser: false,
          timestamp: Date.now(),
        })
      }
    })

    // 情绪
    backendService.on('emotion', (data: any) => {
      currentEmotion.value = data.emotion || 'neutral'
    })

    // 错误
    backendService.on('error', (data: any) => {
      errorMessage.value = data.message
    })
  }

  /** 拉取后端状态 */
  async function _fetchStatus() {
    try {
      const status = await backendService.httpGet('/api/status')
      deviceState.value = (status.device_state || 'IDLE').toUpperCase()
      listeningMode.value = (status.listening_mode || 'REALTIME').toUpperCase()
      isConnected.value = status.connected
    } catch (e) {
      console.warn('[AppStore] 获取状态失败:', e)
    }
  }

  // ========== 操作 ==========

  /** 开始监听 */
  async function startListening(mode?: string) {
    try {
      errorMessage.value = null
      await backendService.sendCommand('start_listening', { mode: mode || listeningMode.value })
    } catch (e: any) {
      errorMessage.value = e.message || '启动监听失败'
    }
  }

  /** 停止监听 */
  async function stopListening() {
    try {
      await backendService.sendCommand('stop_listening')
    } catch (e: any) {
      errorMessage.value = e.message || '停止监听失败'
    }
  }

  /** 中止说话 */
  async function abortSpeaking() {
    try {
      await backendService.sendCommand('abort_speaking')
    } catch (e: any) {
      errorMessage.value = e.message || '中止失败'
    }
  }

  /** 发送文本消息 */
  async function sendText(text: string) {
    if (!text.trim()) return
    chatHistory.value.push({
      text: text.trim(),
      isUser: true,
      timestamp: Date.now(),
    })
    try {
      await backendService.sendCommand('send_text', { text: text.trim() })
    } catch (e: any) {
      errorMessage.value = e.message || '发送失败'
    }
  }

  /** 连接服务器 */
  async function connectServer() {
    try {
      await backendService.sendCommand('connect_server')
    } catch (e: any) {
      errorMessage.value = e.message || '连接服务器失败'
    }
  }

  /** 断开服务器 */
  async function disconnectServer() {
    try {
      await backendService.sendCommand('disconnect_server')
    } catch (e: any) {
      errorMessage.value = e.message || '断开失败'
    }
  }

  return {
    // 状态
    deviceState,
    listeningMode,
    isConnected,
    isBackendConnected,
    errorMessage,
    currentText,
    currentEmotion,
    chatHistory,
    // 操作
    connectBackend,
    startListening,
    stopListening,
    abortSpeaking,
    sendText,
    connectServer,
    disconnectServer,
  }
})
