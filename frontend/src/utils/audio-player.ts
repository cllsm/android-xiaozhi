/**
 * 音频播放器 — 支持前端播放模式
 *
 * 基于 Web Audio API 的流式 PCM 播放器。
 * 接收后端通过 WebSocket 推送的 Int16 PCM 二进制数据，
 * 转换为 Float32 并通过 AudioContext 无缝衔接播放。
 *
 * 使用场景：
 * - 前端播放模式：后端将 TTS PCM 推送到前端，由此播放器输出
 * - 后端播放模式：此播放器不工作（后端 sounddevice 直接播放）
 */

/** 最大缓冲帧数（超出时丢弃最旧的帧，防止延迟累积） */
const MAX_BUFFERED_DURATION = 0.5 // 秒

/** 音量数据回调类型 */
export type VolumeCallback = (volume: number) => void

export class AudioPlayer {
  private audioCtx: AudioContext | null = null
  private analyser: AnalyserNode | null = null
  private sampleRate: number
  private nextStartTime = 0
  private _isPlaying = false
  private _activeSources: Set<AudioBufferSourceNode> = new Set()

  // 音量分析
  private _volumeCallback: VolumeCallback | null = null
  private _volumeTimer: ReturnType<typeof setInterval> | null = null
  private _analyserData: Uint8Array | null = null

  constructor(sampleRate = 24000) {
    this.sampleRate = sampleRate
  }

  /** 设置音量回调（用于驱动波形动画） */
  onVolume(callback: VolumeCallback | null): void {
    this._volumeCallback = callback
    if (callback && this._isPlaying && !this._volumeTimer) {
      this._startVolumePolling()
    }
    else if (!callback) {
      this._stopVolumePolling()
    }
  }

  /** 确保 AudioContext 已创建并恢复（需在用户交互后调用） */
  ensureContext(): void {
    if (!this.audioCtx) {
      try {
        this.audioCtx = new AudioContext({ sampleRate: this.sampleRate })
        // 创建 AnalyserNode 用于音量分析
        this.analyser = this.audioCtx.createAnalyser()
        this.analyser.fftSize = 256
        this.analyser.connect(this.audioCtx.destination)
      }
      catch (e) {
        console.error('[AudioPlayer] 创建 AudioContext 失败:', e)
        return
      }
    }
    if (this.audioCtx.state === 'suspended') {
      this.audioCtx.resume().catch(() => {})
    }
  }

  /** 播放 PCM Int16 二进制数据 */
  play(pcmData: ArrayBuffer): void {
    this.ensureContext()
    if (!this.audioCtx || !this.analyser) return

    try {
      // Int16 → Float32
      const int16 = new Int16Array(pcmData)
      const float32 = new Float32Array(int16.length)
      for (let i = 0; i < int16.length; i++) {
        float32[i] = int16[i] / 32768
      }

      const numSamples = float32.length
      if (numSamples === 0) return

      const buffer = this.audioCtx.createBuffer(1, numSamples, this.sampleRate)
      buffer.getChannelData(0).set(float32)

      const source = this.audioCtx.createBufferSource()
      source.buffer = buffer
      // 连接到 analyser（而非直接连 destination），analyser 已经连了 destination
      source.connect(this.analyser)

      // 追踪活跃的 source，stop 时统一清理
      this._activeSources.add(source)
      source.onended = () => {
        this._activeSources.delete(source)
        // 所有 source 播完 → 通知停止
        if (this._activeSources.size === 0) {
          this._isPlaying = false
          this._stopVolumePolling()
          if (this._volumeCallback) this._volumeCallback(0)
        }
      }

      // 无缝衔接：基于时间轴调度
      const now = this.audioCtx.currentTime

      // 防止延迟累积：如果缓冲超过阈值，重置到当前时间
      if (this.nextStartTime > now + MAX_BUFFERED_DURATION) {
        this.nextStartTime = now
      }

      if (this.nextStartTime < now) {
        this.nextStartTime = now
      }

      source.start(this.nextStartTime)
      this.nextStartTime += buffer.duration
      this._isPlaying = true

      // 启动音量轮询
      if (this._volumeCallback && !this._volumeTimer) {
        this._startVolumePolling()
      }
    }
    catch (e) {
      console.error('[AudioPlayer] 播放失败:', e)
    }
  }

  /** 停止播放并释放资源 */
  stop(): void {
    // 停止所有活跃的 source
    for (const source of this._activeSources) {
      try {
        source.stop()
      }
      catch (_) {
        // source 可能已经播放结束
      }
    }
    this._activeSources.clear()
    this._stopVolumePolling()

    if (this.audioCtx) {
      this.audioCtx.close().catch(() => {})
      this.audioCtx = null
      this.analyser = null
    }

    this._isPlaying = false
    this.nextStartTime = 0
  }

  /** 重置状态（等同于 stop） */
  reset(): void {
    this.stop()
  }

  /** 更新采样率（配置变更时调用） */
  setSampleRate(rate: number): void {
    if (rate !== this.sampleRate) {
      this.stop()
      this.sampleRate = rate
    }
  }

  /** 是否正在播放 */
  get isPlaying(): boolean {
    return this._isPlaying
  }

  // ========== 内部：音量分析 ==========

  private _startVolumePolling(): void {
    if (this._volumeTimer) return
    this._volumeTimer = setInterval(() => {
      if (!this.analyser || !this._volumeCallback) return
      if (!this._analyserData) {
        this._analyserData = new Uint8Array(this.analyser.frequencyBinCount)
      }
      this.analyser.getByteFrequencyData(this._analyserData)
      // 计算 RMS 音量（0-100）
      let sum = 0
      for (let i = 0; i < this._analyserData.length; i++) {
        sum += this._analyserData[i]
      }
      const avg = sum / this._analyserData.length
      const volume = Math.round((avg / 255) * 100)
      this._volumeCallback(volume)
    }, 50) // 50ms 采样间隔，20fps
  }

  private _stopVolumePolling(): void {
    if (this._volumeTimer) {
      clearInterval(this._volumeTimer)
      this._volumeTimer = null
    }
    this._analyserData = null
  }
}
