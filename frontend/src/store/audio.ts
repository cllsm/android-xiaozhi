/**
 * 音频状态管理
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useAudioStore = defineStore('audio', () => {
  const isRecording = ref(false)
  const isPlaying = ref(false)
  const volumeLevel = ref(0)
  const waveData = ref<number[]>([])

  function setRecording(value: boolean) {
    isRecording.value = value
  }

  function setPlaying(value: boolean) {
    isPlaying.value = value
  }

  function setVolume(level: number) {
    volumeLevel.value = Math.max(0, Math.min(100, level))
  }

  return {
    isRecording,
    isPlaying,
    volumeLevel,
    waveData,
    setRecording,
    setPlaying,
    setVolume,
  }
})
