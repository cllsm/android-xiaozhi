<template>
  <view class="container">
    <!-- 顶部状态栏 -->
    <StatusBar />

    <!-- 对话区域 -->
    <scroll-view class="chat-area" scroll-y :scroll-top="scrollTop" :scroll-with-animation="true">
      <view class="chat-messages">
        <view v-if="chatHistory.length === 0" class="empty-hint">
          <text class="empty-text">点击下方按钮开始对话</text>
        </view>
        <ChatBubble
          v-for="(msg, idx) in chatHistory"
          :key="idx"
          :text="msg.text"
          :is-user="msg.isUser"
        />
      </view>
    </scroll-view>

    <!-- 当前文本显示 -->
    <view v-if="currentText" class="current-text">
      <text class="current-text-content">{{ currentText }}</text>
    </view>

    <!-- 情绪表情 -->
    <EmotionFace :emotion="currentEmotion" />

    <!-- 音频波形 -->
    <AudioWave :active="deviceState === 'LISTENING' || deviceState === 'SPEAKING'" />

    <!-- 错误提示 -->
    <view v-if="errorMessage" class="error-bar">
      <text class="error-text">{{ errorMessage }}</text>
      <text class="error-close" @click="errorMessage = null">✕</text>
    </view>

    <!-- 底部控制栏 -->
    <view class="control-bar">
      <!-- 开始/停止对话按钮 -->
      <view v-if="deviceState === 'IDLE'" class="btn-primary" @click="handleStart">
        <text class="btn-text">开始对话</text>
      </view>

      <view v-else-if="deviceState === 'LISTENING'" class="btn-listening" @click="handleStop">
        <text class="btn-text">正在聆听...</text>
      </view>

      <view v-else-if="deviceState === 'SPEAKING'" class="btn-speaking" @click="handleAbort">
        <text class="btn-text">正在回复 (点击打断)</text>
      </view>

      <view v-else class="btn-connecting">
        <text class="btn-text">连接中...</text>
      </view>

      <!-- 文本输入 -->
      <view class="input-row">
        <input
          class="text-input"
          v-model="inputText"
          placeholder="输入消息..."
          placeholder-class="input-placeholder"
          @confirm="handleSendText"
          confirm-type="send"
        />
        <view class="btn-send" @click="handleSendText">
          <text class="btn-send-text">发送</text>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { useAppStore } from '@/store/app'
import { storeToRefs } from 'pinia'
import ChatBubble from '@/components/ChatBubble.vue'
import AudioWave from '@/components/AudioWave.vue'
import EmotionFace from '@/components/EmotionFace.vue'
import StatusBar from '@/components/StatusBar.vue'

const appStore = useAppStore()
const { deviceState, currentText, currentEmotion, chatHistory, errorMessage } = storeToRefs(appStore)

const inputText = ref('')
const scrollTop = ref(0)

// 对话历史更新时自动滚动到底部
watch(() => chatHistory.value.length, () => {
  setTimeout(() => { scrollTop.value = scrollTop.value + 1000 }, 50)
})

function handleStart() {
  appStore.startListening()
}

function handleStop() {
  appStore.stopListening()
}

function handleAbort() {
  appStore.abortSpeaking()
}

function handleSendText() {
  const text = inputText.value.trim()
  if (!text) return
  appStore.sendText(text)
  inputText.value = ''
}
</script>

<style scoped lang="scss">
.container {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background-color: #16213e;
}

.chat-area {
  flex: 1;
  padding: 16px;
  overflow: hidden;
}

.empty-hint {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 200px;
}

.empty-text {
  color: #616161;
  font-size: 16px;
}

.current-text {
  padding: 8px 16px;
  background-color: #1f2940;
}

.current-text-content {
  color: #e0e0e0;
  font-size: 14px;
}

.error-bar {
  display: flex;
  align-items: center;
  padding: 8px 16px;
  background-color: rgba(239, 83, 80, 0.15);
}

.error-text {
  flex: 1;
  color: #ef5350;
  font-size: 13px;
}

.error-close {
  color: #ef5350;
  padding: 4px 8px;
  font-size: 16px;
}

.control-bar {
  padding: 12px 16px;
  padding-bottom: 24px;
  background-color: #1a1a2e;
  border-top: 1px solid #263148;
}

.btn-primary,
.btn-listening,
.btn-speaking,
.btn-connecting {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 48px;
  border-radius: 24px;
  margin-bottom: 12px;
}

.btn-primary {
  background-color: #4fc3f7;
}

.btn-listening {
  background-color: #66bb6a;
}

.btn-speaking {
  background-color: #ff6f00;
}

.btn-connecting {
  background-color: #616161;
}

.btn-text {
  color: #fff;
  font-size: 16px;
  font-weight: 600;
}

.input-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.text-input {
  flex: 1;
  height: 40px;
  padding: 0 12px;
  background-color: #263148;
  border-radius: 20px;
  color: #e0e0e0;
  font-size: 14px;
}

.input-placeholder {
  color: #616161;
}

.btn-send {
  display: flex;
  justify-content: center;
  align-items: center;
  width: 60px;
  height: 40px;
  background-color: #4fc3f7;
  border-radius: 20px;
}

.btn-send-text {
  color: #fff;
  font-size: 14px;
  font-weight: 600;
}
</style>
