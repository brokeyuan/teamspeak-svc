# -*- coding: utf-8 -*-
# SVC native icon style: 32x32 transparent bg + dark outline glyph + alpha AA
import struct, zlib, math

OUT = 32

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

    def _paint(self, x, y, cov):
        key = y * OUT + x
        prev = self.acc.get(key)
        if prev is None or cov * 255 > prev[3]:
            self.acc[key] = (0, 0, 0, int(min(255, cov * 255)))

    def arc(self, cx, cy, r, a0, a1, w):
        span = (a1 - a0) % 360
        for y in range(OUT):
            for x in range(OUT):
                px, py = x + 0.5 - cx, y + 0.5 - cy
                d = math.hypot(px, py)
                cov = smooth(w / 2 - abs(d - r))
                if cov <= 0:
                    continue
                ang = math.degrees(math.atan2(py, px)) % 360
                rel = (ang - a0) % 360
                p0 = (cx + r * math.cos(math.radians(a0)), cy + r * math.sin(math.radians(a0)))
                p1 = (cx + r * math.cos(math.radians(a1)), cy + r * math.sin(math.radians(a1)))
                cap = smooth(w / 2 - min(math.hypot(x + 0.5 - p0[0], y + 0.5 - p0[1]),
                                         math.hypot(x + 0.5 - p1[0], y + 0.5 - p1[1])))
                cov = max(cov if rel <= span else 0.0, cap)
                if cov > 0:
                    self._paint(x, y, cov)

    def seg(self, ax, ay, bx, by, w):
        for y in range(OUT):
            for x in range(OUT):
                cov = smooth(w / 2 - dist_seg(x + 0.5, y + 0.5, ax, ay, bx, by))
                if cov > 0:
                    self._paint(x, y, cov)

    def rrect_outline(self, x0, y0, x1, y1, rad, w):
        for y in range(OUT):
            for x in range(OUT):
                qx = abs(x + 0.5 - (x0 + x1) / 2) - (x1 - x0) / 2 + rad
                qy = abs(y + 0.5 - (y0 + y1) / 2) - (y1 - y0) / 2 + rad
                d = math.hypot(max(qx, 0), max(qy, 0)) + min(max(qx, qy), 0) - rad
                cov = smooth(w / 2 - abs(d))
                if cov > 0:
                    self._paint(x, y, cov)

    def rrect_fill(self, x0, y0, x1, y1, rad):
        for y in range(OUT):
            for x in range(OUT):
                qx = abs(x + 0.5 - (x0 + x1) / 2) - (x1 - x0) / 2 + rad
                qy = abs(y + 0.5 - (y0 + y1) / 2) - (y1 - y0) / 2 + rad
                d = math.hypot(max(qx, 0), max(qy, 0)) + min(max(qx, qy), 0) - rad
                cov = smooth(-d)
                if cov > 0:
                    self._paint(x, y, cov)

    def dot(self, cx, cy, r):
        for y in range(OUT):
            for x in range(OUT):
                cov = smooth(r - math.hypot(x + 0.5 - cx, y + 0.5 - cy))
                if cov > 0:
                    self._paint(x, y, cov)


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

# --- TS button: headset outline (matches native mic icon style) ---
p = P()
p.arc(16, 18.5, 11, 180, 360, 2.6)
p.rrect_outline(3.5, 17, 8.5, 26, 2, 2.2)
p.rrect_outline(23.5, 17, 28.5, 26, 2, 2.2)
p.arc(12, 22, 8, 20, 90, 2.0)
p.rrect_outline(14, 25, 19, 29, 2, 2.0)
open(out_dir + '/ts_button.png', 'wb').write(png([[p.acc.get(y * OUT + x, (0, 0, 0, 0)) for x in range(OUT)] for y in range(OUT)]))

# --- Extras button: EQ sliders outline (matches native adjust_volumes style) ---
p = P()
for x in (8, 16, 24):
    p.seg(x, 5, x, 27, 1.8)
p.rrect_fill(5, 9, 11, 14, 1.5)
p.rrect_fill(13, 17, 19, 22, 1.5)
p.rrect_fill(21, 6, 27, 11, 1.5)
open(out_dir + '/audio_extras.png', 'wb').write(png([[p.acc.get(y * OUT + x, (0, 0, 0, 0)) for x in range(OUT)] for y in range(OUT)]))

print('native-style icons written')
