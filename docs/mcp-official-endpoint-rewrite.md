# Official MCP endpoint rewrite

## Protocol findings

The Xiaozhi MCP endpoint is an independent WebSocket connection. The cloud is
the MCP client and the Android app is the MCP server. The observed handshake is:

1. Cloud sends `initialize`.
2. Android replies with `protocolVersion`, `capabilities.tools`, and `serverInfo`.
3. Cloud sends `notifications/initialized`; no response is required.
4. Cloud sends `tools/list`.
5. Android returns a page of tool definitions and optional `nextCursor`.
6. Cloud sends `tools/call` when the agent chooses a tool.

Messages are bare JSON-RPC 2.0 objects. They are not wrapped in the voice
WebSocket envelope `{"type":"mcp","payload":...}`.

`tools/mcp_endpoint_probe.py` verifies this without printing credentials or
payloads. Pass the endpoint through the `MCP_ENDPOINT` environment variable and
run it in server mode; the cloud initiates `initialize`.

## Runtime design

- Add `mcpEndpointUrl` to developer settings with a built-in official endpoint
  default. A locally saved non-empty value overrides the default.
- Add a dedicated MCP WebSocket manager with exponential reconnect and OkHttp
  ping frames. It runs independently from the voice WebSocket.
- Move tool registration out of the voice connection and into an MCP tool
  registry. The registry only depends on Android capabilities that the tools
  actually need.
- Keep the voice WebSocket MCP handshake as a bootstrap-only endpoint. It
  returns an empty tool list; its only purpose is to receive vision upload
  credentials for gallery, screenshot, camera, and study-frame flows.
- Cloud TTS remains the primary response path. Local TTS is a delayed fallback
  only when the voice link did not produce `tts start` or audio.
- Gallery, screenshot, camera, and study-frame recognition also request cloud
  speech first and fall back to local speech after the same delay.
- Tool execution failures are returned as MCP call-tool results with
  `isError=true`; protocol-level failures remain JSON-RPC errors.

## Payload limits

The official documentation limits MCP results to around 1024 bytes and limits
the tool-list payload by tokens. The implementation therefore:

- Pages tool definitions using a conservative UTF-8 byte budget.
- Uses concise tool descriptions.
- Runs each tool with a timeout shorter than the cloud tool timeout.
- Compacts and bounds every tool result before wrapping it in MCP
  `content[0].text`.
- Appends a short note when a result was truncated; the complete value remains
  visible in the app or stored in the local vision result cache.

## Observability

Logs contain tool names, methods, response ids, byte counts, durations, and
truncation flags. They do not contain endpoint query credentials, complete tool
arguments, or complete tool results.

## Verification

- `.\gradlew.bat :app:testDebugUnitTest`
- `.\gradlew.bat :app:assembleDebug`

Protocol tests cover initialization capability capture, notification handling,
tool-list pagination, escaped-byte truncation, unknown tools, execution errors,
and timeouts.
