<script setup lang="ts">
import { onLaunch, onHide } from '@dcloudio/uni-app'
import { useAppStore } from '@/store/app'

onLaunch(() => {
  console.log('[App] 应用启动')
  const appStore = useAppStore()
  appStore.connectBackend()
})

onHide(() => {
  console.log('[App] 应用进入后台')
})
</script>

<style>
/* 全局样式 */
page {
  background-color: #16213e;
  color: #e0e0e0;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
}
</style>
