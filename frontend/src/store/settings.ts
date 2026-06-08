/**
 * 设置状态管理
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { backendService } from '@/services/backend'

export const useSettingsStore = defineStore('settings', () => {
  const websocketUrl = ref('wss://api.xiaozhi.com/ws')
  const mqttBroker = ref('mqtt://broker.example.com:8883')
  const protocol = ref('websocket')
  const wakeWordEnabled = ref(true)
  const wakeWordSensitivity = ref(0.2)
  const opusOutputSampleRate = ref(24000)
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
      loaded.value = true
    } catch (e) {
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
    } catch (e: any) {
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
    loaded,
    loadSettings,
    saveSettings,
  }
})
