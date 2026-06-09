/**
 * 后端通信服务
 *
 * 通过 WebSocket 与 Python 后端进行双向通信，
 * 通过 HTTP 进行配置读写和状态查询。
 * 兼容 H5（浏览器 WebSocket）和 App（uni.connectSocket）环境。
 */

/** WebSocket 消息格式 */
interface WsMessage {
  type: string
  data?: any
  id?: string
  timestamp?: number
}

/** 事件回调类型 */
type EventCallback = (data: any) => void

/** 判断是否为 H5 环境 */
function isH5(): boolean {
  // @ts-expect-error 条件编译变量
  // #ifdef H5
  return true
  // #endif
  return false
}

class BackendService {
  /** H5 环境：WebSocket 实例；App 环境：uni.SocketTask */
  private ws: any = null
  private listeners: Map<string, Set<EventCallback>> = new Map()
  private pendingRequests: Map<string, {
    resolve: (value: any) => void
    reject: (reason: any) => void
    timer: ReturnType<typeof setTimeout>
  }> = new Map()
  private messageId = 0
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null
  private heartbeatTimer: ReturnType<typeof setInterval> | null = null
  private backendPort = 18080
  private _isConnected = false
  /** App 环境下后端主机地址（可动态设置，默认电脑局域网 IP） */
  private _appBackendHost = '127.0.0.1'

  /** 设置 App 模式后端地址 */
  setAppBackendHost(host: string): void {
    this._appBackendHost = host
  }

  /** 后端主机地址（动态适配 H5 / App） */
  private get backendHost(): string {
    // #ifdef H5
    if (typeof window !== 'undefined') {
      return window.location.hostname
    }
    // #endif
    // App 环境：使用可配置地址（默认 127.0.0.1 本机 Termux）
    return this._appBackendHost
  }

  private get url(): string {
    return `ws://${this.backendHost}:${this.backendPort}/ws`
  }

  private get httpBase(): string {
    return `http://${this.backendHost}:${this.backendPort}`
  }

  get isConnected(): boolean {
    return this._isConnected
  }

  // #ifdef H5
  /** H5: 使用浏览器 WebSocket */
  private connectH5(connectUrl: string): Promise<void> {
    return new Promise((resolve, reject) => {
      const socket = new WebSocket(connectUrl)
      socket.binaryType = 'arraybuffer' // 关键：浏览器默认 'blob'，必须显式设置
      this.ws = socket

      socket.onopen = () => {
        console.log('[BackendService] WebSocket 已连接 (H5)')
        this._isConnected = true
        this._startHeartbeat()
        this._emitLocal('backend_connected', {})
        resolve()
      }

      socket.onmessage = (event: MessageEvent) => {
        // 二进制消息 = PCM 音频数据
        if (event.data instanceof ArrayBuffer) {
          this._emitLocal('audio', event.data)
          return
        }
        // 文本消息 = JSON
        try {
          const msg: WsMessage = JSON.parse(event.data)
          this._handleMessage(msg)
        }
        catch (e) {
          console.warn('[BackendService] 解析消息失败:', e)
        }
      }

      socket.onerror = (error: Event) => {
        console.error('[BackendService] WebSocket 错误:', error)
        reject(error)
      }

      socket.onclose = () => {
        this._onClose()
      }
    })
  }
  // #endif

  // #ifndef H5
  /** App: 使用 uni.connectSocket */
  private connectApp(connectUrl: string): Promise<void> {
    return new Promise((resolve, reject) => {
      const socketTask = uni.connectSocket({
        url: connectUrl,
        complete: () => {},
      })
      this.ws = socketTask

      socketTask.onOpen(() => {
        console.log('[BackendService] WebSocket 已连接 (App)')
        this._isConnected = true
        this._startHeartbeat()
        this._emitLocal('backend_connected', {})
        resolve()
      })

      socketTask.onMessage((event: { data: any }) => {
        const data = event.data
        // 二进制消息 = PCM 音频数据
        if (data instanceof ArrayBuffer) {
          this._emitLocal('audio', data)
          return
        }
        // App 环境可能以 Blob 形式接收二进制
        if (typeof Blob !== 'undefined' && data instanceof Blob) {
          data.arrayBuffer().then((buf: ArrayBuffer) => {
            this._emitLocal('audio', buf)
          }).catch(() => {})
          return
        }
        // 文本消息 = JSON
        try {
          const msg: WsMessage = JSON.parse(String(data))
          this._handleMessage(msg)
        }
        catch (e) {
          // 解析失败可能是二进制数据被当作文本了
          console.warn('[BackendService] 非JSON消息，长度:', typeof data === 'string' ? data.length : '?')
        }
      })

      socketTask.onError((error: any) => {
        console.error('[BackendService] WebSocket 错误:', JSON.stringify(error))
        reject(new Error(`WebSocket 连接失败: ${connectUrl}`))
      })

      socketTask.onClose(() => {
        this._onClose()
      })
    })
  }
  // #endif

  /** 连接后端 WebSocket */
  connect(url?: string): Promise<void> {
    const connectUrl = url || this.url

    // #ifdef H5
    return this.connectH5(connectUrl)
    // #endif
    // #ifndef H5
    return this.connectApp(connectUrl)
    // #endif
  }

  /** 连接关闭处理 */
  private _onClose(): void {
    console.log('[BackendService] WebSocket 已断开')
    this._isConnected = false
    this.ws = null
    this._stopHeartbeat()
    this._emitLocal('backend_disconnected', {})
    this._scheduleReconnect()
  }

