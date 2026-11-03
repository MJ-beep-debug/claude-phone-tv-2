#!/usr/bin/env python3
"""WiFi TV Receiver - local signaling server.

Zero third-party dependencies (Python 3.8+). Runs on the laptop and does three things:

  1. Serves the browser-based sender (../sender/) on http://localhost:<port>/
  2. Relays WebRTC signaling (offer / answer / ICE / config) between ONE sender
     and ONE receiver (the phone) that present the same 6-digit pairing code.
  3. Refuses every client that is not on a private / loopback / link-local address.

It never sees, proxies or stores audio/video: media flows peer-to-peer over WebRTC.
"""
import argparse
import asyncio
import base64
import hashlib
import ipaddress
import json
import logging
import re
import socket
import struct
import time
import webbrowser
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

log = logging.getLogger("wifitv")

VERSION = "1.0.0"
GUID = b"258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
MAX_MSG = 256 * 1024          # signaling messages are tiny (SDP ~ few KB)
IDLE_TIMEOUT = 35             # seconds without any frame (pings are every 10 s)
PING_EVERY = 10
RELAY_TYPES = {"offer", "answer", "candidate", "config", "bye", "need-offer"}
SENDER_DIR = Path(__file__).resolve().parent.parent / "sender"
MIME = {
    ".html": "text/html; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".js": "application/javascript; charset=utf-8",
}


# --------------------------------------------------------------------------- helpers
def is_local(ip: str) -> bool:
    """True for private, loopback and link-local addresses (IPv4 or IPv6)."""
    try:
        addr = ipaddress.ip_address(ip.split("%")[0])
    except ValueError:
        return False
    if addr.version == 6 and addr.ipv4_mapped:
        addr = addr.ipv4_mapped
    return addr.is_private or addr.is_loopback or addr.is_link_local


def lan_ip() -> str:
    """Best-effort LAN address of this machine (no packets are actually sent)."""
    for probe in ("10.255.255.255", "192.168.255.255", "172.31.255.255"):
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.connect((probe, 1))
            ip = s.getsockname()[0]
            if ip and not ip.startswith("127."):
                return ip
        except OSError:
            pass
        finally:
            s.close()
    try:
        return socket.gethostbyname(socket.gethostname())
    except OSError:
        return "127.0.0.1"


