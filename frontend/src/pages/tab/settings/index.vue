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
          <text class="form-label">前端播放</text>
          <text class="form-hint">开启后音频由前端播放，适合无 sounddevice 的环境</text>
        </view>
        <switch
          :checked="audioPlaybackMode === 'frontend'"
          @change="audioPlaybackMode = $event.detail.value ? 'frontend' : 'backend'"
          color="#4fc3f7"
        />
      </view>

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

    <!-- ==================== 外观 ==================== -->
    <view class="section">
      <text class="section-title">外观</text>

      <view class="form-item">
        <text class="form-label">主题模式</text>
        <view class="radio-group">
          <view
            class="radio-item"
            :class="{ active: theme === 'dark' }"
            @click="setTheme('dark')"
          >
            <text class="radio-text">🌙 深色</text>
          </view>
          <view
            class="radio-item"
            :class="{ active: theme === 'light' }"
            @click="setTheme('light')"
          >
            <text class="radio-text">☀️ 浅色</text>
          </view>
        </view>
      </view>
    </view>

    <!-- ==================== 应用行为 ==================== -->
    <view class="section">
      <text class="section-title">应用行为</text>

      <view class="form-item">
        <text class="form-label">聊天历史保留: {{ chatHistoryLimit }} 条</text>
        <slider
          :value="chatHistoryLimit"
          :min="50"
          :max="500"
          :step="50"
          activeColor="#4fc3f7"
          @change="(e: any) => chatHistoryLimit = e.detail.value"
        />
        <text class="form-hint">超出后自动删除最早的记录</text>
      </view>

      <view class="form-item">
        <view class="btn-danger" @click="handleClearChat">
          <text class="btn-danger-text">清除聊天记录</text>
        </view>
      </view>

      <view class="form-item row">
        <view>
          <text class="form-label">启动时自动重试连接</text>
          <text class="form-hint">后端未就绪时指数退避重试</text>
        </view>
        <switch
          :checked="connectRetryEnabled"
          @change="connectRetryEnabled = !connectRetryEnabled"
          color="#4fc3f7"
        />
      </view>

      <view v-if="connectRetryEnabled" class="form-item">
        <text class="form-label">重试次数: {{ connectRetryCount }} 次</text>
        <slider
          :value="connectRetryCount"
          :min="1"
          :max="10"
          :step="1"
          activeColor="#4fc3f7"
          @change="(e: any) => connectRetryCount = e.detail.value"
        />
      </view>
    </view>

    <!-- ==================== 悬浮窗 ==================== -->
    <view class="section">
      <text class="section-title">悬浮窗</text>

      <view class="form-item row">
        <view>
          <text class="form-label">启用悬浮窗</text>
          <text class="form-hint">在桌面显示悬浮窗，快速回到对话</text>
        </view>
        <switch
          :checked="overlayEnabled"
          @change="handleOverlayToggle"
          color="#4fc3f7"
        />
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
import { useAppStore } from '@/store'
import { callNative } from '@/utils/native-bridge'

const settingsStore = useSettingsStore()
const appStore = useAppStore()
const {
  protocol, websocketUrl, websocketAccessToken,
  mqttBroker, mqttUsername, mqttPassword, mqttPublishTopic, mqttSubscribeTopic,
  wakeWordEnabled, wakeWordText, wakeWordSensitivity, keywordsScore, keywordsThreshold, wakeWordLang,
  aecEnabled, opusOutputSampleRate, audioPlaybackMode,
  musicSearchUrl, musicUrlApi, musicUrlApiKey, musicDefaultQuality,
  backendHost, theme,
  chatHistoryLimit, connectRetryCount, connectRetryEnabled,
  overlayEnabled,
} = storeToRefs(settingsStore)
const { setTheme } = settingsStore

const saveSuccess = ref(false)
const saveMessage = ref('')

/** 悬浮窗开关切换 */
async function handleOverlayToggle(e: any) {
  const show = e.detail.value
  overlayEnabled.value = show
  try {
    await callNative('overlay_window', { show })
  }
  catch (err) {
    console.warn('[Settings] 悬浮窗切换失败:', err)
  }
}

/** 清除聊天记录 */
function handleClearChat() {
  uni.showModal({
    title: '确认清除',
    content: '清除后聊天记录将无法恢复，确定要清除吗？',
    confirmColor: '#ef5350',
    success: (res: any) => {
      if (res.confirm) {
        appStore.clearChatHistory()
        saveMessage.value = '聊天记录已清除'
        saveSuccess.value = true
        setTimeout(() => { saveSuccess.value = false }, 2000)
      }
    },
  })
}

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
  background-color: var(--theme-bg-color);
  padding: 16px;
  padding-bottom: calc(32px + env(safe-area-inset-bottom));
  box-sizing: border-box;
}

.section {
  background-color: var(--theme-bg-card);
  border-radius: 12px;
  padding: 16px;
  margin-bottom: 12px;
}

.section-title {
  display: block;
  color: #4fc3f7;
  font-size: 16px;
  font-weight: 600;
  margin-bottom: 12px;
}

.form-item {
  margin-bottom: 12px;
}

.form-item:last-child {
  margin-bottom: 0;
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
  display: block;
  color: var(--theme-content-color);
  font-size: 14px;
  margin-bottom: 6px;
}

.form-value {
  color: var(--theme-main-color);
  font-size: 14px;
}

.form-input {
  width: 100%;
  height: 40px;
  padding: 0 12px;
  background-color: var(--theme-border-color);
  border-radius: 8px;
  color: var(--theme-main-color);
  font-size: 14px;
  box-sizing: border-box;
}

.input-placeholder {
  color: var(--theme-tips-color);
}

.form-hint {
  display: block;
  color: var(--theme-tips-color);
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
  background-color: var(--theme-border-color);
  border-radius: 8px;
  border: 1px solid transparent;
}

.radio-item.active {
  border-color: #4fc3f7;
  background-color: rgba(79, 195, 247, 0.1);
}

.radio-text {
  color: var(--theme-main-color);
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

.btn-danger {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 44px;
  border-radius: 22px;
  border: 1px solid #ef5350;
  background-color: transparent;
}

.btn-danger-text {
  color: #ef5350;
  font-size: 14px;
  font-weight: 600;
}

.toast-success {
  position: fixed;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  padding: 16px 32px;
  border-radius: 12px;
  background-color: rgba(102, 187, 106, 0.9);
  z-index: 999;
}

.toast-text {
  color: #fff;
  font-size: 16px;
  font-weight: 600;
}
</style>
