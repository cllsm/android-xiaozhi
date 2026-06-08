"""Web 测试页面.

提供一个浏览器端的完整测试界面，用于验证后端所有 HTTP API 和 WebSocket 功能。
启动后端后访问 http://127.0.0.1:18080/test 即可打开测试页面。
"""

WEB_TEST_HTML = r"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>android-xiaozhi 后端测试面板</title>
    <style>
        :root {
            --bg: #0f1923;
            --card: #1a2733;
            --border: #2d3f50;
            --text: #e0e6ed;
            --text-dim: #8899aa;
            --accent: #00d4aa;
            --accent-dim: rgba(0,212,170,0.15);
            --red: #ff6b6b;
            --orange: #ffaa5c;
            --blue: #5ca0ff;
            --purple: #b47aff;
            --radius: 8px;
        }
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body {
            font-family: -apple-system, 'Segoe UI', sans-serif;
            background: var(--bg);
            color: var(--text);
            line-height: 1.6;
        }
        .container { max-width: 1200px; margin: 0 auto; padding: 20px; }

        /* 顶部状态栏 */
        .header {
            display: flex; align-items: center; justify-content: space-between;
            padding: 16px 20px; background: var(--card); border-radius: var(--radius);
            margin-bottom: 20px; border: 1px solid var(--border);
        }
        .header h1 { font-size: 18px; font-weight: 600; }
        .header h1 span { color: var(--accent); }
        .status-badge {
            display: flex; align-items: center; gap: 8px;
            padding: 6px 14px; border-radius: 20px; font-size: 13px; font-weight: 500;
        }
        .status-badge.connected { background: rgba(0,212,170,0.15); color: var(--accent); }
        .status-badge.disconnected { background: rgba(255,107,107,0.15); color: var(--red); }
        .status-dot {
            width: 8px; height: 8px; border-radius: 50%;
        }
        .status-badge.connected .status-dot { background: var(--accent); animation: pulse 2s infinite; }
        .status-badge.disconnected .status-dot { background: var(--red); }
        @keyframes pulse { 0%,100% { opacity: 1; } 50% { opacity: 0.4; } }

        /* 网格布局 */
        .grid {
            display: grid; grid-template-columns: repeat(auto-fit, minmax(360px, 1fr));
            gap: 16px;
        }

        /* 卡片 */
        .card {
            background: var(--card); border-radius: var(--radius);
            border: 1px solid var(--border); overflow: hidden;
        }
        .card-header {
            display: flex; align-items: center; justify-content: space-between;
            padding: 12px 16px; border-bottom: 1px solid var(--border);
            font-size: 14px; font-weight: 600;
        }
        .card-header .icon { margin-right: 8px; font-size: 16px; }
        .card-body { padding: 16px; }

        /* 按钮 */
        .btn {
            display: inline-flex; align-items: center; gap: 6px;
            padding: 7px 14px; border-radius: 6px; font-size: 13px;
            font-weight: 500; cursor: pointer; border: 1px solid var(--border);
            background: var(--bg); color: var(--text); transition: all 0.2s;
        }
        .btn:hover { border-color: var(--accent); color: var(--accent); }
        .btn:active { transform: scale(0.97); }
        .btn.primary { background: var(--accent); color: #000; border-color: var(--accent); font-weight: 600; }
        .btn.primary:hover { opacity: 0.85; }
        .btn.danger { border-color: var(--red); color: var(--red); }
        .btn.danger:hover { background: rgba(255,107,107,0.1); }
        .btn-group { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 12px; }

        /* 输入框 */
        .input-group { margin-bottom: 12px; }
        .input-group label { display: block; font-size: 12px; color: var(--text-dim); margin-bottom: 4px; }
        .input-group input, .input-group select, .input-group textarea {
            width: 100%; padding: 8px 12px; border-radius: 6px; border: 1px solid var(--border);
            background: var(--bg); color: var(--text); font-size: 13px; font-family: inherit;
        }
        .input-group textarea { min-height: 60px; resize: vertical; font-family: 'Consolas', monospace; }
        .input-group input:focus, .input-group textarea:focus {
            outline: none; border-color: var(--accent);
        }

        /* 结果/日志区域 */
        .result-box {
            background: var(--bg); border-radius: 6px; padding: 12px;
            font-family: 'Consolas', 'Monaco', monospace; font-size: 12px;
            max-height: 300px; overflow-y: auto; line-height: 1.5;
            border: 1px solid var(--border);
        }
        .result-box .log-entry { margin-bottom: 4px; }
        .result-box .log-entry .time { color: var(--text-dim); }
        .result-box .log-entry.success { color: var(--accent); }
        .result-box .log-entry.error { color: var(--red); }
        .result-box .log-entry.info { color: var(--blue); }
        .result-box .log-entry.warn { color: var(--orange); }
        .result-box .log-entry.event { color: var(--purple); }

        /* 事件面板 */
        .event-log .result-box { max-height: 400px; }

        /* 状态显示 */
        .state-display {
            display: flex; gap: 16px; padding: 12px; background: var(--bg);
            border-radius: 6px; border: 1px solid var(--border); margin-bottom: 12px;
        }
        .state-item { text-align: center; }
        .state-item .label { font-size: 11px; color: var(--text-dim); text-transform: uppercase; }
        .state-item .value { font-size: 18px; font-weight: 700; color: var(--accent); }

        /* 标签 */
        .tag {
            display: inline-block; padding: 2px 8px; border-radius: 4px;
            font-size: 11px; font-weight: 600;
        }
        .tag.get { background: rgba(92,160,255,0.15); color: var(--blue); }
        .tag.post { background: rgba(0,212,170,0.15); color: var(--accent); }
        .tag.put { background: rgba(255,170,92,0.15); color: var(--orange); }
        .tag.ws { background: rgba(180,122,255,0.15); color: var(--purple); }

        /* 工具提示 */
        .tooltip { position: relative; }
        .tooltip::after {
            content: attr(data-tip); position: absolute; bottom: 100%; left: 50%;
            transform: translateX(-50%); padding: 4px 8px; border-radius: 4px;
            background: #333; color: #fff; font-size: 11px; white-space: nowrap;
            opacity: 0; pointer-events: none; transition: opacity 0.2s;
        }
        .tooltip:hover::after { opacity: 1; }

        /* 滚动条美化 */
        ::-webkit-scrollbar { width: 6px; }
        ::-webkit-scrollbar-track { background: transparent; }
        ::-webkit-scrollbar-thumb { background: var(--border); border-radius: 3px; }
        ::-webkit-scrollbar-thumb:hover { background: var(--text-dim); }

        /* 全宽卡片 */
        .full-width { grid-column: 1 / -1; }
    </style>
</head>
<body>
<div class="container">
    <!-- 顶部状态栏 -->
    <div class="header">
        <h1>🤖 <span>android-xiaozhi</span> 后端测试面板</h1>
        <div id="wsStatus" class="status-badge disconnected">
            <div class="status-dot"></div>
            <span>未连接</span>
        </div>
    </div>

    <!-- 设备状态概览 -->
    <div class="card full-width" style="margin-bottom: 16px;">
        <div class="card-header">📊 设备状态概览</div>
        <div class="card-body">
            <div class="state-display">
                <div class="state-item">
                    <div class="label">设备状态</div>
                    <div class="value" id="stateDevice">--</div>
                </div>
                <div class="state-item">
                    <div class="label">监听模式</div>
                    <div class="value" id="stateMode">--</div>
                </div>
                <div class="state-item">
                    <div class="label">协议连接</div>
                    <div class="value" id="stateConnected">--</div>
                </div>
                <div class="state-item">
                    <div class="label">运行时间</div>
                    <div class="value" id="stateUptime">--</div>
                </div>
                <div class="state-item">
                    <div class="label">版本</div>
                    <div class="value" id="stateVersion">--</div>
                </div>
            </div>
        </div>
    </div>

    <div class="grid">
        <!-- HTTP API 测试 -->
        <div class="card">
            <div class="card-header">🌐 HTTP REST API</div>
            <div class="card-body">
                <div class="btn-group">
                    <button class="btn" onclick="apiCall('GET', '/health')">📦 <span class="tag get">GET</span> 健康检查</button>
                    <button class="btn" onclick="apiCall('GET', '/api/status')">📋 <span class="tag get">GET</span> 状态查询</button>
                    <button class="btn" onclick="apiCall('GET', '/api/config')">⚙️ <span class="tag get">GET</span> 全部配置</button>
                </div>
                <div class="btn-group">
                    <button class="btn" onclick="apiCall('GET', '/api/audio/devices')">🎤 <span class="tag get">GET</span> 音频设备</button>
                    <button class="btn" onclick="apiCall('GET', '/api/mcp/tools')">🔧 <span class="tag get">GET</span> MCP工具</button>
                    <button class="btn" onclick="apiCall('GET', '/api/activation')">🔑 <span class="tag get">GET</span> 激活状态</button>
                    <button class="btn" onclick="apiCall('GET', '/api/system')">💻 <span class="tag get">GET</span> 系统信息</button>
                </div>

                <div class="input-group">
                    <label>配置项查询 (GET /api/config?key=...)</label>
                    <div style="display:flex; gap:8px;">
                        <input id="configKey" placeholder="例如: SYSTEM_OPTIONS.NETWORK.WEBSOCKET_URL" />
                        <button class="btn" onclick="apiCall('GET', '/api/config?key=' + document.getElementById('configKey').value)">查询</button>
                    </div>
                </div>

                <div class="input-group">
                    <label>配置项更新 (PUT /api/config)</label>
                    <div style="display:flex; gap:8px;">
                        <input id="configKeyPut" placeholder="key" style="flex:1;" />
                        <input id="configValuePut" placeholder="value" style="flex:1;" />
                        <button class="btn primary" onclick="updateConfig()">更新</button>
                    </div>
                </div>

                <div class="input-group">
                    <label>HTTP 响应结果</label>
                    <div class="result-box" id="httpResult">等待请求...</div>
                </div>
            </div>
        </div>

        <!-- 激活流程 -->
        <div class="card">
            <div class="card-header">🔑 设备激活</div>
            <div class="card-body">
                <div class="btn-group">
                    <button class="btn" onclick="apiCall('GET', '/api/activation')">📋 查询状态</button>
                    <button class="btn primary" onclick="startActivation()">🚀 开始激活</button>
                </div>
                <div class="input-group">
                    <label>验证码</label>
                    <div id="activationCodeDisplay" style="font-size:32px; font-weight:700;
                        text-align:center; padding:20px; background:var(--bg); border-radius:6px;
                        border:1px solid var(--border); letter-spacing:6px; color:var(--accent);
                        min-height:76px; display:flex; align-items:center; justify-content:center;">
                        等待激活...
                    </div>
                </div>
                <div style="text-align:center; margin-bottom:12px;">
                    <a id="activationUrl" href="https://xiaozhi.me/" target="_blank"
                       style="color:var(--blue); font-size:13px; text-decoration:none;">
                        🔗 点击打开 xiaozhi.me 输入验证码 →
                    </a>
                </div>
                <div class="input-group">
                    <label>激活日志</label>
                    <div class="result-box" id="activationLog" style="max-height:200px;">等待激活操作...</div>
                </div>
            </div>
        </div>

        <!-- WebSocket 命令测试 -->
        <div class="card">
            <div class="card-header">⚡ WebSocket 命令</div>
            <div class="card-body">
                <div class="btn-group">
                    <button class="btn primary" onclick="wsConnect()">🔗 连接 WS</button>
                    <button class="btn danger" onclick="wsDisconnect()">🔌 断开 WS</button>
                </div>

                <div class="input-group">
                    <label>监听控制</label>
                    <div class="btn-group">
                        <button class="btn" onclick="wsCommand('start_listening', {mode:'auto_stop'})">🎙️ 开始监听</button>
                        <button class="btn" onclick="wsCommand('start_listening', {mode:'realtime'})">⚡ 实时监听</button>
                        <button class="btn" onclick="wsCommand('stop_listening')">⏹️ 停止监听</button>
                    </div>
                    <div class="btn-group">
                        <button class="btn" onclick="wsCommand('manual_listen_press')">👇 手动按下</button>
                        <button class="btn" onclick="wsCommand('manual_listen_release')">👆 手动释放</button>
                    </div>
                </div>

                <div class="input-group">
                    <label>协议控制</label>
                    <div class="btn-group">
                        <button class="btn" onclick="wsCommand('connect')">🔗 连接服务器</button>
                        <button class="btn danger" onclick="wsCommand('disconnect_server')">❌ 断开服务器</button>
                        <button class="btn" onclick="wsCommand('abort_speaking', {reason:'user_interruption'})">🛑 中断说话</button>
                    </div>
                </div>

                <div class="input-group">
                    <label>其他命令</label>
                    <div class="btn-group">
                        <button class="btn" onclick="wsCommand('start_auto_conversation')">🤖 自动对话</button>
                        <button class="btn" onclick="wsCommand('set_listening_mode', {mode:'auto_stop'})">emode</button>
                    </div>
                </div>

                <div class="input-group">
                    <label>发送文本 (send_text)</label>
                    <div style="display:flex; gap:8px;">
                        <input id="sendTextInput" placeholder="输入文本..." />
                        <button class="btn" onclick="wsCommand('send_text', {text: document.getElementById('sendTextInput').value})">发送</button>
                    </div>
                </div>

                <div class="input-group">
                    <label>唤醒词设置</label>
                    <div style="display:flex; gap:8px; align-items: center;">
                        <label style="font-size:13px;"><input type="checkbox" id="wakeWordEnabled" checked /> 启用</label>
                        <input id="wakeWordSensitivity" type="number" value="0.2" step="0.05" min="0" max="1" style="width:80px;" />
                        <button class="btn" onclick="wsCommand('set_wake_word', {enabled:document.getElementById('wakeWordEnabled').checked, sensitivity:parseFloat(document.getElementById('wakeWordSensitivity').value)})">设置</button>
                    </div>
                </div>

                <div class="input-group">
                    <label>自定义命令</label>
                    <div style="display:flex; gap:8px;">
                        <input id="customAction" placeholder="action" style="flex:1;" />
                        <input id="customParams" placeholder='{"key":"value"}' style="flex:1;" />
                        <button class="btn" onclick="wsCommand(document.getElementById('customAction').value, JSON.parse(document.getElementById('customParams').value || '{}'))">执行</button>
                    </div>
                </div>

                <div class="input-group">
                    <label>命令响应</label>
                    <div class="result-box" id="wsResult">等待连接...</div>
                </div>
            </div>
        </div>

        <!-- MCP 工具调用 -->
        <div class="card">
            <div class="card-header">🔧 MCP 工具调用</div>
            <div class="card-body">
                <div class="btn-group">
                    <button class="btn" onclick="loadMcpTools()">🔄 刷新工具列表</button>
                </div>
                <div class="input-group">
                    <label>工具名称</label>
                    <input id="mcpToolName" placeholder="例如: get_weather" />
                </div>
                <div class="input-group">
                    <label>工具参数 (JSON)</label>
                    <textarea id="mcpToolArgs" placeholder='{"city": "北京"}'></textarea>
                </div>
                <div class="btn-group">
                    <button class="btn primary" onclick="callMcpTool()">▶️ 调用工具</button>
                    <button class="btn" onclick="wsCommand('call_mcp_tool', {tool_name: document.getElementById('mcpToolName').value, arguments: JSON.parse(document.getElementById('mcpToolArgs').value || '{}')})">⚡ WS调用</button>
                </div>
                <div class="input-group">
                    <label>MCP 工具列表</label>
                    <div class="result-box" id="mcpToolsList">点击"刷新工具列表"加载...</div>
                </div>
            </div>
        </div>

        <!-- 原生调用 -->
        <div class="card">
            <div class="card-header">📱 原生调用 (Native Call)</div>
            <div class="card-body">
                <div class="input-group">
                    <label>方法名</label>
                    <input id="nativeMethod" placeholder="例如: volume.set" />
                </div>
                <div class="input-group">
                    <label>参数 (JSON)</label>
                    <textarea id="nativeArgs" placeholder='{"volume": 50}'></textarea>
                </div>
                <div class="btn-group">
                    <button class="btn primary" onclick="wsCommand('native_call', {method:document.getElementById('nativeMethod').value, args:JSON.parse(document.getElementById('nativeArgs').value||'{}')})">▶️ 调用</button>
                </div>
                <div style="margin-top: 12px;">
                    <label style="font-size:12px; color:var(--text-dim);">快速操作:</label>
                    <div class="btn-group" style="margin-top:6px;">
                        <button class="btn" onclick="nativeQuick('volume.set',{volume:50})">🔊 音量50%</button>
                        <button class="btn" onclick="nativeQuick('volume.set',{volume:80})">🔊 音量80%</button>
                        <button class="btn" onclick="nativeQuick('volume.get',{})">🔊 获取音量</button>
                    </div>
                    <div class="btn-group">
                        <button class="btn" onclick="nativeQuick('camera.take_photo',{quality:80})">📷 拍照</button>
                        <button class="btn" onclick="nativeQuick('app_launcher.open',{package:'com.android.settings'})">📱 打开设置</button>
                    </div>
                </div>
            </div>
        </div>

        <!-- WebSocket 事件日志 -->
        <div class="card full-width event-log">
            <div class="card-header">
                📡 WebSocket 事件日志
                <button class="btn" onclick="clearEventLog()" style="padding:4px 10px; font-size:12px;">清除</button>
            </div>
            <div class="card-body">
                <div class="result-box" id="eventLog">等待 WebSocket 事件...</div>
            </div>
        </div>
    </div>
</div>

<script>
// ========== 配置 ==========
const WS_URL = 'ws://127.0.0.1:18080/ws';
const HTTP_BASE = 'http://127.0.0.1:18080';

let ws = null;
let msgId = 0;
let pendingRequests = new Map();
let heartbeatTimer = null;

// ========== WebSocket 管理 ==========

function wsConnect() {
    if (ws && ws.readyState === WebSocket.OPEN) {
        logEvent('warn', 'WebSocket 已连接');
        return;
    }

    logEvent('info', `正在连接 ${WS_URL} ...`);
    ws = new WebSocket(WS_URL);

    ws.onopen = () => {
        updateWsStatus(true);
        logEvent('success', 'WebSocket 连接成功');
        startHeartbeat();
        // 自动获取状态
        apiCall('GET', '/api/status');
    };

    ws.onmessage = (event) => {
        try {
            const msg = JSON.parse(event.data);
            handleWsMessage(msg);
        } catch (e) {
            logEvent('error', `解析消息失败: ${e}`);
        }
    };

    ws.onerror = (e) => {
        logEvent('error', `WebSocket 错误`);
    };

    ws.onclose = () => {
        updateWsStatus(false);
        stopHeartbeat();
        logEvent('warn', 'WebSocket 连接已关闭');
    };
}

function wsDisconnect() {
    if (ws) {
        ws.close();
        ws = null;
    }
}

function handleWsMessage(msg) {
    // 命令响应
    if (msg.type === 'command_response' && msg.id) {
        const pending = pendingRequests.get(msg.id);
        if (pending) {
            clearTimeout(pending.timer);
            pendingRequests.delete(msg.id);
            const resultBox = document.getElementById('wsResult');
            if (msg.data && msg.data.success !== false) {
                resultBox.innerHTML = formatJson(msg.data);
                logEvent('success', `命令响应 [${msg.id}]: ${JSON.stringify(msg.data).substring(0, 100)}...`);
            } else {
                resultBox.innerHTML = `<span style="color:var(--red);">❌ ${formatJson(msg.data)}</span>`;
                logEvent('error', `命令失败 [${msg.id}]: ${msg.data?.error || '未知错误'}`);
            }
        }
        return;
    }

    // 连接确认
    if (msg.type === 'connected') {
        logEvent('success', `后端版本: ${msg.data?.version}`);
        return;
    }

    // 心跳响应
    if (msg.type === 'pong') return;

    // 状态变更事件
    if (msg.type === 'state_change') {
        const state = msg.data?.state || '--';
        document.getElementById('stateDevice').textContent = state;
        logEvent('event', `状态变更 → ${state}`);
    }
    // 连接状态事件
    else if (msg.type === 'connection_status') {
        const connected = msg.data?.connected;
        document.getElementById('stateConnected').textContent = connected ? '已连接' : '未连接';
        logEvent('event', `连接状态: connected=${connected}, audio_channel=${msg.data?.audio_channel}`);
    }
    // 文本回复
    else if (msg.type === 'text_response') {
        logEvent('event', `文本回复 [${msg.data?.source}]: ${msg.data?.text}`);
    }
    // 情绪
    else if (msg.type === 'emotion') {
        logEvent('event', `情绪: ${msg.data?.emotion}`);
    }
    // 唤醒词检测
    else if (msg.type === 'wake_word_detected') {
        logEvent('event', `唤醒词: ${msg.data?.keyword} (置信度: ${msg.data?.confidence})`);
    }
    // 错误
    else if (msg.type === 'error') {
        logEvent('error', `错误: ${msg.data?.message || msg.data?.type || JSON.stringify(msg.data)}`);
    }
    // JSON 消息
    else if (msg.type === 'json_message') {
        logEvent('event', `JSON消息: ${JSON.stringify(msg.data).substring(0, 120)}...`);
    }
    // 激活相关事件
    else if (msg.type === 'activation_required') {
        logActivation('⚠️ 设备需要激活');
        logActivation(`序列号: ${msg.data?.serial_number || '--'}`);
        logEvent('info', '设备需要激活');
    }
    else if (msg.type === 'activation_code') {
        const code = msg.data?.code || '------';
        document.getElementById('activationCodeDisplay').textContent = code;
        if (msg.data?.url) {
            const link = document.getElementById('activationUrl');
            link.href = msg.data.url;
        }
        logActivation(`🔑 验证码: ${code}`);
        logActivation(`   ${msg.data?.message || '请访问 xiaozhi.me 输入验证码'}`);
        logEvent('event', `验证码: ${code}`);
    }
    else if (msg.type === 'activation_result') {
        if (msg.data?.success) {
            document.getElementById('activationCodeDisplay').innerHTML =
                '<span style="color:var(--accent);">✅ 激活成功</span>';
            logActivation('✅ 设备激活成功！');
            logEvent('success', '设备激活成功');
        } else {
            document.getElementById('activationCodeDisplay').innerHTML =
                '<span style="color:var(--red);">❌ 激活失败</span>';
            logActivation('❌ 激活失败: ' + (msg.data?.message || '未知原因'));
            logEvent('error', '设备激活失败');
        }
    }
    else if (msg.type === 'activation_error') {
        logActivation('❌ 错误: ' + msg.data?.message);
        logEvent('error', '激活错误: ' + msg.data?.message);
    }
    // 其他事件
    else {
        logEvent('event', `[${msg.type}] ${JSON.stringify(msg.data).substring(0, 150)}`);
    }
}

function wsCommand(action, params = {}) {
    if (!ws || ws.readyState !== WebSocket.OPEN) {
        logEvent('error', 'WebSocket 未连接，请先点击"连接 WS"');
        return;
    }

    const id = `cmd_${++msgId}`;
    const timeout = 10000;

    const promise = new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
            pendingRequests.delete(id);
            reject(new Error(`命令超时: ${action}`));
        }, timeout);
        pendingRequests.set(id, { resolve, reject, timer });
    });

    ws.send(JSON.stringify({
        type: 'command',
        id,
        data: { action, params },
    }));

    logEvent('info', `→ 命令: ${action} ${JSON.stringify(params).substring(0, 80)}`);

    promise.catch(err => {
        logEvent('error', err.message);
        const resultBox = document.getElementById('wsResult');
        resultBox.innerHTML = `<span style="color:var(--red);">❌ ${err.message}</span>`;
    });
}

