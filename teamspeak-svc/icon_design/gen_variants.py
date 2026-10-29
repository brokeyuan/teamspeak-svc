# -*- coding: utf-8 -*-
# 原生描边风格图标变体生成器 + 预览合成
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

    def fill_headset_solid(self):
        # 实心耳机剪影：头带环带 + 实心耳罩 + 实心麦
        self.arc(16, 18.5, 11, 180, 360, 4.5)
        self.rrect_fill(3.5, 17, 8.5, 26, 2)
        self.rrect_fill(23.5, 17, 28.5, 26, 2)
        self.arc(12, 22, 8, 20, 90, 2.6)
        self.dot(17, 27, 2.6)


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


def render_variant(kind):
    p = P()
    if kind == 'T1':
        # 细描边耳机 + 麦（现版）
        p.arc(16, 18.5, 11, 180, 360, 2.6)
        p.rrect_outline(3.5, 17, 8.5, 26, 2, 2.2)
        p.rrect_outline(23.5, 17, 28.5, 26, 2, 2.2)
        p.arc(12, 22, 8, 20, 90, 2.0)
        p.rrect_outline(14, 25, 19, 29, 2, 2.0)
    elif kind == 'T2':
        # 粗线简洁：头带 + 耳罩，无麦
        p.arc(16, 18, 11, 180, 360, 3.6)
        p.rrect_outline(3, 16, 9, 27, 2.5, 3.2)
        p.rrect_outline(23, 16, 29, 27, 2.5, 3.2)
    elif kind == 'T3':
        # 描边头带 + 实心耳罩 + 麦
        p.arc(16, 18.5, 11, 180, 360, 2.6)
        p.rrect_fill(3.5, 17, 8.5, 26, 2)
        p.rrect_fill(23.5, 17, 28.5, 26, 2)
        p.arc(12, 22, 8, 20, 90, 2.0)
        p.dot(16.5, 27, 2.4)
    elif kind == 'T4':
        # 实心剪影
        p.fill_headset_solid()
    elif kind == 'E1':
        # 三轨三滑块（现版）
        for x in (8, 16, 24):
            p.seg(x, 5, x, 27, 1.8)
        p.rrect_fill(5, 9, 11, 14, 1.5)
        p.rrect_fill(13, 17, 19, 22, 1.5)
        p.rrect_fill(21, 6, 27, 11, 1.5)
    elif kind == 'E2':
        # 两轨大滑块
        for x in (11, 21):
            p.seg(x, 5, x, 27, 2.2)
        p.rrect_fill(7, 10, 15, 17, 2)
        p.rrect_fill(17, 18, 25, 25, 2)
    elif kind == 'E3':
        # 波形线（声波）
        prev = None
        for i in range(0, 101):
            t = i / 100
            x = 3 + t * 26
            y = 16 + math.sin(t * math.pi * 3) * 9
            if prev is not None:
                p.seg(prev[0], prev[1], x, y, 2.4)
            prev = (x, y)
    elif kind == 'E4':
        # 滑块 + 波形组合
        for x in (7, 25):
            p.seg(x, 5, x, 27, 1.8)
        p.rrect_fill(4, 12, 10, 18, 1.5)
        p.rrect_fill(22, 16, 28, 22, 1.5)
        prev = None
        for i in range(0, 101):
            t = i / 100
            x = 10 + t * 12
            y = 16 + math.sin(t * math.pi * 2) * 5
            if prev is not None:
                p.seg(prev[0], prev[1], x, y, 2.0)
            prev = (x, y)
    return [[p.acc.get(y * OUT + x, (0, 0, 0, 0)) for x in range(OUT)] for y in range(OUT)]


# ===== 生成 8 个变体 =====
kinds = ['T1', 'T2', 'T3', 'T4', 'E1', 'E2', 'E3', 'E4']
out_dir = 'C:/Users/Administrator/Documents/Workspace/Brokeyuan/mods/teamspeak&svcbridge/teamspeak-svc/icon_design'
rendered = []
for k in kinds:
    px = render_variant(k)
    open(f'{out_dir}/variant_{k}.png', 'wb').write(png(px))
    rendered.append((k, px))
    print('rendered', k)

# ===== 合成预览（2 行 x 4 列，8x 放大，带编号角标）=====
S = 8
CELL = 32 * S
PAD = 24
W = 4 * (CELL + PAD) + PAD
H = 2 * (CELL + PAD + 20) + PAD
bg = [[(44, 44, 48, 255)] * W for _ in range(H)]

DIGITS = {
    '1': [(1,0),(2,0),(0,1),(1,1),(2,1),(2,2),(2,3),(2,4)],
    '2': [(0,0),(1,0),(2,0),(2,1),(1,2),(0,3),(0,4),(1,4),(2,4)],
    '3': [(0,0),(1,0),(2,0),(2,1),(1,2),(2,3),(0,4),(1,4),(2,4)],
    '4': [(0,0),(0,1),(0,2),(1,2),(2,0),(2,1),(2,2),(2,3),(2,4)],
    '5': [(0,0),(1,0),(2,0),(0,1),(0,2),(1,2),(2,3),(0,4),(1,4),(2,4)],
    '6': [(0,0),(1,0),(2,0),(0,1),(0,2),(1,2),(2,3),(0,4),(1,4),(2,4)],
    '7': [(0,0),(1,0),(2,0),(2,1),(2,2),(2,3),(2,4)],
    '8': [(0,0),(1,0),(2,0),(0,1),(2,1),(0,2),(1,2),(2,2),(0,3),(1,3),(2,3),(0,4),(1,4),(2,4)],
}
def draw_num(px, n, ox, oy, scale=3, c=(255,220,60,255)):
    s = str(n)
    for i, ch in enumerate(s):
        for (x, y) in DIGITS[ch]:
            for dy in range(scale):
                for dx in range(scale):
                    px[oy + y*scale + dy][ox + i*(scale*4+4) + x*scale + dx] = c

for i, (k, px) in enumerate(rendered):
    row, col = divmod(i, 4)
    ox = PAD + col * (CELL + PAD)
    oy = PAD + row * (CELL + PAD + 20)
    for y in range(CELL):
        for x in range(CELL):
            c = px[y // S][x // S]
            if c[3] > 0:
                a = c[3] / 255
                b = bg[oy + y][ox + x]
                bg[oy + y][ox + x] = tuple(int(c[j] * a + b[j] * (1 - a)) for j in range(3)) + (255,)
    draw_num(bg, i + 1, ox + 4, oy + CELL + 4)

h2, w2 = H * 2, W * 2
raw = b''
for y in range(h2):
    row = bg[y // 2]
    raw += b'\x00' + b''.join(struct.pack('4B', *row[x // 2]) for x in range(w2))
def chunk(t, d):
    return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
preview = (b'\x89PNG\r\n\x1a\n'
           + chunk(b'IHDR', struct.pack('>IIBBBBB', w2, h2, 8, 6, 0, 0, 0))
           + chunk(b'IDAT', zlib.compress(raw, 9))
           + chunk(b'IEND', b''))
preview_path = 'C:/Users/Administrator/Documents/Workspace/Brokeyuan/mods/teamspeak&svcbridge/teamspeak-svc/icon_preview.png'
open(preview_path, 'wb').write(preview)
print('preview written:', preview_path)
