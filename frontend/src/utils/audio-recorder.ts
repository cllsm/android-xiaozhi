/**
 * 麦克风录音器 - 重构版本
 * 
 * 设计原则:
 * 1. 清晰的策略模式 - 每种录音方案独立实现
 * 2. 统一的接口 - 所有方案输出一致的 Float32 PCM 数据
 * 3. 智能降级 - 自动选择最优方案并支持回退
 * 4. 完善的日志 - 便于调试和问题定位
 * 
 * 方案优先级 (App 环境):
 *   1. WebAudio (getUserMedia) - 最可靠，不走 Java 桥接
 *   2. RecorderManager - uni-app 官方方案
 *   3. Native AudioRecord - 原生方案（最后尝试）
 * 
 * H5 环境仅使用 WebAudio
 */

type RecordingMethod = 'webaudio' | 'recorderManager' | 'native' | 'none'

interface AudioConfig {
  sampleRate: number
  channels: number
  format: 'pcm'
}

export class AudioRecorder {
  // ==================== 配置常量 ====================
  private static readonly CONFIG: AudioConfig = {
    sampleRate: 16000,
    channels: 1,
    format: 'pcm',
  }

  private static readonly SILENT_THRESHOLD = 10      // 连续静音帧数触发音源切换
  private static readonly FALLBACK_THRESHOLD = 20    // 连续静音帧数触发方案回退
  private static readonly DEBUG_FRAME_COUNT = 5      // 前 N 帧输出详细日志

  // renderjs PCM 帧回调
  private _renderjsFrameCount = 0

  /** 从十六进制字符串解码 Int16 PCM → Float32（renderjs 传回，100% 可靠） */
  onPcmFrameHex(hex: string): void {
    if (!this._recording || !this.onData) return

    try {
      const numSamples = hex.length / 4
      const float32 = new Float32Array(numSamples)
      let maxVal = 0

      for (let i = 0; i < numSamples; i++) {
        const hexVal = hex.substring(i * 4, i * 4 + 4)
        const raw = parseInt(hexVal, 16)
        // 无符号 → 有符号 Int16
        const signed = raw > 32767 ? raw - 65536 : raw
        float32[i] = signed / 32768
        const absVal = Math.abs(float32[i])
        if (absVal > maxVal) maxVal = absVal
      }

      this._renderjsFrameCount++
      if (this._renderjsFrameCount <= AudioRecorder.DEBUG_FRAME_COUNT || this._renderjsFrameCount % 100 === 0) {
        console.log(`[AudioRecorder] renderjs #${this._renderjsFrameCount}, samples=${numSamples}, max=${maxVal.toFixed(4)}`)
      }

      this.onData(float32.buffer)
    }
    catch (e: any) {
      console.error('[AudioRecorder] hex PCM 解码失败:', e.message || e)
    }
  }

  /** 从 Int16 数值数组解码并发送（备用） */
  onPcmFrameFromInt16(samples: number[]): void {
    if (!this._recording || !this.onData) return

    try {
      const float32 = new Float32Array(samples.length)
      let maxVal = 0

      for (let i = 0; i < samples.length; i++) {
        float32[i] = samples[i] / 32768
        const absVal = Math.abs(float32[i])
        if (absVal > maxVal) maxVal = absVal
      }

      this._renderjsFrameCount++
      if (this._renderjsFrameCount <= AudioRecorder.DEBUG_FRAME_COUNT || this._renderjsFrameCount % 100 === 0) {
        console.log(`[AudioRecorder] renderjs #${this._renderjsFrameCount}, samples=${samples.length}, max=${maxVal.toFixed(4)}`)
      }

      this.onData(float32.buffer)
    }
    catch (e: any) {
      console.error('[AudioRecorder] renderjs PCM 解码失败:', e.message || e)
    }
  }

