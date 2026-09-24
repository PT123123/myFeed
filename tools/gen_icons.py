"""生成 FeedReader 的 launcher 图标资源。

产物：
  res/drawable/ic_launcher_foreground.xml   自适应图标前景（vector）
  res/drawable/ic_launcher_background.xml   自适应图标背景（vector 渐变）
  res/mipmap-anydpi-v26/ic_launcher.xml     API 26+ 自适应图标
  res/mipmap-anydpi-v26/ic_launcher_round.xml
  res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher(_round).png   API 21-25 兜底

图形：绿色渐变底 + 白色 RSS 弧线。
"""

import math
import os
from PIL import Image, ImageDraw

RES = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "res",
)

C0 = (76, 175, 80)    # 绿 (#4CAF50)
C1 = (27, 94, 32)     # 深绿 (#1B5E20)
SS = 8                # 超采样倍数

# 108 空间下的 RSS 图形参数
# 比例参照 Material rss_feed（24px 网格）：圆点 r≈2.2/24，弧半径 ≈6/24 与 ≈12/24，笔画 ≈2.2/24
G_DOT = (34.0, 76.0)
G_DOT_R = 6.5
G_ARCS = (26.0, 49.0)
G_STROKE = 8.5


def gradient_square(side):
    """对角线线性渐变，64x64 生成后放大，够用且快。"""
    small = Image.new("RGB", (64, 64))
    px = small.load()
    for y in range(64):
        for x in range(64):
            t = (x + y) / 126.0
            px[x, y] = tuple(
                int(round(C0[i] + (C1[i] - C0[i]) * t)) for i in range(3)
            )
    return small.resize((side, side), Image.BILINEAR).convert("RGBA")


def rss_glyph(side):
    """在 side x side 透明画布上画白色 RSS 图形，返回图像。"""
    img = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    k = side / 108.0
    ox, oy = G_DOT[0] * k, G_DOT[1] * k
    stroke = G_STROKE * k
    w = max(2, int(round(stroke)))
    white = (255, 255, 255, 255)

    for r in G_ARCS:
        r *= k
        d.arc([ox - r, oy - r, ox + r, oy + r], 270, 360, fill=white, width=w)

    dr = G_DOT_R * k
    d.ellipse([ox - dr, oy - dr, ox + dr, oy + dr], fill=white)
    return img


def fit_into(glyph, box):
    """把 glyph 按比例缩放到 box 内并居中，返回 (图像, 粘贴位置)。"""
    bbox = glyph.getbbox()
    if bbox is None:
        return glyph, (0, 0)
    cropped = glyph.crop(bbox)
    cw, ch = cropped.size
    scale = min(box / cw, box / ch)
    nw, nh = max(1, int(round(cw * scale))), max(1, int(round(ch * scale)))
    return cropped.resize((nw, nh), Image.LANCZOS), (nw, nh)


