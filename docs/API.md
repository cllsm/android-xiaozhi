# 前后端通信 API 设计

> UniApp X 前端与 Python 后端之间的通信协议定义。

---

## 1. 通信架构

```
UniApp 前端              Python 后端
    │                        │
    ├── HTTP REST ──────────→│  配置读写、状态查询
    │                        │
    ├── WebSocket ──────────→│  实时事件、指令下发
    │←────── WebSocket ──────│  状态推送、音频状态
    │                        │
    └── UTS Native Call ────→│  仅限 Android 环境内
```

### 1.1 端点地址

| 端点 | 地址 | 用途 |
|------|------|------|
| WebSocket | `ws://127.0.0.1:18080/ws` | 双向实时通信 |
| HTTP API | `http://127.0.0.1:18080/api/` | 配置和状态查询 |
| 健康检查 | `http://127.0.0.1:18080/health` | 后端存活检测 |

---

## 2. WebSocket 协议

### 2.1 消息格式

所有 WebSocket 消息使用 JSON 格式：

```typescript
// 通用消息结构
interface WsMessage {
  type: string           // 消息类型
  data?: any             // 消息数据
  timestamp?: number     // 时间戳 (ms)
  id?: string            // 消息 ID（用于请求-响应关联）
}
```

### 2.2 前端 → 后端：指令消息

#### 开始监听

```json
{
  "type": "command",
  "data": {
    "action": "start_listening",
    "params": {
      "mode": "REALTIME"
    }
  }
}
```

#### 停止监听

```json
{
  "type": "command",
  "data": {
    "action": "stop_listening"
  }
}
```

#### 中断说话

```json
{
  "type": "command",
  "data": {
    "action": "abort_speaking"
  }
}
```

#### 连接服务器

```json
{
  "type": "command",
  "data": {
    "action": "connect_server",
    "params": {
      "protocol": "websocket",
      "url": "wss://example.com/ws"
    }
  }
}
```

#### 断开服务器

```json
{
  "type": "command",
  "data": {
    "action": "disconnect_server"
  }
}
```

#### 切换监听模式

```json
{
  "type": "command",
  "data": {
    "action": "set_listening_mode",
    "params": {
      "mode": "AUTO_STOP"
    }
  }
}
```

#### 设备激活

```json
{
  "type": "command",
  "data": {
    "action": "activate",
    "params": {
      "code": "XXXX-XXXX"
    }
  }
}
```

#### MCP 工具调用

```json
{
  "type": "command",
  "data": {
    "action": "call_mcp_tool",
    "params": {
      "tool_name": "get_weather",
      "arguments": {
        "city": "北京"
      }
    }
  }
}
```

#### 唤醒词开关

```json
{
  "type": "command",
  "data": {
    "action": "set_wake_word",
    "params": {
      "enabled": true,
      "sensitivity": 0.2
    }
  }
}
```

#### 原生能力调用（透传到 UTS）

```json
{
  "type": "command",
  "data": {
    "action": "native_call",
    "params": {
      "method": "volume.set",
      "args": {
        "volume": 50
      }
    }
  }
}
```

**后端回复（请求-响应模式）：**

```json
{
  "type": "command_response",
  "id": "<原消息 id>",
  "data": {
    "success": true,
    "result": {}
  }
}
```

### 2.3 后端 → 前端：事件推送

#### 设备状态变更

```json
{
  "type": "state_change",
  "data": {
    "state": "LISTENING",
    "previous_state": "IDLE"
  }
}
```

**状态枚举：** `IDLE` | `CONNECTING` | `LISTENING` | `SPEAKING`

#### 收到文本回复

```json
{
  "type": "text_response",
  "data": {
    "text": "你好，有什么可以帮你的吗？",
    "is_final": true
  }
}
```

#### 情绪表情

```json
{
  "type": "emotion",
  "data": {
    "emotion": "happy",
    "emoji": "😊",
    "animation": "bounce"
  }
}
```

**情绪枚举：** `neutral` | `happy` | `sad` | `thinking` | `surprised` | `angry`

#### 音频状态

```json
{
  "type": "audio_status",
  "data": {
    "is_recording": true,
    "is_playing": false,
    "volume_level": 65,
    "wave_data": [0.1, 0.3, -0.2, ...]
  }
}
```

#### 服务器连接状态

```json
{
  "type": "connection_status",
  "data": {
    "connected": true,
    "protocol": "websocket",
    "server_url": "wss://example.com/ws",
    "session_id": "abc123",
    "latency_ms": 45
  }
}
```

#### 激活状态

