#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
修复被 PowerShell Set-Content 二次编码搞坏的文件。

成因：Set-Content -Encoding UTF8 在 PS 5.1 下把【已经正确解码的 Unicode 文本】
按 UTF-8 写出没问题；但前面 Get-Content -Raw 是按 GBK（系统 ANSI 代码页）
读的 UTF-8 文件，读进来就已经是乱码字符串，再写出去就成了
"每个 UTF-8 字节被当成一个字符编码一次"的双重编码。

修复：把当前字节按 UTF-8 解码得到乱码字符串，再按 cp936 编码回原始字节，
最后按 UTF-8 解码 —— 正好是逆操作（前提是过程中没有字符被替换成 '?'）。
"""
import sys

path = sys.argv[1]
with open(path, "rb") as f:
    raw = f.read()

text = raw.decode("utf-8")
# Set-Content -Encoding UTF8 在 PS 5.1 下会加 BOM；BOM 无法编码回 GBK，先去掉
text = text.lstrip("\ufeff")
if "?" in text and "锛" not in text:
    print("[fix] 警告：文本里几乎没有典型乱码特征，可能已经修过了")

try:
    restored = text.encode("cp936").decode("utf-8")
except (UnicodeEncodeError, UnicodeDecodeError) as e:
    print("[fix] 无法还原：%s" % e)
    print("[fix] 说明有字符在损坏过程中已不可逆丢失（通常是被替换成 '?'）")
    sys.exit(1)

# 校验：还原后应该能重新编码成 UTF-8，且不含典型 mojibake 特征
if "锛" in restored or "涓" in restored:
    print("[fix] 还原结果仍有乱码特征，放弃")
    sys.exit(1)

with open(path, "w", encoding="utf-8", newline="") as f:
    f.write(restored)
print("[fix] 已还原 %s（%d -> %d 字符）" % (path, len(text), len(restored)))
