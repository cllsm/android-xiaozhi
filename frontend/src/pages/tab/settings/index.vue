<template>
  <view class="container">
    <!-- ==================== 网络配置 ==================== -->
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

      <view class="form-item">
        <text class="form-label">访问令牌</text>
        <input
          class="form-input"
          v-model="websocketAccessToken"
          placeholder="可选，留空不使用认证"
          placeholder-class="input-placeholder"
        />
      </view>

      <template v-if="protocol === 'mqtt'">
        <view class="form-item">
          <text class="form-label">MQTT Broker</text>
          <input
            class="form-input"
            v-model="mqttBroker"
            placeholder="mqtt://broker:8883"
            placeholder-class="input-placeholder"
          />
        </view>
        <view class="form-item">
          <text class="form-label">用户名</text>
          <input
            class="form-input"
            v-model="mqttUsername"
            placeholder="MQTT 用户名"
            placeholder-class="input-placeholder"
          />
        </view>
        <view class="form-item">
          <text class="form-label">密码</text>
          <input
            class="form-input"
            v-model="mqttPassword"
            placeholder="MQTT 密码"
            placeholder-class="input-placeholder"
            :password="true"
          />
        </view>
        <view class="form-item">
          <text class="form-label">发布主题</text>
          <input
            class="form-input"
            v-model="mqttPublishTopic"
            placeholder="publish/topic"
            placeholder-class="input-placeholder"
          />
        </view>
        <view class="form-item">
          <text class="form-label">订阅主题</text>
          <input
            class="form-input"
            v-model="mqttSubscribeTopic"
            placeholder="subscribe/topic"
            placeholder-class="input-placeholder"
          />
        </view>
      </template>
    </view>

    <!-- ==================== 唤醒词 ==================== -->
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

      <view v-if="wakeWordEnabled" class="wake-word-area">
        <view class="form-item">
          <view class="label-row">
            <text class="form-label">唤醒词文本</text>
            <view v-if="wakeWordLang" class="lang-badge" :class="wakeWordLang === '中文' ? 'zh' : 'en'">
              <text class="lang-text">{{ wakeWordLang }}</text>
            </view>
          </view>
          <input
            class="form-input"
            v-model="wakeWordText"
            placeholder="输入唤醒词，如 你好小智"
            placeholder-class="input-placeholder"
          />
        </view>

        <view class="form-item">
          <text class="form-label">灵敏度: {{ wakeWordSensitivity.toFixed(2) }}</text>
          <slider
            :value="wakeWordSensitivity * 100"
            :min="5"
            :max="50"
            :step="5"
            activeColor="#4fc3f7"
            @change="(e: any) => wakeWordSensitivity = e.detail.value / 100"
          />
        </view>

        <view class="form-item">
          <text class="form-label">关键词得分: {{ keywordsScore.toFixed(1) }}</text>
          <slider
            :value="keywordsScore * 10"
            :min="5"
            :max="30"
            :step="1"
            activeColor="#4fc3f7"
            @change="(e: any) => keywordsScore = e.detail.value / 10"
          />
          <text class="form-hint">得分越高越容易匹配</text>
        </view>

        <view class="form-item">
          <text class="form-label">检测阈值: {{ keywordsThreshold.toFixed(2) }}</text>
          <slider
            :value="keywordsThreshold * 100"
            :min="5"
            :max="50"
            :step="5"
            activeColor="#4fc3f7"
            @change="(e: any) => keywordsThreshold = e.detail.value / 100"
          />
          <text class="form-hint">阈值越低越灵敏</text>
        </view>
      </view>
    </view>

    <!-- ==================== 音频设置 ==================== -->
    <view class="section">
      <text class="section-title">音频设置</text>

      <view class="form-item row">
        <view>
          <text class="form-label">回声消除 (AEC)</text>
          <text class="form-hint">建议在扬声器外放时开启</text>
        </view>
        <switch
          :checked="aecEnabled"
          @change="aecEnabled = !aecEnabled"
          color="#4fc3f7"
        />
      </view>

      <view class="form-item">
        <text class="form-label">Opus 输出采样率</text>
        <view class="radio-group">
          <view
            class="radio-item"
            :class="{ active: opusOutputSampleRate === 24000 }"
            @click="opusOutputSampleRate = 24000"
          >
            <text class="radio-text">24000 Hz</text>
          </view>
          <view
            class="radio-item"
            :class="{ active: opusOutputSampleRate === 16000 }"
            @click="opusOutputSampleRate = 16000"
          >
            <text class="radio-text">16000 Hz</text>
          </view>
        </view>
        <text class="form-hint">官方服务器用 24kHz，第三方用 16kHz</text>
      </view>
    </view>

    <!-- ==================== 音乐设置 ==================== -->
    <view class="section">
      <text class="section-title">音乐设置</text>

      <view class="form-item">
        <text class="form-label">搜索 API</text>
        <input
          class="form-input"
          v-model="musicSearchUrl"
          placeholder="留空使用默认搜索 API"
          placeholder-class="input-placeholder"
        />
      </view>

      <view class="form-item">
        <text class="form-label">直链 API</text>
        <input
          class="form-input"
          v-model="musicUrlApi"
          placeholder="留空使用默认直链 API"
          placeholder-class="input-placeholder"
        />
      </view>

      <view class="form-item">
        <text class="form-label">API Key</text>
        <input
          class="form-input"
          v-model="musicUrlApiKey"
          placeholder="留空使用默认 Key"
          placeholder-class="input-placeholder"
        />
      </view>

      <view class="form-item">
        <text class="form-label">默认音质</text>
        <view class="radio-group">
          <view
            class="radio-item"
            :class="{ active: musicDefaultQuality === '128k' }"
            @click="musicDefaultQuality = '128k'"
          >
            <text class="radio-text">128k</text>
          </view>
          <view
            class="radio-item"
            :class="{ active: musicDefaultQuality === '320k' }"
            @click="musicDefaultQuality = '320k'"
          >
            <text class="radio-text">320k</text>
          </view>
        </view>
      </view>
    </view>

    <!-- ==================== 后端服务 ==================== -->
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

    <!-- ==================== 关于 ==================== -->
    <view class="section">
      <text class="section-title">关于</text>
      <view class="form-item row">
        <text class="form-label">版本</text>
        <text class="form-value">1.0.0</text>
      </view>
    </view>

    <!-- 保存按钮 -->
    <view class="btn-save" @click="handleSave">
      <text class="btn-save-text">保存设置</text>
    </view>

    <!-- 保存成功提示 -->
    <view v-if="saveSuccess" class="toast-success">
      <text class="toast-text">{{ saveMessage }}</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { useSettingsStore } from '@/store'

