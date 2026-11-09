#!/usr/bin/env python3
"""生成简单的启动图标：深色圆角方块 + 青色播放三角。纯 zlib 手写 PNG，不依赖 PIL。"""
import os
import struct
import zlib

SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "res")


def blend(dst, src, a):
    return tuple(int(d + (s - d) * a) for d, s in zip(dst, src))


def render(size):
    px = [[(0, 0, 0, 0) for _ in range(size)] for _ in range(size)]
    r = size * 0.22
    for y in range(size):
        for x in range(size):
            # 圆角矩形遮罩
            cx = min(max(x + 0.5, r), size - r)
            cy = min(max(y + 0.5, r), size - r)
            dx, dy = x + 0.5 - cx, y + 0.5 - cy
            if dx * dx + dy * dy > r * r:
                continue
            # 对角渐变：左上深灰 -> 右下近黑
            t = (x + y) / (2.0 * size)
            col = (int(28 - 12 * t), int(30 - 12 * t), int(36 - 14 * t))
            px[y][x] = (col[0], col[1], col[2], 255)
    # 播放三角
    cx, cy = size * 0.42, size * 0.5
    h = size * 0.28
    w = size * 0.22
    for y in range(size):
        for x in range(size):
            if px[y][x][3] == 0:
                continue
            fy = (y + 0.5 - cy) / h
            if -1 <= fy <= 1:
                half = w * (1 - abs(fy))
                if 0 <= (x + 0.5 - cx) <= half:
                    px[y][x] = blend(px[y][x][:3], (46, 226, 201), 1.0) + (255,)
    return px


def write_png(path, px):
    n = len(px)
    raw = b"".join(b"\x00" + b"".join(struct.pack("4B", *p) for p in row) for row in px)
    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", n, n, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9))
    png += chunk(b"IEND", b"")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, "wb").write(png)


for dpi, size in SIZES.items():
    write_png(os.path.join(OUT, "mipmap-" + dpi, "ic_launcher.png"), render(size))
    print("wrote", dpi, size)