function startHeartbeat() {
    stopHeartbeat();
    heartbeatTimer = setInterval(() => {
        if (ws && ws.readyState === WebSocket.OPEN) {
            ws.send(JSON.stringify({ type: 'ping' }));
        }
    }, 30000);
}

function stopHeartbeat() {
    if (heartbeatTimer) {
        clearInterval(heartbeatTimer);
        heartbeatTimer = null;
    }
}

// ========== HTTP API ==========

async function apiCall(method, path) {
    const resultBox = document.getElementById('httpResult');
    try {
        logEvent('info', `${method} ${path}`);
        const url = `${HTTP_BASE}${path}`;
        const options = { method, headers: { 'Content-Type': 'application/json' } };
        const response = await fetch(url, options);
        const data = await response.json();
        resultBox.innerHTML = `<span style="color:var(--accent);">✅ ${method} ${path} → ${response.status}</span>\n${formatJson(data)}`;
        logEvent('success', `${method} ${path} → HTTP ${response.status}`);

        // 更新状态显示
        if (path === '/health') {
            document.getElementById('stateVersion').textContent = data.version || '--';
            document.getElementById('stateUptime').textContent = formatUptime(data.uptime_seconds || 0);
        }
        if (path === '/api/status') {
            document.getElementById('stateDevice').textContent = data.device_state || '--';
            document.getElementById('stateMode').textContent = data.listening_mode || '--';
            document.getElementById('stateConnected').textContent = data.connected ? '已连接' : '未连接';
        }
    } catch (e) {
        resultBox.innerHTML = `<span style="color:var(--red);">❌ ${method} ${path}\n${e.message}</span>`;
        logEvent('error', `${method} ${path} 失败: ${e.message}`);
    }
}

