<template>
  <!-- v-show 代替 v-if：保留 DOM 以支持 CSS transition 动画 -->
  <view v-show="emotion !== 'neutral'" class="emotion-container" :class="{ 'emotion-active': emotion !== 'neutral' }">
    <text class="emotion-emoji">{{ emotionEmoji }}</text>
  </view>
</template>

<script setup lang="ts">
import { emotionEmojis } from '@/utils/common'

const props = defineProps<{
  emotion: string
}>()

const emotionEmoji = computed(() => emotionEmojis[props.emotion] || '')
</script>

<style scoped lang="scss">
.emotion-container {
  display: flex;
  justify-content: center;
  align-items: center;
  padding: 4px;
  min-height: 48px;

  // 初始隐藏态（配合 v-show）
  opacity: 0;
  transform: scale(0.5);
  transition: opacity 0.35s ease, transform 0.35s ease;
}

// 激活态 — 淡入 + 放大
.emotion-active {
  opacity: 1;
  transform: scale(1);
}

.emotion-emoji {
  font-size: 40px;
}
</style>