# --------------------------------------------------------------------------- minimal RFC 6455 WebSocket
class WebSocket:
    def __init__(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
        self.r = reader
        self.w = writer
        self.closed = False

    @staticmethod
    def _frame(opcode: int, payload: bytes = b"") -> bytes:
        n = len(payload)
        if n < 126:
            head = struct.pack("!BB", 0x80 | opcode, n)
        elif n < 65536:
            head = struct.pack("!BBH", 0x80 | opcode, 126, n)
        else:
            head = struct.pack("!BBQ", 0x80 | opcode, 127, n)
        return head + payload

    async def _write(self, data: bytes) -> None:
        if self.closed:
            return
        try:
            self.w.write(data)
            await self.w.drain()
        except (ConnectionError, OSError):
            self.closed = True

    async def send(self, obj: dict) -> None:
        await self._write(self._frame(0x1, json.dumps(obj, separators=(",", ":")).encode()))

    async def ping(self) -> None:
        await self._write(self._frame(0x9))

    async def close(self, code: int = 1000, reason: str = "") -> None:
        if not self.closed:
            await self._write(self._frame(0x8, struct.pack("!H", code) + reason.encode()[:100]))
        self.closed = True
        try:
            self.w.close()
        except OSError:
            pass

    async def recv(self):
        """Return the next text message, or None when the peer closed."""
        text = bytearray()
        while True:
            b1, b2 = await asyncio.wait_for(self.r.readexactly(2), IDLE_TIMEOUT)
            fin, opcode = b1 & 0x80, b1 & 0x0F
            masked, length = b2 & 0x80, b2 & 0x7F
            if length == 126:
                (length,) = struct.unpack("!H", await self.r.readexactly(2))
            elif length == 127:
                (length,) = struct.unpack("!Q", await self.r.readexactly(8))
            if length > MAX_MSG:
                raise ValueError("message too large")
            mask = await self.r.readexactly(4) if masked else b""
            data = await self.r.readexactly(length) if length else b""
            if masked:
                data = bytes(b ^ mask[i & 3] for i, b in enumerate(data))
            if opcode == 0x8:
                return None
            if opcode == 0x9:                       # ping -> pong
                await self._write(self._frame(0xA, data))
                continue
            if opcode == 0xA:                       # pong
                continue
            if opcode in (0x1, 0x0):                # text / continuation
                text += data
                if len(text) > MAX_MSG:
                    raise ValueError("message too large")
                if fin:
                    return text.decode("utf-8", "replace")
            # binary frames are ignored


# --------------------------------------------------------------------------- signaling rooms
class Room:
    def __init__(self):
        self.receiver = None
        self.sender = None
        self.sender_info = {}


class State:
    def __init__(self, port: int, allow_public: bool):
        self.port = port
        self.allow_public = allow_public
        self.rooms = {}
        self.fails = {}          # ip -> [timestamps of wrong-code attempts]


def cleanup(st: State, code: str, room: Room) -> None:
    if not room.receiver and not room.sender and st.rooms.get(code) is room:
        del st.rooms[code]


async def fail(ws: WebSocket, message: str) -> None:
    await ws.send({"type": "error", "fatal": True, "message": message})
    await ws.close(4001, "error")


async def ping_loop(ws: WebSocket) -> None:
    while not ws.closed:
        await asyncio.sleep(PING_EVERY)
        await ws.ping()


async def pump(ws: WebSocket, get_peer) -> None:
    """Relay allowed signaling messages from ws to the other side of the room."""
    pinger = asyncio.ensure_future(ping_loop(ws))
    try:
        while True:
            raw = await ws.recv()
            if raw is None:
                break
            try:
                obj = json.loads(raw)
            except ValueError:
                continue
            if not isinstance(obj, dict) or obj.get("type") not in RELAY_TYPES:
                continue
            peer = get_peer()
            if peer is not None and not peer.closed:
                await peer.send(obj)
    except (asyncio.IncompleteReadError, asyncio.TimeoutError, ConnectionError, OSError, ValueError):
        pass
    finally:
        pinger.cancel()


async def run_receiver(ws: WebSocket, code: str, st: State, ip: str) -> None:
    room = st.rooms.setdefault(code, Room())
    old, room.receiver = room.receiver, ws
    if old is not None and old is not ws:
        await old.send({"type": "error", "fatal": True, "message": "Replaced by a newer receiver with the same code"})
        await old.close(4002, "replaced")
    log.info("receiver %s registered code %s", ip, code)
    if room.sender is not None and not room.sender.closed:
        await ws.send({"type": "sender-joined", **room.sender_info})
        await room.sender.send({"type": "receiver-ready"})
    try:
        await pump(ws, lambda: room.sender)
    finally:
        if room.receiver is ws:
            room.receiver = None
            if room.sender is not None:
                await room.sender.send({"type": "receiver-left"})
        cleanup(st, code, room)
        await ws.close()
        log.info("receiver %s left", ip)


async def run_sender(ws: WebSocket, code: str, name: str, st: State, ip: str) -> None:
    now = time.time()
    recent = [t for t in st.fails.get(ip, []) if now - t < 60]
    st.fails[ip] = recent
    if len(recent) >= 5:
        await fail(ws, "Too many wrong pairing codes. Wait a minute and try again.")
        return
    room = st.rooms.get(code)
    if room is None or room.receiver is None or room.receiver.closed:
        recent.append(now)
        await fail(ws, "No receiver found with that pairing code. Check the code on the phone and "
                       "make sure the app shows 'Waiting for a sender'.")
        return
    if room.sender is not None and not room.sender.closed:
        if room.sender_info.get("ip") == ip:     # same machine reconnecting: take over
            await room.sender.close(4003, "replaced")
        else:
            await fail(ws, "This receiver already has an active sender.")
            return
    room.sender = ws
    room.sender_info = {"name": name, "ip": ip}
    log.info("sender %s (%s) paired with code %s", ip, name, code)
    await room.receiver.send({"type": "sender-joined", **room.sender_info})
    await ws.send({"type": "receiver-ready"})
    try:
        await pump(ws, lambda: room.receiver)
    finally:
        if room.sender is ws:
            room.sender = None
            if room.receiver is not None:
                await room.receiver.send({"type": "sender-left"})
        cleanup(st, code, room)
        await ws.close()
        log.info("sender %s left", ip)


async def signaling(ws: WebSocket, query: dict, ip: str, st: State) -> None:
    role = query.get("role", [""])[0]
    code = query.get("code", [""])[0]
    name = (query.get("name", [""])[0] or "Unknown device")[:80]
    if role not in ("receiver", "sender") or not re.fullmatch(r"\d{6}", code):
        await fail(ws, "Invalid role or pairing code (must be 6 digits)")
        return
    if role == "receiver":
        await run_receiver(ws, code, st, ip)
    else:
        await run_sender(ws, code, name, st, ip)


# --------------------------------------------------------------------------- HTTP
async def respond(writer, status: str, body: bytes, ctype: str = "text/plain; charset=utf-8", extra=None) -> None:
    head = [f"HTTP/1.1 {status}", f"Content-Type: {ctype}", f"Content-Length: {len(body)}",
            "Cache-Control: no-store", "Connection: close", "X-Content-Type-Options: nosniff"]
    head += extra or []
    writer.write(("\r\n".join(head) + "\r\n\r\n").encode() + body)
    try:
        await writer.drain()
    except (ConnectionError, OSError):
        pass


async def handle(reader: asyncio.StreamReader, writer: asyncio.StreamWriter, st: State) -> None:
    peer = writer.get_extra_info("peername")
    ip = peer[0] if peer else ""
    try:
        try:
            head = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), 10)
        except (asyncio.IncompleteReadError, asyncio.LimitOverrunError, asyncio.TimeoutError, ConnectionError):
            return
        lines = head.decode("latin-1").split("\r\n")
        try:
            method, target, _ = lines[0].split(" ", 2)
        except ValueError:
            await respond(writer, "400 Bad Request", b"bad request")
            return
        headers = {}
        for line in lines[1:]:
            if ":" in line:
                k, v = line.split(":", 1)
                headers[k.strip().lower()] = v.strip()

        if not st.allow_public and not is_local(ip):
            log.warning("rejected non-local client %s", ip)
            await respond(writer, "403 Forbidden", b"Local network clients only.")
            return

        url = urlsplit(target)
        if url.path == "/ws":
            key = headers.get("sec-websocket-key")
            if "websocket" not in headers.get("upgrade", "").lower() or not key:
                await respond(writer, "400 Bad Request", b"WebSocket upgrade required")
                return
            origin = headers.get("origin")
            if origin and urlsplit(origin).netloc != headers.get("host", ""):
                log.warning("rejected cross-origin websocket from %s (%s)", ip, origin)
                await respond(writer, "403 Forbidden", b"Cross-origin WebSocket refused.")
                return
            accept = base64.b64encode(hashlib.sha1(key.encode() + GUID).digest()).decode()
            writer.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n"
                          f"Connection: Upgrade\r\nSec-WebSocket-Accept: {accept}\r\n\r\n").encode())
            await writer.drain()
            ws = WebSocket(reader, writer)
            await signaling(ws, parse_qs(url.query), ip, st)
            return

        if method not in ("GET", "HEAD"):
            await respond(writer, "405 Method Not Allowed", b"method not allowed")
            return
        if url.path == "/api/info":
            body = json.dumps({"ip": lan_ip(), "port": st.port, "version": VERSION}).encode()
            await respond(writer, "200 OK", body, "application/json")
            return
        name = "index.html" if url.path == "/" else url.path.lstrip("/")
        f = SENDER_DIR / name
        if "/" in name or f.suffix not in MIME or not f.is_file():
            await respond(writer, "404 Not Found", b"not found")
            return
        await respond(writer, "200 OK", f.read_bytes(), MIME[f.suffix])
    except (ConnectionError, OSError):
        pass
    except Exception:                                   # never let one client kill the server
        log.exception("unhandled error for %s", ip)
    finally:
        try:
            writer.close()
        except OSError:
            pass