```json
{
  "type": "activation_status",
  "data": {
    "activated": true,
    "device_id": "xxx",
    "expires_at": "2026-12-31T00:00:00Z"
  }
}
```

#### 唤醒词检测

```json
{
  "type": "wake_word_detected",
  "data": {
    "keyword": "你好小智",
    "confidence": 0.95
  }
}
```

#### 错误通知

```json
{
  "type": "error",
  "data": {
    "code": "CONNECTION_FAILED",
    "message": "无法连接到服务器",
    "details": {
      "url": "wss://example.com/ws",
      "retry_count": 3
    }
  }
}
```

**错误码枚举：**

| 错误码 | 说明 |
|--------|------|
| `CONNECTION_FAILED` | 服务器连接失败 |
| `AUTH_FAILED` | 认证失败（激活码无效） |
| `AUDIO_INIT_FAILED` | 音频设备初始化失败 |
| `AUDIO_PERMISSION_DENIED` | 麦克风权限被拒绝 |
| `PROTOCOL_ERROR` | 协议解析错误 |
| `MCP_TOOL_ERROR` | MCP 工具执行错误 |
| `WAKE_WORD_ERROR` | 唤醒词引擎错误 |
| `CONFIG_ERROR` | 配置读写错误 |
| `NETWORK_ERROR` | 网络错误 |

#### 日志输出（调试用）

```json
{
  "type": "log",
  "data": {
    "level": "info",
    "message": "WebSocket 已连接",
    "module": "protocols.websocket"
  }
}
```

### 2.4 心跳机制

```json
// 前端每 30 秒发送
{ "type": "ping", "data": {} }

// 后端立即回复
{ "type": "pong", "data": {} }
```

超过 60 秒未收到 pong，前端认为后端断开，触发重连。

---

## 3. HTTP REST API

### 3.1 健康检查

```
GET /health
```

**响应：**
```json
{
  "status": "ok",
  "version": "1.0.0",
  "uptime_seconds": 3600,
  "python_version": "3.10.12"
}
```

### 3.2 获取应用状态

```
GET /api/status
```

**响应：**
```json
{
  "device_state": "IDLE",
  "listening_mode": "REALTIME",
  "connected": true,
  "protocol": "websocket",
  "wake_word_enabled": true,
  "activated": true,
  "session_id": "abc123"
}
```

### 3.3 配置管理

#### 获取配置

```
GET /api/config?key=SYSTEM_OPTIONS.NETWORK.WEBSOCKET_URL
```

**响应：**
```json
{
  "key": "SYSTEM_OPTIONS.NETWORK.WEBSOCKET_URL",
  "value": "wss://api.xiaozhi.com/ws"
}
```

#### 获取所有配置

```
GET /api/config
```

**响应：**
```json
{
  "SYSTEM_OPTIONS": {
    "NETWORK": {
      "PROTOCOL": "websocket",
      "WEBSOCKET_URL": "wss://api.xiaozhi.com/ws",
      "MQTT_BROKER": "mqtt://broker.example.com:8883"
    },
    "AUDIO": {
      "INPUT_SAMPLE_RATE": 16000,
      "OUTPUT_SAMPLE_RATE": 24000,
      "OPUS_OUTPUT_SAMPLE_RATE": 24000
    },
    "WAKE_WORD": {
      "ENABLED": true,
      "SENSITIVITY": 0.2,
      "LANGUAGE": "zh"
    }
  }
}
```

#### 更新配置

```
PUT /api/config
Content-Type: application/json
```

**请求体：**
```json
{
  "key": "SYSTEM_OPTIONS.NETWORK.WEBSOCKET_URL",
  "value": "wss://new-server.com/ws"
}
```

**响应：**
```json
{
  "success": true,
  "key": "SYSTEM_OPTIONS.NETWORK.WEBSOCKET_URL",
  "value": "wss://new-server.com/ws"
}
```

### 3.4 音频设备

#### 获取音频设备列表

```
GET /api/audio/devices
```

**响应（Android 简化版）：**
```json
{
  "input": [
    {
      "id": "default",
      "name": "内置麦克风",
      "sample_rate": 16000,
      "channels": 1
    }
  ],
  "output": [
    {
      "id": "default",
      "name": "内置扬声器",
      "sample_rate": 24000,
      "channels": 1
    },
    {
      "id": "bluetooth",
      "name": "蓝牙耳机",
      "sample_rate": 24000,
      "channels": 1
    }
  ]
}
```

### 3.5 MCP 工具

#### 获取可用工具列表

```
GET /api/mcp/tools
```

