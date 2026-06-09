<template>
  <view class="status-bar">
    <!-- 后端连接状态 -->
    <view class="status-item">
      <view class="dot" :class="isBackendConnected ? 'connected' : 'disconnected'" />
      <text class="status-label">{{ isBackendConnected ? '后端' : '离线' }}</text>
    </view>

    <!-- 设备状态 -->
    <view class="status-item">
      <text class="status-label state">{{ stateLabel }}</text>
    </view>

    <!-- 服务器连接状态 -->
    <view class="status-item">
      <view class="dot" :class="isConnected ? 'connected' : 'disconnected'" />
      <text class="status-label">{{ isConnected ? '已连接' : '未连接' }}</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { useAppStore } from '@/store'

const appStore = useAppStore()
const { deviceState, isConnected, isBackendConnected } = storeToRefs(appStore)

const stateLabelMap: Record<string, string> = {
  IDLE: '待机',
  CONNECTING: '连接中',
  LISTENING: '聆听中',
  SPEAKING: '回复中',
}

const stateLabel = computed(() => stateLabelMap[deviceState.value] || deviceState.value)
</script>

<style scoped lang="scss">
.status-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 16px;
  background-color: #1a1a2e;
  border-bottom: 1px solid #263148;
}

.status-item {
  display: flex;
  align-items: center;
  gap: 6px;
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.dot.connected {
  background-color: #66bb6a;
}

.dot.disconnected {
  background-color: #616161;
}

.status-label {
  color: #9e9e9e;
  font-size: 12px;
}

.status-label.state {
  color: #4fc3f7;
  font-weight: 600;
  font-size: 13px;
}
</style>
