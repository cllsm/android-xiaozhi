<template>
  <view v-if="active" class="wave-container">
    <view class="wave-bars">
      <view
        v-for="i in 20"
        :key="i"
        class="wave-bar"
        :class="{ animate: active }"
        :style="{ animationDelay: `${(i - 1) * 0.05}s` }"
      />
    </view>
  </view>
</template>

<script setup lang="ts">
defineProps<{
  active: boolean
}>()
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
  height: 32px;
}

.wave-bar {
  width: 3px;
  height: 8px;
  background-color: #4fc3f7;
  border-radius: 2px;
  opacity: 0.4;
}

.wave-bar.animate {
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
