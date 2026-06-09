/**
 * 麦克风录音器
 *
 * 三种方案按优先级尝试:
 * 1. Web Audio API (getUserMedia + ScriptProcessorNode)
 * 2. uni.getRecorderManager (onFrameRecorded)
 * 3. plus.android 原生 AudioRecord (Html5Plus App 专用)
 */

export class AudioRecorder {
  // 方案 1: Web Audio API
  private stream: MediaStream | null = null
  private audioCtx: AudioContext | null = null
  private source: MediaStreamAudioSourceNode | null = null
  private processor: ScriptProcessorNode | null = null

  // 方案 2: recorderManager
  private recorderManager: any = null

  // 方案 3: plus.android 原生
  private nativeRecorder: any = null
  private nativeThread: any = null
  private _nativeRunning = false

  private onData: ((pcmData: ArrayBuffer) => void) | null = null
  private _recording = false
  private _method = '' // 'webaudio' | 'recorderManager' | 'native'

  get recording(): boolean {
    return this._recording
  }

  get method(): string {
    return this._method
  }

  /**
   * 开始录音
   * @param onData 回调函数，接收 PCM float32 二进制数据 (16kHz, mono)
   */
  async start(onData: (pcmData: ArrayBuffer) => void): Promise<void> {
    if (this._recording) {
      console.warn('[AudioRecorder] 已在录音中，先停止再重新启动')
      this.stop()
    }

    this.onData = onData

    // 方案 1: Web Audio API
    if (typeof navigator !== 'undefined' && navigator.mediaDevices?.getUserMedia) {
      try {
        await this.startWebAudio()
        return
      }
      catch (e: any) {
        console.warn('[AudioRecorder] getUserMedia 失败:', e.message || e)
      }
    }

    // 方案 3: plus.android 原生 AudioRecord（App 环境专用，最可靠）
    if (typeof plus !== 'undefined' && plus.android) {
      try {
        this.startNative()
        return
      }
      catch (e: any) {
        console.warn('[AudioRecorder] plus.android 原生录音失败:', e.message || e)
      }
    }

    // 方案 2: recorderManager 回退
    this.startRecorderManager()
  }

  /** 方案 1: Web Audio API */
  private async startWebAudio(): Promise<void> {
    this.stream = await navigator.mediaDevices.getUserMedia({
      audio: { sampleRate: 16000, channelCount: 1, echoCancellation: true, noiseSuppression: true },
    })

    this.audioCtx = new AudioContext({ sampleRate: 16000 })
    this.source = this.audioCtx.createMediaStreamSource(this.stream)
    this.processor = this.audioCtx.createScriptProcessor(4096, 1, 1)

    let frameCount = 0
    this.processor.onaudioprocess = (event: AudioProcessingEvent) => {
      if (!this._recording || !this.onData)
        return
      frameCount++
      if (frameCount <= 3)
        console.log(`[AudioRecorder] WebAudio 帧 #${frameCount}`)
      this.onData(new Float32Array(event.inputBuffer.getChannelData(0)).buffer)
    }

    this.source.connect(this.processor)
    this.processor.connect(this.audioCtx.destination)
    this._recording = true
    this._method = 'webaudio'
    console.log('[AudioRecorder] 开始录音 (WebAudio)')
  }

  /** 方案 2: uni.getRecorderManager */
  private startRecorderManager(): void {
    this.recorderManager = uni.getRecorderManager()

    let frameCount = 0
    this.recorderManager.onFrameRecorded((res: any) => {
      if (!this._recording || !this.onData)
        return
      frameCount++
      if (frameCount <= 3)
        console.log(`[AudioRecorder] RecorderManager 帧 #${frameCount}`)
      const int16 = new Int16Array(res.frameBuffer)
      const float32 = new Float32Array(int16.length)
      for (let i = 0; i < int16.length; i++)
        float32[i] = int16[i] / 32768
      this.onData(float32.buffer)
    })

    this.recorderManager.onError((err: any) => {
      console.error('[AudioRecorder] RecorderManager 错误:', err)
    })

    this.recorderManager.start({ format: 'pcm', sampleRate: 16000, numberOfChannels: 1, frameSize: 10 })
    this._recording = true
    this._method = 'recorderManager'
    console.log('[AudioRecorder] 开始录音 (RecorderManager)')
  }

