#!/usr/bin/env python3
"""Probe a Xiaozhi MCP endpoint without printing credentials or payloads."""

import argparse
import base64
import hashlib
import json
import os
import secrets
import socket
import ssl
import struct
import time
from urllib.parse import urlsplit


class WebSocketConnection:
    def __init__(self, url, timeout):
        parts = urlsplit(url)
        if parts.scheme not in ("ws", "wss"):
            raise ValueError("MCP_ENDPOINT must be a ws:// or wss:// URL")
        secure = parts.scheme == "wss"
        host = parts.hostname
        port = parts.port or (443 if secure else 80)
        path = parts.path or "/"
        if parts.query:
            path += "?" + parts.query
        token = None
        if os.environ.get("MCP_AUTH_MODE", "query").lower() == "bearer":
            query = dict(part.split("=", 1) for part in parts.query.split("&") if "=" in part)
            token = query.get("token")
            if token:
                path = parts.path or "/"

        raw_socket = socket.create_connection((host, port), timeout=timeout)
        if secure:
            context = ssl.create_default_context()
            raw_socket = context.wrap_socket(raw_socket, server_hostname=host)
        self.socket = raw_socket
        self.timeout = timeout
        self.buffer = b""

        key = base64.b64encode(secrets.token_bytes(16)).decode("ascii")
        request = (
            f"GET {path} HTTP/1.1\r\n"
            f"Host: {host}\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            "Sec-WebSocket-Version: 13\r\n\r\n"
        )
        if token:
            request = request.replace(
                "Sec-WebSocket-Version: 13\r\n",
                f"Sec-WebSocket-Version: 13\r\nAuthorization: Bearer {token}\r\n",
            )
        self.socket.sendall(request.encode("ascii"))
        response = self.recv_until(b"\r\n\r\n")
        headers = response.decode("iso-8859-1")
        status_line = headers.split("\r\n", 1)[0]
        if " 101 " not in status_line:
            body = self.buffer[:1000].decode("utf-8", errors="replace")
            detail = f" {body}" if body.strip() else ""
            raise RuntimeError("WebSocket handshake failed: " + status_line + detail)
        expected_accept = base64.b64encode(
            hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode("ascii")).digest()
        ).decode("ascii")
        if expected_accept not in headers:
            raise RuntimeError("WebSocket handshake accept header mismatch")

    def recv_until(self, delimiter):
        while delimiter not in self.buffer:
            chunk = self.socket.recv(4096)
            if not chunk:
                raise EOFError
            self.buffer += chunk
        response, self.buffer = self.buffer.split(delimiter, 1)
        return response + delimiter

    def recv_exact(self, size):
        while len(self.buffer) < size:
            chunk = self.socket.recv(max(4096, size - len(self.buffer)))
            if not chunk:
                raise EOFError
            self.buffer += chunk
        data, self.buffer = self.buffer[:size], self.buffer[size:]
        return data

    def send_text(self, text):
        self.send_frame(0x1, text.encode("utf-8"))

    def send_frame(self, opcode, payload):
        header = bytearray([0x80 | opcode])
        length = len(payload)
        mask_bit = 0x80
        if length < 126:
            header.append(mask_bit | length)
        elif length <= 0xFFFF:
            header.append(mask_bit | 126)
            header.extend(struct.pack("!H", length))
        else:
            header.append(mask_bit | 127)
            header.extend(struct.pack("!Q", length))
        mask = secrets.token_bytes(4)
        header.extend(mask)
        masked = bytes(byte ^ mask[index % 4] for index, byte in enumerate(payload))
        self.socket.sendall(bytes(header) + masked)

    def recv_message(self):
        fragments = []
        while True:
            first_two = self.recv_exact(2)
            fin = bool(first_two[0] & 0x80)
            opcode = first_two[0] & 0x0F
            masked = bool(first_two[1] & 0x80)
            length = first_two[1] & 0x7F
            if length == 126:
                length = struct.unpack("!H", self.recv_exact(2))[0]
            elif length == 127:
                length = struct.unpack("!Q", self.recv_exact(8))[0]
            mask = self.recv_exact(4) if masked else None
            payload = self.recv_exact(length)
            if mask:
                payload = bytes(byte ^ mask[index % 4] for index, byte in enumerate(payload))

            if opcode == 0x8:
                self.send_frame(0x8, payload[:2])
                raise EOFError("peer closed the WebSocket")
            if opcode == 0x9:
                self.send_frame(0xA, payload)
                continue
            if opcode == 0xA:
                continue
            fragments.append(payload)
            if fin:
                data = b"".join(fragments)
                if opcode == 0x2:
                    return "binary", data
                return "text", data.decode("utf-8")

    def close(self):
        try:
            self.send_frame(0x8, struct.pack("!H", 1000))
        except Exception:
            pass
        self.socket.close()


