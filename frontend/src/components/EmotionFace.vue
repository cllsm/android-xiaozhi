<template>
  <view v-if="emotion !== 'neutral'" class="emotion-container">
    <text class="emotion-emoji">{{ emotionEmoji }}</text>
  </view>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  emotion: string
}>()

const emotionMap: Record<string, string> = {
  neutral: '',
  happy: '😊',
  sad: '😢',
  thinking: '🤔',
  surprised: '😮',
  angry: '😠',
  love: '❤️',
  laugh: '😄',
}

const emotionEmoji = computed(() => emotionMap[props.emotion] || '😊')
</script>

<style scoped lang="scss">
.emotion-container {
  display: flex;
  justify-content: center;
  padding: 4px;
}

.emotion-emoji {
  font-size: 40px;
}
</style>