  /** 从 Base64 字符串解码 Int16 PCM → Float32（renderjs 传回） */
  onPcmFrame(pcmBase64: string): void {
    if (!this._recording || !this.onData) return

    try {
      const raw = atob(pcmBase64)
      const numSamples = raw.length / 2
      const float32 = new Float32Array(numSamples)
      let maxVal = 0

      for (let i = 0; i < numSamples; i++) {
        const low = raw.charCodeAt(i * 2) & 0xFF
        const high = raw.charCodeAt(i * 2 + 1) & 0xFF
        // Little-endian Int16 → signed
        const sample = (high << 8) | low
        const signed = sample > 32767 ? sample - 65536 : sample
        // ★ 数据已是 Int16 量级（如 -5000, 3000），直接 /32768 得到 [-1, 1]
        float32[i] = signed / 32768
        const absVal = Math.abs(float32[i])
        if (absVal > maxVal) maxVal = absVal
      }

      this._renderjsFrameCount++
      if (this._renderjsFrameCount <= AudioRecorder.DEBUG_FRAME_COUNT || this._renderjsFrameCount % 100 === 0) {
        console.log(`[AudioRecorder] renderjs #${this._renderjsFrameCount}, samples=${numSamples}, max=${maxVal.toFixed(4)}`)
      }

      this.onData(float32.buffer)
    }
    catch (e: any) {
      console.error('[AudioRecorder] renderjs PCM 解码失败:', e.message || e)
    }
  }

  // ==================== 状态管理 ====================
  private _recording = false
  private _method: RecordingMethod = 'none'
  private onData: ((pcmData: ArrayBuffer) => void) | null = null

  // WebAudio 相关
  private webAudio: {
    stream: MediaStream | null
    audioCtx: AudioContext | null
    source: MediaStreamAudioSourceNode | null
    processor: ScriptProcessorNode | null
  } = { stream: null, audioCtx: null, source: null, processor: null }

  // RecorderManager 相关
  private recorderManager: any = null
  private recorderManagerFrameCount = 0

  // Native AudioRecord 相关
  private nativeState: {
    recorder: any
    running: boolean
    readSize: number
    readCount: number
    silentCount: number
    sourceSwitched: boolean
    methodSwitched: boolean
  } = {
    recorder: null,
    running: false,
    readSize: 0,
    readCount: 0,
    silentCount: 0,
    sourceSwitched: false,
    methodSwitched: false,
  }

  // ==================== 公共接口 ====================
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
    this._renderjsFrameCount = 0
    this.log('开始初始化录音...')

    // 策略 0: renderjs (App 环境最优先 — WebView getUserMedia)
    // #ifdef APP
    if (this._renderjsControl) {
      try {
        this.startRenderjs()
        return
      }
      catch (error) {
        this.warn(`renderjs 失败: ${this.getErrorMessage(error)}，尝试其他方案`)
      }
    }
    // #endif

    // 策略 1: WebAudio (H5 环境优先)
    if (this.isWebAudioAvailable()) {
      try {
        await this.startWebAudio()
        return
      }
      catch (error) {
        this.warn(`WebAudio 失败: ${this.getErrorMessage(error)}，尝试其他方案`)
      }
    }

    // 策略 2: RecorderManager (App 环境回退)
    if (this.isRecorderManagerAvailable()) {
      try {
        this.startRecorderManager()
        return
      }
      catch (error) {
        this.warn(`RecorderManager 失败: ${this.getErrorMessage(error)}，尝试原生方案`)
      }
    }

    // 策略 3: Native AudioRecord (最后尝试)
    if (this.isNativeAvailable()) {
      try {
        this.startNative()
        return
      }
      catch (error) {
        this.error(`所有录音方案均失败: ${this.getErrorMessage(error)}`)
        throw error
      }
    }