def make_icon(size, round_shape):
    side = size * SS
    base = Image.new("RGBA", (side, side), (0, 0, 0, 0))

    # 背景形状遮罩
    mask = Image.new("L", (side, side), 0)
    md = ImageDraw.Draw(mask)
    if round_shape:
        md.ellipse([0, 0, side - 1, side - 1], fill=255)
    else:
        md.rounded_rectangle(
            [0, 0, side - 1, side - 1], radius=int(side * 0.19), fill=255
        )

    base.paste(gradient_square(side), (0, 0), mask)

    # 前景：圆形版留更多留白
    ratio = 0.50 if round_shape else 0.60
    glyph = rss_glyph(side)
    scaled, (nw, nh) = fit_into(glyph, int(side * ratio))
    layer = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    layer.paste(scaled, ((side - nw) // 2, (side - nh) // 2), scaled)

    # 前景同样裁到形状内
    base = Image.alpha_composite(
        base, Image.composite(layer, Image.new("RGBA", (side, side), (0, 0, 0, 0)), mask)
    )
    return base.resize((size, size), Image.LANCZOS)


DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

for name, px in DENSITIES.items():
    out = os.path.join(RES, f"mipmap-{name}")
    os.makedirs(out, exist_ok=True)
    make_icon(px, False).save(os.path.join(out, "ic_launcher.png"))
    make_icon(px, True).save(os.path.join(out, "ic_launcher_round.png"))
    print(f"mipmap-{name}: {px}x{px} x2")

# 顺手导出一张预览图，方便肉眼确认（不参与打包）
preview = Image.new("RGBA", (1100, 160), (245, 245, 247, 255))
x = 20
for name, px in DENSITIES.items():
    for round_shape in (False, True):
        ic = make_icon(120, round_shape)
        preview.paste(ic, (x, 20), ic)
        x += 108
preview.save(os.path.join(os.path.dirname(os.path.abspath(__file__)), "icon_preview.png"))
print("legacy preview -> tools/icon_preview.png")

# ---------- 自适应图标预览：按启动器真实遮罩裁一遍，确认前景没被裁到 ----------

ADAPTIVE_SCALE = 0.74          # 与 ic_launcher_foreground.xml 里的缩放一致
ADAPTIVE_ORIGIN = (57.4, 52.6)  # 108 空间里 RSS 图形的视觉中心
SAFE_RADIUS_DP = 33.0           # 保证可见的直径 66dp -> 半径 33dp
MASK_INSET_DP = 18.0            # 遮罩是居中的 72x72dp


def glyph_extent_dp():
    """图形相对画布中心 (54,54) 的最大外接半径，单位 dp（108 空间即 dp）。"""
    s = ADAPTIVE_SCALE
    cx, cy = ADAPTIVE_ORIGIN
    ox = (G_DOT[0] - cx) * s + 54.0
    oy = (G_DOT[1] - cy) * s + 54.0

    half = G_STROKE * s / 2.0
    dot_r = G_DOT_R * s
    outer = max(G_ARCS) * s

    x_min = min(ox - half, ox - dot_r)
    x_max = max(ox + outer + half, ox + dot_r)
    y_min = min(oy - outer - half, oy - dot_r)
    y_max = max(oy + half, oy + dot_r)

    corners = [(x_min, y_min), (x_max, y_min), (x_min, y_max), (x_max, y_max)]
    return max(math.hypot(px - 54.0, py - 54.0) for px, py in corners), (
        (x_min + x_max) / 2.0,
        (y_min + y_max) / 2.0,
    )


def adaptive_foreground(side):
    """按 vector 里的坐标渲染前景。"""
    img = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    k = side / 108.0
    s = ADAPTIVE_SCALE
    cx, cy = ADAPTIVE_ORIGIN

    ox = ((G_DOT[0] - cx) * s + 54.0) * k
    oy = ((G_DOT[1] - cy) * s + 54.0) * k
    width = max(2, int(round(G_STROKE * s * k)))
    white = (255, 255, 255, 255)

    for r in G_ARCS:
        rr = r * s * k
        d.arc([ox - rr, oy - rr, ox + rr, oy + rr], 270, 360, fill=white, width=width)

    dr = G_DOT_R * s * k
    d.ellipse([ox - dr, oy - dr, ox + dr, oy + dr], fill=white)
    return img


def adaptive_preview():
    side = 360
    k = side / 108.0
    lo = MASK_INSET_DP * k
    hi = side - lo

    bg = gradient_square(side)
    fg = adaptive_foreground(side)
    blank = Image.new("RGBA", (side, side), (0, 0, 0, 0))

    shapes = {
        "squircle": lambda d: d.rounded_rectangle([lo, lo, hi, hi], radius=20 * k, fill=255),
        "circle": lambda d: d.ellipse([lo, lo, hi, hi], fill=255),
    }

    out = Image.new("RGB", (side * 2 + 90, side + 50), (245, 245, 247))
    x = 30
    for name, draw_mask in shapes.items():
        mask = Image.new("L", (side, side), 0)
        draw_mask(ImageDraw.Draw(mask))

        layer = Image.new("RGBA", (side, side), (0, 0, 0, 0))
        layer.paste(bg, (0, 0), mask)
        layer = Image.alpha_composite(layer, Image.composite(fg, blank, mask))

        # 安全区参考圈：直径 66dp
        guide = ImageDraw.Draw(layer)
        c = side / 2.0
        r = SAFE_RADIUS_DP * k
        guide.ellipse([c - r, c - r, c + r, c + r], outline=(255, 87, 34, 255), width=2)

        out.paste(layer, (x, 25), layer)
        x += side + 30

    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "adaptive_preview.png")
    out.save(path)
    return path


radius, center = glyph_extent_dp()
print(f"前景外接半径 = {radius:.2f}dp，安全半径 = {SAFE_RADIUS_DP}dp -> "
      f"{'PASS' if radius <= SAFE_RADIUS_DP else 'FAIL 会被裁掉'}")
print(f"图形中心 = ({center[0]:.2f}, {center[1]:.2f})，画布中心 = (54, 54)")
print(f"adaptive preview -> {adaptive_preview()}")