const settingsStore = useSettingsStore()
const {
  protocol, websocketUrl, websocketAccessToken,
  mqttBroker, mqttUsername, mqttPassword, mqttPublishTopic, mqttSubscribeTopic,
  wakeWordEnabled, wakeWordText, wakeWordSensitivity, keywordsScore, keywordsThreshold, wakeWordLang,
  aecEnabled, opusOutputSampleRate,
  musicSearchUrl, musicUrlApi, musicUrlApiKey, musicDefaultQuality,
  backendHost,
} = storeToRefs(settingsStore)

const saveSuccess = ref(false)
const saveMessage = ref('')

onMounted(() => {
  if (!settingsStore.loaded) {
    settingsStore.loadSettings()
  }
})

async function handleSave() {
  try {
    await settingsStore.saveSettings()
    saveMessage.value = '设置已保存'
    saveSuccess.value = true
    setTimeout(() => { saveSuccess.value = false }, 2000)
  }
  catch (e) {
    saveMessage.value = '保存失败'
    saveSuccess.value = true
    setTimeout(() => { saveSuccess.value = false }, 2000)
    console.error('保存设置失败:', e)
  }
}
</script>

<style scoped lang="scss">
.container {
  min-height: 100vh;
  background-color: #16213e;
  padding: 16px;
  padding-bottom: 32px;
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

.label-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.lang-badge {
  padding: 2px 8px;
  border-radius: 4px;
}

.lang-badge.zh {
  background-color: rgba(79, 195, 247, 0.15);
}

.lang-badge.en {
  background-color: rgba(129, 199, 132, 0.15);
}

.lang-text {
  font-size: 11px;
  font-weight: 600;
}

.lang-badge.zh .lang-text {
  color: #4fc3f7;
}

.lang-badge.en .lang-text {
  color: #81c784;
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
  margin-top: 4px;
}

.wake-word-area {
  padding-top: 4px;
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
  border-radius: 12px;
  z-index: 999;
}

.toast-success {
  background-color: rgba(102, 187, 106, 0.9);
}

.toast-text {
  color: #fff;
  font-size: 16px;
  font-weight: 600;
}
</style>