    throw new Error('当前环境不支持任何录音方案')
  }

  // ==================== renderjs 方案 (App 优先) ====================
  /** renderjs 控制接口 (由页面 mounted 时注入) */
  private _renderjsControl: { startRecording: () => void; stopRecording: () => void } | null = null

  /**
   * 绑定 renderjs 控制方法 (页面 mounted 时调用)
   * @param control { startRecording, stopRecording } — 改变 prop 触发 renderjs
   */
  bindRenderjsControl(control: { startRecording: () => void; stopRecording: () => void }): void {
    this._renderjsControl = control
    this.log('✓ renderjs 控制接口已绑定')
  }

  private startRenderjs(): void {
    if (!this._renderjsControl) {
      throw new Error('renderjs 控制接口未绑定')
    }
    this._recording = true
    this._method = 'webaudio' // renderjs 内部就是 WebAudio
    this._renderjsControl.startRecording()
    this.log('✓ renderjs 录音已请求启动...')
  }

  /** 停止录音 */
  stop(): void {
    if (!this._recording) {
      this.warn('未在录音中')
      return
    }

    this.log(`停止录音 (当前方案: ${this._method})`)
    this._recording = false

    // 清理各方案的资源
    switch (this._method) {
      case 'webaudio':
        // renderjs 录音也走 webaudio 清理逻辑
        if (this._renderjsControl) {
          try { this._renderjsControl.stopRecording() } catch (_) {}
        }
        this.cleanupWebAudio()
        break
      case 'recorderManager':
        this.cleanupRecorderManager()
        break
      case 'native':
        this.cleanupNative()
        break
    }

    this._method = 'none'
    this.onData = null
    this.log('录音已停止')
  }

  // ==================== 环境检测 ====================
  private isWebAudioAvailable(): boolean {
    const hasNavigator = typeof navigator !== 'undefined'
    const hasMediaDevices = hasNavigator && !!navigator.mediaDevices
    const hasGetUserMedia = hasMediaDevices && !!navigator.mediaDevices.getUserMedia
    
    this.debug(`WebAudio 检测: navigator=${hasNavigator}, mediaDevices=${hasMediaDevices}, getUserMedia=${hasGetUserMedia}`)
    
    if (hasGetUserMedia) {
      this.log('✓ 检测到 WebAudio API 可用')
    }
    else {
      this.warn('✗ WebAudio API 不可用（App 环境中通常不可用）')
    }
    
    return hasGetUserMedia
  }

  private isRecorderManagerAvailable(): boolean {
    const hasUni = typeof uni !== 'undefined'
    const hasRecorderManager = hasUni && !!uni.getRecorderManager
    
    this.debug(`RecorderManager 检测: uni=${hasUni}, getRecorderManager=${hasRecorderManager}`)
    
    if (hasRecorderManager) {
      this.log('✓ 检测到 RecorderManager 可用')
    }
    
    return hasRecorderManager
  }

  private isNativeAvailable(): boolean {
    const hasPlus = typeof plus !== 'undefined'
    const hasAndroid = hasPlus && !!plus.android
    
    this.debug(`Native 检测: plus=${hasPlus}, android=${hasAndroid}`)
    
    if (hasAndroid) {
      this.log('✓ 检测到 plus.android 原生环境')
    }
    
    return hasAndroid
  }

  // ==================== WebAudio 方案 ====================
  private async startWebAudio(): Promise<void> {
    this.log('启动 WebAudio 方案...')

    // 获取音频流
    this.webAudio.stream = await navigator.mediaDevices.getUserMedia({
      audio: {
        sampleRate: AudioRecorder.CONFIG.sampleRate,
        channelCount: AudioRecorder.CONFIG.channels,
        echoCancellation: true,
        noiseSuppression: true,
      },
    })
    this.log(`✓ 获取音频流成功 (tracks: ${this.webAudio.stream.getAudioTracks().length})`)

    // 创建音频上下文
    this.webAudio.audioCtx = new AudioContext({ 
      sampleRate: AudioRecorder.CONFIG.sampleRate 
    })
    this.log(`✓ 创建 AudioContext (sampleRate: ${this.webAudio.audioCtx.sampleRate})`)

    // 创建处理节点
    this.webAudio.source = this.webAudio.audioCtx.createMediaStreamSource(this.webAudio.stream)
    this.webAudio.processor = this.webAudio.audioCtx.createScriptProcessor(4096, 1, 1)

    let frameCount = 0
    this.webAudio.processor.onaudioprocess = (event: AudioProcessingEvent) => {
      if (!this._recording || !this.onData) return

      frameCount++
      const data = new Float32Array(event.inputBuffer.getChannelData(0))
      
      // 调试日志
      if (frameCount <= AudioRecorder.DEBUG_FRAME_COUNT) {
        const maxVal = this.calculateMaxAmplitude(data)
        this.debug(`WebAudio 帧 #${frameCount}, samples=${data.length}, max=${maxVal.toFixed(4)}`)
      }

      this.onData(data.buffer)
    }

    // 连接节点
    this.webAudio.source.connect(this.webAudio.processor)
    this.webAudio.processor.connect(this.webAudio.audioCtx.destination)

    this._recording = true
    this._method = 'webaudio'
    this.log('✓ WebAudio 录音已启动')
  }

  private cleanupWebAudio(): void {
    try {
      this.webAudio.processor?.disconnect()
      this.webAudio.source?.disconnect()
      this.webAudio.audioCtx?.close()
      this.webAudio.stream?.getTracks().forEach(track => track.stop())
    }
    catch (error) {
      this.warn(`清理 WebAudio 资源时出错: ${this.getErrorMessage(error)}`)
    }
    finally {
      this.webAudio = { stream: null, audioCtx: null, source: null, processor: null }
    }
  }

  // ==================== RecorderManager 方案 ====================
  private startRecorderManager(): void {
    this.log('启动 RecorderManager 方案...')

    this.recorderManager = uni.getRecorderManager()
    this.recorderManagerFrameCount = 0

    // 绑定事件 - 添加详细日志
    this.recorderManager.onStart(() => {
      this.log('✓ RecorderManager onStart 回调触发')
    })

    this.recorderManager.onStop((res: any) => {
      this.log(`RecorderManager onStop 回调触发, duration=${res?.duration || 'unknown'}, fileSize=${res?.fileSize || 'unknown'}`)
    })

    this.recorderManager.onFrameRecorded((res: any) => {
      this.debug(`[DEBUG] onFrameRecorded 回调触发`)
      
      if (!this._recording) {
        this.warn('onFrameRecorded: _recording=false，忽略此帧')
        return
      }
      
      if (!this.onData) {
        this.warn('onFrameRecorded: onData=null，忽略此帧')
        return
      }

      this.recorderManagerFrameCount++
      
      // 检查 frameBuffer
      if (!res.frameBuffer) {
        this.warn(`帧 #${this.recorderManagerFrameCount}: frameBuffer 为空`)
        return
      }

      const int16 = new Int16Array(res.frameBuffer)
      const float32 = this.int16ToFloat32(int16)

      // 调试日志（前几帧输出详细信息）
      if (this.recorderManagerFrameCount <= AudioRecorder.DEBUG_FRAME_COUNT) {
        const maxVal = this.calculateMaxAmplitude(float32)
        this.debug(`RecorderManager 帧 #${this.recorderManagerFrameCount}, bufferSize=${res.frameBuffer.byteLength}, samples=${int16.length}, max=${maxVal.toFixed(4)}`)
        
        // 输出前几个样本值用于诊断
        if (this.recorderManagerFrameCount === 1) {
          const sampleStr = Array.from(int16.slice(0, Math.min(10, int16.length)))
            .map(v => v.toString())
            .join(', ')
          this.debug(`前10个样本值: [${sampleStr}]`)
        }
      }
      else if (this.recorderManagerFrameCount % 50 === 0) {
        // 每50帧输出一次摘要
        const maxVal = this.calculateMaxAmplitude(float32)
        this.log(`RecorderManager 帧 #${this.recorderManagerFrameCount}, max=${maxVal.toFixed(4)}`)
      }

      try {
        this.onData(float32.buffer)
      }
      catch (error) {
        this.error(`发送音频数据失败: ${this.getErrorMessage(error)}`)
      }
    })

    this.recorderManager.onError((err: any) => {
      this.error(`RecorderManager onError: ${JSON.stringify(err)}`)
    })

    // 开始录音 - 尝试不同的配置
    this.log('调用 recorderManager.start()...')
    
    // 方案 1: 使用较小的 frameSize (更频繁回调)
    const config1 = {
      format: AudioRecorder.CONFIG.format as 'pcm',
      sampleRate: AudioRecorder.CONFIG.sampleRate,
      numberOfChannels: AudioRecorder.CONFIG.channels,
      frameSize: 5,  // 减小到 5KB，更快收到第一帧
      encodeBitRate: 96000,
    }
    
    this.log(`RecorderManager 配置: ${JSON.stringify(config1)}`)
    
    try {
      this.recorderManager.start(config1)
      this.log('✓ recorderManager.start() 调用成功')
    }
    catch (startError: any) {
      this.error(`recorderManager.start() 调用失败: ${this.getErrorMessage(startError)}`)
      throw startError
    }

    this._recording = true
    this._method = 'recorderManager'
    this.log('✓ RecorderManager 录音已启动（等待帧数据...）')
    
    // 分阶段检查是否收到帧数据
    setTimeout(() => {
      if (this._recording && this.recorderManagerFrameCount === 0) {
        this.warn('⚠️ 启动2秒后仍未收到任何帧数据')
      }
    }, 2000)
    
    setTimeout(() => {
      if (this._recording && this.recorderManagerFrameCount === 0) {
        this.error('✗ 启动5秒后仍未收到任何帧数据，RecorderManager 可能不工作')
        
        // 自动回退到 Native AudioRecord
        if (this.isNativeAvailable()) {
          this.warn('尝试自动回退到 Native AudioRecord 方案...')
          this.cleanupRecorderManager()
          
          try {
            this.startNative()
            this.log('✓ 已自动切换到 Native AudioRecord')
          }
          catch (nativeError: any) {
            this.error(`Native AudioRecord 也失败: ${this.getErrorMessage(nativeError)}`)
            this._recording = false
          }
        }
        else {
          this.warn('Native AudioRecord 不可用，无法回退')
          this._recording = false
        }
      }
      else if (this._recording) {
        this.log(`✓ 录音正常，已收到 ${this.recorderManagerFrameCount} 帧数据`)
      }
    }, 5000)
  }

  private cleanupRecorderManager(): void {
    try {
      this.recorderManager?.stop()
    }
    catch (error) {
      this.warn(`清理 RecorderManager 时出错: ${this.getErrorMessage(error)}`)
    }
    finally {
      this.recorderManager = null
      this.recorderManagerFrameCount = 0
    }
  }

  // ==================== Native AudioRecord 方案 ====================
  private startNative(): void {
    this.log('启动 Native AudioRecord 方案...')

    const mainActivity = plus.android.runtimeMainActivity()

    // 检查权限
    this.checkAndRequestPermission(mainActivity)

    // 请求音频焦点
    this.requestAudioFocus(mainActivity)

    // 初始化 AudioRecord
    this.initializeAudioRecord()

    // 启动读取循环
    this.nativeState.running = true
    this.nativeState.readCount = 0
    this.nativeState.silentCount = 0
    this.nativeState.sourceSwitched = false
    this.nativeState.methodSwitched = false

    this.readNativeLoop()

    this._recording = true
    this._method = 'native'
    this.log(`✓ Native AudioRecord 录音已启动 (bufferSize: ${this.nativeState.readSize})`)
  }

  private checkAndRequestPermission(mainActivity: any): void {
    const hasPermission = mainActivity.checkSelfPermission('android.permission.RECORD_AUDIO')
    this.log(`权限状态: ${hasPermission} (0=GRANTED)`)

    if (hasPermission !== 0) {
      this.warn('未获得录音权限，尝试请求...')
      const ActivityCompat = plus.android.importClass('androidx.core.app.ActivityCompat')
      if (ActivityCompat) {
        ActivityCompat.requestPermissions(mainActivity, ['android.permission.RECORD_AUDIO'], 1001)
      }
      else {
        mainActivity.requestPermissions(['android.permission.RECORD_AUDIO'], 1001)
      }
    }
  }

  private requestAudioFocus(mainActivity: any): void {
    try {
      const AudioManager = plus.android.importClass('android.media.AudioManager')
      const Context = plus.android.importClass('android.content.Context')
      const audioManager = mainActivity.getSystemService(Context.AUDIO_SERVICE)
      
      const OnAudioFocusChangeListener = plus.android.implements(
        'android.media.AudioManager$OnAudioFocusChangeListener',
        { onAudioFocusChange: (_focusChange: number) => {} }
      )
      
      const focusResult = audioManager.requestAudioFocus(OnAudioFocusChangeListener, 3, 2)
      this.log(`音频焦点请求结果: ${focusResult} (1=GRANTED)`)
    }
    catch (error) {
      this.warn(`请求音频焦点失败 (非致命): ${this.getErrorMessage(error)}`)
    }
  }

  private initializeAudioRecord(): void {
    const AudioRecord = plus.android.importClass('android.media.AudioRecord')
    const AudioSource = plus.android.importClass('android.media.MediaRecorder$AudioSource')
    const AudioFormat = plus.android.importClass('android.media.AudioFormat')

    const SAMPLE_RATE = AudioRecorder.CONFIG.sampleRate
    const CHANNEL = AudioFormat.CHANNEL_IN_MONO
    const ENCODING = AudioFormat.ENCODING_PCM_16BIT
    
    const bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
    if (bufferSize <= 0) {
      throw new Error(`AudioRecord.getMinBufferSize 返回无效值: ${bufferSize}`)
    }

    const internalBufferSize = bufferSize * 3

    // 尝试不同音源
    const audioSources = [
      { source: AudioSource.MIC, name: 'MIC' },
      { source: AudioSource.VOICE_RECOGNITION, name: 'VOICE_RECOGNITION' },
      { source: AudioSource.DEFAULT, name: 'DEFAULT' },
    ]

    for (const { source, name } of audioSources) {
      try {
        const recorder = new AudioRecord(source, SAMPLE_RATE, CHANNEL, ENCODING, internalBufferSize)
        const state = recorder.getState()
        
        if (state !== 1) {
          recorder.release()
          continue
        }

        recorder.startRecording()
        const recordState = recorder.getRecordingState()
        this.log(`${name} 音源: state=${state}, recordState=${recordState}`)

        if (recordState === 3) {
          this.nativeState.recorder = recorder
          this.nativeState.readSize = Math.min(3200, bufferSize)
          this.log(`✓ 使用 ${name} 音源`)
          return
        }

        recorder.release()
      }
      catch (error) {
        this.warn(`${name} 音源初始化失败: ${this.getErrorMessage(error)}`)
      }
    }

    throw new Error('AudioRecord 无法初始化（所有音源均失败）')
  }

  private readNativeLoop(): void {
    if (!this.nativeState.running || !this.onData || !this.nativeState.recorder) {
      return
    }

    try {
      this.readNativeFrame()
    }
    catch (error) {
      if (this.nativeState.readCount < 3) {
        this.error(`Native 读取错误: ${this.getErrorMessage(error)}`)
      }
    }

    if (this.nativeState.running) {
      setTimeout(() => this.readNativeLoop(), 50)
    }
  }

  private readNativeFrame(): void {
    const ArrayClass = plus.android.importClass('java.lang.reflect.Array')
    const ByteClass = plus.android.importClass('java.lang.Byte')
    const Base64 = plus.android.importClass('android.util.Base64')

    // 创建字节数组并读取数据
    const freshBuffer = ArrayClass.newInstance(ByteClass.TYPE, this.nativeState.readSize)
    const numBytes = this.nativeState.recorder.read(freshBuffer, 0, this.nativeState.readSize)

    if (numBytes == null || numBytes <= 0) {
      if (this.nativeState.readCount < 3) {
        this.warn(`read 返回异常值: ${numBytes}`)
      }
      return
    }

    const numSamples = numBytes / 2
    let float32: Float32Array
    let maxVal = 0

    // ★ 核心策略: Base64 编码传输（避免 byte[] 桥接问题）
    try {
      const encodedStr = String(Base64.encodeToString(freshBuffer, 0, numBytes, Base64.NO_WRAP))
      const raw = atob(encodedStr)
      
      float32 = new Float32Array(numSamples)
      for (let i = 0; i < numSamples; i++) {
        const low = raw.charCodeAt(i * 2) & 0xFF
        const high = raw.charCodeAt(i * 2 + 1) & 0xFF
        const sample = (high << 8) | low
        const signed = sample > 32767 ? sample - 65536 : sample
        float32[i] = signed / 32768
        
        const absVal = Math.abs(float32[i])
        if (absVal > maxVal) maxVal = absVal
      }

      // 调试日志
      if (this.nativeState.readCount < AudioRecorder.DEBUG_FRAME_COUNT) {
        const b0 = raw.charCodeAt(0) & 0xFF
        const b1 = raw.charCodeAt(1) & 0xFF
        this.debug(`Base64方案: byte[0]=${b0}, byte[1]=${b1}, samples=${numSamples}, max=${maxVal.toFixed(4)}`)
      }
    }
    catch (error) {
      this.warn(`Base64 解码失败: ${this.getErrorMessage(error)}`)
      // 降级：返回空数据
      float32 = new Float32Array(numSamples)
      maxVal = 0
    }

    // 发送数据
    if (this.onData) {
      this.onData(float32.buffer)
    }

    this.nativeState.readCount++

    // 定期日志
    if (this.nativeState.readCount <= AudioRecorder.DEBUG_FRAME_COUNT 
        || this.nativeState.readCount % 50 === 0) {
      this.debug(`Native #${this.nativeState.readCount}, size=${numBytes}, max=${maxVal.toFixed(4)}`)
    }

    // 静音检测和处理
    this.handleSilentDetection(maxVal)
  }

  private handleSilentDetection(maxVal: number): void {
    if (maxVal === 0) {
      this.nativeState.silentCount++
    }
    else {
      this.nativeState.silentCount = 0
    }

    // 触发音源切换
    if (this.nativeState.silentCount >= AudioRecorder.SILENT_THRESHOLD 
        && !this.nativeState.sourceSwitched) {
      this.nativeState.sourceSwitched = true
      this.warn(`检测到连续 ${this.nativeState.silentCount} 帧静音，尝试切换音源`)
      this.switchAudioSource()
      this.nativeState.silentCount = 0
    }

    // 触发方案回退
    if (this.nativeState.silentCount >= AudioRecorder.FALLBACK_THRESHOLD 
        && !this.nativeState.methodSwitched) {
      this.nativeState.methodSwitched = true
      this.warn(`多次切换音源仍无声音，回退到 RecorderManager`)
      this.fallbackToRecorderManager()
    }
  }

  private switchAudioSource(): boolean {
    if (!this.nativeState.recorder) return false

    const AudioSource = plus.android.importClass('android.media.MediaRecorder$AudioSource')
    const AudioFormat = plus.android.importClass('android.media.AudioFormat')
    const AudioRecord = plus.android.importClass('android.media.AudioRecord')

    const SAMPLE_RATE = AudioRecorder.CONFIG.sampleRate
    const CHANNEL = AudioFormat.CHANNEL_IN_MONO
    const ENCODING = AudioFormat.ENCODING_PCM_16BIT
    const bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)

    // 释放当前录音器
    try {
      this.nativeState.recorder.stop()
      this.nativeState.recorder.release()
    }
    catch (_) {}
    this.nativeState.recorder = null

    // 尝试其他音源
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
          this.nativeState.recorder = recorder
          this.log(`✓ 静音切换 => ${name} 成功`)
          return true
        }
        recorder.release()
      }
      catch (_) {}
    }

    this.warn('✗ 所有音源切换均失败')
    return false
  }

  private fallbackToRecorderManager(): void {
    this.nativeState.running = false
    this.cleanupNative()

    try {
      this.startRecorderManager()
      this.log('✓ 已回退到 RecorderManager')
    }
    catch (error) {
      this.error(`RecorderManager 回退失败: ${this.getErrorMessage(error)}`)
      this._recording = false
    }
  }

  private cleanupNative(): void {
    try {
      if (this.nativeState.recorder) {
        this.nativeState.recorder.stop()
        this.nativeState.recorder.release()
      }
    }
    catch (error) {
      this.warn(`清理 Native 资源时出错: ${this.getErrorMessage(error)}`)
    }
    finally {
      this.nativeState = {
        recorder: null,
        running: false,
        readSize: 0,
        readCount: 0,
        silentCount: 0,
        sourceSwitched: false,
        methodSwitched: false,
      }
    }
  }

  // ==================== 工具方法 ====================
  private int16ToFloat32(int16: Int16Array): Float32Array {
    const float32 = new Float32Array(int16.length)
    for (let i = 0; i < int16.length; i++) {
      float32[i] = int16[i] / 32768
    }
    return float32
  }

  private calculateMaxAmplitude(data: Float32Array): number {
    let maxVal = 0
    for (let i = 0; i < data.length; i++) {
      const absVal = Math.abs(data[i])
      if (absVal > maxVal) maxVal = absVal
    }
    return maxVal
  }

  private log(message: string): void {
    console.log(`[AudioRecorder] ${message}`)
  }

  private debug(message: string): void {
    console.debug(`[AudioRecorder] ${message}`)
  }

  private warn(message: string): void {
    console.warn(`[AudioRecorder] ${message}`)
  }

  private error(message: string): void {
    console.error(`[AudioRecorder] ${message}`)
  }

  private getErrorMessage(error: any): string {
    return error?.message || String(error)
  }
}

/** 全局单例 — 页面和 store 共用同一个实例，renderjs 绑定在此实例上 */
export const audioRecorder = new AudioRecorder()
