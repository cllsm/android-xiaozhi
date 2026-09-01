# MCP endpoint support report

## Symptom

The official Xiaozhi MCP endpoint rejected the WebSocket upgrade before the
MCP protocol started for several generated tokens. An older endpoint that
previously completed the official handshake, later generated endpoints, and a
generated endpoint with `endpoint=agent_2262431` all returned:

```text
HTTP/1.1 401 Unauthorized
Invalid token
```

The backend endpoint card says new addresses are generated on every open and
old addresses remain valid. That currently contradicts the observed result.

A subsequent token file used the new claim name `endpointId` and completed the
full handshake successfully, so the client implementation is confirmed working.

## Request tested

```text
GET /mcp/?token=*** HTTP/1.1
Host: api.xiaozhi.me
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Version: 13
```

The token is passed as a URL query parameter. Passing it as
`Authorization: Bearer ***` instead returns:

```text
HTTP/1.1 400 Bad Request
Missing token
```

This confirms that query-token authentication is the expected transport.

## Token evidence

Credentials are intentionally omitted. The tested tokens have these shapes:

| Token | SHA-256 prefix | Result |
| --- | --- | --- |
| Previously completed WebSocket and MCP handshake | `67334ce808692d7c` | Now `401 Invalid token` |
| Generated later | `9aaefe51eedd591a` | `401 Invalid token` |
| Generated after the endpoint claim was corrected to `agent_2262431` | `307033608aa188ec` | `401 Invalid token` |
| Token file using `endpointId=agent_2262431` | `6d1c16bf8be59a73` | WebSocket handshake, `initialize`, and `tools/list` succeeded |

The first two JWT payloads contain:

```json
{
  "agentId": 2262431,
  "endpoint": "agent_226431",
  "purpose": "mcp-endpoint",
  "userId": 962507
}
```

The latest JWT instead contains the apparently correct endpoint identifier:

```json
{
  "agentId": 2262431,
  "endpoint": "agent_2262431",
  "purpose": "mcp-endpoint",
  "userId": 962507
}
```

Its `iat` and `exp` are valid at test time. It was retried after a delay, but
authentication still returned `Invalid token`. Please check why this token is
not accepted and why a token that previously completed the handshake is no
longer accepted.

The successful token from the file contains:

```json
{
  "agentId": 2262431,
  "endpointId": "agent_2262431",
  "purpose": "mcp-endpoint",
  "userId": 962507
}
```

## Client behavior

The same request shape is used by the public `mcp_server_exe` WebSocket mode:

```text
npx mcp_exe --ws wss://api.xiaozhi.me/mcp/?token=... --mcp-config ...
```

Its implementation directly opens the WebSocket URL and starts a standard MCP
server only after the upgrade succeeds. No additional signature or
authorization header is used. Therefore the failure occurs before any
client-specific MCP behavior can run.

## Requested platform check

1. Verify that both endpoint tokens are persisted and enabled.
2. Check why the endpoint authentication service reports `Invalid token`.
3. Confirm whether generating a new address revokes or corrupts old addresses.
4. Check token validation for the corrected JWT whose SHA-256 prefix is
   `307033608aa188ec`.