  /** 发送消息（兼容 H5 和 App） */
  private _send(data: string): void {
    if (!this.ws)
      return
    // #ifdef H5
    if (this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(data)
    }
    // #endif
    // #ifndef H5
    this.ws.send({ data })
    // #endif
  }

  /** 发送指令并等待响应 */
  sendCommand(action: string, params?: Record<string, any>): Promise<any> {
    if (!this._isConnected) {
      return Promise.reject(new Error('WebSocket 未连接'))
    }

    const id = `cmd_${++this.messageId}`
    const timeout = 10000

    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pendingRequests.delete(id)
        reject(new Error(`指令超时: ${action}`))
      }, timeout)

      this.pendingRequests.set(id, { resolve, reject, timer })

      this._send(JSON.stringify({
        type: 'command',
        id,
        data: { action, params: params || {} },
      }))
    })
  }

  /** 发送音频二进制数据（麦克风 PCM） */
  private _audioSendCount = 0
  sendAudio(data: ArrayBuffer): void {
    if (!this.ws || !this._isConnected)
      return
    this._audioSendCount++
    if (this._audioSendCount <= 3) {
      console.log(`[BackendService] sendAudio #${this._audioSendCount}, size=${data.byteLength}, isBuffer=${data instanceof ArrayBuffer}`)
    }
    // #ifdef H5
    if (this.ws.readyState === WebSocket.OPEN) {
      this.ws.send(data)
    }
    // #endif
    // #ifndef H5
    this.ws.send({ data })
    // #endif
  }

  /** 监听后端事件 */
  on(event: string, callback: EventCallback): void {
    if (!this.listeners.has(event)) {
      this.listeners.set(event, new Set())
    }
    this.listeners.get(event)!.add(callback)
  }

  /** 取消监听 */
  off(event: string, callback: EventCallback): void {
    this.listeners.get(event)?.delete(callback)
  }

  /** HTTP GET 请求 */
  async httpGet(path: string): Promise<any> {
    // #ifdef H5
    const response = await fetch(`${this.httpBase}${path}`)
    if (!response.ok)
      throw new Error(`HTTP ${response.status}`)
    return response.json()
    // #endif
    // #ifndef H5
    return new Promise((resolve, reject) => {
      uni.request({
        url: `${this.httpBase}${path}`,
        method: 'GET',
        success: (res: any) => {
          if (res.statusCode === 200) {
            resolve(res.data)
          }
          else {
            reject(new Error(`HTTP ${res.statusCode}`))
          }
        },
        fail: (err: any) => reject(err),
      })
    })
    // #endif
  }

  /** HTTP PUT 请求 */
  async httpPut(path: string, body: any): Promise<any> {
    // #ifdef H5
    const response = await fetch(`${this.httpBase}${path}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
    if (!response.ok)
      throw new Error(`HTTP ${response.status}`)
    return response.json()
    // #endif
    // #ifndef H5
    return new Promise((resolve, reject) => {
      uni.request({
        url: `${this.httpBase}${path}`,
        method: 'PUT',
        data: body,
        success: (res: any) => {
          if (res.statusCode === 200) {
            resolve(res.data)
          }
          else {
            reject(new Error(`HTTP ${res.statusCode}`))
          }
        },
        fail: (err: any) => reject(err),
      })
    })
    // #endif
  }

  /** 断开连接 */
  disconnect(): void {
    this._stopHeartbeat()
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    if (this.ws) {
      // #ifdef H5
      this.ws.close()
      // #endif
      // #ifndef H5
      this.ws.close({})
      // #endif
    }
    this.ws = null
    this._isConnected = false
  }

  // ========== 内部方法 ==========

  private _handleMessage(msg: WsMessage): void {
    if (msg.type === 'command_response' && msg.id) {
      const pending = this.pendingRequests.get(msg.id)
      if (pending) {
        clearTimeout(pending.timer)
        this.pendingRequests.delete(msg.id)
        if (msg.data?.success !== false) {
          pending.resolve(msg.data?.result ?? msg.data)
        }
        else {
          pending.reject(new Error(msg.data?.error || '指令执行失败'))
        }
      }
      return
    }

    if (msg.type === 'pong') {
      return
    }

    const callbacks = this.listeners.get(msg.type)
    if (callbacks) {
      callbacks.forEach((cb) => {
        try { cb(msg.data) }
        catch (e) { console.error('[BackendService] 事件回调错误:', e) }
      })
    }
  }

  /** 本地事件（不经过 WebSocket） */
  private _emitLocal(event: string, data: any): void {
    const callbacks = this.listeners.get(event)
    if (callbacks) {
      callbacks.forEach((cb) => {
        try { cb(data) }
        catch (e) { console.error('[BackendService] 本地事件回调错误:', e) }
      })
    }
  }

  private _startHeartbeat(): void {
    this._stopHeartbeat()
    this.heartbeatTimer = setInterval(() => {
      this._send(JSON.stringify({ type: 'ping', data: {} }))
    }, 30000)
  }

  private _stopHeartbeat(): void {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer)
      this.heartbeatTimer = null
    }
  }

  private _scheduleReconnect(): void {
    if (this.reconnectTimer)
      return
    console.log('[BackendService] 3 秒后尝试重连...')
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null
      this.connect().catch(() => {})
    }, 3000)
  }
}

/** 全局单例 */
export const backendService = new BackendService()