  /** 方案 3: plus.android 原生 AudioRecord */
  private _nativeByteArray: any = null
  private _nativeShortArray: any = null
  private _nativeReadSize = 0
  private _nativeReadCount = 0
  private _nativeSilentCount = 0 // 连续静音帧数
  private _nativeSourceSwitched = false // 是否已尝试切换音源
  // 读取模式：'byte' (byte[]+Base64) | 'short' (short[]+Array.get) | 'probe' (探测中)
  private _nativeReadMode: 'byte' | 'short' | 'probe' = 'probe'

  private startNative(): void {
    const mainActivity = plus.android.runtimeMainActivity()

    // 检查是否已有权限
    const hasPermission = mainActivity.checkSelfPermission('android.permission.RECORD_AUDIO')
    console.log(`[AudioRecorder] RECORD_AUDIO 权限状态: ${hasPermission} (0=GRANTED)`)

    if (hasPermission !== 0) {
      const ActivityCompat = plus.android.importClass('androidx.core.app.ActivityCompat')
      if (ActivityCompat) {
        ActivityCompat.requestPermissions(mainActivity, ['android.permission.RECORD_AUDIO'], 1001)
        console.log('[AudioRecorder] 正在请求 RECORD_AUDIO 权限...')
      }
      else {
        mainActivity.requestPermissions(['android.permission.RECORD_AUDIO'], 1001)
        console.log('[AudioRecorder] 正在请求权限 (旧版 API)...')
      }
    }

    // ★ 请求音频焦点（部分国产机型不获取焦点就无法采集真实音频）
    try {
      const AudioManager = plus.android.importClass('android.media.AudioManager')
      const Context = plus.android.importClass('android.content.Context')
      const audioManager = mainActivity.getSystemService(Context.AUDIO_SERVICE)
      const OnAudioFocusChangeListener = plus.android.implements('android.media.AudioManager$OnAudioFocusChangeListener', {
        onAudioFocusChange(focusChange: number) {},
      })
      const focusResult = audioManager.requestAudioFocus(
        OnAudioFocusChangeListener,
        // STREAM_MUSIC = 3, AUDIOFOCUS_GAIN_TRANSIENT = 2
        3, // streamType
        2, // durationHint
      )
      console.log(`[AudioRecorder] 音频焦点请求结果: ${focusResult} (1=GRANTED)`)
    }
    catch (e: any) {
      console.warn('[AudioRecorder] 请求音频焦点失败 (非致命):', e.message || e)
    }

    const AudioRecord = plus.android.importClass('android.media.AudioRecord')
    const AudioSource = plus.android.importClass('android.media.MediaRecorder$AudioSource')
    const AudioFormat = plus.android.importClass('android.media.AudioFormat')

    const SAMPLE_RATE = 16000
    const CHANNEL = AudioFormat.CHANNEL_IN_MONO
    const ENCODING = AudioFormat.ENCODING_PCM_16BIT
    const bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)

    if (bufferSize <= 0)
      throw new Error(`AudioRecord.getMinBufferSize 返回 ${bufferSize}`)

    const internalBufferSize = bufferSize * 3

    // 按优先级尝试不同音源：MIC > VOICE_RECOGNITION > DEFAULT
    const audioSources = [
      { source: AudioSource.MIC, name: 'MIC' },
      { source: AudioSource.VOICE_RECOGNITION, name: 'VOICE_RECOGNITION' },
      { source: AudioSource.DEFAULT, name: 'DEFAULT' },
    ]

