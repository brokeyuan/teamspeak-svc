# -*- coding: utf-8 -*-
# TS 原生 logo 像素化（128px AA 渲染 → 32x32，SVC 图标规格）
import struct, zlib, math

SS = 4
BASE = 128
OUT = 32

def clamp(v, a, b):
    return max(a, min(b, v))

def smooth(f):
    return max(0.0, min(1.0, f + 0.5))

def dist_seg(px, py, ax, ay, bx, by):
    dx, dy = bx - ax, by - ay
    L2 = dx * dx + dy * dy
    if L2 == 0:
        return math.hypot(px - ax, py - ay)
    t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / L2))
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy))


class P:
    def __init__(self):
        self.acc = {}
        self.size = BASE

    def _paint(self, x, y, cov, c):
        key = y * BASE + x
        prev = self.acc.get(key)
        if prev is None or cov * c[3] >= prev[3]:
            self.acc[key] = (c[0], c[1], c[2], int(min(255, cov * c[3])))

    def circle_fill(self, cx, cy, r, c):
        for y in range(max(0, int(cy - r - 2)), min(self.size, int(cy + r + 3))):
            for x in range(max(0, int(cx - r - 2)), min(self.size, int(cx + r + 3))):
                cov = smooth(r - math.hypot(x + 0.5 - cx, y + 0.5 - cy)) * c[3] / 255.0
                if cov > 0:
                    self._paint(x, y, cov, c)

    def circle_ring(self, cx, cy, r, stroke, c):
        for y in range(max(0, int(cy - r - stroke - 2)), min(self.size, int(cy + r + stroke + 3))):
            for x in range(max(0, int(cx - r - stroke - 2)), min(self.size, int(cx + r + stroke + 3))):
                d = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
                cov = smooth(stroke / 2 - abs(d - r)) * c[3] / 255.0
                if cov > 0:
                    self._paint(x, y, cov, c)

    def arc_stroke(self, cx, cy, r, a0, a1, stroke, c):
        span = (a1 - a0) % 360
        for y in range(max(0, int(cy - r - stroke - 2)), min(self.size, int(cy + r + stroke + 3))):
            for x in range(max(0, int(cx - r - stroke - 2)), min(self.size, int(cx + r + stroke + 3))):
                px, py = x + 0.5 - cx, y + 0.5 - cy
                d = math.hypot(px, py)
                cov = smooth(stroke / 2 - abs(d - r))
                if cov <= 0:
                    continue
                ang = math.degrees(math.atan2(py, px)) % 360
                rel = (ang - a0) % 360
                p0 = (cx + r * math.cos(math.radians(a0)), cy + r * math.sin(math.radians(a0)))
                p1 = (cx + r * math.cos(math.radians(a1)), cy + r * math.sin(math.radians(a1)))
                cap = smooth(stroke / 2 - min(math.hypot(x + 0.5 - p0[0], y + 0.5 - p0[1]),
                                              math.hypot(x + 0.5 - p1[0], y + 0.5 - p1[1])))
                cov = max(cov if rel <= span else 0.0, cap) * c[3] / 255.0
                if cov > 0:
                    self._paint(x, y, cov, c)

    def capsule(self, ax, ay, bx, by, width, c):
        r = width / 2
        for y in range(max(0, int(min(ay, by) - r - 2)), min(self.size, int(max(ay, by) + r + 3))):
            for x in range(max(0, int(min(ax, bx) - r - 2)), min(self.size, int(max(ax, bx) + r + 3))):
                cov = smooth(r - dist_seg(x + 0.5, y + 0.5, ax, ay, bx, by)) * c[3] / 255.0
                if cov > 0:
                    self._paint(x, y, cov, c)

    def rrect_fill(self, x0, y0, x1, y1, rad, c):
        for y in range(max(0, int(y0 - 2)), min(self.size, int(y1 + 3))):
            for x in range(max(0, int(x0 - 2)), min(self.size, int(x1 + 3))):
                qx = abs(x + 0.5 - (x0 + x1) / 2) - (x1 - x0) / 2 + rad
                qy = abs(y + 0.5 - (y0 + y1) / 2) - (y1 - y0) / 2 + rad
                d = math.hypot(max(qx, 0), max(qy, 0)) + min(max(qx, qy), 0) - rad
                cov = smooth(-d) * c[3] / 255.0
                if cov > 0:
                    self._paint(x, y, cov, c)

    def flatten(self):
        out = [[(0, 0, 0, 0)] * OUT for _ in range(OUT)]
        for (key, c) in self.acc.items():
            x, y = key % BASE, key // BASE
            ox, oy = x // SS, y // SS
            if 0 <= ox < OUT and 0 <= oy < OUT:
                out[oy][ox] = c
        return out