async function updateConfig() {
    const key = document.getElementById('configKeyPut').value;
    const value = document.getElementById('configValuePut').value;
    if (!key) { alert('请输入配置项 key'); return; }

    const resultBox = document.getElementById('httpResult');
    try {
        logEvent('info', `PUT /api/config {key: "${key}", value: "${value}"}`);
        // 尝试解析为 JSON 数字或布尔值
        let parsedValue = value;
        try { parsedValue = JSON.parse(value); } catch {}
        const response = await fetch(`${HTTP_BASE}/api/config`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ key, value: parsedValue }),
        });
        const data = await response.json();
        resultBox.innerHTML = `<span style="color:var(--accent);">✅ PUT /api/config → ${response.status}</span>\n${formatJson(data)}`;
        logEvent('success', `配置更新: ${key} = ${value}`);
    } catch (e) {
        resultBox.innerHTML = `<span style="color:var(--red);">❌ ${e.message}</span>`;
        logEvent('error', `配置更新失败: ${e.message}`);
    }
}

// ========== MCP 工具 ==========

async function loadMcpTools() {
    const listBox = document.getElementById('mcpToolsList');
    try {
        const response = await fetch(`${HTTP_BASE}/api/mcp/tools`);
        const data = await response.json();
        if (data.tools && data.tools.length > 0) {
            listBox.innerHTML = data.tools.map(t => {
                const params = t.properties.map(p => `${p.name}(${p.type}${p.required ? '*' : ''})`).join(', ');
                return `<div style="margin-bottom:8px; padding:6px 8px; background:rgba(0,212,170,0.05); border-radius:4px; cursor:pointer;" onclick="document.getElementById('mcpToolName').value='${t.name}'; document.getElementById('mcpToolArgs').value='${JSON.stringify(Object.fromEntries(t.properties.map(p => [p.name, ''])), null, 2).replace(/'/g, "\\'")}'">
                    <strong style="color:var(--accent);">${t.name}</strong>
                    <span style="color:var(--text-dim);"> — ${t.description}</span>
                    <div style="font-size:11px; color:var(--blue);">${params || '无参数'}</div>
                </div>`;
            }).join('');
            logEvent('success', `加载了 ${data.tools.length} 个 MCP 工具`);
        } else {
            listBox.innerHTML = '<span style="color:var(--text-dim);">暂无可用工具</span>';
        }
    } catch (e) {
        listBox.innerHTML = `<span style="color:var(--red);">加载失败: ${e.message}</span>`;
        logEvent('error', `加载 MCP 工具失败: ${e.message}`);
    }
}

