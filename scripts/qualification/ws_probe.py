#!/usr/bin/env python3
"""Minimal, dependency-free WebSocket probe for local qualification checks.

The Fedora qualification host has no websocket client package installed, and the
ChromaScape debugger live view is only observable over `/ws/viewport` (binary
frames) and `/ws/debugger/tick-stream` (text messages). This module performs a
single RFC 6455 client handshake with the standard library, counts server
frames for a bounded time, and prints one machine-readable line.

It is a diagnostic probe, not a general client: it never masks frames it sends
beyond a control pong, it never retries, and it exits non-zero when the
handshake or the required frame count is not met.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import socket
import struct
import sys
import time
from typing import Any

GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
OPCODE_CONTINUATION = 0x0
OPCODE_TEXT = 0x1
OPCODE_BINARY = 0x2
OPCODE_CLOSE = 0x8
OPCODE_PING = 0x9
OPCODE_PONG = 0xA


def handshake_key() -> str:
    return base64.b64encode(os.urandom(16)).decode("ascii")


def expected_accept(key: str) -> str:
    digest = hashlib.sha1((key + GUID).encode("ascii")).digest()
    return base64.b64encode(digest).decode("ascii")


def build_handshake_request(host: str, port: int, path: str, key: str) -> bytes:
    lines = [
        f"GET {path} HTTP/1.1",
        f"Host: {host}:{port}",
        "Upgrade: websocket",
        "Connection: Upgrade",
        f"Sec-WebSocket-Key: {key}",
        "Sec-WebSocket-Version: 13",
        "",
        "",
    ]
    return "\r\n".join(lines).encode("ascii")


def parse_handshake(response: bytes) -> tuple[int, dict[str, str]]:
    head, _, _ = response.partition(b"\r\n\r\n")
    lines = head.decode("latin-1").split("\r\n")
    if not lines or not lines[0].startswith("HTTP/"):
        raise ValueError("malformed handshake response")
    status = int(lines[0].split(" ", 2)[1])
    headers: dict[str, str] = {}
    for line in lines[1:]:
        name, separator, value = line.partition(":")
        if separator:
            headers[name.strip().lower()] = value.strip()
    return status, headers


def read_exact(stream: Any, length: int) -> bytes:
    chunks = bytearray()
    while len(chunks) < length:
        chunk = stream.recv(length - len(chunks))
        if not chunk:
            raise ConnectionError("connection closed mid-frame")
        chunks.extend(chunk)
    return bytes(chunks)


def classify(opcode: int) -> str:
    return {
        OPCODE_CONTINUATION: "continuation",
        OPCODE_TEXT: "text",
        OPCODE_BINARY: "binary",
        OPCODE_CLOSE: "close",
        OPCODE_PING: "ping",
        OPCODE_PONG: "pong",
    }.get(opcode, f"unknown-{opcode}")


def read_frame(stream: Any) -> tuple[int, bytes]:
    """Reads one server frame; returns (opcode, payload)."""
    header = read_exact(stream, 2)
    first, second = header[0], header[1]
    opcode = first & 0x0F
    masked = bool(second & 0x80)
    length = second & 0x7F
    if length == 126:
        length = struct.unpack("!H", read_exact(stream, 2))[0]
    elif length == 127:
        length = struct.unpack("!Q", read_exact(stream, 8))[0]
    mask = read_exact(stream, 4) if masked else b""
    payload = read_exact(stream, length) if length else b""
    if masked:
        payload = bytes(byte ^ mask[index % 4] for index, byte in enumerate(payload))
    return opcode, payload


def send_pong(stream: Any, payload: bytes) -> None:
    mask = os.urandom(4)
    masked = bytes(byte ^ mask[index % 4] for index, byte in enumerate(payload))
    frame = bytes([0x80 | OPCODE_PONG, 0x80 | len(payload)]) + mask + masked
    stream.sendall(frame)


def connect(url: str, timeout: float) -> tuple[Any, dict[str, str]]:
    if not url.startswith("ws://"):
        raise ValueError("only ws:// URLs are supported")
    remainder = url[len("ws://") :]
    authority, _, path = remainder.partition("/")
    path = "/" + path if path else "/"
    host, _, port_text = authority.partition(":")
    port = int(port_text) if port_text else 80

    stream = socket.create_connection((host, port), timeout=timeout)
    key = handshake_key()
    stream.sendall(build_handshake_request(host, port, path, key))
    response = b""
    deadline = time.monotonic() + timeout
    while b"\r\n\r\n" not in response:
        if time.monotonic() > deadline:
            raise TimeoutError("handshake response timed out")
        chunk = stream.recv(4096)
        if not chunk:
            raise ConnectionError("connection closed during handshake")
        response += chunk
    status, headers = parse_handshake(response)
    if status != 101:
        raise ConnectionError(f"handshake returned HTTP {status}")
    if headers.get("sec-websocket-accept") != expected_accept(key):
        raise ConnectionError("handshake accept digest mismatch")
    return stream, headers


def probe(url: str, seconds: float, timeout: float) -> dict[str, Any]:
    stream, _ = connect(url, timeout)
    result: dict[str, Any] = {
        "url": url,
        "seconds": seconds,
        "binaryFrames": 0,
        "binaryBytes": 0,
        "textMessages": 0,
        "textBytes": 0,
        "controlFrames": 0,
        "firstBinarySha256": None,
        "firstTextPreview": None,
        "closed": False,
    }
    stream.settimeout(timeout)
    deadline = time.monotonic() + seconds
    try:
        while time.monotonic() < deadline:
            try:
                opcode, payload = read_frame(stream)
            except socket.timeout:
                continue
            kind = classify(opcode)
            if kind == "binary":
                result["binaryFrames"] += 1
                result["binaryBytes"] += len(payload)
                if result["firstBinarySha256"] is None:
                    result["firstBinarySha256"] = hashlib.sha256(payload).hexdigest()
            elif kind == "text":
                result["textMessages"] += 1
                result["textBytes"] += len(payload)
                if result["firstTextPreview"] is None:
                    result["firstTextPreview"] = payload[:160].decode("utf-8", "replace")
            elif kind == "ping":
                send_pong(stream, payload)
                result["controlFrames"] += 1
            elif kind == "pong":
                result["controlFrames"] += 1
            elif kind == "close":
                result["closed"] = True
                break
    finally:
        try:
            stream.close()
        except OSError:
            pass
    return result


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description=__doc__)
    root.add_argument("--url", required=True, help="ws://host:port/path")
    root.add_argument("--seconds", type=float, default=5.0, help="bounded observation window")
    root.add_argument("--timeout", type=float, default=1.0, help="per-read socket timeout")
    root.add_argument("--require-binary", type=int, default=0, help="minimum binary frames")
    root.add_argument("--require-text", type=int, default=0, help="minimum text messages")
    return root


def main() -> None:
    args = parser().parse_args()
    try:
        result = probe(args.url, args.seconds, args.timeout)
    except (ValueError, OSError, TimeoutError, ConnectionError) as failure:
        print(f"WS_PROBE status=FAILED url={args.url} message={json.dumps(str(failure))}")
        raise SystemExit(2) from failure
    result["status"] = "OK"
    print("WS_PROBE " + json.dumps(result, sort_keys=True))
    if result["binaryFrames"] < args.require_binary:
        print(
            f"WS_PROBE status=INSUFFICIENT binaryFrames={result['binaryFrames']} "
            f"required={args.require_binary}"
        )
        raise SystemExit(4)
    if result["textMessages"] < args.require_text:
        print(
            f"WS_PROBE status=INSUFFICIENT textMessages={result['textMessages']} "
            f"required={args.require_text}"
        )
        raise SystemExit(4)


if __name__ == "__main__":
    main()
