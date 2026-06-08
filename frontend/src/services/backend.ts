/**
 * 后端通信服务
 *
 * 通过 WebSocket 与 Python 后端进行双向通信，
 * 通过 HTTP 进行配置读写和状态查询。
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

class BackendService {
  private ws: WebSocket | null = null
  private listeners: Map<string, Set<EventCallback>> = new Map()
  private pendingRequests: Map<string, {
    resolve: (value: any) => void
    reject: (reason: any) => void
    timer: number
  }> = new Map()
  private messageId = 0
  private reconnectTimer: number | null = null
  private heartbeatTimer: number | null = null
  private backendPort = 18080  // 后端端口，H5 和 App 共用
  private _isConnected = false

  /** 后端主机地址（动态适配 H5 / App） */
  private get backendHost(): string {
    // H5 浏览器环境：使用当前页面的 hostname（支持 localhost、局域网 IP 等）
    if (typeof window !== 'undefined' && window.location.protocol !== 'file:') {
      return window.location.hostname
    }
    // App 环境：固定使用 localhost
    return '127.0.0.1'
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

  /** 连接后端 WebSocket */
  connect(url?: string): Promise<void> {
    // url 参数仅做兼容，实际使用动态计算的地址
    const connectUrl = url || this.url

    return new Promise((resolve, reject) => {
      try {
        this.ws = new WebSocket(connectUrl)

        this.ws.onopen = () => {
          console.log('[BackendService] WebSocket 已连接')
          this._isConnected = true
          this._startHeartbeat()
          this._emitLocal('backend_connected', {})
          resolve()
        }

        this.ws.onmessage = (event) => {
          try {
            const msg: WsMessage = JSON.parse(event.data)
            this._handleMessage(msg)
          } catch (e) {
            console.warn('[BackendService] 解析消息失败:', e)
          }
        }

        this.ws.onerror = (error) => {
          console.error('[BackendService] WebSocket 错误:', error)
          reject(error)
        }

        this.ws.onclose = () => {
          console.log('[BackendService] WebSocket 已断开')
          this._isConnected = false
          this._stopHeartbeat()
          this._emitLocal('backend_disconnected', {})
          this._scheduleReconnect()
        }
      } catch (e) {
        reject(e)
      }
    })
  }

  /** 发送指令并等待响应 */
  sendCommand(action: string, params?: Record<string, any>): Promise<any> {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
      return Promise.reject(new Error('WebSocket 未连接'))
    }

    const id = `cmd_${++this.messageId}`
    const timeout = 10000

    return new Promise((resolve, reject) => {
      const timer = window.setTimeout(() => {
        this.pendingRequests.delete(id)
        reject(new Error(`指令超时: ${action}`))
      }, timeout)

      this.pendingRequests.set(id, { resolve, reject, timer })

      this.ws!.send(JSON.stringify({
        type: 'command',
        id,
        data: { action, params: params || {} },
      }))
    })
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
    const response = await fetch(`${this.httpBase}${path}`)
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    return response.json()
  }

  /** HTTP PUT 请求 */
  async httpPut(path: string, body: any): Promise<any> {
    const response = await fetch(`${this.httpBase}${path}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    return response.json()
  }

  /** 断开连接 */
  disconnect(): void {
    this._stopHeartbeat()
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    this.ws?.close()
    this.ws = null
    this._isConnected = false
  }

  // ========== 内部方法 ==========

  private _handleMessage(msg: WsMessage): void {
    // 命令响应
    if (msg.type === 'command_response' && msg.id) {
      const pending = this.pendingRequests.get(msg.id)
      if (pending) {
        clearTimeout(pending.timer)
        this.pendingRequests.delete(msg.id)
        if (msg.data?.success !== false) {
          pending.resolve(msg.data?.result ?? msg.data)
        } else {
          pending.reject(new Error(msg.data?.error || '指令执行失败'))
        }
      }
      return
    }

    // 心跳响应
    if (msg.type === 'pong') {
      return
    }

    // 事件分发
    const callbacks = this.listeners.get(msg.type)
    if (callbacks) {
      callbacks.forEach(cb => {
        try { cb(msg.data) } catch (e) { console.error('[BackendService] 事件回调错误:', e) }
      })
    }
  }

  /** 本地事件（不经过 WebSocket） */
  private _emitLocal(event: string, data: any): void {
    const callbacks = this.listeners.get(event)
    if (callbacks) {
      callbacks.forEach(cb => {
        try { cb(data) } catch (e) { console.error('[BackendService] 本地事件回调错误:', e) }
      })
    }
  }

  private _startHeartbeat(): void {
    this._stopHeartbeat()
    this.heartbeatTimer = window.setInterval(() => {
      if (this.ws?.readyState === WebSocket.OPEN) {
        this.ws.send(JSON.stringify({ type: 'ping', data: {} }))
      }
    }, 30000)
  }

  private _stopHeartbeat(): void {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer)
      this.heartbeatTimer = null
    }
  }

  private _scheduleReconnect(): void {
    if (this.reconnectTimer) return
    console.log('[BackendService] 3 秒后尝试重连...')
    this.reconnectTimer = window.setTimeout(() => {
      this.reconnectTimer = null
      this.connect().catch(() => {})
    }, 3000)
  }
}

/** 全局单例 */
export const backendService = new BackendService()
