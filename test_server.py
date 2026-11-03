#!/usr/bin/env python3
"""End-to-end protocol tests: starts the real server and talks to it with a raw WebSocket client.
Run:  python3 test_server.py
"""
import asyncio
import base64
import json
import os
import struct
import subprocess
import sys
import time
import unittest
import urllib.request

import server as srv

PORT = 18765
HERE = os.path.dirname(os.path.abspath(__file__))


class Client:
    """Tiny masked-frame WebSocket client (what browsers / OkHttp do)."""

    def __init__(self, r, w):
        self.r, self.w = r, w

    @classmethod
    async def connect(cls, query, origin=None, host=f"127.0.0.1:{PORT}"):
        r, w = await asyncio.open_connection("127.0.0.1", PORT)
        key = base64.b64encode(os.urandom(16)).decode()
        extra = f"Origin: {origin}\r\n" if origin else ""
        w.write((f"GET /ws?{query} HTTP/1.1\r\nHost: {host}\r\nUpgrade: websocket\r\n"
                 f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n{extra}\r\n").encode())
        status = (await r.readuntil(b"\r\n\r\n")).split(b"\r\n")[0].decode()
        return cls(r, w), status

    async def send(self, obj):
        data = json.dumps(obj).encode()
        mask = os.urandom(4)
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
        n = len(data)
        head = bytes([0x81, 0x80 | n]) if n < 126 else bytes([0x81, 0x80 | 126]) + struct.pack("!H", n)
        self.w.write(head + mask + masked)
        await self.w.drain()

    async def recv(self, timeout=3):
        while True:
            b1, b2 = await asyncio.wait_for(self.r.readexactly(2), timeout)
            n = b2 & 0x7F
            if n == 126:
                (n,) = struct.unpack("!H", await self.r.readexactly(2))
            data = await self.r.readexactly(n) if n else b""
            op = b1 & 0x0F
            if op == 8:
                return None
            if op == 1:
                return json.loads(data)

    def close(self):
        self.w.close()


class ServerTests(unittest.IsolatedAsyncioTestCase):
    proc = None

    @classmethod
    def setUpClass(cls):
        cls.proc = subprocess.Popen([sys.executable, os.path.join(HERE, "server.py"), "--port", str(PORT), "--no-browser"],
                                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for _ in range(50):
            try:
                urllib.request.urlopen(f"http://127.0.0.1:{PORT}/api/info", timeout=1).read()
                return
            except OSError:
                time.sleep(0.1)
        raise RuntimeError("server did not start")

    @classmethod
    def tearDownClass(cls):
        cls.proc.terminate()
        cls.proc.wait(5)

    # ---- unit
    def test_is_local(self):
        for ok in ("192.168.1.5", "10.0.0.2", "172.16.4.4", "127.0.0.1", "169.254.1.1", "::1", "fe80::1", "::ffff:192.168.0.9"):
            self.assertTrue(srv.is_local(ok), ok)
        for bad in ("8.8.8.8", "1.1.1.1", "2001:4860:4860::8888", "garbage", ""):
            self.assertFalse(srv.is_local(bad), bad)

    # ---- http
    def test_static_files_and_api(self):
        base = f"http://127.0.0.1:{PORT}"
        self.assertIn(b"Start Casting", urllib.request.urlopen(base + "/").read())
        self.assertIn(b"getDisplayMedia", urllib.request.urlopen(base + "/sender.js").read())
        self.assertIn(b"--bg", urllib.request.urlopen(base + "/style.css").read())
        info = json.loads(urllib.request.urlopen(base + "/api/info").read())
        self.assertEqual(info["port"], PORT)
        for bad in ("/../server/server.py", "/server.py", "/%2e%2e/server/server.py", "/nope.js"):
            with self.assertRaises(urllib.error.HTTPError, msg=bad) as cm:
                urllib.request.urlopen(base + bad)
            self.assertEqual(cm.exception.code, 404)

    # ---- signaling
    async def test_full_pairing_and_relay(self):
        rx, st = await Client.connect("role=receiver&code=111111")
        self.assertIn("101", st)
        tx, st = await Client.connect("role=sender&code=111111&name=Chrome%20on%20Windows")
        self.assertIn("101", st)
        joined = await rx.recv()
        self.assertEqual(joined["type"], "sender-joined")
        self.assertEqual(joined["name"], "Chrome on Windows")
        self.assertEqual((await tx.recv())["type"], "receiver-ready")

        await tx.send({"type": "offer", "sdp": "v=0 fake"})
        self.assertEqual(await rx.recv(), {"type": "offer", "sdp": "v=0 fake"})
        await rx.send({"type": "answer", "sdp": "v=0 answer"})
        self.assertEqual(await tx.recv(), {"type": "answer", "sdp": "v=0 answer"})
        cand = {"type": "candidate", "candidate": "candidate:1 1 udp 1 192.168.1.2 5000 typ host", "sdpMid": "0", "sdpMLineIndex": 0}
        await tx.send(cand)
        self.assertEqual(await rx.recv(), cand)
        await rx.send({"type": "config", "height": 480, "fps": 15})
        self.assertEqual((await tx.recv())["fps"], 15)
        await rx.send({"type": "need-offer"})
        self.assertEqual((await tx.recv())["type"], "need-offer")
        # disallowed types are NOT relayed; the next allowed one is
        await tx.send({"type": "evil", "x": 1})
        await tx.send({"type": "bye"})
        self.assertEqual((await rx.recv())["type"], "bye")

        # sender disconnect is reported to the receiver
        tx.close()
        self.assertEqual((await rx.recv())["type"], "sender-left")
        rx.close()

    async def test_wrong_code_and_format(self):
        c, _ = await Client.connect("role=sender&code=222222")
        m = await c.recv()
        self.assertEqual((m["type"], m["fatal"]), ("error", True))
        c.close()
        c, _ = await Client.connect("role=sender&code=abc")
        self.assertEqual((await c.recv())["type"], "error")
        c.close()
        c, _ = await Client.connect("role=hacker&code=123456")
        self.assertEqual((await c.recv())["type"], "error")
        c.close()

    async def test_second_sender_rejected_then_receiver_reconnect(self):
        rx, _ = await Client.connect("role=receiver&code=333333")
        tx, _ = await Client.connect("role=sender&code=333333&name=A")
        await rx.recv(); await tx.recv()
        # same IP (loopback) may take over; verify old one is closed and new one is paired
        tx2, _ = await Client.connect("role=sender&code=333333&name=B")
        self.assertEqual((await rx.recv())["name"], "B")
        self.assertEqual((await tx2.recv())["type"], "receiver-ready")
        # receiver drops (Wi-Fi blip): sender is told, and re-pairing re-triggers the offer cycle
        rx.close()
        self.assertEqual((await tx2.recv())["type"], "receiver-left")
        rx2, _ = await Client.connect("role=receiver&code=333333")
        self.assertEqual((await rx2.recv())["type"], "sender-joined")
        self.assertEqual((await tx2.recv())["type"], "receiver-ready")
        tx2.close(); rx2.close()

    async def test_receiver_replaced(self):
        a, _ = await Client.connect("role=receiver&code=444444")
        b, _ = await Client.connect("role=receiver&code=444444")
        self.assertEqual((await a.recv())["type"], "error")
        a.close(); b.close()

    async def test_cross_origin_websocket_refused(self):
        r, w = await asyncio.open_connection("127.0.0.1", PORT)
        w.write((f"GET /ws?role=sender&code=555555 HTTP/1.1\r\nHost: 127.0.0.1:{PORT}\r\nUpgrade: websocket\r\n"
                 "Connection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n"
                 "Origin: http://evil.example\r\n\r\n").encode())
        self.assertIn(b"403", (await r.readuntil(b"\r\n\r\n")).split(b"\r\n")[0])
        w.close()

    async def test_zz_brute_force_limited(self):
        msgs = []
        for _ in range(7):
            c, _ = await Client.connect("role=sender&code=999999")
            msgs.append((await c.recv())["message"])
            c.close()
        self.assertTrue(any("Too many" in m for m in msgs[5:]), msgs)


if __name__ == "__main__":
    unittest.main(verbosity=2)