# --------------------------------------------------------------------------- main
async def serve(host: str, port: int, allow_public: bool, open_browser: bool) -> None:
    st = State(port, allow_public)
    server = await asyncio.start_server(lambda r, w: handle(r, w, st), host, port)
    ip = lan_ip()
    print("=" * 60)
    print(f" WiFi TV Receiver - signaling server v{VERSION}")
    print("=" * 60)
    print(f" Sender page (open on THIS computer):  http://localhost:{port}/")
    print(f" Address to type on the phone:         {ip}:{port}")
    print(" Pairing:                              enter the 6-digit code shown on the phone")
    print(f" Access:                               {'ANY network (--allow-public)' if allow_public else 'local network only'}")
    print(" Press Ctrl+C to stop.")
    print("=" * 60)
    if open_browser:
        webbrowser.open(f"http://localhost:{port}/")
    async with server:
        await server.serve_forever()


def main() -> None:
    ap = argparse.ArgumentParser(description="WiFi TV Receiver signaling server")
    ap.add_argument("--host", default="0.0.0.0", help="bind address (default 0.0.0.0)")
    ap.add_argument("--port", type=int, default=8765, help="TCP port (default 8765)")
    ap.add_argument("--allow-public", action="store_true",
                    help="DISABLE the local-network-only check (not recommended)")
    ap.add_argument("--no-browser", action="store_true", help="do not open the sender page automatically")
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()
    logging.basicConfig(level=logging.DEBUG if args.verbose else logging.INFO,
                        format="%(asctime)s %(levelname)s %(message)s", datefmt="%H:%M:%S")
    try:
        asyncio.run(serve(args.host, args.port, args.allow_public, not args.no_browser))
    except KeyboardInterrupt:
        print("\nStopped.")
    except OSError as e:
        raise SystemExit(f"Cannot listen on {args.host}:{args.port}: {e}\n"
                         "Is another copy running? Try --port 8766.")


if __name__ == "__main__":
    main()
