<script setup lang="ts">
import { useAppStore } from '@/store'
import { useSettingsStore } from '@/store'

const appStore = useAppStore()

/** 指数退避连接重试（参数从 settings 读取） */
async function connectWithRetry() {
  let maxRetries = 5
  let enabled = true
  try {
    const settingsStore = useSettingsStore()
    enabled = settingsStore.connectRetryEnabled
    maxRetries = settingsStore.connectRetryCount || 5
  }
  catch (_) {}

  if (!enabled) {
    appStore.connectBackend()
    return
  }

  const baseDelay = 2000
  for (let i = 0; i < maxRetries; i++) {
    try {
      await appStore.connectBackend()
      return // 连接成功
    }
    catch (e) {
      const delay = baseDelay * Math.pow(1.5, i)
      console.warn(`[App] 连接失败，${Math.round(delay / 1000)}s 后重试 (${i + 1}/${maxRetries})`)
      await new Promise(r => setTimeout(r, delay))
    }
  }
  console.error('[App] 连接后端失败，已用尽重试次数')
}

onLaunch(() => {
  console.log('[App] 应用启动')
  connectWithRetry()
})

onShow(() => {
  console.log('[App] App Show')
})

onHide(() => {
  console.log('[App] 应用进入后台')
})
</script>

<style lang="scss">
@import 'uview-plus/index.scss';
@import '@/static/styles/theme.scss';
@import '@/static/styles/common.scss';
</style>
