<template>
  <view class="bubble" :class="[isUser ? 'user' : 'ai']">
    <text class="bubble-text" :class="[isUser ? 'user-text' : 'ai-text']">{{ text }}</text>
    <text class="bubble-time">{{ formatTime(timestamp) }}</text>
  </view>
</template>

<script setup lang="ts">
defineProps<{
  text: string
  isUser: boolean
  timestamp?: number
}>()

function formatTime(ts?: number): string {
  if (!ts)
    return ''
  const d = new Date(ts)
  return `${d.getHours().toString().padStart(2, '0')}:${d.getMinutes().toString().padStart(2, '0')}`
}
</script>

<style scoped lang="scss">
.bubble {
  max-width: 80%;
  padding: 10px 14px;
  border-radius: 12px;
  margin-bottom: 8px;
}

.bubble.user {
  align-self: flex-end;
  background-color: #4fc3f7;
  border-bottom-right-radius: 4px;
}

.bubble.ai {
  align-self: flex-start;
  background-color: #1f2940;
  border: 1px solid #263148;
  border-bottom-left-radius: 4px;
}

.bubble-text {
  font-size: 15px;
  line-height: 1.5;
}

.bubble-text.user-text {
  color: #fff;
}

.bubble-text.ai-text {
  color: #e0e0e0;
}

.bubble-time {
  display: block;
  font-size: 11px;
  margin-top: 4px;
  text-align: right;
}

.bubble.user .bubble-time {
  color: rgba(255, 255, 255, 0.7);
}

.bubble.ai .bubble-time {
  color: #616161;
}
</style>