def public_shape(value, depth=0):
    if depth > 2:
        return "..."
    if isinstance(value, dict):
        keys = sorted(str(key) for key in value.keys())
        return {key: public_shape(value[key], depth + 1) for key in keys[:8]}
    if isinstance(value, list):
        return {"count": len(value), "first": public_shape(value[0], depth + 1) if value else None}
    if isinstance(value, str):
        return {"length": len(value)}
    if value is None or isinstance(value, (bool, int, float)):
        return value
    return type(value).__name__


def jsonrpc_response(request_id, result=None, error=None):
    response = {"jsonrpc": "2.0", "id": request_id}
    if error is None:
        response["result"] = result
    else:
        response["error"] = error
    return response


def initialize_result(protocol_version):
    return {
        "protocolVersion": protocol_version or "2024-11-05",
        "capabilities": {"tools": {}},
        "serverInfo": {"name": "python-mcp-probe", "version": "1.0.0"},
    }


def probe_tool():
    return {
        "name": "probe_echo",
        "description": "Return a short acknowledgement.",
        "inputSchema": {
            "type": "object",
            "properties": {"text": {"type": "string"}},
            "required": ["text"],
        },
    }


def run_server_mode(connection, duration):
    print("mode=server wait_for_cloud_request", flush=True)
    deadline = time.monotonic() + duration
    wrapped_mode = None
    while time.monotonic() < deadline:
        connection.socket.settimeout(max(0.1, deadline - time.monotonic()))
        try:
            kind, text = connection.recv_message()
        except socket.timeout:
            continue
        if kind != "text":
            print("received_non_text_frame=" + kind, flush=True)
            continue
        try:
            message = json.loads(text)
        except json.JSONDecodeError:
            print("received_invalid_json_length=" + str(len(text)), flush=True)
            continue

        payload = message
        is_wrapped = isinstance(message, dict) and message.get("type") == "mcp"
        if is_wrapped:
            payload = message.get("payload")
            wrapped_mode = True
        elif wrapped_mode is True:
            print("protocol_inconsistent_wrap=false", flush=True)
            wrapped_mode = None

        print(
            "received_bytes=%d wrapped=%s shape=%s" % (len(text), is_wrapped, json.dumps(public_shape(payload), ensure_ascii=True)),
            flush=True,
        )
        if not isinstance(payload, dict):
            continue

        method = payload.get("method")
        request_id = payload.get("id")
        if method == "initialize":
            protocol = payload.get("params", {}).get("protocolVersion")
            response = jsonrpc_response(request_id, initialize_result(protocol))
        elif method == "tools/list":
            response = jsonrpc_response(request_id, {"tools": [probe_tool()]})
        elif method == "tools/call":
            response = jsonrpc_response(
                request_id,
                {
                    "content": [{"type": "text", "text": "probe-ok"}],
                    "isError": False,
                },
            )
        elif request_id is not None:
            response = jsonrpc_response(
                request_id,
                error={"code": -32601, "message": "Method not found"},
            )
        else:
            continue

        outgoing = {"type": "mcp", "payload": response} if is_wrapped else response
        encoded = json.dumps(outgoing, ensure_ascii=False, separators=(",", ":"))
        connection.send_text(encoded)
        print("sent_bytes=%d method=%s" % (len(encoded), method), flush=True)


def run_client_mode(connection):
    print("mode=client send_initialize", flush=True)
    requests = [
        {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": {
                "protocolVersion": "2024-11-05",
                "capabilities": {},
                "clientInfo": {"name": "python-mcp-probe", "version": "1.0.0"},
            },
        },
        {"jsonrpc": "2.0", "method": "notifications/initialized"},
        {"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}},
    ]
    for request in requests:
        encoded = json.dumps(request, ensure_ascii=False, separators=(",", ":"))
        connection.send_text(encoded)
        print("sent_bytes=%d method=%s" % (len(encoded), request.get("method")), flush=True)

    for _ in range(2):
        kind, text = connection.recv_message()
        if kind != "text":
            print("received_non_text_frame=" + kind, flush=True)
            continue
        message = json.loads(text)
        payload = message.get("payload") if message.get("type") == "mcp" else message
        print(
            "received_bytes=%d wrapped=%s shape=%s" % (len(text), message.get("type") == "mcp", json.dumps(public_shape(payload), ensure_ascii=True)),
            flush=True,
        )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=("server", "client"), default="server")
    parser.add_argument("--duration", type=float, default=15.0)
    args = parser.parse_args()

    endpoint = os.environ.get("MCP_ENDPOINT")
    if not endpoint:
        raise SystemExit("Set MCP_ENDPOINT to a ws:// or wss:// URL")

    started = time.monotonic()
    connection = WebSocketConnection(endpoint, timeout=10.0)
    print("connected_seconds=%.3f" % (time.monotonic() - started), flush=True)
    try:
        if args.mode == "server":
            run_server_mode(connection, args.duration)
        else:
            run_client_mode(connection)
    finally:
        connection.close()


if __name__ == "__main__":
    main()
