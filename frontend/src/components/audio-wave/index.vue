<template>
  <view v-if="active || volume > 0" class="wave-container">
    <view class="wave-bars">
      <view
        v-for="i in 20"
        :key="i"
        class="wave-bar"
        :style="barStyle(i)"
      />
    </view>
  </view>
</template>

<script setup lang="ts">
const props = defineProps<{
  active: boolean
  /** 音量 0-100，由 AudioPlayer 的 onVolume 回调提供 */
  volume?: number
}>()

/** 根据音量计算每根柱子的高度 */
function barStyle(index: number) {
  const vol = props.volume || 0
  if (vol <= 0 && props.active) {
    // 无真实数据但处于活跃状态 → CSS 动画
    return {
      animationDelay: `${(index - 1) * 0.05}s`,
    }
  }

  // 基于音量生成伪随机高度，模拟真实波形
  const seed = Math.sin(index * 3.7 + Date.now() * 0.003) * 0.5 + 0.5
  const normalizedVol = vol / 100
  const minHeight = 4
  const maxHeight = 32
  const height = minHeight + (maxHeight - minHeight) * normalizedVol * (0.3 + seed * 0.7)
  const opacity = 0.3 + normalizedVol * 0.7

  return {
    height: `${Math.round(height)}px`,
    opacity,
    animation: 'none',
  }
}
</script>

<style scoped lang="scss">
.wave-container {
  display: flex;
  justify-content: center;
  padding: 8px 16px;
}

.wave-bars {
  display: flex;
  align-items: center;
  gap: 3px;
  height: 36px;
}

.wave-bar {
  width: 3px;
  height: 8px;
  background-color: #4fc3f7;
  border-radius: 2px;
  opacity: 0.4;
  transition: height 0.08s ease-out, opacity 0.08s ease-out;
}

// 无真实音量数据时的 CSS 动画 fallback
.wave-bar:not([style*="animation: none"]) {
  animation: wavePulse 0.8s ease-in-out infinite alternate;
}

@keyframes wavePulse {
  0% {
    height: 8px;
    opacity: 0.4;
  }
  100% {
    height: 28px;
    opacity: 1;
  }
}
</style>
