#!/usr/bin/env python3
"""Generate the mic-recorder launcher icons (pure Python stdlib, no deps).

Renders a simple microphone glyph: dark rounded-square background,
light mic capsule + holder arc, red stand and base. Writes PNG mipmaps
into res/mipmap-*/ and a 512px copy into docs/ for the README.
"""
import math
import os
import struct
import zlib

BG = (26, 26, 26)        # #1A1A1A dark background
LIGHT = (238, 238, 238)  # mic body
RED = (229, 57, 53)      # #E53935 accent

CX = 0.5
MIC_HW = 0.085           # mic capsule half width
MIC_TOP = 0.15
MIC_BOT = 0.50
ARC_CY = 0.50
ARC_R = 0.20
ARC_T = 0.034
ARC_A0 = 20.0            # degrees, lower arc sweep
ARC_A1 = 160.0
STEM_HW = 0.016
STEM_TOP = 0.70
STEM_BOT = 0.80
BASE_Y0 = 0.80
BASE_Y1 = 0.846
BASE_X0 = 0.33
BASE_X1 = 0.67
CORNER_R = 0.18


def px_color(x, y):
    # mic capsule (vertical stadium)
    in_rect = abs(x - CX) <= MIC_HW and (MIC_TOP + MIC_HW) <= y <= (MIC_BOT - MIC_HW)
    if (in_rect or math.hypot(x - CX, y - (MIC_TOP + MIC_HW)) <= MIC_HW
            or math.hypot(x - CX, y - (MIC_BOT - MIC_HW)) <= MIC_HW):
        return LIGHT + (255,)
    # holder arc (lower sweep of a ring)
    d = math.hypot(x - CX, y - ARC_CY)
    if ARC_R - ARC_T <= d <= ARC_R:
        ang = math.degrees(math.atan2(y - ARC_CY, x - CX))
        if ARC_A0 <= ang <= ARC_A1:
            return LIGHT + (255,)
    # stand
    if abs(x - CX) <= STEM_HW and STEM_TOP <= y <= STEM_BOT:
        return RED + (255,)
    # base bar (horizontal stadium)
    br = (BASE_Y1 - BASE_Y0) / 2.0
    byc = (BASE_Y0 + BASE_Y1) / 2.0
    if ((BASE_X0 + br) <= x <= (BASE_X1 - br) and BASE_Y0 <= y <= BASE_Y1
            or math.hypot(x - (BASE_X0 + br), y - byc) <= br
            or math.hypot(x - (BASE_X1 - br), y - byc) <= br):
        return RED + (255,)
    # rounded-square background (SDF)
    px = abs(x - 0.5) - (0.5 - CORNER_R)
    py = abs(y - 0.5) - (0.5 - CORNER_R)
    d_bg = math.hypot(max(px, 0.0), max(py, 0.0)) + min(max(px, py), 0.0) - CORNER_R
    if d_bg <= 0:
        return BG + (255,)
    return (0, 0, 0, 0)


def render(size):
    ss = 4 if size <= 192 else 2
    rows = []
    for oy in range(size):
        row = bytearray()
        for ox in range(size):
            r = g = b = a = 0.0
            for sy in range(ss):
                for sx in range(ss):
                    x = (ox + (sx + 0.5) / ss) / size
                    y = (oy + (sy + 0.5) / ss) / size
                    pr, pg, pb, pa = px_color(x, y)
                    af = pa / 255.0
                    r += pr * af
                    g += pg * af
                    b += pb * af
                    a += pa
            cnt = ss * ss
            alpha = a / cnt
            if alpha > 0.5:
                un = 255.0 / alpha
                row += bytes((min(255, int(r / cnt * un + 0.5)),
                              min(255, int(g / cnt * un + 0.5)),
                              min(255, int(b / cnt * un + 0.5)),
                              int(alpha + 0.5)))
            else:
                row += b'\x00\x00\x00\x00'
        rows.append(bytes(row))
    return rows


def write_png(path, size, rows):
    raw = b''.join(b'\x00' + row for row in rows)

    def chunk(tag, data):
        c = tag + data
        return struct.pack('>I', len(data)) + c + struct.pack('>I', zlib.crc32(c) & 0xffffffff)

    ihdr = struct.pack('>IIBBBBB', size, size, 8, 6, 0, 0, 0)
    with open(path, 'wb') as f:
        f.write(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', ihdr)
                + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))


def main():
    root = os.path.dirname(os.path.abspath(__file__))
    for dpi, size in [('mdpi', 48), ('hdpi', 72), ('xhdpi', 96),
                      ('xxhdpi', 144), ('xxxhdpi', 192)]:
        d = os.path.join(root, 'res', 'mipmap-' + dpi)
        if not os.path.isdir(d):
            os.makedirs(d)
        write_png(os.path.join(d, 'ic_launcher.png'), size, render(size))
    docs = os.path.join(root, 'docs')
    if not os.path.isdir(docs):
        os.makedirs(docs)
    write_png(os.path.join(docs, 'icon.png'), 512, render(512))
    print('icons written')


if __name__ == '__main__':
    main()
