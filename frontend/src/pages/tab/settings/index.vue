<template>
  <view class="container">
    <view class="section">
      <text class="section-title">网络配置</text>

      <view class="form-item">
        <text class="form-label">通信协议</text>
        <view class="radio-group">
          <view
            class="radio-item"
            :class="{ active: protocol === 'websocket' }"
            @click="protocol = 'websocket'"
          >
            <text class="radio-text">WebSocket</text>
          </view>
          <view
            class="radio-item"
            :class="{ active: protocol === 'mqtt' }"
            @click="protocol = 'mqtt'"
          >
            <text class="radio-text">MQTT</text>
          </view>
        </view>
      </view>

      <view class="form-item">
        <text class="form-label">WebSocket 地址</text>
        <input
          class="form-input"
          v-model="websocketUrl"
          placeholder="wss://..."
          placeholder-class="input-placeholder"
        />
      </view>

      <view v-if="protocol === 'mqtt'" class="form-item">
        <text class="form-label">MQTT Broker</text>
        <input
          class="form-input"
          v-model="mqttBroker"
          placeholder="mqtt://broker:8883"
          placeholder-class="input-placeholder"
        />
      </view>
    </view>

    <view class="section">
      <text class="section-title">唤醒词</text>

      <view class="form-item row">
        <text class="form-label">启用唤醒词</text>
        <switch
          :checked="wakeWordEnabled"
          @change="wakeWordEnabled = !wakeWordEnabled"
          color="#4fc3f7"
        />
      </view>

      <view v-if="wakeWordEnabled" class="form-item">
        <text class="form-label">灵敏度: {{ wakeWordSensitivity.toFixed(1) }}</text>
        <slider
          :value="wakeWordSensitivity * 100"
          :min="5"
          :max="50"
          :step="5"
          activeColor="#4fc3f7"
          @change="(e: any) => wakeWordSensitivity = e.detail.value / 100"
        />
      </view>
    </view>

    <view class="section">
      <text class="section-title">后端服务</text>

      <view class="form-item">
        <text class="form-label">后端地址</text>
        <input
          class="form-input"
          v-model="backendHost"
          placeholder="127.0.0.1 或局域网 IP"
          placeholder-class="input-placeholder"
        />
      </view>

      <view class="form-item">
        <text class="form-hint">本机 Termux 填 127.0.0.1，电脑后端填局域网 IP</text>
      </view>
    </view>

    <view class="section">
      <text class="section-title">关于</text>
      <view class="form-item row">
        <text class="form-label">版本</text>
        <text class="form-value">1.0.0</text>
      </view>
    </view>

    <view class="btn-save" @click="handleSave">
      <text class="btn-save-text">保存设置</text>
    </view>

    <!-- 保存成功提示 -->
    <view v-if="saveSuccess" class="toast-success">
      <text class="toast-text">设置已保存</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { useSettingsStore } from '@/store'

const settingsStore = useSettingsStore()
const { websocketUrl, mqttBroker, protocol, wakeWordEnabled, wakeWordSensitivity, backendHost } = storeToRefs(settingsStore)

const saveSuccess = ref(false)

onMounted(() => {
  if (!settingsStore.loaded) {
    settingsStore.loadSettings()
  }
})

async function handleSave() {
  try {
    await settingsStore.saveSettings()
    saveSuccess.value = true
    setTimeout(() => { saveSuccess.value = false }, 2000)
  }
  catch (e) {
    console.error('保存设置失败:', e)
  }
}
</script>

<style scoped lang="scss">
.container {
  min-height: 100vh;
  background-color: #16213e;
  padding: 16px;
}

.section {
  background-color: #1f2940;
  border-radius: 12px;
  padding: 16px;
  margin-bottom: 16px;
}

.section-title {
  color: #4fc3f7;
  font-size: 16px;
  font-weight: 600;
  margin-bottom: 12px;
}

.form-item {
  margin-bottom: 12px;
}

.form-item.row {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.form-label {
  color: #9e9e9e;
  font-size: 14px;
  margin-bottom: 6px;
}

.form-value {
  color: #e0e0e0;
  font-size: 14px;
}

.form-input {
  height: 40px;
  padding: 0 12px;
  background-color: #263148;
  border-radius: 8px;
  color: #e0e0e0;
  font-size: 14px;
}

.input-placeholder {
  color: #616161;
}

.form-hint {
  color: #616161;
  font-size: 12px;
}

.radio-group {
  display: flex;
  gap: 8px;
}

.radio-item {
  flex: 1;
  display: flex;
  justify-content: center;
  align-items: center;
  height: 36px;
  background-color: #263148;
  border-radius: 8px;
  border: 1px solid transparent;
}

.radio-item.active {
  border-color: #4fc3f7;
  background-color: rgba(79, 195, 247, 0.1);
}

.radio-text {
  color: #e0e0e0;
  font-size: 14px;
}

.btn-save {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 48px;
  background-color: #4fc3f7;
  border-radius: 24px;
  margin-top: 8px;
}

.btn-save-text {
  color: #fff;
  font-size: 16px;
  font-weight: 600;
}

.toast-success {
  position: fixed;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  padding: 16px 32px;
  background-color: rgba(102, 187, 106, 0.9);
  border-radius: 12px;
  z-index: 999;
}

.toast-text {
  color: #fff;
  font-size: 16px;
  font-weight: 600;
}
</style>
