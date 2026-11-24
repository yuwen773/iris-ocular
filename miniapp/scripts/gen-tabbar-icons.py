#!/usr/bin/env python
"""生成 tabBar 图标（81×81 PNG，描边 + 实心两态）。

用法（在 miniapp/ 下）：`python scripts/gen-tabbar-icons.py`，产物写进
`src/assets/tabbar/*.png`，由 `config/index.ts` 的 copy 规则原样拷进 dist。

为什么是一套同色图标：
「微信的 theme.json 能覆盖的是颜色变量（导航栏 / 窗口 / tabBar 的文字与底色），
图标路径不在其中」。所以一枚 PNG 要同时待在浅色白底（#ffffff）和深色面板
（#17263d）上。选 #66799A：对白底 3.1:1、对 #17263d 3.4:1，两边都过 3:1 的
非文本对比线。**状态靠实心/描边区分，不靠色相**——文字色随主题翻转，
图标不跟着翻，颜色在这里不承担主题信息。

画法：24 格网格按 12 倍画到 288，再 LANCZOS 缩到 81（等于 3 倍超采样），
圆角用弧线上的点近似，整条路径一次 `line(joint='curve')` 画完，接头不留缝。
需要 Pillow。
"""
import math
import os

from PIL import Image, ImageDraw

U = 12                  # 24 格网格 → 288 画布
S = 24 * U
OUT = 81                # 微信 tabBar 图标规格
STROKE = int(1.5 * U)   # 1.5 格
COLOR = (0x66, 0x79, 0x9A)
TARGET_DIR = os.path.join('src', 'assets', 'tabbar')


def canvas():
    im = Image.new('L', (S, S), 0)
    return im, ImageDraw.Draw(im)


def p(v):
    return v * U


def stroke_line(d, pts, w=STROKE):
    """折线 + 圆头（PIL 的 line 端点不封口）。"""
    d.line([(p(x), p(y)) for x, y in pts], fill=255, width=w, joint='curve')
    r = w / 2
    for x, y in (pts[0], pts[-1]):
        d.ellipse([p(x) - r, p(y) - r, p(x) + r, p(y) + r], fill=255)


def arc_pts(cx, cy, r, a0, a1, n=24):
    return [
        (cx + r * math.cos(math.radians(a0 + (a1 - a0) * i / n)),
         cy + r * math.sin(math.radians(a0 + (a1 - a0) * i / n)))
        for i in range(n + 1)
    ]


def scan_normal():
    """判读：取景框四角 + 镜头。"""
    im, d = canvas()
    for pts in (
        [(10, 3.2), (3.2, 3.2), (3.2, 10)],
        [(14, 3.2), (20.8, 3.2), (20.8, 10)],
        [(20.8, 14), (20.8, 20.8), (14, 20.8)],
        [(10, 20.8), (3.2, 20.8), (3.2, 14)],
    ):
        stroke_line(d, pts)
    r = 4.1 * U
    d.ellipse([p(12) - r, p(12) - r, p(12) + r, p(12) + r], outline=255, width=STROKE)
    return im


def scan_selected():
    """判读（选中）：角标加重 + 镜头填实。"""
    im, d = canvas()
    for pts in (
        [(10, 3.2), (3.2, 3.2), (3.2, 10)],
        [(14, 3.2), (20.8, 3.2), (20.8, 10)],
        [(20.8, 14), (20.8, 20.8), (14, 20.8)],
        [(10, 20.8), (3.2, 20.8), (3.2, 14)],
    ):
        stroke_line(d, pts, w=int(2.1 * U))
    r = 4.6 * U
    d.ellipse([p(12) - r, p(12) - r, p(12) + r, p(12) + r], fill=255)
    return im


def record_normal():
    """记录：三条台账行（点 + 线）。"""
    im, d = canvas()
    for y in (6.4, 12.0, 17.6):
        d.ellipse([p(2.6) - p(1.15), p(y) - p(1.15), p(2.6) + p(1.15), p(y) + p(1.15)], fill=255)
        stroke_line(d, [(6.6, y), (20.4, y)])
    return im


def record_selected():
    """记录（选中）：三条行填实。"""
    im, d = canvas()
    for y in (6.4, 12.0, 17.6):
        d.rounded_rectangle(
            [p(2.6) - p(1.15), p(y) - p(1.15), p(2.6) + p(1.15), p(y) + p(1.15)],
            radius=p(1.15), fill=255,
        )
        d.rounded_rectangle(
            [p(6.6), p(y) - p(1.15), p(20.6), p(y) + p(1.15)],
            radius=p(1.15), fill=255,
        )
    return im


def chat_normal():
    """咨询：气泡轮廓。尾巴是轮廓的一部分，尾巴里面是空的。"""
    im, d = canvas()
    r = 3.2
    L, T, R, B = 3.6, 4.6, 20.4, 16.4
    path = [(L + r, T), (R - r, T)]
    path += arc_pts(R - r, T + r, r, -90, 0)
    path += [(R, B - r)]
    path += arc_pts(R - r, B - r, r, 0, 90)
    path += [(12.0, B), (7.6, 21.4), (8.4, B), (L + r, B)]
    path += arc_pts(L + r, B - r, r, 90, 180)
    path += [(L, T + r)]
    path += arc_pts(L + r, T + r, r, 180, 270)
    path += [(L + r, T)]
    d.line([(p(x), p(y)) for x, y in path], fill=255, width=STROKE, joint='curve')
    return im


def chat_selected():
    """咨询（选中）：气泡与尾巴填实。"""
    im, d = canvas()
    d.rounded_rectangle([p(3.6), p(4.6), p(20.4), p(16.4)], radius=p(3.2), fill=255)
    d.polygon([(p(7.9), p(15.2)), (p(7.9), p(20.6)), (p(11.3), p(15.2))], fill=255)
    return im


def me(selected=False):
    """我的：头 + 肩。描边态用圆弧，底边不封口；选中态填实。"""
    im, d = canvas()
    hx, hy, hr = 12.0, 7.9, 3.6
    shoulders = [p(4.2), p(13.6), p(19.8), p(26.0)]
    if selected:
        d.ellipse([p(hx) - p(hr), p(hy) - p(hr), p(hx) + p(hr), p(hy) + p(hr)], fill=255)
        d.chord(shoulders, 180, 360, fill=255)
    else:
        d.ellipse([p(hx) - p(hr), p(hy) - p(hr), p(hx) + p(hr), p(hy) + p(hr)], outline=255, width=STROKE)
        d.arc(shoulders, 180, 360, fill=255, width=STROKE)
    return im


def save(mask, name):
    small = mask.resize((OUT, OUT), Image.LANCZOS)
    rgba = Image.new('RGBA', (OUT, OUT), COLOR + (0,))
    rgba.putalpha(small)
    rgba.save(os.path.join(TARGET_DIR, name))


def main():
    os.makedirs(TARGET_DIR, exist_ok=True)
    pairs = [
        ('scan', scan_normal(), scan_selected()),
        ('record', record_normal(), record_selected()),
        ('chat', chat_normal(), chat_selected()),
        ('me', me(False), me(True)),
    ]
    for name, normal, selected in pairs:
        save(normal, f'{name}.png')
        save(selected, f'{name}-on.png')
    print(f'[gen-tabbar-icons] {len(pairs) * 2} 枚图标 → {TARGET_DIR}')


if __name__ == '__main__':
    main()
