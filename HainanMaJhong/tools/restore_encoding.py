#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
尽力还原被 PowerShell 二次编码搞坏的文件。

成因：
  1. `Get-Content -Raw`（PS 5.1 默认按系统 ANSI = cp936 读）把 UTF-8 源文件
     按 GBK 解码 —— 于是文本变成了乱码字符串，但这一步是【无损】的：
     GBK 解码出的字符码位一一对应原始字节。
  2. cp936 里没有映射的字节序列被替换成私用区（PUA）字符 —— **这一步是有损的**，
     每一处丢失 1 个原始字节。
  3. `Set-Content -Encoding UTF8` 把乱码字符串按 UTF-8 写出。

还原：把当前字节按 UTF-8 解码 → 按 cp936 编码回原始字节 → 按 UTF-8 解码。
PUA 字符是丢字节的位置，无法还原，用 U+FFFD 标出来供人工修补。
"""
import sys

path = sys.argv[1]
with open(path, "rb") as f:
    raw = f.read()

text = raw.decode("utf-8").lstrip("\ufeff")

# PUA 字符 = 损坏过程中丢失的那个字节；先换成占位符，最后统计
pua_count = sum(1 for c in text if 0xE000 <= ord(c) <= 0xF8FF)
buf = bytearray()
lost = 0
for c in text:
    try:
        buf += c.encode("cp936")
    except UnicodeEncodeError:
        lost += 1
        buf += b"?"  # 占位：原字节已不可知

try:
    restored = buf.decode("utf-8")
except UnicodeDecodeError as e:
    print("[restore] 还原后不是合法 UTF-8（丢字节导致）：%s" % e)
    # 用 replace 强行解出来，至少能看到结构
    restored = buf.decode("utf-8", errors="replace")

out = path + ".restored"
with open(out, "w", encoding="utf-8", newline="") as f:
    f.write(restored)

print("[restore] PUA 丢弃字符 %d 个，还原后 %d 字符" % (pua_count, len(restored)))
print("[restore] 已写出 %s" % out)
