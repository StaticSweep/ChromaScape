import socket
import struct
import unittest

from scripts.qualification import ws_probe


def frame(opcode, payload, *, masked=False, fin=True):
    first = (0x80 if fin else 0x00) | opcode
    length = len(payload)
    if length < 126:
        header = bytes([first, length])
    elif length < (1 << 16):
        header = bytes([first, 126]) + struct.pack("!H", length)
    else:
        header = bytes([first, 127]) + struct.pack("!Q", length)
    if not masked:
        return header + payload
    mask = b"\x01\x02\x03\x04"
    masked_payload = bytes(byte ^ mask[index % 4] for index, byte in enumerate(payload))
    return bytes([header[0], header[1] | 0x80]) + header[2:] + mask + masked_payload


class WsProbeTest(unittest.TestCase):
    def test_expected_accept_matches_rfc6455_example(self):
        key = "dGhlIHNhbXBsZSBub25jZQ=="

        self.assertEqual("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", ws_probe.expected_accept(key))

    def test_handshake_request_carries_required_headers(self):
        request = ws_probe.build_handshake_request("127.0.0.1", 8090, "/ws/viewport", "abc123")

        self.assertTrue(request.startswith(b"GET /ws/viewport HTTP/1.1\r\n"))
        self.assertIn(b"Host: 127.0.0.1:8090\r\n", request)
        self.assertIn(b"Upgrade: websocket\r\n", request)
        self.assertIn(b"Connection: Upgrade\r\n", request)
        self.assertIn(b"Sec-WebSocket-Key: abc123\r\n", request)
        self.assertIn(b"Sec-WebSocket-Version: 13\r\n", request)
        self.assertTrue(request.endswith(b"\r\n\r\n"))

    def test_parse_handshake_reports_status_and_headers(self):
        response = (
            b"HTTP/1.1 101 Switching Protocols\r\n"
            b"Upgrade: websocket\r\n"
            b"Connection: Upgrade\r\n"
            b"Sec-WebSocket-Accept: abc\r\n\r\n"
        )

        status, headers = ws_probe.parse_handshake(response)

        self.assertEqual(101, status)
        self.assertEqual("websocket", headers["upgrade"])
        self.assertEqual("abc", headers["sec-websocket-accept"])

    def test_parse_handshake_rejects_malformed_response(self):
        with self.assertRaises(ValueError):
            ws_probe.parse_handshake(b"not-http\r\n\r\n")

    def test_read_frame_parses_text_binary_and_extended_lengths(self):
        for opcode, payload in (
            (ws_probe.OPCODE_TEXT, b'{"type":"SESSION_CREATED"}'),
            (ws_probe.OPCODE_BINARY, b"\xff\xd8jpeg-bytes"),
            (ws_probe.OPCODE_BINARY, b"x" * 300),
            (ws_probe.OPCODE_BINARY, b"y" * 70000),
        ):
            server, client = socket.socketpair()
            try:
                client.sendall(frame(opcode, payload))
                parsed_opcode, parsed_payload = ws_probe.read_frame(server)
            finally:
                server.close()
                client.close()

            self.assertEqual(opcode, parsed_opcode)
            self.assertEqual(payload, parsed_payload)

    def test_read_frame_unmasks_client_frames(self):
        server, client = socket.socketpair()
        try:
            client.sendall(frame(ws_probe.OPCODE_TEXT, b"masked", masked=True))
            opcode, payload = ws_probe.read_frame(server)
        finally:
            server.close()
            client.close()

        self.assertEqual(ws_probe.OPCODE_TEXT, opcode)
        self.assertEqual(b"masked", payload)

    def test_read_frame_rejects_truncated_connection(self):
        server, client = socket.socketpair()
        try:
            client.sendall(b"\x82\x05ab")
            client.close()
            with self.assertRaises(ConnectionError):
                ws_probe.read_frame(server)
        finally:
            server.close()

    def test_classify_names_control_and_data_opcodes(self):
        self.assertEqual("binary", ws_probe.classify(ws_probe.OPCODE_BINARY))
        self.assertEqual("text", ws_probe.classify(ws_probe.OPCODE_TEXT))
        self.assertEqual("ping", ws_probe.classify(ws_probe.OPCODE_PING))
        self.assertEqual("close", ws_probe.classify(ws_probe.OPCODE_CLOSE))
        self.assertEqual("unknown-3", ws_probe.classify(3))


if __name__ == "__main__":
    unittest.main()
