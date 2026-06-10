/**
 * 音频状态管理
 *
 * 管理前端播放器的实时状态，供 UI 组件（如 AudioWave）消费。
 * 播放/录音状态由 app store 在音频事件回调中同步更新。
 */
export const useAudioStore = defineStore('audio', () => {
  const isRecording = ref(false)
  const isPlaying = ref(false)
  const volumeLevel = ref(0)

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
    setRecording,
    setPlaying,
    setVolume,
  }
})