**响应：**
```json
{
  "tools": [
    {
      "name": "get_weather",
      "description": "查询天气信息",
      "properties": [
        { "name": "city", "type": "string", "required": true }
      ]
    },
    {
      "name": "set_volume",
      "description": "设置音量",
      "properties": [
        { "name": "volume", "type": "integer", "required": true }
      ]
    }
  ]
}
```

### 3.6 激活

#### 获取激活状态

```
GET /api/activation
```

**响应：**
```json
{
  "activated": true,
  "device_id": "abc123",
  "expires_at": "2026-12-31T00:00:00Z"
}
```

#### 执行激活

```
POST /api/activation
Content-Type: application/json
```

**请求体：**
```json
{
  "code": "XXXX-XXXX"
}
```

**响应：**
```json
{
  "success": true,
  "device_id": "abc123",
  "expires_at": "2026-12-31T00:00:00Z"
}
```

### 3.7 系统信息

```
GET /api/system
```

**响应：**
```json
{
  "version": "1.0.0",
  "python_version": "3.10.12",
  "device_model": "Pixel 7",
  "android_version": "14",
  "battery_level": 85,
  "network_type": "wifi",
  "memory_usage_mb": 128,
  "cpu_usage_percent": 5
}
```

---

## 4. 原生调用协议

前端通过 UTS 插件直接调用 Android 原生能力，**不经过 Python 后端**。

### 4.1 音频控制

```typescript
// UTS 插件直接调用
interface AudioNativeAPI {
  startRecord(options: {
    sampleRate: number       // 16000
    channels: number         // 1
    callback: (data: Float32Array) => void
  }): void

  stopRecord(): void

  startPlay(options: {
    sampleRate: number       // 24000
    channels: number         // 1
  }): void

  writePlayData(data: Float32Array): void

  stopPlay(): void

  getVolume(): number
  setVolume(volume: number): void
}
```

### 4.2 前台服务

```typescript
interface ServiceNativeAPI {
  startForeground(options: {
    title: string
    content: string
    channelId: string
  }): void

  stopForeground(): void

  acquireWakeLock(): void
  releaseWakeLock(): void
}
```

### 4.3 唤醒词

```typescript
interface WakeWordNativeAPI {
  init(options: {
    modelDir: string
    threshold: number
    callback: (keyword: string, confidence: number) => void
  }): void

  feedAudio(data: Float32Array): void
  reset(): void
  release(): void
}
```

### 4.4 摄像头

```typescript
interface CameraNativeAPI {
  takePhoto(options: {
    quality: number          // 0-100
  }): Promise<string>        // 返回 base64 图片

  startStream(options: {
    width: number
    height: number
    fps: number
    callback: (frame: string) => void  // base64 帧
  }): void

  stopStream(): void
}
```

---

## 5. Python 后端实现参考

### 5.1 LocalServer 路由注册

