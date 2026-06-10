/**
 * 通用工具函数
 */

/** 格式化时间戳为 HH:MM */
export function formatTimestamp(ts: number): string {
  const d = new Date(ts)
  return `${d.getHours().toString().padStart(2, '0')}:${d.getMinutes().toString().padStart(2, '0')}`
}

/** 设备状态标签 */
export const stateLabels: Record<string, string> = {
  IDLE: '待机',
  CONNECTING: '连接中',
  LISTENING: '聆听中',
  SPEAKING: '回复中',
}

/**
 * 情绪表情映射（单一数据源，EmotionFace 组件引用此表）
 * key: 后端返回的 emotion 字段值
 * value: 对应的 Unicode emoji（空字符串表示不显示）
 */
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
