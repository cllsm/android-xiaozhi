<template>
  <view v-if="overlayEnabled && isInForeground" class="floating-ball-wrapper">
    <Fab
      :draggable="true"
      :autosorption="true"
      :layout="'circle'"
      :safeDistance="12"
      :bottomSafeDistance="80"
      :topSafeDistance="60"
      :zIndex="9999"
      :menuItems="menuItems"
      @select="handleMenuSelect"
      @open="onMenuOpen"
      @close="onMenuClose"
    >
      <template #main-button>
        <view class="ball" :class="ballClass">
          <text class="ball-emoji">{{ stateEmoji }}</text>
          <view v-if="!isMenuOpen" class="pulse-ring" :class="pulseClass" />
        </view>
      </template>
      <template #menu-item="{ data, index }">
        <view class="custom-menu-item" :style="{ transitionDelay: `${index * 0.05}s` }">
          <text class="menu-icon">{{ data.icon }}</text>
          <text class="menu-label">{{ data.text }}</text>
        </view>
      </template>
    </Fab>
  </view>
</template>

<script setup lang="ts">
import { useAppStore } from '@/store'
import { useSettingsStore } from '@/store'
import {
  checkOverlayPermission,
  createSystemOverlay,
  updateSystemOverlay,
  removeSystemOverlay,
} from '@/utils/native-bridge'
import Fab from '@/components/myl-uniapp-fab/index.vue'

const appStore = useAppStore()
const settingsStore = useSettingsStore()
const { deviceState, isWakeWordMonitoring, isBackendConnected, isConnected } = storeToRefs(appStore)
const { overlayEnabled } = storeToRefs(settingsStore)

const isMenuOpen = ref(false)

// 自动收回定时器
let autoShrinkTimer: ReturnType<typeof setTimeout> | null = null
const AUTO_SHRINK_DELAY = 5000

// ========== 前后台状态 ==========

const isInForeground = ref(true)
let _permissionGranted = false

/** App 进入后台 → 创建系统悬浮球 */
function onAppEnterBackground() {
  console.log('[FloatingBall] onAppHide 触发, overlayEnabled=' + overlayEnabled.value + ', permission=' + _permissionGranted)
  if (!overlayEnabled.value) return
  isInForeground.value = false
  if (_permissionGranted) {
    createSystemOverlay(deviceState.value || 'IDLE')
  }
}

/** App 回到前台 → 移除系统悬浮球 */
function onAppEnterForeground() {
  console.log('[FloatingBall] onAppShow 触发')
  isInForeground.value = true
  removeSystemOverlay()
}

// ========== 状态显示 ==========

const stateEmoji = computed(() => {
  switch (deviceState.value) {
    case 'LISTENING': return '🎙'
    case 'SPEAKING': return '🔊'
    case 'CONNECTING': return '⏳'
    default: return '💬'
  }
})

const ballClass = computed(() => {
  return `state-${deviceState.value?.toLowerCase() || 'idle'}`
})

const pulseClass = computed(() => {
  if (deviceState.value === 'LISTENING') return 'pulse-green'
  if (deviceState.value === 'SPEAKING') return 'pulse-orange'
  return 'pulse-blue'
})

// ========== 菜单项 ==========

const menuItems = computed(() => {
  const items = []

  // 主操作按钮
  if (deviceState.value === 'IDLE') {
    items.push({ icon: '🎤', text: '开始对话', action: 'start' })
  }
  else if (deviceState.value === 'LISTENING') {
    items.push({ icon: '⏹', text: '停止聆听', action: 'stop' })
  }
  else if (deviceState.value === 'SPEAKING') {
    items.push({ icon: '✋', text: '打断回复', action: 'abort' })
  }
  else {
    items.push({ icon: '⏳', text: '连接中...', action: 'none' })
  }

  // 唤醒词
  if (isWakeWordMonitoring.value) {
    items.push({ icon: '🔇', text: '关唤醒词', action: 'wake_off' })
  }
  else if (deviceState.value === 'IDLE' && isBackendConnected.value) {
    items.push({ icon: '👂', text: '唤醒词', action: 'wake_on' })
  }

  // 主页
  items.push({ icon: '🏠', text: '主页', action: 'home' })

  return items
})

// ========== 事件处理 ==========

function handleMenuSelect(item: any) {
  resetAutoShrink()
  switch (item.action) {
    case 'start':
      appStore.startListening()
      break
    case 'stop':
      appStore.stopListening()
      break
    case 'abort':
      appStore.abortSpeaking()
      break
    case 'wake_on':
      appStore.startWakeWordMonitoring()
      break
    case 'wake_off':
      appStore.stopWakeWordMonitoring()
      break
    case 'home':
      goHome()
      break
  }
}

