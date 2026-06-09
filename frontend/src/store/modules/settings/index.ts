/**
 * 设置状态管理
 */
import { backendService } from '@/api/backend'

export const useSettingsStore = defineStore('settings', () => {
  const websocketUrl = ref('wss://api.xiaozhi.com/ws')
  const mqttBroker = ref('mqtt://broker.example.com:8883')
  const protocol = ref('websocket')
  const wakeWordEnabled = ref(true)
  const wakeWordSensitivity = ref(0.2)
  const opusOutputSampleRate = ref(24000)
  /** 后端服务地址（App 模式下使用，默认电脑局域网 IP） */
  const backendHost = ref('127.0.0.1')
  const loaded = ref(false)

  /** 从后端加载设置 */
  async function loadSettings() {
    try {
      const config = await backendService.httpGet('/api/config')
      if (config.SYSTEM_OPTIONS?.NETWORK) {
        const net = config.SYSTEM_OPTIONS.NETWORK
        websocketUrl.value = net.WEBSOCKET_URL || websocketUrl.value
        mqttBroker.value = net.MQTT_INFO?.broker || mqttBroker.value
        protocol.value = net.PROTOCOL || protocol.value
      }
      if (config.SYSTEM_OPTIONS?.WAKE_WORD) {
        const ww = config.SYSTEM_OPTIONS.WAKE_WORD
        wakeWordEnabled.value = ww.ENABLED ?? wakeWordEnabled.value
        wakeWordSensitivity.value = ww.SENSITIVITY ?? wakeWordSensitivity.value
      }
      // 加载后端地址配置
      if (config.APP_OPTIONS?.BACKEND_HOST) {
        backendHost.value = config.APP_OPTIONS.BACKEND_HOST
      }
      loaded.value = true
    }
    catch (e) {
      console.warn('[SettingsStore] 加载设置失败:', e)
    }
  }

  /** 保存设置到后端 */
  async function saveSettings() {
    try {
      await backendService.httpPut('/api/config', {
        key: 'SYSTEM_OPTIONS.NETWORK.PROTOCOL',
        value: protocol.value,
      })
      await backendService.httpPut('/api/config', {
        key: 'SYSTEM_OPTIONS.NETWORK.WEBSOCKET_URL',
        value: websocketUrl.value,
      })
      await backendService.httpPut('/api/config', {
        key: 'SYSTEM_OPTIONS.WAKE_WORD.ENABLED',
        value: wakeWordEnabled.value,
      })
      await backendService.httpPut('/api/config', {
        key: 'APP_OPTIONS.BACKEND_HOST',
        value: backendHost.value,
      })
    }
    catch (e: any) {
      console.error('[SettingsStore] 保存设置失败:', e)
      throw e
    }
  }

  return {
    websocketUrl,
    mqttBroker,
    protocol,
    wakeWordEnabled,
    wakeWordSensitivity,
    opusOutputSampleRate,
    backendHost,
    loaded,
    loadSettings,
    saveSettings,
  }
})
