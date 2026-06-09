/**
 * 麦克风录音器
 *
 * H5: 使用 Web Audio API + ScriptProcessorNode
 * App: 使用 uni.getRecorderManager + onFrameRecorded (PCM)
 */

export class AudioRecorder {
  // H5 环境
  private stream: MediaStream | null = null
  private h5Ctx: AudioContext | null = null
  private source: MediaStreamAudioSourceNode | null = null
  private processor: ScriptProcessorNode | null = null

  // App 环境
  private recorderManager: UniApp.RecorderManager | null = null

  private onData: ((pcmData: ArrayBuffer) => void) | null = null
  private _recording = false

  get recording(): boolean {
    return this._recording
  }

  /**
   * 开始录音
   * @param onData 回调函数，接收 PCM float32 二进制数据
   */
  async start(onData: (pcmData: ArrayBuffer) => void): Promise<void> {
    if (this._recording)
      return

    this.onData = onData

    // #ifdef H5
    await this.startH5()
    // #endif

    // #ifndef H5
    this.startApp()
    // #endif
  }

  // #ifdef H5
  private async startH5(): Promise<void> {
    try {
      this.stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          sampleRate: 16000,
          channelCount: 1,
          echoCancellation: true,
          noiseSuppression: true,
        },
      })

      this.h5Ctx = new AudioContext({ sampleRate: 16000 })
      this.source = this.h5Ctx.createMediaStreamSource(this.stream)
      this.processor = this.h5Ctx.createScriptProcessor(4096, 1, 1)

      this.processor.onaudioprocess = (event: AudioProcessingEvent) => {
        if (!this._recording || !this.onData)
          return
        const inputData = event.inputBuffer.getChannelData(0)
        this.onData(new Float32Array(inputData).buffer)
      }

      this.source.connect(this.processor)
      this.processor.connect(this.h5Ctx.destination)
      this._recording = true
      console.log('[AudioRecorder] 开始录音 (H5)')
    }
    catch (e) {
      console.error('[AudioRecorder] 录音启动失败:', e)
      this.stop()
      throw e
    }
  }
  // #endif

  // #ifndef H5
  private startApp(): void {
    try {
      this.recorderManager = uni.getRecorderManager()

      this.recorderManager.onFrameRecorded((res: any) => {
        if (!this._recording || !this.onData)
          return
        // res.frameBuffer 是 ArrayBuffer（PCM int16 格式）
        // 转为 float32 给后端
        const int16 = new Int16Array(res.frameBuffer)
        const float32 = new Float32Array(int16.length)
        for (let i = 0; i < int16.length; i++) {
          float32[i] = int16[i] / 32768
        }
        this.onData(float32.buffer)
      })

      this.recorderManager.onStart(() => {
        console.log('[AudioRecorder] 开始录音 (App)')
      })

      this.recorderManager.onError((err: any) => {
        console.error('[AudioRecorder] 录音错误:', err)
      })

      this.recorderManager.start({
        format: 'pcm',
        sampleRate: 16000,
        numberOfChannels: 1,
        frameSize: 10, // 10帧
      })

      this._recording = true
    }
    catch (e) {
      console.error('[AudioRecorder] 录音启动失败:', e)
      this.stop()
      throw e
    }
  }
  // #endif

  /** 停止录音 */
  stop(): void {
    this._recording = false

    // #ifdef H5
    try {
      this.processor?.disconnect()
      this.source?.disconnect()
      this.h5Ctx?.close()
    }
    catch (_) {}
    if (this.stream) {
      this.stream.getTracks().forEach(track => track.stop())
      this.stream = null
    }
    this.h5Ctx = null
    this.source = null
    this.processor = null
    // #endif

    // #ifndef H5
    if (this.recorderManager) {
      this.recorderManager.stop()
      this.recorderManager = null
    }
    // #endif

    this.onData = null
    console.log('[AudioRecorder] 停止录音')
  }
}
