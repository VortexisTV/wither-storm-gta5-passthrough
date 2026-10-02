#!/usr/bin/env python3
"""A stand-in for the host game, to test the Minecraft side on its own (no GTA): it connects to the mod's link,
drives the camera, lays ground, lists a few "people" and "vehicles", and saves what Minecraft exports.

    python fakehost.py [--seconds 60] [--out DIR] [--size 640x360] [--storm PHASE] [--evolve N] [--peds 12] [--vehs 4]

Coordinates here are Minecraft's. The ground is a flat patch whose surface is at y = 64, around (0, 64, 0); the
camera stands at its edge and looks across it. With --storm, a Wither Storm is summoned ahead (phase 0: it charges up
and goes off, as in the mod), and --evolve presses "evolve" that many times, a few seconds apart.

Everything the mod sends is counted, and the interesting messages (storms, grab, eaten, rip, explosion, ...) are
printed as they come. Frames are saved as PNGs: world (what the host composites by depth), depth, overlay.
Standard library only.
"""
import argparse
import base64
import json
import math
import mmap
import os
import socket
import struct
import sys
import threading
import time
import zlib

NAME = "Local\\MCPassthroughFrame"
HEADER = 4096


class Link:
    """A minimal WebSocket client (text frames), like the host plugin's."""

    def __init__(self, host="127.0.0.1", port=25599):
        self.sock = socket.create_connection((host, port), timeout=5)
        self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        key = base64.b64encode(os.urandom(16)).decode()
        self.sock.sendall((f"GET / HTTP/1.1\r\nHost: {host}:{port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                           f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n").encode())
        response = b""
        while b"\r\n\r\n" not in response:
            chunk = self.sock.recv(1)
            if not chunk:
                raise ConnectionError("closed during handshake")
            response += chunk
        if not response.startswith(b"HTTP/1.1 101"):
            raise ConnectionError(response.decode(errors="replace"))
        self.sock.settimeout(None)
        self.lock = threading.Lock()

    def send(self, obj):
        data = (obj if isinstance(obj, str) else json.dumps(obj, separators=(",", ":"))).encode()
        mask = os.urandom(4)
        n = len(data)
        head = bytes([0x81]) + (bytes([0x80 | n]) if n < 126 else bytes([0x80 | 126]) + struct.pack(">H", n) if n < 65536
                                else bytes([0x80 | 127]) + struct.pack(">Q", n))
        body = bytes(b ^ mask[i & 3] for i, b in enumerate(data)) if n < 4096 else bytes(
            a ^ b for a, b in zip(data, (mask * (n // 4 + 1))[:n]))
        with self.lock:
            self.sock.sendall(head + mask + body)

    def _read(self, n):
        out = b""
        while len(out) < n:
            chunk = self.sock.recv(n - len(out))
            if not chunk:
                raise ConnectionError("closed")
            out += chunk
        return out

    def recv(self):
        partial = b""
        while True:
            b0, b1 = self._read(2)
            n = b1 & 0x7F
            if n == 126:
                n = struct.unpack(">H", self._read(2))[0]
            elif n == 127:
                n = struct.unpack(">Q", self._read(8))[0]
            mask = self._read(4) if b1 & 0x80 else None
            payload = self._read(n)
            if mask:
                payload = bytes(b ^ mask[i & 3] for i, b in enumerate(payload))
            opcode = b0 & 0x0F
            if opcode in (0, 1):
                partial += payload
                if b0 & 0x80:
                    return partial.decode(errors="replace")
            elif opcode == 8:
                raise ConnectionError("closed by Minecraft")


def png(path, w, h, rgba, flip=True):
    """rgba: bytes of w * h * 4, rows bottom-up when flip."""
    rows = []
    stride = w * 4
    for y in range(h):
        src = (h - 1 - y) if flip else y
        rows.append(b"\x00" + rgba[src * stride:(src + 1) * stride])

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(b"".join(rows), 3)) + chunk(b"IEND", b""))


class Frames:
    def __init__(self):
        self.map = None
        self.slots = 0
        self.stride = 0

    def open(self):
        if self.map is not None:
            return True
        try:
            head = mmap.mmap(-1, HEADER, tagname=NAME, access=mmap.ACCESS_READ)
        except OSError:
            return False
        if head[0:4] != b"MCPT":
            head.close()
            return False
        self.slots = struct.unpack_from("<i", head, 12)[0]
        self.stride = struct.unpack_from("<q", head, 16)[0]
        head.close()
        self.map = mmap.mmap(-1, HEADER + self.stride * self.slots, tagname=NAME, access=mmap.ACCESS_READ)
        return True

    def latest(self):
        """(info, world, depth, overlay) of the newest published frame, or None."""
        if not self.open():
            return None
        m = self.map
        published = struct.unpack_from("<q", m, 32)[0]
        slot = struct.unpack_from("<i", m, 40)[0]
        if slot < 0:
            return None
        d = 256 + 128 * slot
        seq = struct.unpack_from("<q", m, d)[0]
        if seq & 1:
            return None
        frame, host_frame, w, h, near, far, fov, flags = struct.unpack_from("<qqiifffi", m, d + 8)
        x, y, z, yaw, pitch, roll, fp = struct.unpack_from("<dddfffi", m, d + 48)
        if w <= 0 or h <= 0:
            return None
        base = HEADER + self.stride * slot
        n = w * h * 4
        world, depth, overlay = bytes(m[base:base + n]), bytes(m[base + n:base + 2 * n]), bytes(m[base + 2 * n:base + 3 * n])
        if struct.unpack_from("<q", m, d)[0] != seq:
            return None
        info = dict(published=published, frame=frame, host_frame=host_frame, w=w, h=h, near=near, far=far, fov=fov, flags=flags,
                    cam=(x, y, z), yaw=yaw, pitch=pitch, roll=roll, fp=fp)
        return info, world, depth, overlay


def linear(d, near, far, flags):
    if flags & 4:
        return 1e9 if d <= 0.0 else near * far / (near + d * (far - near))
    return 1e9 if d >= 1.0 else near * far / (far - d * (far - near))


def save(frames, out, tag):
    got = frames.latest()
    if got is None:
        print(f"[frame {tag}] none published yet")
        return None
    info, world, depth, overlay = got
    w, h = info["w"], info["h"]
    covered = 0
    zmin, zmax = 1e9, 0.0
    gray = bytearray(w * h * 4)
    floats = struct.unpack(f"<{w * h}f", depth)
    for i in range(w * h):
        if world[i * 4 + 3]:
            covered += 1
        z = linear(floats[i], info["near"], info["far"], info["flags"])
        if z < 1e8:
            zmin, zmax = min(zmin, z), max(zmax, z)
            v = max(0, min(255, int(255 - 44 * math.log10(max(z, 0.1) * 10))))
            gray[i * 4:i * 4 + 4] = bytes((v, v, v, 255))
    hud = sum(1 for i in range(3, len(overlay), 4) if overlay[i])
    png(os.path.join(out, f"{tag}_world.png"), w, h, world)
    png(os.path.join(out, f"{tag}_depth.png"), w, h, bytes(gray))
    png(os.path.join(out, f"{tag}_overlay.png"), w, h, overlay)
    info.update(covered=round(covered / (w * h), 4), zmin=round(zmin, 2), zmax=round(zmax, 2), overlay=round(hud / (w * h), 4))
    print(f"[frame {tag}] {json.dumps(info)}")
    return info


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seconds", type=float, default=60)
    ap.add_argument("--out", default="fakehost_out")
    ap.add_argument("--size", default="640x360")
    ap.add_argument("--storm", type=int, default=-1, help="summon a Wither Storm at this phase (0-7)")
    ap.add_argument("--evolve", type=int, default=0, help="press 'evolve' this many times")
    ap.add_argument("--dist", type=float, default=40)
    ap.add_argument("--up", type=float, default=20)
    ap.add_argument("--peds", type=int, default=12)
    ap.add_argument("--vehs", type=int, default=4)
    ap.add_argument("--third", action="store_true", help="third person (Steve is drawn)")
    ap.add_argument("--pitch", type=float, default=-12.0, help="camera pitch (Minecraft: negative looks up)")
    ap.add_argument("--remove", action="store_true", help="remove the storm at the end")
    ap.add_argument("--fresh", action="store_true", help="remove any storm there already, first")
    ap.add_argument("--radius", type=int, default=48, help="ground radius")
    ap.add_argument("--every", type=float, default=10.0, help="seconds between saved frames")
    ap.add_argument("--wait", type=float, default=120.0, help="seconds to wait for Minecraft's link")
    args = ap.parse_args()
    w, h = (int(v) for v in args.size.split("x"))
    os.makedirs(args.out, exist_ok=True)
    sys.stdout.reconfigure(line_buffering=True)

    link = None
    t0 = time.time()
    while link is None:
        try:
            link = Link()
        except OSError as e:
            if time.time() - t0 > args.wait:
                sys.exit(f"no link on 127.0.0.1:25599 after {args.wait:.0f}s ({e})")
            time.sleep(2)
    print(f"connected after {time.time() - t0:.0f}s")

    counts = {}
    quiet = {"mobs", "storms", "grab", "mcpos", "proj", "blocks", "hot"}
    last = {}
    stop = threading.Event()

    def reader():
        try:
            while not stop.is_set():
                text = link.recv()
                try:
                    kind = json.loads(text).get("t", "?")
                except ValueError:
                    kind = "?"
                counts[kind] = counts.get(kind, 0) + 1
                last[kind] = text
                if kind not in quiet or counts[kind] in (1, 2):
                    print(f"[{time.time() - t0:6.1f}] {text[:400]}")
        except (ConnectionError, OSError) as e:
            if not stop.is_set():
                print(f"link lost: {e}")
                stop.set()

    threading.Thread(target=reader, daemon=True).start()

    # the ground: a disc, surface at y = 64 (the columns' top block is y = 63)
    link.send({"t": "view", "w": w, "h": h})
    link.send({"t": "clear"})
    cols = []
    r = args.radius
    for x in range(-r, r + 1):
        for z in range(-r, r + 1):
            if x * x + z * z <= r * r:
                cols += [x, z, 62, 63]
    for i in range(0, len(cols), 4000):
        link.send({"t": "ground", "c": cols[i:i + 4000]})

    # the people and vehicles: standing about on the ground, strolling in small circles
    def people(t):
        peds, vehs = [], []
        for i in range(args.peds):
            a = i * 2.39996
            rr = 6 + (i * 5) % 22
            peds.append([1000 + i, round(rr * math.cos(a) + math.sin(t * 0.5 + i), 3), 64.0, round(8 + rr * math.sin(a) * 0.6 + 14, 3)])
        for i in range(args.vehs):
            vehs.append([2000 + i, round(-12 + i * 8, 3), 64.0, round(10 + 5 * (i % 2) + 12, 3)])
        return peds, vehs

    cam = {"p": [0.5, 67.0, -6.0], "yaw": 0.0, "pitch": args.pitch}
    frame = [0]

    def pump():
        nextPeople = 0.0
        while not stop.is_set():
            frame[0] += 1
            t = time.time() - t0
            x, y, z = cam["p"]
            link.send({"t": "cam", "f": frame[0], "p": [x, y, z], "r": [cam["yaw"], cam["pitch"], 0], "fov": 60.0, "fp": not args.third,
                       "pl": [x, y - 1.62 - (1.0 if args.third else 0.0), z + (4.0 if args.third else 0.0)], "h": cam["yaw"]})
            if t >= nextPeople and (counts.get("storms") or counts.get("mobs")):
                nextPeople = t + 0.05
                peds, vehs = people(t)
                link.send({"t": "peds", "p": peds})
                link.send({"t": "vehs", "v": vehs})
            time.sleep(1 / 60)

    threading.Thread(target=pump, daemon=True).start()

    frames = Frames()
    time.sleep(3)
    for c in ("fill -6 64 6 6 70 20 minecraft:air", "fill 0 64 6 0 66 6 minecraft:diamond_block", "setblock 2 64 6 minecraft:gold_block",
              "setblock -3 64 9 minecraft:glass"):
        link.send({"t": "cmd", "c": c})
    time.sleep(3)
    save(frames, args.out, "00_blocks")

    if args.fresh:
        link.send({"t": "storm", "op": "remove"})
        time.sleep(1.0)
    if args.storm >= 0:
        msg = {"t": "storm", "op": "summon", "dist": args.dist, "up": args.up}
        if args.storm > 0:
            msg["phase"] = args.storm
        link.send(msg)
        print(f"[{time.time() - t0:6.1f}] summon sent: {msg}")

    evolved = 0
    shot = 1
    nextShot = time.time() + args.every
    nextEvolve = time.time() + 20
    end = time.time() + args.seconds
    while time.time() < end and not stop.is_set():
        time.sleep(0.25)
        if time.time() >= nextShot:
            save(frames, args.out, f"{shot:02d}_t{int(time.time() - t0)}")
            shot += 1
            nextShot += args.every
            print(f"[{time.time() - t0:6.1f}] counts {json.dumps(counts)}")
            for k in ("storms", "grab"):
                if k in last:
                    print(f"         last {k}: {last[k][:300]}")
        if evolved < args.evolve and time.time() >= nextEvolve:
            evolved += 1
            nextEvolve += 15
            link.send({"t": "storm", "op": "evolve"})
            print(f"[{time.time() - t0:6.1f}] evolve {evolved}/{args.evolve}")

    save(frames, args.out, "99_end")
    if args.remove:
        link.send({"t": "storm", "op": "remove"})
        time.sleep(1.5)
    print(f"final counts {json.dumps(counts)}")
    stop.set()


if __name__ == "__main__":
    main()
