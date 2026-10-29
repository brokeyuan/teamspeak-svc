# -*- coding: utf-8 -*-
# 深色描边风格图标（SVC 原生风格）：官方 logo 形状转黑 + Extra 推子+增强星
# 生成两态：normal（黑）与 dim（暗淡，TS 不可用时）
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


def read_png_rgba(path):
    data = open(path, 'rb').read()
    pos = 8
    w = h = 0
    idat = b''
    while pos < len(data):
        ln = struct.unpack('>I', data[pos:pos + 4])[0]
        tag = data[pos + 4:pos + 8]
        if tag == b'IHDR':
            w, h = struct.unpack('>II', data[pos + 8:pos + 16])
        elif tag == b'IDAT':
            idat += data[pos + 8:pos + 8 + ln]
        pos += 12 + ln
    raw = zlib.decompress(idat)
    bpp = 4
    stride = w * bpp
    prev = bytearray(stride)
    out = []
    for y in range(h):
        row = bytearray(raw[y * (stride + 1) + 1:(y + 1) * (stride + 1)])
        f = row[0]
        for i in range(stride):
            a = row[i - bpp] if i >= bpp else 0
            b = prev[i]
            c = prev[i - bpp] if i >= bpp else 0
            if f == 1:
                row[i] = (row[i] + a) & 255
            elif f == 2:
                row[i] = (row[i] + b) & 255
            elif f == 3:
                row[i] = (row[i] + (a + b) // 2) & 255
            elif f == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                row[i] = (row[i] + (a if pa <= pb and pa <= pc else (b if pb <= pc else c))) & 255
        prev = row
        out.append([tuple(row[x * 4:x * 4 + 4]) for x in range(w)])
    return out


def png(px, size):
    raw = b''
    for y in range(size):
        raw += b'\x00' + b''.join(struct.pack('4B', *px[y][x]) for x in range(size))
    def chunk(t, d):
        return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    return (b'\x89PNG\r\n\x1a\n'
            + chunk(b'IHDR', struct.pack('>IIBBBBB', size, size, 8, 6, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw, 9))
            + chunk(b'IEND', b''))


# ===== 1) TS 按钮：官方 logo 形状（上轮像素化的 alpha）转黑墨色 =====
cur = read_png_rgba('C:/Users/Administrator/Documents/Workspace/Brokeyuan/mods/teamspeak&svcbridge/teamspeak-svc/src/main/resources/assets/teamspeak-svc/textures/icons/ts_button.png')
INK = (26, 26, 28, 255)          # 与原生图标一致的深墨色
INK_DIM = (26, 26, 28, 110)      # 暗态（TS 不可用）

normal = [[(INK[0], INK[1], INK[2], c[3]) for c in row] for row in cur]
dim = [[(INK[0], INK[1], INK[2], int(c[3] * 0.38)) for c in row] for row in cur]

# ===== 2) Extra 按钮：推子描边 + 增强星（深色描边，SVC 滑条同款风格）=====
def draw_extras(base_alpha):
    px = [[(0, 0, 0, 0)] * OUT for _ in range(OUT)]
    c = (INK[0], INK[1], INK[2], int(INK[3] * base_alpha))
    def seg(ax, ay, bx, by, w):
        for y in range(OUT):
            for x in range(OUT):
                cov = smooth(w / 2 - dist_seg(x + 0.5, y + 0.5, ax, ay, bx, by)) * c[3] / 255.0
                if cov > 0:
                    old = px[y][x]
                    if cov * 255 >= old[3]:
                        px[y][x] = (c[0], c[1], c[2], int(min(255, cov * c[3])))
    def rrect_fill(x0, y0, x1, y1, rad):
        for y in range(OUT):
            for x in range(OUT):
                qx = abs(x + 0.5 - (x0 + x1) / 2) - (x1 - x0) / 2 + rad
                qy = abs(y + 0.5 - (y0 + y1) / 2) - (y1 - y0) / 2 + rad
                d = math.hypot(max(qx, 0), max(qy, 0)) + min(max(qx, qy), 0) - rad
                cov = smooth(-d) * c[3] / 255.0
                if cov > 0:
                    old = px[y][x]
                    if cov * 255 >= old[3]:
                        px[y][x] = (c[0], c[1], c[2], int(min(255, cov * c[3])))
    def sparkle(cx, cy, r_out, r_in):
        # 四角星（菱形渐尖）
        for y in range(OUT):
            for x in range(OUT):
                dx = abs(x + 0.5 - cx)
                dy = abs(y + 0.5 - cy)
                d = (dx + dy)
                if d <= r_out and max(dx, dy) >= r_in:
                    cov = smooth(min(r_out - d, 1.2)) * c[3] / 255.0
                    if cov > 0:
                        old = px[y][x]
                        if cov * 255 >= old[3]:
                            px[y][x] = (c[0], c[1], c[2], int(min(255, cov * c[3])))
    # 两条推子轨 + 错位滑块（描边风格同原生 adjust_volumes）
    seg(11, 6, 11, 26, 1.8)
    seg(20, 6, 20, 26, 1.8)
    rrect_fill(8, 11, 14, 16, 1.2)
    rrect_fill(17, 17, 23, 22, 1.2)
    # 右上角增强星
    sparkle(26, 6, 5.2, 1.6)
    return px

extras_normal = draw_extras(1.0)
extras_dim = draw_extras(0.38)

out_dir = 'C:/Users/Administrator/Documents/Workspace/Brokeyuan/mods/teamspeak&svcbridge/teamspeak-svc/src/main/resources/assets/teamspeak-svc/textures/icons'
open(out_dir + '/ts_button.png', 'wb').write(png(normal, OUT))
open(out_dir + '/ts_button_dim.png', 'wb').write(png(dim, OUT))
open(out_dir + '/audio_extras.png', 'wb').write(png(extras_normal, OUT))
print('dark-style icons written (normal + dim)')