async function callMcpTool() {
    const name = document.getElementById('mcpToolName').value;
    const argsStr = document.getElementById('mcpToolArgs').value;
    if (!name) { alert('请输入工具名称'); return; }

    const listBox = document.getElementById('mcpToolsList');
    try {
        const args = JSON.parse(argsStr || '{}');
        logEvent('info', `调用 MCP 工具: ${name}(${JSON.stringify(args)})`);
        // 通过 WS 调用
        wsCommand('call_mcp_tool', { tool_name: name, arguments: args });
    } catch (e) {
        logEvent('error', `参数 JSON 解析失败: ${e.message}`);
    }
}

// ========== 原生调用快捷操作 ==========

function nativeQuick(method, args) {
    document.getElementById('nativeMethod').value = method;
    document.getElementById('nativeArgs').value = JSON.stringify(args, null, 2);
    wsCommand('native_call', { method, args });
}

// ========== 激活流程 ==========

function startActivation() {
    logActivation('🚀 发送激活命令...');
    wsCommand('activate', {});
}

function logActivation(message) {
    const logBox = document.getElementById('activationLog');
    const now = new Date().toLocaleTimeString('zh-CN', {
        hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit'
    });
    const entry = document.createElement('div');
    entry.className = 'log-entry';
    entry.innerHTML = `<span class="time">[${now}]</span> ${message}`;
    logBox.appendChild(entry);
    logBox.scrollTop = logBox.scrollHeight;
    // 限制条数
    while (logBox.children.length > 200) {
        logBox.removeChild(logBox.firstChild);
    }
}

