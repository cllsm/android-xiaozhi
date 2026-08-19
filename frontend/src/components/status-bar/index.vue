<template>
  <view class="status-bar">
    <!-- 后端连接状态 -->
    <view class="status-item" @click="handleRetryBackend">
      <view class="dot" :class="backendDotClass" />
      <text class="status-label">{{ backendLabel }}</text>
    </view>

    <!-- 设备状态 -->
    <view class="status-item">
      <text class="status-label state">{{ stateLabel }}</text>
    </view>

    <!-- 服务器连接状态 -->
    <view class="status-item">
      <view class="dot" :class="serverDotClass" />
      <text class="status-label">{{ serverLabel }}</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { useAppStore } from '@/store'

const appStore = useAppStore()
const { deviceState, isConnected, isBackendConnected, errorMessage } = storeToRefs(appStore)

const stateLabelMap: Record<string, string> = {
  IDLE: '待机',
  CONNECTING: '连接中',
  LISTENING: '聆听中',
  SPEAKING: '回复中',
}

const stateLabel = computed(() => stateLabelMap[deviceState.value] || deviceState.value)

// ========== 后端状态 ==========
const backendDotClass = computed(() => {
  if (isBackendConnected.value) return 'connected'
  return 'disconnected'
})

const backendLabel = computed(() => {
  if (isBackendConnected.value) return '后端'
  return '后端离线'
})

// ========== 服务器状态 ==========
const serverDotClass = computed(() => {
  if (isConnected.value) return 'connected'
  if (isBackendConnected.value && deviceState.value === 'CONNECTING') return 'connecting'
  return 'disconnected'
})

const serverLabel = computed(() => {
  if (isConnected.value) return '已连接'
  if (deviceState.value === 'CONNECTING') return '连接中'
  if (isBackendConnected.value) return '待连接'
  return '离线'
})

/** 点击后端状态区域重试连接 */
function handleRetryBackend() {
  if (!isBackendConnected.value) {
    appStore.connectBackend()
  }
}
</script>

<style scoped lang="scss">
.status-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 16px;
  // background-color: var(--theme-bg-color-secondary);
  // border-bottom: 1px solid var(--theme-border-color);
}

.status-item {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 4px 8px;
  border-radius: 12px;
  background-color: var(--theme-bg-card);
}

// 离线状态可点击重试
.status-item:active {
  opacity: 0.7;
}

.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex-shrink: 0;
  transition: background-color 0.3s ease;
}

.dot.connected {
  background-color: var(--theme-success);
}

.dot.disconnected {
  background-color: var(--theme-tips-color);
}

.dot.connecting {
  background-color: var(--theme-warning);
  animation: pulse-dot 1.5s infinite;
}

@keyframes pulse-dot {
  0% { opacity: 1; }
  50% { opacity: 0.4; }
  100% { opacity: 1; }
}

.status-label {
  color: var(--theme-content-color);
  font-size: 11px;
  white-space: nowrap;
}

.status-label.state {
  color: var(--theme-primary);
  font-weight: 600;
  font-size: 12px;
}
</style>
