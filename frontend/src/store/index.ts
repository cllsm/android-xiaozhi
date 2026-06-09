import type { App } from 'vue'
import { createPinia } from 'pinia'
import { createPersistedState } from 'pinia-plugin-persistedstate'

import { useAppStore } from './modules/app'
import { useSettingsStore } from './modules/settings'
import { useAudioStore } from './modules/audio'

function setupStore(app: App) {
  const store = createPinia()
  const piniaPersist = createPersistedState({
    storage: {
      getItem: uni.getStorageSync,
      setItem: uni.setStorageSync,
    },
  })
  store.use(piniaPersist)
  app.use(store)
}

export { useAppStore, useSettingsStore, useAudioStore }
export default setupStore
