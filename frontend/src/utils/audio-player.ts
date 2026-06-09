/**
 * 音频播放器（后端播放模式）
 *
 * 参考py-xiaozhi架构：音频播放统一由后端通过 sounddevice 完成。
 * - 桌面端: sounddevice → 电脑扬声器
 * - Termux: sounddevice + portaudio → 手机扬声器
 *
 * 前端仅负责 UI 展示和指令发送，不负责音频播放。
 * 此类保留为空壳，供未来前端播放需求扩展。
 */

export class AudioPlayer {
  /** 播放 PCM float32 数据（后端播放模式下为空操作） */
  play(_pcmData: ArrayBuffer): void {
    // 音频由后端 sounddevice 统一播放，前端不处理
  }

  /** 停止播放 */
  stop(): void {
    // no-op
  }

  /** 重置状态 */
  reset(): void {
    // no-op
  }
}
