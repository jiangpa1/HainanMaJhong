# -*- coding: utf-8 -*-
"""
把 tile_back.png 缩到实际显示尺寸。

背景：这张图原来是 726x976 / 1.36MB，而它只显示在 30x41（对手手牌 xs2）的格子里 ——
每边放大约 24 倍，是全项目最大的单个资源（占整包 34%），
首次渲染一次解码 2.8MB 位图，手机上就是"进场顿一下 + 牌背先绿底后出图"。

目标尺寸取 3 倍设备像素：显示 30x41 CSS px，手机 DPR 一般 3 → 90x123 设备像素。
这里输出 92x123（保持原图宽高比 726:976），PNG 调色板量化后约 4~6KB。
原图在 git 里有（commit d6d4488），需要时能取回。
"""
import os
import sys
from PIL import Image

SRC = r'F:\HainanMaJhong2\HainanMaJhong\src\main\resources\static\img\tile_back.png'
TARGET_H = 123

im = Image.open(SRC)
orig_bytes = os.path.getsize(SRC)
orig_size = im.size
print('原图: %s  %.1f KB' % (im.size, orig_bytes / 1024.0))

w, h = im.size
target_w = max(1, round(w * TARGET_H / h))
small = im.convert('RGBA').resize((target_w, TARGET_H), Image.LANCZOS)

# 试三种存法，挑最小的（牌背是纯色块+纹样，量化后肉眼看不出差别）
cands = []
p_rgba = SRC + '.rgba.png'
small.save(p_rgba, format='PNG', optimize=True)
cands.append(('RGBA 直接存', p_rgba))

p_q128 = SRC + '.q128.png'
small.convert('P', palette=Image.ADAPTIVE, colors=128).save(p_q128, format='PNG', optimize=True)
cands.append(('调色板 128 色', p_q128))

p_q64 = SRC + '.q64.png'
small.convert('P', palette=Image.ADAPTIVE, colors=64).save(p_q64, format='PNG', optimize=True)
cands.append(('调色板 64 色', p_q64))

best = None
for name, path in cands:
    b = os.path.getsize(path)
    print('  %-14s %6.1f KB' % (name, b / 1024.0))
    if best is None or b < best[1]:
        best = (path, b, name)

print('采用: %s  %.1f KB  ->  %dx%d' % (best[2], best[1] / 1024.0, target_w, TARGET_H))
with open(best[0], 'rb') as f:
    data = f.read()
with open(SRC, 'wb') as f:
    f.write(data)
for _, path in cands:
    if os.path.exists(path):
        os.remove(path)

new_bytes = os.path.getsize(SRC)
print('完成: %.1f KB -> %.1f KB  (省 %.1f%%)'
      % (orig_bytes / 1024.0, new_bytes / 1024.0,
         (1 - new_bytes / float(orig_bytes)) * 100))
print('尺寸: %dx%d -> %dx%d' % (orig_size[0], orig_size[1], target_w, TARGET_H))
