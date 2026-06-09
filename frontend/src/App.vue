<script setup lang="ts">
import { useAppStore } from '@/store'

const appStore = useAppStore()

onLaunch(() => {
  console.log('[App] 应用启动')
  appStore.connectBackend()
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
@import '@/static/styles/common.scss';
</style>
