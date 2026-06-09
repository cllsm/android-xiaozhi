import type { App } from 'vue'
import setupStore from '@/store'

function setupUI(app: App) {
  // uview-plus 初始化（通过 easycom 自动注册组件）
}

export default {
  install(app: App) {
    setupUI(app)
    setupStore(app)
  },
}