// ========== 日志 ==========

function logEvent(level, message) {
    const logBox = document.getElementById('eventLog');
    const now = new Date().toLocaleTimeString('zh-CN', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit', fractionalSecondDigits: 3 });
    const entry = document.createElement('div');
    entry.className = `log-entry ${level}`;
    entry.innerHTML = `<span class="time">[${now}]</span> ${message}`;
    logBox.appendChild(entry);
    // 自动滚动到底部
    logBox.scrollTop = logBox.scrollHeight;
    // 限制日志条数
    while (logBox.children.length > 500) {
        logBox.removeChild(logBox.firstChild);
    }
}

function clearEventLog() {
    document.getElementById('eventLog').innerHTML = '<span style="color:var(--text-dim);">日志已清除</span>';
}

// ========== UI 工具 ==========

function updateWsStatus(connected) {
    const badge = document.getElementById('wsStatus');
    badge.className = `status-badge ${connected ? 'connected' : 'disconnected'}`;
    badge.innerHTML = `<div class="status-dot"></div><span>${connected ? '已连接' : '未连接'}</span>`;
}

function formatJson(obj) {
    try {
        return `<pre style="margin:0; white-space:pre-wrap;">${syntaxHighlight(JSON.stringify(obj, null, 2))}</pre>`;
    } catch {
        return String(obj);
    }
}

function syntaxHighlight(json) {
    json = json.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    return json.replace(
        /("(\\u[a-zA-Z0-9]{4}|\\[^u]|[^\\"])*"(\s*:)?|\b(true|false|null)\b|-?\d+(?:\.\d*)?(?:[eE][+\-]?\d+)?)/g,
        function (match) {
            let cls = 'color: #ffaa5c'; // number
            if (/^"/.test(match)) {
                if (/:$/.test(match)) {
                    cls = 'color: #5ca0ff'; // key
                } else {
                    cls = 'color: #00d4aa'; // string
                }
            } else if (/true|false/.test(match)) {
                cls = 'color: #b47aff'; // boolean
            } else if (/null/.test(match)) {
                cls = 'color: #8899aa'; // null
            }
            return `<span style="${cls}">${match}</span>`;
        }
    );
}

function formatUptime(seconds) {
    if (seconds < 60) return `${seconds}s`;
    if (seconds < 3600) return `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
    const h = Math.floor(seconds / 3600);
    const m = Math.floor((seconds % 3600) / 60);
    return `${h}h ${m}m`;
}

// ========== 初始化 ==========

// 页面加载后自动连接
window.addEventListener('load', () => {
    logEvent('info', '页面已加载，尝试自动连接...');
    wsConnect();

    // 定期刷新状态
    setInterval(async () => {
        try {
            const res = await fetch(`${HTTP_BASE}/health`);
            const data = await res.json();
            document.getElementById('stateUptime').textContent = formatUptime(data.uptime_seconds || 0);
            document.getElementById('stateVersion').textContent = data.version || '--';
        } catch {}
    }, 5000);
});
</script>
</body>
</html>
"""
