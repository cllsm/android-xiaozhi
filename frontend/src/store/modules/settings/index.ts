/**
 * 设置状态管理
 *
 * 对比 py-xiaozhi 桌面端的 6 个设置 Tab，
 * 补齐唤醒词文本自定义、AEC 开关、MQTT 详细配置、音乐设置等。
 */
import { backendService } from '@/api/backend'

/** 检测文本是否包含中文 */
function hasChinese(text: string): boolean {
  return /[一-鿿]/.test(text)
}

export const useSettingsStore = defineStore('settings', () => {
  // ==================== 网络 ====================
  const protocol = ref('websocket')
  const websocketUrl = ref('wss://api.xiaozhi.com/ws')
  const websocketAccessToken = ref('')
  const mqttBroker = ref('')
  const mqttUsername = ref('')
  const mqttPassword = ref('')
  const mqttPublishTopic = ref('')
  const mqttSubscribeTopic = ref('')

  // ==================== 唤醒词 ====================
  const wakeWordEnabled = ref(true)
  const wakeWordText = ref('你好小智')
  const wakeWordSensitivity = ref(0.25)
  const keywordsScore = ref(1.8)
  const keywordsThreshold = ref(0.25)

  /** 语言标签（只读，自动检测） */
  const wakeWordLang = computed(() => {
    const text = wakeWordText.value
    if (!text) return ''
    return hasChinese(text) ? '中文' : 'English'
  })

  // ==================== 音频 ====================
  const aecEnabled = ref(true)
  const opusOutputSampleRate = ref(24000)
  /** 播放模式：backend（后端 sounddevice 播放）| frontend（前端 Web Audio API 播放） */
  const audioPlaybackMode = ref('backend')

  // ==================== 音乐 ====================
  const musicSearchUrl = ref('')
  const musicUrlApi = ref('')
  const musicUrlApiKey = ref('')
  const musicDefaultQuality = ref('320k')

  // ==================== 后端服务 ====================
  const backendHost = ref('127.0.0.1')

  // ==================== 应用行为 ====================
  /** 聊天历史最大保留条数 */
  const chatHistoryLimit = ref(200)
  /** 首次连接自动重试次数 */
  const connectRetryCount = ref(5)
  /** 首次连接重试开关 */
  const connectRetryEnabled = ref(true)

  // ==================== 外观 ====================
  /** 主题：dark（默认） | light */
  const theme = ref('dark')

  // ==================== 悬浮窗 ====================
  /** 悬浮窗开关 */
  const overlayEnabled = ref(false)

  /** 应用主题到 DOM */
  function setTheme(newTheme: string) {
    theme.value = newTheme
    applyTheme(newTheme)
    // 持久化到本地
    try {
      uni.setStorageSync('xiaozhi_theme', newTheme)
    }
    catch (_) {}
  }

  /** 将主题应用到 page 元素 */
  function applyTheme(t: string) {
    // #ifdef H5
    document.documentElement.setAttribute('data-theme', t)
    // #endif
    // #ifndef H5
    // App 端通过 page 的 style 设置
    try {
      const pages = getCurrentPages()
      if (pages.length > 0) {
        const page = pages[pages.length - 1] as any
        page.$page?.setStyle?.({}) // 触发重渲染
      }
    }
    catch (_) {}
    // #endif
  }

  // ==================== 内部状态 ====================
  const loaded = ref(false)

  /** 从后端加载设置 */
  async function loadSettings() {
    try {
      const config = await backendService.httpGet('/api/config')

      // 网络
      if (config.SYSTEM_OPTIONS?.NETWORK) {
        const net = config.SYSTEM_OPTIONS.NETWORK
        protocol.value = net.PROTOCOL || protocol.value
        websocketUrl.value = net.WEBSOCKET_URL || websocketUrl.value
        websocketAccessToken.value = net.WEBSOCKET_ACCESS_TOKEN || ''
        if (net.MQTT_INFO) {
          mqttBroker.value = net.MQTT_INFO.endpoint || net.MQTT_INFO.broker || ''
          mqttUsername.value = net.MQTT_INFO.username || ''
          mqttPassword.value = net.MQTT_INFO.password || ''
          mqttPublishTopic.value = net.MQTT_INFO.publish_topic || ''
          mqttSubscribeTopic.value = net.MQTT_INFO.subscribe_topic || ''
        }
      }

      // 唤醒词
      if (config.WAKE_WORD_OPTIONS) {
        const ww = config.WAKE_WORD_OPTIONS
        wakeWordEnabled.value = ww.USE_WAKE_WORD ?? wakeWordEnabled.value
        wakeWordText.value = ww.WAKE_WORD || wakeWordText.value
        wakeWordSensitivity.value = ww.KEYWORDS_THRESHOLD ?? wakeWordSensitivity.value
        keywordsScore.value = ww.KEYWORDS_SCORE ?? keywordsScore.value
        keywordsThreshold.value = ww.KEYWORDS_THRESHOLD ?? keywordsThreshold.value
      }

      // 音频
      if (config.AEC_OPTIONS) {
        aecEnabled.value = config.AEC_OPTIONS.ENABLED ?? aecEnabled.value
      }
      if (config.AUDIO_DEVICES) {
        opusOutputSampleRate.value = config.AUDIO_DEVICES.opus_output_sample_rate ?? opusOutputSampleRate.value
      }

      // 播放模式
      if (config.APP_OPTIONS?.AUDIO_PLAYBACK_MODE) {
        const mode = config.APP_OPTIONS.AUDIO_PLAYBACK_MODE
        if (mode === 'frontend' || mode === 'backend') {
          audioPlaybackMode.value = mode
        }
      }

      // 音乐
      if (config.MUSIC) {
        const m = config.MUSIC
        musicSearchUrl.value = m.SEARCH_URL || ''
        musicUrlApi.value = m.URL_API || ''
        musicUrlApiKey.value = m.URL_API_KEY || ''
        musicDefaultQuality.value = m.DEFAULT_QUALITY || '320k'
      }

      // 后端地址
      if (config.APP_OPTIONS?.BACKEND_HOST) {
        backendHost.value = config.APP_OPTIONS.BACKEND_HOST
      }

      // 应用行为（优先从本地存储读取）
      try {
        const savedLimit = uni.getStorageSync('xiaozhi_chat_limit')
        if (savedLimit) chatHistoryLimit.value = Number(savedLimit) || 200
        const savedRetry = uni.getStorageSync('xiaozhi_connect_retry_enabled')
        if (savedRetry !== '') connectRetryEnabled.value = savedRetry !== 'false'
        const savedRetryCount = uni.getStorageSync('xiaozhi_connect_retry_count')
        if (savedRetryCount) connectRetryCount.value = Number(savedRetryCount) || 5
        // 悬浮窗开关
        const savedOverlay = uni.getStorageSync('xiaozhi_overlay_enabled')
        if (savedOverlay !== '') overlayEnabled.value = savedOverlay !== 'false'
      }
      catch (_) {}

      // 主题（优先从本地存储读取，其次从后端配置）
      try {
        const savedTheme = uni.getStorageSync('xiaozhi_theme')
        if (savedTheme === 'light' || savedTheme === 'dark') {
          theme.value = savedTheme
        }
        else if (config.APP_OPTIONS?.THEME) {
          const t = config.APP_OPTIONS.THEME
          if (t === 'light' || t === 'dark') {
            theme.value = t
          }
        }
      }
      catch (_) {}
      applyTheme(theme.value)

      loaded.value = true
    }
    catch (e) {
      console.warn('[SettingsStore] 加载设置失败:', e)
    }
  }

  /** 保存设置到后端（并行发送所有请求） */
  async function saveSettings() {
    try {
      // 构建所有配置更新请求
      const updates: Promise<any>[] = []

      // 网络
      updates.push(backendService.httpPut('/api/config', {
        key: 'SYSTEM_OPTIONS.NETWORK.PROTOCOL',
        value: protocol.value,
      }))
      updates.push(backendService.httpPut('/api/config', {
        key: 'SYSTEM_OPTIONS.NETWORK.WEBSOCKET_URL',
        value: websocketUrl.value,
      }))
      updates.push(backendService.httpPut('/api/config', {
        key: 'SYSTEM_OPTIONS.NETWORK.WEBSOCKET_ACCESS_TOKEN',
        value: websocketAccessToken.value,
      }))

      // MQTT
      updates.push(backendService.httpPut('/api/config', {
        key: 'SYSTEM_OPTIONS.NETWORK.MQTT_INFO',
        value: {
          endpoint: mqttBroker.value,
          username: mqttUsername.value,
          password: mqttPassword.value,
          publish_topic: mqttPublishTopic.value,
          subscribe_topic: mqttSubscribeTopic.value,
        },
      }))

      // 唤醒词（通过专用命令触发模型热重载）
      updates.push(backendService.sendCommand('set_wake_word', {
        enabled: wakeWordEnabled.value,
        sensitivity: wakeWordSensitivity.value,
        wake_word: wakeWordText.value,
      }))
      // 同步保存检测参数
      updates.push(backendService.httpPut('/api/config', {
        key: 'WAKE_WORD_OPTIONS.KEYWORDS_SCORE',
        value: keywordsScore.value,
      }))
      updates.push(backendService.httpPut('/api/config', {
        key: 'WAKE_WORD_OPTIONS.KEYWORDS_THRESHOLD',
        value: keywordsThreshold.value,
      }))

      // 音频
      updates.push(backendService.httpPut('/api/config', {
        key: 'AEC_OPTIONS.ENABLED',
        value: aecEnabled.value,
      }))
      updates.push(backendService.httpPut('/api/config', {
        key: 'AUDIO_DEVICES.opus_output_sample_rate',
        value: opusOutputSampleRate.value,
      }))

      // 播放模式
      updates.push(backendService.httpPut('/api/config', {
        key: 'APP_OPTIONS.AUDIO_PLAYBACK_MODE',
        value: audioPlaybackMode.value,
      }))

      // 音乐
      updates.push(backendService.httpPut('/api/config', {
        key: 'MUSIC.SEARCH_URL',
        value: musicSearchUrl.value,
      }))
      updates.push(backendService.httpPut('/api/config', {
        key: 'MUSIC.URL_API',
        value: musicUrlApi.value,
      }))
      updates.push(backendService.httpPut('/api/config', {
        key: 'MUSIC.URL_API_KEY',
        value: musicUrlApiKey.value,
      }))
      updates.push(backendService.httpPut('/api/config', {
        key: 'MUSIC.DEFAULT_QUALITY',
        value: musicDefaultQuality.value,
      }))

      // 后端地址
      updates.push(backendService.httpPut('/api/config', {
        key: 'APP_OPTIONS.BACKEND_HOST',
        value: backendHost.value,
      }))

      // 应用行为（本地持久化，不需要同步到后端）
      try {
        uni.setStorageSync('xiaozhi_chat_limit', String(chatHistoryLimit.value))
        uni.setStorageSync('xiaozhi_connect_retry_enabled', String(connectRetryEnabled.value))
        uni.setStorageSync('xiaozhi_connect_retry_count', String(connectRetryCount.value))
        uni.setStorageSync('xiaozhi_overlay_enabled', String(overlayEnabled.value))
      }
      catch (_) {}

      // 并行发送所有请求
      await Promise.all(updates)
    }
    catch (e: any) {
      console.error('[SettingsStore] 保存设置失败:', e)
      throw e
    }
  }

  return {
    // 网络
    protocol,
    websocketUrl,
    websocketAccessToken,
    mqttBroker,
    mqttUsername,
    mqttPassword,
    mqttPublishTopic,
    mqttSubscribeTopic,
    // 唤醒词
    wakeWordEnabled,
    wakeWordText,
    wakeWordSensitivity,
    keywordsScore,
    keywordsThreshold,
    wakeWordLang,
    // 音频
    aecEnabled,
    opusOutputSampleRate,
    audioPlaybackMode,
    // 音乐
    musicSearchUrl,
    musicUrlApi,
    musicUrlApiKey,
    musicDefaultQuality,
    // 后端
    backendHost,
    // 外观
    theme,
    setTheme,
    // 应用行为
    chatHistoryLimit,
    connectRetryCount,
    connectRetryEnabled,
    // 悬浮窗
    overlayEnabled,
    loaded,
    loadSettings,
    saveSettings,
  }
})
