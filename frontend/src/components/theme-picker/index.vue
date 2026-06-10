<template>
  <view class="theme-picker" @click="toggleTheme">
    <text class="theme-icon">{{ isDark ? '🌙' : '☀️' }}</text>
  </view>
</template>

<script setup lang="ts">
import { useSettingsStore } from '@/store'

const settingsStore = useSettingsStore()
const { theme } = storeToRefs(settingsStore)

const isDark = computed(() => theme.value === 'dark')

function toggleTheme() {
  const newTheme = isDark.value ? 'light' : 'dark'
  settingsStore.setTheme(newTheme)
}
</script>

<style scoped lang="scss">
.theme-picker {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 18px;
  background-color: var(--theme-bg-input);
}

.theme-icon {
  font-size: 18px;
}
</style>
