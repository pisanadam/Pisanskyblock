"""Pisan sunuculari icin 64x64 piksel-art server-icon.png uretir.
Kullanim: python3 tools/make_icons.py <cikti_klasoru>"""
import math, random, sys, os
from PIL import Image, ImageDraw

GOLD, GOLD_D = (240, 180, 40), (170, 110, 20)

def frame(img):
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, 63, 63], outline=GOLD_D)
    d.rectangle([1, 1, 62, 62], outline=GOLD)
    return img

def vgrad(top, bot):
    img = Image.new("RGB", (64, 64))
    for y in range(64):
        t = y / 63
        c = tuple(int(top[i] * (1 - t) + bot[i] * t) for i in range(3))
        for x in range(64):
            img.putpixel((x, y), c)
    return img

def stars(img, n, seed, area=(2, 2, 61, 40)):
    r = random.Random(seed)
    for _ in range(n):
        x, y = r.randint(area[0], area[2]), r.randint(area[1], area[3])
        img.putpixel((x, y), (255, 255, 255) if r.random() < .6 else (200, 180, 255))

def games():
    img = vgrad((40, 10, 70), (120, 50, 160))
    stars(img, 40, 1)
    d = ImageDraw.Draw(img)
    # platform
    d.ellipse([6, 40, 57, 54], fill=(60, 40, 60))
    d.ellipse([6, 37, 57, 51], fill=(235, 230, 240))
    d.ellipse([10, 39, 53, 49], outline=(170, 110, 220))
    d.rectangle([29, 42, 34, 45], fill=(180, 120, 255))
    # 3 portal kemeri
    for cx, col, glow in [(16, (200, 40, 40), (255, 120, 120)), (32, (60, 190, 60), (150, 255, 150)), (48, (50, 150, 230), (150, 210, 255))]:
        top = 22 if cx == 32 else 25
        d.rectangle([cx - 6, top, cx + 6, 42], fill=col)
        d.rectangle([cx - 4, top + 2, cx + 4, 42], fill=(25, 15, 35))
        d.rectangle([cx - 3, top + 3, cx + 3, 41], fill=glow)
        d.rectangle([cx - 2, top + 5, cx + 2, 40], fill=(255, 255, 255) if cx == 32 else glow)
    # yildiz/parilti
    for x, y in [(32, 10), (10, 14), (54, 12)]:
        d.point([(x, y - 2), (x, y - 1), (x, y), (x, y + 1), (x, y + 2), (x - 2, y), (x - 1, y), (x + 1, y), (x + 2, y)], fill=(255, 220, 90))
    return frame(img)

def sword(d, x0, y0, x1, y1, blade, edge):
    n = max(abs(x1 - x0), abs(y1 - y0))
    for i in range(n + 1):
        x = round(x0 + (x1 - x0) * i / n); y = round(y0 + (y1 - y0) * i / n)
        if i < n * 0.68:
            d.rectangle([x - 1, y - 1, x + 1, y + 1], fill=blade); d.point((x, y), fill=edge)
        elif i < n * 0.74:
            pass
        else:
            d.rectangle([x - 1, y - 1, x, y], fill=(110, 70, 30))
    return n

def pvp():
    img = vgrad((120, 10, 10), (40, 0, 0))
    d = ImageDraw.Draw(img)
    for r_, c in [(26, (160, 30, 30)), (20, (190, 50, 40)), (13, (220, 90, 50))]:
        d.ellipse([32 - r_, 32 - r_, 32 + r_, 32 + r_], fill=c)
    blade, edge = (90, 230, 220), (220, 255, 255)
    sword(d, 50, 10, 12, 52, blade, edge)
    sword(d, 13, 10, 51, 52, blade, edge)
    # kabzalar (guard)
    for gx, gy in [(23, 39), (40, 39)]:
        d.line([gx - 4, gy - 4, gx + 4, gy + 4] if gx < 32 else [gx + 4, gy - 4, gx - 4, gy + 4], fill=(240, 200, 60), width=2)
    return frame(img)

def sf():
    img = Image.new("RGB", (64, 64), (60, 150, 60))
    d = ImageDraw.Draw(img)
    for i in range(0, 64, 8):
        d.rectangle([i, 0, i + 3, 63], fill=(70, 165, 70))
    w = (235, 245, 235)
    d.rectangle([5, 5, 58, 58], outline=w)
    d.line([31, 5, 31, 58], fill=w)
    d.ellipse([22, 22, 40, 40], outline=w)
    d.rectangle([5, 22, 11, 41], outline=w); d.rectangle([52, 22, 58, 41], outline=w)
    # futbol topu
    d.ellipse([12, 30, 32, 50], fill=(250, 250, 250), outline=(30, 30, 30))
    d.polygon([(22, 36), (26, 39), (25, 44), (19, 44), (18, 39)], fill=(30, 30, 30))
    for p in [(14, 37), (29, 37), (17, 47), (27, 47), (22, 31)]:
        d.rectangle([p[0] - 1, p[1] - 1, p[0] + 1, p[1] + 1], fill=(30, 30, 30))
    # basketbol topu
    d.ellipse([33, 12, 53, 32], fill=(235, 120, 30), outline=(90, 40, 10))
    d.line([43, 12, 43, 32], fill=(90, 40, 10)); d.line([33, 22, 53, 22], fill=(90, 40, 10))
    d.arc([29, 12, 41, 32], -60, 60, fill=(90, 40, 10)); d.arc([45, 12, 57, 32], 120, 240, fill=(90, 40, 10))
    return frame(img)

def strike():
    img = vgrad((50, 45, 40), (15, 15, 15))
    d = ImageDraw.Draw(img)
    for y in range(44, 62, 3):
        d.line([2, y, 61, y], fill=(70, 60, 45))
    o = (255, 150, 30)
    d.ellipse([12, 12, 51, 51], outline=o, width=3)
    d.ellipse([22, 22, 41, 41], outline=(255, 200, 120))
    for a, b, c_, e in [(31, 4, 32, 18), (31, 45, 32, 59), (4, 31, 18, 32), (45, 31, 59, 32)]:
        d.rectangle([a, b, c_, e], fill=(255, 255, 255))
    d.rectangle([30, 30, 33, 33], fill=(230, 40, 40))
    return frame(img)

if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "."
    os.makedirs(out, exist_ok=True)
    for name, fn in [("pisangames", games), ("pisanpvp", pvp), ("sfleague", sf), ("pisanstrike", strike)]:
        fn().save(os.path.join(out, name + ".png"), optimize=True)
