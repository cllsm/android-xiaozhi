/**
 * 通用工具函数
 */

/** 格式化时间戳为 HH:MM */
export function formatTimestamp(ts: number): string {
  const d = new Date(ts)
  return `${d.getHours().toString().padStart(2, '0')}:${d.getMinutes().toString().padStart(2, '0')}`
}

/** 设备状态中文标签 */
export const stateLabels: Record<string, string> = {
  IDLE: '待机',
  CONNECTING: '连接中',
  LISTENING: '聆听中',
  SPEAKING: '回复中',
}

/** 情绪 emoji 映射 */
export const emotionEmojis: Record<string, string> = {
  neutral: '',
  happy: '😊',
  sad: '😢',
  thinking: '🤔',
  surprised: '😮',
  angry: '😠',
  love: '❤️',
  laugh: '😄',
}

/** 获取状态标签 */
export function getStateLabel(state: string): string {
  return stateLabels[state] || state
}

/** 获取情绪 emoji */
export function getEmotionEmoji(emotion: string): string {
  return emotionEmojis[emotion] || '😊'
}
