<template>
  <view class="container">
    <view class="activation-card">
      <text class="title">设备激活</text>

      <!-- 激活状态 -->
      <view v-if="isActivated" class="status-activated">
        <text class="status-icon">✓</text>
        <text class="status-text">设备已激活</text>
        <view v-if="deviceId" class="info-row">
          <text class="info-label">设备 ID</text>
          <text class="info-value">{{ deviceId }}</text>
        </view>
      </view>

      <view v-else class="status-not-activated">
        <text class="status-icon pending">⏳</text>
        <text class="status-text">设备未激活</text>
      </view>

      <!-- 激活码输入 -->
      <view v-if="!isActivated" class="activation-form">
        <text class="form-label">激活码</text>
        <input
          class="form-input"
          v-model="activationCode"
          placeholder="输入激活码 (XXXX-XXXX)"
          placeholder-class="input-placeholder"
        />
        <view
          class="btn-activate"
          :class="{ disabled: isActivating || !activationCode.trim() }"
          @click="handleActivate"
        >
          <text class="btn-text">{{ isActivating ? '激活中...' : '激活' }}</text>
        </view>
      </view>

      <!-- 错误提示 -->
      <view v-if="errorMsg" class="error-bar">
        <text class="error-text">{{ errorMsg }}</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { backendService } from '@/services/backend'

const isActivated = ref(false)
const isActivating = ref(false)
const deviceId = ref('')
const activationCode = ref('')
const errorMsg = ref('')

onMounted(async () => {
  try {
    const result = await backendService.httpGet('/api/activation')
    isActivated.value = result.activated
    deviceId.value = result.device_id || ''
  } catch (e) {
    console.warn('[Activation] 获取激活状态失败:', e)
  }
})

async function handleActivate() {
  if (isActivating.value || !activationCode.value.trim()) return

  isActivating.value = true
  errorMsg.value = ''

  try {
    const result = await backendService.sendCommand('activate', {
      code: activationCode.value.trim(),
    })
    isActivated.value = true
    deviceId.value = result?.device_id || ''
  } catch (e: any) {
    errorMsg.value = e.message || '激活失败'
  } finally {
    isActivating.value = false
  }
}
</script>

<style scoped lang="scss">
.container {
  min-height: 100vh;
  background-color: #16213e;
  padding: 16px;
  display: flex;
  justify-content: center;
  align-items: flex-start;
  padding-top: 60px;
}

.activation-card {
  width: 100%;
  max-width: 400px;
  background-color: #1f2940;
  border-radius: 16px;
  padding: 24px;
}

.title {
  color: #e0e0e0;
  font-size: 24px;
  font-weight: 700;
  text-align: center;
  margin-bottom: 24px;
}

.status-activated,
.status-not-activated {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 24px 0;
}

.status-icon {
  font-size: 48px;
  color: #66bb6a;
}

.status-icon.pending {
  color: #ffa726;
}

.status-text {
  color: #e0e0e0;
  font-size: 16px;
  margin-top: 8px;
}

.info-row {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}

.info-label {
  color: #9e9e9e;
  font-size: 13px;
}

.info-value {
  color: #4fc3f7;
  font-size: 13px;
}

.activation-form {
  margin-top: 16px;
}

.form-label {
  color: #9e9e9e;
  font-size: 14px;
  margin-bottom: 6px;
}

.form-input {
  height: 44px;
  padding: 0 12px;
  background-color: #263148;
  border-radius: 8px;
  color: #e0e0e0;
  font-size: 16px;
  text-align: center;
  letter-spacing: 2px;
}

.input-placeholder {
  color: #616161;
}

.btn-activate {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 44px;
  background-color: #4fc3f7;
  border-radius: 22px;
  margin-top: 16px;
}

.btn-activate.disabled {
  background-color: #616161;
}

.btn-text {
  color: #fff;
  font-size: 16px;
  font-weight: 600;
}

.error-bar {
  margin-top: 12px;
  padding: 8px 12px;
  background-color: rgba(239, 83, 80, 0.15);
  border-radius: 8px;
}

.error-text {
  color: #ef5350;
  font-size: 13px;
}
</style>