def png(px):
    raw = b''
    for y in range(OUT):
        raw += b'\x00' + b''.join(struct.pack('4B', *px[y][x]) for x in range(OUT))
    def chunk(t, d):
        return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    return (b'\x89PNG\r\n\x1a\n'
            + chunk(b'IHDR', struct.pack('>IIBBBBB', OUT, OUT, 8, 6, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw, 9))
            + chunk(b'IEND', b''))


# ===== TS 原生 logo 像素化：蓝球 + 白色头戴耳机 =====
# 配色取自 TeamSpeak 客户端图标（亮蓝→深蓝渐变球 + 白色耳机）
BLUE_LIGHT = (58, 168, 224, 255)
BLUE_MID = (37, 147, 214, 255)
BLUE_DARK = (18, 105, 168, 255)
WHITE = (255, 255, 255, 255)


def ts_logo_button():
    p = P()
    # 蓝色球体（径向渐变：上亮下暗）
    for y in range(2, 126):
        for x in range(2, 126):
            d = math.hypot(x + 0.5 - 64, y + 0.5 - 64)
            if d <= 60:
                t = clamp((y - 10) / 108.0, 0.0, 1.0)
                c = (int(BLUE_LIGHT[0] * (1 - t) + BLUE_DARK[0] * t),
                     int(BLUE_LIGHT[1] * (1 - t) + BLUE_DARK[1] * t),
                     int(BLUE_LIGHT[2] * (1 - t) + BLUE_DARK[2] * t), 255)
                cov = smooth(60 - d)
                if cov > 0:
                    p._paint(x, y, cov, c)
    # 白色耳机：头带弧 + 双耳罩 + 麦克风
    p.arc_stroke(64, 66, 38, 180, 360, 13, WHITE)
    p.capsule(26, 62, 26, 88, 20, WHITE)
    p.capsule(102, 62, 102, 88, 20, WHITE)
    p.arc_stroke(42, 82, 26, 15, 85, 7, WHITE)
    p.circle_fill(62, 92, 7, WHITE)
    return p.flatten()


# ===== Extra 图标：同款蓝球 + 白色调音推子 =====
def extras_logo_button():
    p = P()
    for y in range(2, 126):
        for x in range(2, 126):
            d = math.hypot(x + 0.5 - 64, y + 0.5 - 64)
            if d <= 60:
                t = clamp((y - 10) / 108.0, 0.0, 1.0)
                c = (int(BLUE_LIGHT[0] * (1 - t) + BLUE_DARK[0] * t),
                     int(BLUE_LIGHT[1] * (1 - t) + BLUE_DARK[1] * t),
                     int(BLUE_LIGHT[2] * (1 - t) + BLUE_DARK[2] * t), 255)
                cov = smooth(60 - d)
                if cov > 0:
                    p._paint(x, y, cov, c)
    # 白色调音推子（三轨 + 错位滑块）
    for i, x in enumerate((40, 64, 88)):
        p.capsule(x, 28, x, 100, 7, WHITE)
        knob_y = (40, 62, 80)[i]
        p.rrect_fill(x - 13, knob_y, x + 13, knob_y + 17, 5, WHITE)
    return p.flatten()


def png(px):
    raw = b''
    for y in range(OUT):
        raw += b'\x00' + b''.join(struct.pack('4B', *px[y][x]) for x in range(OUT))
    def chunk(t, d):
        return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    return (b'\x89PNG\r\n\x1a\n'
            + chunk(b'IHDR', struct.pack('>IIBBBBB', OUT, OUT, 8, 6, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw, 9))
            + chunk(b'IEND', b''))


out_dir = 'C:/Users/Administrator/Documents/Workspace/Brokeyuan/mods/teamspeak&svcbridge/teamspeak-svc/src/main/resources/assets/teamspeak-svc/textures/icons'
open(out_dir + '/ts_button.png', 'wb').write(png(ts_logo_button()))
open(out_dir + '/audio_extras.png', 'wb').write(png(extras_logo_button()))
print('TS logo pixelated icons written')