    for (const { source, name } of audioSources) {
      try {
        this.nativeRecorder = new AudioRecord(source, SAMPLE_RATE, CHANNEL, ENCODING, internalBufferSize)

        const state = this.nativeRecorder.getState()
        if (state !== 1) {
          console.log(`[AudioRecorder] ${name} 音源 state=${state}，跳过`)
          this.nativeRecorder.release()
          this.nativeRecorder = null
          continue
        }

        this.nativeRecorder.startRecording()
        const recordState = this.nativeRecorder.getRecordingState()
        console.log(`[AudioRecorder] ${name} 音源 state=${state}, recordState=${recordState}`)

        if (recordState === 3) {
          console.log(`[AudioRecorder] 使用 ${name} 音源`)
          break
        }

        this.nativeRecorder.release()
        this.nativeRecorder = null
      }
      catch (e: any) {
        console.log(`[AudioRecorder] ${name} 音源失败:`, e.message || e)
      }
    }

    if (!this.nativeRecorder)
      throw new Error('AudioRecord 无法初始化 (MIC / VOICE_RECOGNITION / DEFAULT 均失败)')

    // 预分配 byte[] 和 short[] 两种缓冲区，运行时自动选择可用的方式
    const ArrayClass = plus.android.importClass('java.lang.reflect.Array')
    const ByteClass = plus.android.importClass('java.lang.Byte')
    const ShortClass = plus.android.importClass('java.lang.Short')
    this._nativeReadSize = Math.min(3200, bufferSize)
    this._nativeByteArray = ArrayClass.newInstance(ByteClass.TYPE, this._nativeReadSize)
    this._nativeShortArray = ArrayClass.newInstance(ShortClass.TYPE, this._nativeReadSize / 2)

    this._nativeRunning = true
    this._nativeReadCount = 0
    this._nativeSilentCount = 0
    this._nativeSourceSwitched = false
    this._nativeReadMode = 'probe' // 首帧自动探测
    this._method = 'native'
    this._recording = true