```python
# backend/server.py

from aiohttp import web
import json

class LocalServer:
    def __init__(self, event_bus, state_manager, config_manager, protocol_manager):
        self.event_bus = event_bus
        self.state = state_manager
        self.config = config_manager
        self.protocol = protocol_manager
        self.ws_clients: list[web.WebSocketResponse] = []

        self.app = web.Application()
        self._register_routes()

    def _register_routes(self):
        # HTTP 路由
        self.app.router.add_get('/health', self.health_check)
        self.app.router.add_get('/api/status', self.get_status)
        self.app.router.add_get('/api/config', self.get_config)
        self.app.router.add_put('/api/config', self.set_config)
        self.app.router.add_get('/api/audio/devices', self.get_audio_devices)
        self.app.router.add_get('/api/mcp/tools', self.get_mcp_tools)
        self.app.router.add_get('/api/activation', self.get_activation)
        self.app.router.add_post('/api/activation', self.post_activation)
        self.app.router.add_get('/api/system', self.get_system_info)

        # WebSocket
        self.app.router.add_get('/ws', self.websocket_handler)

    async def start(self, port: int = 18080):
        self.runner = web.AppRunner(self.app)
        await self.runner.setup()
        site = web.TCPSite(self.runner, '127.0.0.1', port)
        await site.start()

    async def stop(self):
        # 关闭所有 WebSocket 连接
        for ws in self.ws_clients:
            await ws.close()
        await self.runner.cleanup()

    async def websocket_handler(self, request: web.Request):
        ws = web.WebSocketResponse()
        await ws.prepare(request)
        self.ws_clients.append(ws)

        try:
            async for msg in ws:
                if msg.type == web.WSMsgType.TEXT:
                    data = json.loads(msg.data)
                    await self._handle_ws_message(ws, data)
                elif msg.type == web.WSMsgType.PING:
                    await ws.pong()
                elif msg.type == web.WSMsgType.ERROR:
                    break
        finally:
            self.ws_clients.remove(ws)

        return ws

    async def _handle_ws_message(self, ws, message: dict):
        """处理前端发来的 WebSocket 消息"""
        msg_type = message.get('type')
        data = message.get('data', {})
        msg_id = message.get('id')

        if msg_type == 'command':
            result = await self._execute_command(data)
            if msg_id:
                await ws.send_json({
                    'type': 'command_response',
                    'id': msg_id,
                    'data': result,
                })
        elif msg_type == 'ping':
            await ws.send_json({'type': 'pong', 'data': {}})

    async def _execute_command(self, data: dict) -> dict:
        """执行前端指令"""
        action = data.get('action')
        params = data.get('params', {})

        handlers = {
            'start_listening': self._cmd_start_listening,
            'stop_listening': self._cmd_stop_listening,
            'abort_speaking': self._cmd_abort_speaking,
            'connect_server': self._cmd_connect_server,
            'disconnect_server': self._cmd_disconnect_server,
            'set_listening_mode': self._cmd_set_listening_mode,
            'activate': self._cmd_activate,
            'set_wake_word': self._cmd_set_wake_word,
            'call_mcp_tool': self._cmd_call_mcp_tool,
            'native_call': self._cmd_native_call,
        }

        handler = handlers.get(action)
        if handler:
            try:
                result = await handler(params)
                return {'success': True, 'result': result}
            except Exception as e:
                return {'success': False, 'error': str(e)}
        return {'success': False, 'error': f'Unknown action: {action}'}

    async def broadcast_event(self, event_type: str, data: dict):
        """向所有前端客户端广播事件"""
        message = json.dumps({
            'type': event_type,
            'data': data,
        })
        for ws in self.ws_clients:
            try:
                await ws.send_str(message)
            except Exception:
                pass
```

### 5.2 EventBus → WebSocket 桥接

```python
# backend/plugins/bridge_plugin.py

from backend.core.event_bus import EventBus, Events
from backend.plugins.base import Plugin

class BridgePlugin(Plugin):
    """将 EventBus 事件转发到前端 WebSocket"""

    name = "bridge"
    priority = 40

    def __init__(self):
        self.local_server = None

    async def setup(self, ctx, cmd):
        self.event_bus = ctx.event_bus
        self.local_server = ctx.local_server

        # 订阅关键事件，转发到前端
        self.event_bus.on(Events.STATE_CHANGED, self._on_state_changed)
        self.event_bus.on(Events.TEXT_RESPONSE, self._on_text_response)
        self.event_bus.on(Events.EMOTION, self._on_emotion)
        self.event_bus.on(Events.CONNECTION_STATUS, self._on_connection_status)
        self.event_bus.on(Events.WAKE_WORD_DETECTED, self._on_wake_word)
        self.event_bus.on(Events.ERROR, self._on_error)

    async def _on_state_changed(self, data):
        await self.local_server.broadcast_event('state_change', {
            'state': data['new_state'],
            'previous_state': data['old_state'],
        })

    async def _on_text_response(self, data):
        await self.local_server.broadcast_event('text_response', {
            'text': data['text'],
            'is_final': data.get('is_final', True),
        })

    async def _on_emotion(self, data):
        await self.local_server.broadcast_event('emotion', data)

    async def _on_connection_status(self, data):
        await self.local_server.broadcast_event('connection_status', data)

    async def _on_wake_word(self, data):
        await self.local_server.broadcast_event('wake_word_detected', data)

    async def _on_error(self, data):
        await self.local_server.broadcast_event('error', data)
```

---

## 6. 前端 BackendService 实现