function onMenuOpen() {
  isMenuOpen.value = true
  resetAutoShrink()
}

function onMenuClose() {
  isMenuOpen.value = false
  clearAutoShrink()
}

function goHome() {
  const pages = getCurrentPages()
  const currentPage = pages[pages.length - 1]
  if (currentPage?.route !== 'pages/tab/home/index') {
    uni.reLaunch({ url: '/pages/tab/home/index' })
  }
}

// ========== 自动收回 ==========

function resetAutoShrink() {
  clearAutoShrink()
  autoShrinkTimer = setTimeout(() => {
    isMenuOpen.value = false
  }, AUTO_SHRINK_DELAY)
}

function clearAutoShrink() {
  if (autoShrinkTimer) {
    clearTimeout(autoShrinkTimer)
    autoShrinkTimer = null
  }
}

// ========== 监听 deviceState 变化同步到系统悬浮球 ==========

watch(deviceState, (newState) => {
  if (!isInForeground.value && _permissionGranted) {
    updateSystemOverlay(newState || 'IDLE')
  }
})

// ========== 监听 overlayEnabled ==========

watch(overlayEnabled, (enabled) => {
  if (enabled) {
    // #ifdef APP-PLUS
    console.log('[FloatingBall] 悬浮窗开关打开，检查权限...')
    _permissionGranted = checkOverlayPermission()
    console.log('[FloatingBall] 权限检查结果: ' + _permissionGranted)
    // #endif
  }
  else {
    // 关闭时移除系统悬浮球
    removeSystemOverlay()
  }
})

// ========== 生命周期 ==========

onMounted(() => {
  // #ifdef APP-PLUS
  console.log('[FloatingBall] onMounted, overlayEnabled=' + overlayEnabled.value)
  if (overlayEnabled.value) {
    _permissionGranted = checkOverlayPermission()
    console.log('[FloatingBall] 初始权限检查结果: ' + _permissionGranted)
  }
  // #endif

  // ★ 使用 uni API 监听前后台（App 端没有 document）
  uni.onAppHide(() => onAppEnterBackground())
  uni.onAppShow(() => onAppEnterForeground())
})

onUnmounted(() => {
  clearAutoShrink()
  removeSystemOverlay()
})
</script>

<style scoped lang="scss">
.floating-ball-wrapper {
  /* 容器不占布局空间 */
}

// ========== 悬浮球 ==========

.ball {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 48px;
  height: 48px;
  border-radius: 24px;
  background-color: var(--theme-primary);
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.3);
  transition: background-color 0.3s ease, transform 0.2s ease;
}

.ball:active {
  transform: scale(0.92);
}

.ball.state-listening {
  background-color: var(--theme-success);
}

.ball.state-speaking {
  background-color: var(--theme-warning);
}

.ball.state-connecting {
  background-color: var(--theme-tips-color);
}

.ball-emoji {
  font-size: 22px;
  z-index: 1;
}

// ========== 脉冲动画 ==========

.pulse-ring {
  position: absolute;
  top: -4px;
  left: -4px;
  right: -4px;
  bottom: -4px;
  border-radius: 28px;
  opacity: 0;
  pointer-events: none;
}

.pulse-blue {
  animation: pulse-breathe 3s ease-in-out infinite;
  border: 2px solid var(--theme-primary);
}

.pulse-green {
  animation: pulse-active 1.5s ease-in-out infinite;
  border: 2px solid var(--theme-success);
}

.pulse-orange {
  animation: pulse-active 1.5s ease-in-out infinite;
  border: 2px solid var(--theme-warning);
}

@keyframes pulse-breathe {
  0%, 100% {
    opacity: 0;
    transform: scale(1);
  }
  50% {
    opacity: 0.4;
    transform: scale(1.15);
  }
}

@keyframes pulse-active {
  0% {
    opacity: 0.6;
    transform: scale(1);
  }
  100% {
    opacity: 0;
    transform: scale(1.6);
  }
}

// ========== 自定义菜单项 ==========

.custom-menu-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  background-color: var(--theme-bg-card);
  border-radius: 22px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.2);
  border: 1px solid var(--theme-border-color);
}

.menu-icon {
  font-size: 18px;
}

.menu-label {
  font-size: 8px;
  color: var(--theme-content-color);
  margin-top: 1px;
  white-space: nowrap;
}
</style>