    this.readNativeLoop()
    console.log(`[AudioRecorder] 开始录音 (Native AudioRecord), bufferSize=${bufferSize}, internalBuffer=${internalBufferSize}`)
  }

  /**
   * 切换音源（当检测到持续静音时调用）
   */
  private switchAudioSource(): boolean {
    if (!this.nativeRecorder)
      return false

    const AudioSource = plus.android.importClass('android.media.MediaRecorder$AudioSource')
    const AudioFormat = plus.android.importClass('android.media.AudioFormat')
    const AudioRecord = plus.android.importClass('android.media.AudioRecord')
    const SAMPLE_RATE = 16000
    const CHANNEL = AudioFormat.CHANNEL_IN_MONO
    const ENCODING = AudioFormat.ENCODING_PCM_16BIT
    const bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)

    try {
      this.nativeRecorder.stop()
      this.nativeRecorder.release()
    }
    catch (_) {}
    this.nativeRecorder = null

    const sources = [
      { source: AudioSource.MIC, name: 'MIC' },
      { source: AudioSource.VOICE_RECOGNITION, name: 'VOICE_RECOGNITION' },
      { source: AudioSource.DEFAULT, name: 'DEFAULT' },
    ]

    for (const { source, name } of sources) {
      try {
        const recorder = new AudioRecord(source, SAMPLE_RATE, CHANNEL, ENCODING, bufferSize * 3)
        if (recorder.getState() !== 1) {
          recorder.release()
          continue
        }
        recorder.startRecording()
        if (recorder.getRecordingState() === 3) {
          this.nativeRecorder = recorder
          console.log(`[AudioRecorder] 静音切换 => ${name} 音源成功`)
          return true
        }
        recorder.release()
      }
      catch (_) {}
    }

    console.error('[AudioRecorder] 所有音源切换均失败')
    return false
  }

  private readNativeLoop(): void {
    if (!this._nativeRunning || !this.onData || !this.nativeRecorder) {
      return
    }

    try {
      if (this._nativeReadMode === 'probe') {
        // ★ 首帧探测：先用 byte[] 试读，如果返回有效字节数就用 byte 模式
        //   如果 byte[] 也返回 null，再尝试 short[]
        this.probeReadMode()
      }
      else if (this._nativeReadMode === 'short') {
        this.readShortArray()
      }
      else {
        this.readByteArray()
      }
    }
    catch (e: any) {
      console.error('[AudioRecorder] Native read error:', e.message || e)
    }

    if (this._nativeRunning) {
      setTimeout(() => this.readNativeLoop(), 50)
    }
  }

  /** 首帧探测：确定哪种 read 重载在该设备的 plus.android 桥接上可用 */
  private probeReadMode(): void {
    // 1) 先试 byte[] read
    try {
      const numBytes = this.nativeRecorder.read(this._nativeByteArray, 0, this._nativeReadSize)
      if (numBytes != null && numBytes > 0) {
        this._nativeReadMode = 'byte'
        console.log(`[AudioRecorder] 探测结果: byte[] read 可用 (返回 ${numBytes})`)
        // 立即处理这帧数据，不要浪费
        this.processByteFrame(numBytes)
        return
      }
      console.log(`[AudioRecorder] byte[] read 返回 ${numBytes}，尝试 short[]...`)
    }
    catch (e: any) {
      console.log(`[AudioRecorder] byte[] read 异常: ${e.message || e}，尝试 short[]...`)
    }

    // 2) 再试 short[] read
    try {
      const shortLen = this._nativeReadSize / 2
      const numRead = this.nativeRecorder.read(this._nativeShortArray, 0, shortLen)
      if (numRead != null && numRead > 0) {
        this._nativeReadMode = 'short'
        console.log(`[AudioRecorder] 探测结果: short[] read 可用 (返回 ${numRead})`)
        this.processShortFrame(numRead)
        return
      }
      console.log(`[AudioRecorder] short[] read 返回 ${numRead}，两种方式均不可用!`)
    }
    catch (e: any) {
      console.error(`[AudioRecorder] short[] read 也异常: ${e.message || e}`)
    }

    // 3) 都不行，默认用 byte[] 并祈祷（Base64 编码可能有数据）
    this._nativeReadMode = 'byte'
    console.warn('[AudioRecorder] 两种 read 均不可用，回退到 byte[] 模式')
  }

  /** byte[] + Base64 读取方式 */
  private readByteArray(): void {
    let numBytes: any = -1
    try {
      numBytes = this.nativeRecorder.read(this._nativeByteArray, 0, this._nativeReadSize)
    }
    catch (e: any) {
      if (this._nativeReadCount < 3) {
        console.error('[AudioRecorder] byte[] read 失败:', e.message || e)
      }
      return
    }

    if (numBytes != null && numBytes > 0) {
      this.processByteFrame(numBytes)
    }
    else if (this._nativeReadCount < 3) {
      console.log(`[AudioRecorder] byte[] read 返回: ${numBytes}`)
      this._nativeReadCount++
    }
  }

  /** 处理 byte[] 帧 → Base64 解码 → float32 */
  private processByteFrame(numBytes: number): void {
    if (!this.onData) return

    const numSamples = numBytes / 2
    const Base64 = plus.android.importClass('android.util.Base64')
    const encodedStr = String(Base64.encodeToString(this._nativeByteArray, 0, numBytes, Base64.NO_WRAP))

    if (this._nativeReadCount < 3) {
      console.log(`[AudioRecorder] Base64 前20字符: "${encodedStr.substring(0, 20)}", 长度=${encodedStr.length}`)
    }

    const raw = atob(encodedStr)
    const float32 = new Float32Array(numSamples)
    let maxVal = 0
    for (let i = 0; i < numSamples; i++) {
      const low = raw.charCodeAt(i * 2) & 0xFF
      const high = raw.charCodeAt(i * 2 + 1)
      const sample = (high << 8) | low
      float32[i] = sample / 32768
      const absVal = Math.abs(float32[i])
      if (absVal > maxVal) maxVal = absVal
    }
    this.onData(float32.buffer)
    this._nativeReadCount++
    this.handleSilenceDetection(maxVal)
    this.logFrame('byte', numBytes, maxVal)
  }

  /** short[] 读取方式 */
  private readShortArray(): void {
    const ArrayClass = plus.android.importClass('java.lang.reflect.Array')
    const shortLen = this._nativeReadSize / 2
    let numRead: any = -1

    try {
      numRead = this.nativeRecorder.read(this._nativeShortArray, 0, shortLen)
    }
    catch (e: any) {
      if (this._nativeReadCount < 3) {
        console.error('[AudioRecorder] short[] read 失败:', e.message || e)
      }
      return
    }

    if (numRead != null && numRead > 0) {
      this.processShortFrame(numRead)
    }
    else if (this._nativeReadCount < 3) {
      console.log(`[AudioRecorder] short[] read 返回: ${numRead}`)
      this._nativeReadCount++
    }
  }

  /** 处理 short[] 帧 → Array.get 逐元素 → float32 */
  private processShortFrame(numRead: number): void {
    if (!this.onData) return

    const ArrayClass = plus.android.importClass('java.lang.reflect.Array')
    const float32 = new Float32Array(numRead)
    let maxVal = 0
    for (let i = 0; i < numRead; i++) {
      const raw = ArrayClass.get(this._nativeShortArray, i)
      float32[i] = raw / 32768
      const absVal = Math.abs(float32[i])
      if (absVal > maxVal) maxVal = absVal
    }
    this.onData(float32.buffer)
    this._nativeReadCount++
    this.handleSilenceDetection(maxVal)
    this.logFrame('short', numRead, maxVal)
  }

  /** 静音检测 + 自动切换音源 */
  private handleSilenceDetection(maxVal: number): void {
    if (maxVal === 0) {
      this._nativeSilentCount++
    }
    else {
      this._nativeSilentCount = 0
    }

    if (this._nativeSilentCount >= 10 && !this._nativeSourceSwitched) {
      console.warn(`[AudioRecorder] 连续 ${this._nativeSilentCount} 帧静音，尝试切换音源...`)
      this._nativeSourceSwitched = true
      if (this.switchAudioSource()) {
        // 切换成功，重建缓冲区
        const ArrayClass = plus.android.importClass('java.lang.reflect.Array')
        const ByteClass = plus.android.importClass('java.lang.Byte')
        const ShortClass = plus.android.importClass('java.lang.Short')
        this._nativeByteArray = ArrayClass.newInstance(ByteClass.TYPE, this._nativeReadSize)
        this._nativeShortArray = ArrayClass.newInstance(ShortClass.TYPE, this._nativeReadSize / 2)
        this._nativeSilentCount = 0
      }
    }
  }

  /** 帧日志（前 5 帧及每 50 帧输出一次） */
  private logFrame(mode: string, size: number, maxVal: number): void {
    if (this._nativeReadCount <= 5 || this._nativeReadCount % 50 === 0) {
      console.log(`[AudioRecorder] Native #${this._nativeReadCount} [${mode}], size=${size}, max=${maxVal.toFixed(4)}`)
    }
  }

  /** 停止录音 */
  stop(): void {
    this._recording = false
    this._nativeRunning = false

    // 方案 1 清理
    if (this._method === 'webaudio') {
      try { this.processor?.disconnect(); this.source?.disconnect(); this.audioCtx?.close() } catch (_) {}
      this.stream?.getTracks().forEach(t => t.stop())
      this.stream = null; this.audioCtx = null; this.source = null; this.processor = null
    }

    // 方案 2 清理
    if (this._method === 'recorderManager' && this.recorderManager) {
      this.recorderManager.stop()
      this.recorderManager = null
    }

    // 方案 3 清理
    if (this._method === 'native' && this.nativeRecorder) {
      try {
        this.nativeRecorder.stop()
        this.nativeRecorder.release()
      }
      catch (_) {}
      this.nativeRecorder = null
    }

    this._method = ''
    this.onData = null
    console.log('[AudioRecorder] 停止录音')
  }
}