```typescript
// frontend/src/services/backend.ts

interface WsMessage {
  type: string
  data?: any
  timestamp?: number
  id?: string
}

type EventCallback = (data: any) => void

class BackendService {
  private ws: WebSocket | null = null
  private listeners: Map<string, Set<EventCallback>> = new Map()
  private pendingRequests: Map<string, {
    resolve: (value: any) => void
    reject: (reason: any) => void
  }> = new Map()
  private messageId = 0
  private reconnectTimer: number | null = null

  // 连接后端
  async connect(url: string = 'ws://127.0.0.1:18080/ws'): Promise<void> {
    return new Promise((resolve, reject) => {
      this.ws = new WebSocket(url)

      this.ws.onopen = () => {
        resolve()
        this._startHeartbeat()
      }

      this.ws.onmessage = (event) => {
        const msg: WsMessage = JSON.parse(event.data)
        this._handleMessage(msg)
      }

      this.ws.onerror = (error) => {
        reject(error)
      }

      this.ws.onclose = () => {
        this._scheduleReconnect()
      }
    })
  }

  // 发送指令（异步等待响应）
  async sendCommand(action: string, params?: Record<string, any>): Promise<any> {
    const id = `cmd_${++this.messageId}`

    return new Promise((resolve, reject) => {
      this.pendingRequests.set(id, { resolve, reject })

      this.ws!.send(JSON.stringify({
        type: 'command',
        id,
        data: { action, params: params || {} },
      }))

      // 超时
      setTimeout(() => {
        if (this.pendingRequests.has(id)) {
          this.pendingRequests.delete(id)
          reject(new Error('Command timeout'))
        }
      }, 10000)
    })
  }

  // 监听事件
  on(event: string, callback: EventCallback): void {
    if (!this.listeners.has(event)) {
      this.listeners.set(event, new Set())
    }
    this.listeners.get(event)!.add(callback)
  }

  // 取消监听
  off(event: string, callback: EventCallback): void {
    this.listeners.get(event)?.delete(callback)
  }

  // HTTP 请求
  async httpGet(path: string): Promise<any> {
    const response = await fetch(`http://127.0.0.1:18080${path}`)
    return response.json()
  }

  async httpPut(path: string, body: any): Promise<any> {
    const response = await fetch(`http://127.0.0.1:18080${path}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
    return response.json()
  }

  // 内部方法
  private _handleMessage(msg: WsMessage): void {
    if (msg.type === 'command_response' && msg.id) {
      const pending = this.pendingRequests.get(msg.id)
      if (pending) {
        this.pendingRequests.delete(msg.id)
        if (msg.data?.success) {
          pending.resolve(msg.data.result)
        } else {
          pending.reject(new Error(msg.data?.error || 'Unknown error'))
        }
      }
      return
    }

    // 分发事件
    const callbacks = this.listeners.get(msg.type)
    if (callbacks) {
      callbacks.forEach(cb => cb(msg.data))
    }
  }

  private _startHeartbeat(): void {
    setInterval(() => {
      if (this.ws?.readyState === WebSocket.OPEN) {
        this.ws.send(JSON.stringify({ type: 'ping' }))
      }
    }, 30000)
  }

  private _scheduleReconnect(): void {
    if (this.reconnectTimer) return
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null
      this.connect()
    }, 3000) as unknown as number
  }

  disconnect(): void {
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    this.ws?.close()
    this.ws = null
  }
}

export const backendService = new BackendService()
```

---

## 7. 消息流程示例

### 7.1 用户按下"开始对话"按钮

```
前端 (Vue)                      Python 后端                     小智服务器
   │                               │                               │
   │ 1. 用户点击按钮                │                               │
   │                               │                               │
   │ WS: command.start_listening   │                               │
   │──────────────────────────────→│                               │
   │                               │ 2. 连接服务器                  │
   │                               │ WS: connect                   │
   │                               │──────────────────────────────→│
   │                               │                               │
   │ WS: connection_status         │                               │
   │←──────────────────────────────│                               │
   │ 3. [UI: 显示"已连接"]          │                               │
   │                               │                               │
   │ WS: state_change(LISTENING)   │                               │
   │←──────────────────────────────│                               │
   │ 4. [UI: 显示"正在聆听"动画]    │                               │
   │                               │                               │
   │ WS: audio_status(recording)   │                               │
   │←──────────────────────────────│                               │
   │ 5. [UI: 显示音频波形]          │                               │
```

### 7.2 AI 回复

```
   │                               │ ← Opus 音频 + JSON 文本       │
   │                               │                               │
   │ WS: text_response             │                               │
   │←──────────────────────────────│                               │
   │ [UI: 显示"今天天气不错"]       │                               │
   │                               │                               │
   │ WS: state_change(SPEAKING)    │                               │
   │←──────────────────────────────│                               │
   │ [UI: 显示"正在播放"动画]       │                               │
   │                               │                               │
   │ WS: audio_status(playing)     │                               │
   │←──────────────────────────────│                               │
   │ [UI: 音频波形动画]             │                               │
   │                               │                               │
   │ WS: emotion(happy)            │                               │
   │←──────────────────────────────│                               │
   │ [UI: 显示😊表情]               │                               │
   │                               │                               │
   │ WS: state_change(IDLE)        │                               │
   │←──────────────────────────────│                               │
   │ [UI: 回到待机状态]             │                               │
```
