"""Pisan Strike 3D silah kaynak paketi ureticisi.

Kullanim:  python3 strike-pack/build_pack.py [cikti.zip]

PisanStrike plugini silahlari CROSSBOW + CustomModelData (1001...) olarak verir,
diger esyalar kendi materyalleri + CustomModelData ile gelir. Bu script her biri icin
kup (Blockbench tarzi) 3D model, tek bir renk paleti dokusu ve:
  - 1.20.1-1.21.3 istemciler icin  assets/minecraft/models/item/<esya>.json  override'lari
  - 1.21.4+ istemciler icin        overlay icinde assets/minecraft/items/<esya>.json
uretir. Modeller tamamen ozgun, sadece gercek silah tiplerinin genel hatlarini izler.
"""
import io, json, os, sys, zipfile, hashlib
from PIL import Image

NS = "pisanstrike"
GUN_ITEM = "iron_horse_armor"

# ---------------------------------------------------------------- palet (16x16, 4x4 kareler)
PALETTE = {
    "black": (28, 28, 30), "metal": (58, 60, 64), "steel": (120, 124, 130), "silver": (185, 188, 192),
    "wood": (120, 72, 36), "wood_l": (160, 104, 56), "tan": (176, 150, 104), "olive": (86, 96, 58),
    "green": (60, 82, 52), "polymer": (44, 46, 42), "orange": (220, 120, 30), "red": (180, 40, 36),
    "glass": (60, 140, 200), "yellow": (230, 200, 60), "white": (225, 225, 220), "brass": (190, 150, 60),
}
NAMES = list(PALETTE)

def palette_png():
    img = Image.new("RGBA", (16, 16))
    for i, n in enumerate(NAMES):
        x0, y0 = (i % 4) * 4, (i // 4) * 4
        r, g, b = PALETTE[n]
        for x in range(4):
            for y in range(4):
                # hafif golge: kenarlar biraz koyu, ortasi duz
                k = 0.86 if (x in (0, 3) or y in (0, 3)) else 1.0
                img.putpixel((x0 + x, y0 + y), (int(r * k), int(g * k), int(b * k), 255))
    buf = io.BytesIO(); img.save(buf, "PNG"); return buf.getvalue()

def uv(color):
    i = NAMES.index(color)
    x0, y0 = (i % 4) * 4, (i // 4) * 4
    return [x0 + 1, y0 + 1, x0 + 3, y0 + 3]

# ---------------------------------------------------------------- model kurucu
class M:
    """Silah X ekseni boyunca (+X = namlu ucu), Y yukari, Z genislik (8 merkez)."""
    def __init__(self):
        self.el = []

    def box(self, x1, y1, z1, x2, y2, z2, color, rot=None):
        f = [min(x1, x2), min(y1, y2), min(z1, z2)]; t = [max(x1, x2), max(y1, y2), max(z1, z2)]
        e = {"from": f, "to": t, "faces": {d: {"uv": uv(color), "texture": "#p"} for d in
                                            ("north", "south", "east", "west", "up", "down")}}
        if rot:  # (eksen, aci, pivot) - Minecraft sadece -45/-22.5/0/22.5/45 kabul eder
            allowed = (-45, -22.5, 0, 22.5, 45)
            ang = min(allowed, key=lambda a: abs(a - rot[1]))
            rot = (rot[0], ang, rot[2])
        if rot and rot[1] != 0:
            e["rotation"] = {"axis": rot[0], "angle": rot[1], "origin": list(rot[2])}
        self.el.append(e)
        return self

    def bar(self, x1, x2, yc, h, w, color, rot=None):
        """X boyunca uzanan, (yc) yuksekliginde ortali cubuk."""
        return self.box(x1, yc - h / 2, 8 - w / 2, x2, yc + h / 2, 8 + w / 2, color, rot)

    def tube(self, x1, x2, yc, d, color):
        """Yaklasik silindir: arti seklinde iki kutu."""
        self.box(x1, yc - d / 2, 8 - d / 4, x2, yc + d / 2, 8 + d / 4, color)
        self.box(x1, yc - d / 4, 8 - d / 2, x2, yc + d / 4, 8 + d / 2, color)
        return self

    def grip(self, x, y_top, length, w, color, angle=-22.5):
        return self.box(x - 1, y_top - length, 8 - w / 2, x + 1, y_top, 8 + w / 2, color, ("z", angle, (x, y_top, 8)))

    def mag(self, x, y_top, length, thick, w, color, angle=0):
        rot = ("z", angle, (x, y_top, 8)) if angle else None
        return self.box(x - thick / 2, y_top - length, 8 - w / 2, x + thick / 2, y_top, 8 + w / 2, color, rot)

    def scope(self, x1, x2, y, d, color="black", lens="glass"):
        self.tube(x1, x2, y, d, color)
        self.tube(x1 - 0.6, x1, y, d + 0.8, color); self.tube(x2, x2 + 0.6, y, d + 0.8, color)
        self.box(x2 + 0.6, y - d / 4, 8 - d / 4, x2 + 0.7, y + d / 4, 8 + d / 4, lens)
        mid = (x1 + x2) / 2
        self.box(mid - 2.5, y - d / 2 - 1, 7.5, mid - 1.5, y - d / 2, 8.5, color)
        self.box(mid + 1.5, y - d / 2 - 1, 7.5, mid + 2.5, y - d / 2, 8.5, color)
        return self

    def fit(self):
        """Modeli (8,8,8) merkezine ve -16..32 sinirina sigdir."""
        xs = [p for e in self.el for p in (e["from"][0], e["to"][0])]
        ys = [p for e in self.el for p in (e["from"][1], e["to"][1])]
        dx = 8 - (min(xs) + max(xs)) / 2; dy = 8 - (min(ys) + max(ys)) / 2
        for e in self.el:
            for k in ("from", "to"):
                e[k][0] = round(e[k][0] + dx, 3); e[k][1] = round(e[k][1] + dy, 3)
            if "rotation" in e:
                o = e["rotation"]["origin"]; o[0] = round(o[0] + dx, 3); o[1] = round(o[1] + dy, 3)
        span = max(max(xs) - min(xs), max(ys) - min(ys))
        for e in self.el:
            for k in ("from", "to"):
                assert all(-16 <= v <= 32 for v in e[k]), ("model sinir disi", e[k])
        return span

# ---------------------------------------------------------------- tipler
def pistol(body="black", slide="black", grip_c="polymer", slide_len=7, barrel_ext=0, suppressor=0, big=False, tec=False):
    m = M(); h = 2.2 if big else 1.8
    m.bar(0, slide_len, 10, h, 1.6 if big else 1.4, slide)                     # surgu
    m.bar(0.4, slide_len - 0.6, 8.6, 1.2, 1.3, body)                          # govde
    if barrel_ext: m.bar(slide_len, slide_len + barrel_ext, 10, 0.9, 0.9, "metal")
    if suppressor: m.tube(slide_len + barrel_ext, slide_len + barrel_ext + suppressor, 10, 1.4, "black")
    m.grip(1.6, 8.4, 4.4 if not big else 4.8, 1.3 if not big else 1.5, grip_c)  # kabza
    m.box(2.8, 7.2, 7.75, 4.6, 7.5, 8.25, body)                                # tetik korkulugu
    m.box(3.2, 7.4, 7.9, 3.5, 8.1, 8.1, "metal")                               # tetik
    m.box(slide_len - 0.6, h / 2 + 10, 7.85, slide_len - 0.2, h / 2 + 10.4, 8.15, "black")  # arpacik
    if tec:
        m.mag(4.2, 8.2, 5, 1.0, 1.0, "black")
        m.bar(-0.8, 0, 9.8, 1.0, 1.0, body)
    return m

def rifle(stock="wood", body="black", guard="wood", mag_c="black", barrel=7, length=13, curved=True,
          rail=False, carry=False, suppressor=0, scope=None, mag_len=5, folding=False):
    m = M()
    # dipcik
    if folding:
        m.bar(-5, 0, 9.4, 0.5, 0.6, body).bar(-5, 0, 8.0, 0.5, 0.6, body).box(-5.6, 7.6, 7.6, -5, 10, 8.4, body)
    else:
        m.box(-5, 6.8, 7.2, 0, 10.6, 8.8, stock, ("z", 0 if rail else -0.0, (0, 8, 8)) if False else None)
        m.box(-5.4, 6.6, 7.15, -5, 10.8, 8.85, "black")                         # dipcik pedi
    m.bar(0, 6, 9.6, 2.6, 1.8, body)                                          # gövde (receiver)
    m.bar(6, 6 + length - 6, 9.4, 2.0, 1.7, guard)                            # el korumasi
    x_end = length
    m.bar(x_end, x_end + barrel, 9.8, 0.8, 0.8, "metal")                      # namlu
    m.bar(x_end - 4, x_end - 0.2, 10.9, 0.5, 0.5, "metal")                    # gaz tupu
    m.box(x_end + barrel - 1.2, 10.2, 7.8, x_end + barrel - 0.7, 11.4, 8.2, "black")  # arpacik
    if rail: m.bar(0.2, x_end - 0.4, 11.05, 0.3, 1.2, "black")
    if carry:
        m.bar(1, 5, 12.2, 0.6, 0.8, "black"); m.box(1, 10.9, 7.7, 1.6, 12.2, 8.3, "black"); m.box(4.4, 10.9, 7.7, 5, 12.2, 8.3, "black")
    if suppressor: m.tube(x_end + barrel, x_end + barrel + suppressor, 9.8, 1.5, "black")
    if scope: m.scope(*scope)
    m.grip(1.6, 8.4, 3.8, 1.3, "black" if stock == "black" else stock if stock != "wood" else "wood")
    m.box(2.8, 7.8, 7.75, 4.8, 8.1, 8.25, "black")                             # tetik korkulugu
    m.mag(5.2, 8.4, mag_len, 1.6, 1.1, mag_c, 22.5 if curved else 0)          # one dogru kivrik          # sarjor
    return m

def bullpup(body="olive", scope=True, length=14, barrel=3, mag_c="black"):
    m = M()
    m.box(-2, 7, 7.1, 7, 11, 8.9, body)                                        # govde (dipcik+mekanizma)
    m.box(-2.4, 6.8, 7.05, -2, 11.2, 8.95, "black")
    m.bar(7, length, 9.6, 2.4, 1.7, body)
    m.bar(length, length + barrel, 9.8, 0.8, 0.8, "metal")
    m.grip(8.5, 8.2, 3.6, 1.3, body, -15)
    m.box(9.6, 7.4, 7.7, 10.8, 7.8, 8.3, body)                                # on kabza
    m.mag(3, 7.2, 4, 1.5, 1.1, mag_c, -15)                                     # kabza arkasinda sarjor
    if scope: m.scope(3, 9, 12.2, 1.8, "black")
    else: m.bar(1, 10, 11.6, 1.0, 1.0, body)
    return m

def sniper(body="green", stock="green", scope_d=2.4, barrel=12, slim=False):
    m = M()
    m.box(-6, 6.4, 7.1, 0, 10.6, 8.9, stock)                                   # dipcik
    if not slim: m.box(-4.5, 7.4, 7.0, -1, 8.6, 9.0, "black")                  # basparmak deligi
    m.bar(0, 9, 9.6, 2.6, 1.8, body)
    m.bar(9, 9 + barrel, 9.9, 1.0 if not slim else 0.8, 1.0 if not slim else 0.8, "metal")
    m.tube(9 + barrel - 1.6, 9 + barrel, 9.9, 1.4, "metal")                    # namlu freni
    m.scope(1, 8.6, 12.6, scope_d, "black")
    m.box(4, 10.8, 8.9, 4.6, 11.4, 9.6, "metal")                               # mekanizma kolu
    m.grip(1.2, 8.4, 3.6, 1.3, stock, -15)
    m.box(2.4, 7.8, 7.75, 4.2, 8.1, 8.25, "black")
    m.mag(6.2, 8.4, 2.2, 1.6, 1.2, "black")
    if not slim: m.box(12, 7, 7.4, 12.6, 9.5, 8.6, "black")                    # bipod ayak
    return m

def smg(body="black", length=8, barrel=2, stock=None, mag_len=5, mag_in_grip=False, p90=False, ump=False):
    m = M()
    if p90:
        m.box(-3, 7.6, 7.0, 9, 11, 9.0, body)
        m.box(-3.4, 7.2, 6.95, -3, 11.2, 9.05, "black")
        m.bar(0, 8.8, 11.5, 0.9, 1.6, "glass")                                 # ust sarjor
        m.box(5.5, 6.2, 7.5, 7, 7.6, 8.5, body)                                # basparmak deligi on
        m.box(1, 6.4, 7.5, 2.2, 7.6, 8.5, body)
        m.bar(9, 10.5, 9.4, 0.8, 0.8, "metal")
        return m
    m.bar(0, length, 9.6, 2.4, 1.7 if not ump else 1.9, body)
    m.bar(length, length + barrel, 9.8, 0.8, 0.8, "metal")
    m.box(length - 0.7, 10.8, 7.85, length - 0.3, 11.3, 8.15, "black")
    if stock == "fixed": m.box(-5, 7.2, 7.3, 0, 10.4, 8.7, body); m.box(-4, 8.0, 7.25, -1.5, 9.6, 8.75, "black")
    elif stock == "wire": m.bar(-4.5, 0, 10.2, 0.5, 0.6, "metal").bar(-4.5, 0, 8.4, 0.5, 0.6, "metal").box(-5, 8.0, 7.6, -4.5, 10.6, 8.4, "metal")
    if mag_in_grip:
        m.grip(2.2, 8.4, 4.6, 1.3, body, -10)
        m.mag(2.6, 4.2, 2.5, 1.0, 0.9, "black", -10)
    else:
        m.grip(1.4, 8.4, 3.4, 1.3, body)
        m.mag(4.8, 8.4, mag_len, 1.4, 1.0, "black", 22.5 if ump else 0)
    m.box(2.4, 7.8, 7.75, 4.0, 8.1, 8.25, "black")
    m.bar(0.2, length - 0.4, 11.0, 0.3, 1.0, "black")
    return m

def shotgun(body="black", stock="black", barrel=12, pump=True, mag_tube=True, sawed=False, box_mag=False):
    m = M()
    if not sawed:
        m.box(-5, 6.6, 7.2, 0, 10.4, 8.8, stock)
        m.box(-5.4, 6.4, 7.15, -5, 10.6, 8.85, "black")
    else:
        m.grip(0, 9.4, 4, 1.5, stock, -35)
    m.bar(0, 5, 9.6, 2.6, 1.8, body)
    m.bar(5, 5 + barrel, 10.1, 1.0, 1.0, "metal")
    if mag_tube: m.bar(5, 5 + barrel - 2, 8.9, 0.9, 0.9, "metal")
    if pump: m.bar(6.5, 10.5, 8.9, 1.5, 1.6, stock if stock != "black" else "polymer")
    if box_mag: m.mag(3.6, 8.4, 3, 1.6, 1.2, "black")
    if not sawed: m.grip(1.2, 8.4, 3.4, 1.3, stock if stock != "wood" else "wood")
    m.box(2.0, 7.8, 7.75, 3.6, 8.1, 8.25, "black")
    m.box(5 + barrel - 0.6, 10.6, 7.85, 5 + barrel - 0.2, 11.0, 8.15, "white")  # boncuk arpacik
    return m

def lmg(body="black", length=12, barrel=6):
    m = rifle(stock=body, body=body, guard=body, mag_c=body, barrel=barrel, length=length, curved=False, rail=True, mag_len=0.01)
    m.box(3.6, 4.2, 6.6, 7.4, 8.2, 9.4, "olive")                               # kutu sarjor
    m.box(6.6, 8.2, 9.2, 7.4, 9.4, 9.6, "brass")                               # mermi kemeri
    m.box(length + 2, 7.4, 7.0, length + 2.6, 9.4, 7.6, "black", ("x", 22.5, (length + 2.3, 9.4, 7.3)))
    m.box(length + 2, 7.4, 8.4, length + 2.6, 9.4, 9.0, "black", ("x", -22.5, (length + 2.3, 9.4, 8.7)))
    m.bar(5, 9, 11.7, 1.2, 1.2, "black")                                       # tasima kolu
    return m

def knife():
    m = M()
    m.bar(0, 4, 8, 1.4, 1.1, "black")                                          # sap
    m.bar(4, 4.4, 8, 2.4, 1.4, "metal")                                        # siper
    m.box(4.4, 7.4, 7.8, 11, 9.2, 8.2, "silver")                               # bicak
    m.box(11, 7.9, 7.8, 12.2, 9.2, 8.2, "silver", ("z", 22.5, (11, 9.2, 8)))   # uc
    m.box(4.4, 9.0, 7.85, 10.6, 9.3, 8.15, "steel")                            # sirt
    return m

def grenade(body="olive", top="metal", flat=False, bottle=False, tall=False):
    m = M()
    if bottle:
        m.box(6, 2, 6, 10, 9, 10, "glass"); m.box(7, 9, 7, 9, 12, 9, "glass")
        m.box(7.2, 12, 7.2, 8.8, 13.5, 8.8, "white"); m.box(6.2, 4, 5.9, 9.8, 7, 6.0, "orange")
        return m
    h = 9 if tall else 7
    m.box(5.5, 2, 5.5, 10.5, 2 + h, 10.5, body)
    m.box(6.5, 2 + h, 6.5, 9.5, 3.5 + h, 9.5, top)                             # fuze
    m.box(9.5, 0.5 + h, 7.5, 10.5, 3.5 + h, 8.5, top)                          # kol
    m.box(5.5, 3 + h, 7.8, 6.8, 4.2 + h, 8.2, "silver")                        # pim halkasi
    if flat: m.box(5.4, 4, 5.4, 10.6, 5, 10.6, "white")
    return m

def c4():
    m = M()
    m.box(3, 2, 4, 13, 6, 12, "tan")
    m.box(3.5, 6, 4.5, 12.5, 6.4, 11.5, "black")
    m.box(5, 6.4, 6, 11, 7.4, 10, "metal")                                     # tus takimi
    for i in range(3):
        for j in range(3):
            m.box(5.5 + i * 1.8, 7.4, 6.4 + j * 1.2, 6.6 + i * 1.8, 7.7, 7.2 + j * 1.2, "white")
    m.box(9.8, 7.4, 6.2, 10.8, 7.8, 7.2, "red")
    m.box(3, 3, 3.4, 13, 3.6, 4, "red"); m.box(3, 4.6, 3.4, 13, 5.2, 4, "yellow")  # kablolar
    return m

def zeus():
    m = pistol(body="yellow", slide="black", grip_c="black", slide_len=6)
    m.box(6, 9.2, 7.4, 6.6, 10.8, 8.6, "glass")
    return m

def defuse_kit():
    m = M()
    m.box(3, 4, 6, 13, 9, 10, "red")
    m.box(4, 9, 6.5, 12, 10, 9.5, "black")
    m.box(12, 5, 7, 15, 6, 9, "silver"); m.box(1, 5, 7, 3, 6, 9, "silver")
    return m

# ---------------------------------------------------------------- silah listesi (CustomModelData -> model)
GUNS = {
    1001: ("glock", pistol(body="polymer", slide="black", grip_c="polymer")),
    1002: ("usp", pistol(body="black", slide="black", grip_c="polymer", slide_len=7.4, suppressor=5)),
    1003: ("p250", pistol(body="black", slide="metal", grip_c="black", slide_len=6.6)),
    1005: ("fiveseven", pistol(body="tan", slide="black", grip_c="tan", slide_len=7.4)),
    1006: ("tec9", pistol(body="black", slide="metal", grip_c="black", slide_len=6, barrel_ext=3, tec=True)),
    1007: ("deagle", pistol(body="silver", slide="silver", grip_c="black", slide_len=9, big=True)),
    1011: ("mac10", smg(body="black", length=5, barrel=1.5, mag_in_grip=True, stock="wire")),
    1012: ("mp9", smg(body="black", length=6.5, barrel=1.5, mag_in_grip=True, stock="wire")),
    1013: ("mp7", smg(body="black", length=7, barrel=2, stock="wire", mag_in_grip=True)),
    1014: ("ump", smg(body="polymer", length=9, barrel=2, stock="fixed", ump=True)),
    1015: ("p90", smg(body="polymer", p90=True)),
    1021: ("galil", rifle(stock="tan", body="metal", guard="tan", mag_c="metal", barrel=6, length=13, folding=False)),
    1022: ("famas", bullpup(body="black", scope=False, length=13, barrel=3)),
    1023: ("ak47", rifle(stock="wood", body="metal", guard="wood", mag_c="orange", barrel=7, length=13)),
    1024: ("m4a4", rifle(stock="black", body="black", guard="black", mag_c="metal", barrel=7, length=13, curved=False, rail=True, carry=False)),
    1025: ("m4a1s", rifle(stock="black", body="black", guard="black", mag_c="metal", barrel=5, length=12, curved=False, rail=True, suppressor=7)),
    1026: ("sg553", rifle(stock="olive", body="olive", guard="olive", mag_c="glass", barrel=5, length=13, curved=True, scope=(1, 6, 12.2, 1.6))),
    1027: ("aug", bullpup(body="olive", scope=True, length=14, barrel=3)),
    1031: ("ssg08", sniper(body="black", stock="black", scope_d=2.0, barrel=13, slim=True)),
    1032: ("awp", sniper(body="green", stock="green", scope_d=2.6, barrel=12)),
    1041: ("nova", shotgun(body="black", stock="wood", barrel=13)),
    1042: ("xm1014", shotgun(body="black", stock="black", barrel=12, pump=False)),
    1043: ("mag7", shotgun(body="metal", stock="black", barrel=7, mag_tube=False, box_mag=True)),
    1044: ("sawedoff", shotgun(body="metal", stock="wood", barrel=6, sawed=True, pump=True)),
    1051: ("negev", lmg(body="black", length=13, barrel=6)),
    1052: ("m249", lmg(body="metal", length=12, barrel=7)),
}
OTHER = {  # materyal -> {cmd: (ad, model)}
    "iron_sword": {1061: ("knife", knife())},
    "shears": {1073: ("defuse_kit", defuse_kit())},
    "lightning_rod": {1074: ("zeus", zeus())},
    "firework_star": {1081: ("he", grenade("olive", "metal"))},
    "glowstone_dust": {1082: ("flash", grenade("steel", "black", flat=True))},
    "light_gray_dye": {1083: ("smoke", grenade("green", "metal", tall=True))},
    "fire_charge": {1084: ("molotov", grenade(bottle=True))},
    "blaze_powder": {1085: ("incendiary", grenade("red", "metal", tall=True))},
    "tnt_minecart": {1091: ("c4", c4())},
    "carved_pumpkin": {1099: ("scope_hidden", None)},
}

def dual():
    a = pistol(body="black", slide="silver", grip_c="black", slide_len=7)
    m = M()
    for e in a.el:
        for dz, in ((-2.2,), (2.2,)):
            c = json.loads(json.dumps(e)); c["from"][2] += dz; c["to"][2] += dz
            if "rotation" in c: c["rotation"]["origin"][2] += dz
            m.el.append(c)
    return m
# 1004 (Dual Berettas) oyundan kaldirildi
# durbun acikken eldeki silah gizlenir (CS2'deki gibi); plugin CustomModelData'yi gecici olarak 1098 yapar
GUNS[1098] = ("scope_hidden", None)

# ---------------------------------------------------------------- gosterim (display) ayarlari
# Elde gorunum ayarlari; Minecraft 1.20.1 istemcisinde gozle ayarlandi (bkz. strike-pack/README).
DISPLAY_OVERRIDE = json.loads(os.environ.get("PISAN_DISPLAY", "null"))

def display(kind, span, s=None, fp_small=None):
    if s is None:
        s = min(1.0, 16 / max(span, 1))
    if DISPLAY_OVERRIDE and kind in DISPLAY_OVERRIDE:
        out = {}
        for k, v in DISPLAY_OVERRIDE[kind].items():
            v = dict(v); v["scale"] = [round(v.pop("k", 1.0) * s, 4)] * 3; out[k] = v
        return out
    if kind == "gun":
        # Minecraft 1.20.1 istemcisinde (Xvfb) ekran goruntusuyle dogrulandi: namlu ileri, sarjor asagi.
        # kisa silahlar (tabanca, bicak, SMG) birinci sahista ekrani kaplamasin
        small = fp_small if fp_small is not None else (0.55 if span < 12 else (0.7 if span < 20 else 1.0))
        fp = {"translation": [-0.5 + (1 - small) * 2, 4 + (1 - small) * 2, -0.5], "scale": [round(0.8 * s * small, 4)] * 3}
        tp = {"translation": [0, 3, 1], "scale": [1.0 * s] * 3}
        return {
            "firstperson_righthand": {"rotation": [0, 96, 0], **fp},
            "firstperson_lefthand": {"rotation": [0, -96, 0], **fp},
            "thirdperson_righthand": {"rotation": [180, 0, -90], **tp},
            "thirdperson_lefthand": {"rotation": [180, 0, 90], **tp},
            "gui": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [s] * 3},
            "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.5 * s] * 3},
            "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [s] * 3},
            "head": {"rotation": [0, 0, 0], "translation": [0, 10, 0], "scale": [s] * 3},
        }
    return {
        "firstperson_righthand": {"rotation": [0, -90, 0], "translation": [1.5, 4.5, 0], "scale": [0.38 * s] * 3},
        "firstperson_lefthand": {"rotation": [0, 90, 0], "translation": [1.5, 4.5, 0], "scale": [0.38 * s] * 3},
        "thirdperson_righthand": {"rotation": [0, 0, 0], "translation": [0, 2, 1], "scale": [0.6 * s] * 3},
        "thirdperson_lefthand": {"rotation": [0, 0, 0], "translation": [0, 2, 1], "scale": [0.6 * s] * 3},
        "gui": {"rotation": [20, -30, 0], "translation": [0, 0, 0], "scale": [s] * 3},
        "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.5 * s] * 3},
        "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [s] * 3},
    }

def scope_overlay_png():
    """CS2 tarzi durbun: siyah ekran, ortada daire, ince arti cizgileri.
    Minecraft bu dokuyu (pumpkinblur) tum ekrana gerer; 16:9 ekranda daire gorunsun diye elips cizilir."""
    from PIL import ImageDraw
    W = H = 1024
    img = Image.new("RGBA", (W, H), (0, 0, 0, 255))
    d = ImageDraw.Draw(img)
    rx, ry = int(W * 9 / 16 / 2 * 0.98), int(H / 2 * 0.98)
    cx, cy = W // 2, H // 2
    d.ellipse([cx - rx, cy - ry, cx + rx, cy + ry], fill=(0, 0, 0, 0))
    # ince arti cizgileri (tum ekran boyunca)
    d.rectangle([cx - 1, 0, cx, H], fill=(0, 0, 0, 255))
    d.rectangle([0, cy - 1, W, cy + 1], fill=(0, 0, 0, 255))
    buf = io.BytesIO(); img.save(buf, "PNG"); return buf.getvalue()

CC_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "countercraft")
CC_NS = "tactical_arsenal"

def cc_available():
    return os.path.isdir(CC_DIR) and os.path.exists(os.path.join(CC_DIR, "weapon_map.json"))

def cc_model(wid, real_span=None):
    """Counter Craft silahinin govde+mekanizma+sarjor parcalarini tek modelde birlestirir."""
    cc = json.load(open(os.path.join(CC_DIR, "weapon_map.json"))).get(wid)
    if cc is None:
        return None
    elements, textures = [], {}
    for part in ("body", "action", "magazine"):
        path = os.path.join(CC_DIR, "models", "item", f"{cc}_{part}.json")
        if not os.path.exists(path):
            continue
        d = json.load(open(path))
        tex = d["textures"].get("tex")
        textures[part] = tex
        for e in d.get("elements", []):
            e = json.loads(json.dumps(e))
            for f in e.get("faces", {}).values():
                f["texture"] = "#" + part
            elements.append(e)
    if not elements:
        return None
    textures["particle"] = textures.get("body") or next(iter(textures.values()))
    xs = [v for e in elements for v in (e["from"][0], e["to"][0])]
    ys = [v for e in elements for v in (e["from"][1], e["to"][1])]
    span = max(max(xs) - min(xs), max(ys) - min(ys))
    if real_span is not None:
        # gercek silah boyutuna gore olcekle (tabanca kucuk, tufek uzun)
        sc = min(real_span, 16.0) / max(span, 1.0) * CC_SIZE.get(wid, 1.0)
        disp = display("gun", real_span, s=sc, fp_small=0.95 if real_span < 12 else (0.85 if real_span < 20 else 1.0))
    else:
        disp = display("gun", span)
    return {"credit": "Counter Craft (Tactical Arsenal)", "textures": textures, "elements": elements, "display": disp}

# Yuksek detayli (HQ) modeller: tek dokulu, binlerce kupluk, hepsi 16 birim uzunluga olcekli.
HQ_DIR = os.environ.get("PISAN_HQ_DIR") or os.path.join(os.path.dirname(os.path.abspath(__file__)), "countercraft-hq")

def hq_available():
    return os.path.isdir(os.path.join(HQ_DIR, "models"))

def hq_model(wid, real_span):
    """HQ modeli bizim silahin gercek boyutuna gore olcekler (tabanca kucuk, tufek uzun)."""
    if wid in HQ_SKIP:
        return None
    cc = "knife" if wid == "knife" else json.load(open(os.path.join(CC_DIR, "weapon_map.json"))).get(wid)
    path = os.path.join(HQ_DIR, "models", "item", "hq", f"{cc}.json") if cc else None
    if not path or not os.path.exists(path):
        return None
    d = json.load(open(path))
    if not d.get("elements"):
        return None
    # bizim modelde silah 16/span olcekle cizilir ve uzunlugu min(span,16) birim gorunur; HQ model 16 birim
    s = min(real_span, 16.0) / 16.0 * HQ_SIZE.get(wid, 1.0)
    # HQ tabancalar 1. sahista fazla kucuk kaliyordu (oyunda goruntuyle ayarlandi)
    fp_small = 0.95 if real_span < 12 else (0.85 if real_span < 20 else 1.0)
    return {"credit": "Counter Craft HQ", "textures": d["textures"], "elements": d["elements"],
            "display": display("gun", real_span, s=s, fp_small=fp_small)}

HQ_SIZE = {}
CC_SIZE = {}
HQ_SKIP = set()

def hq_files():
    out = {}
    root = os.path.join(HQ_DIR, "textures")
    for dp, _, fs in os.walk(root):
        for fn in fs:
            full = os.path.join(dp, fn)
            out[f"assets/{CC_NS}/" + os.path.relpath(full, HQ_DIR).replace(os.sep, "/")] = open(full, "rb").read()
    return out

def cc_files():
    """Counter Craft dokulari, sesleri ve sounds.json (paket yolu -> bayt)."""
    out = {}
    for sub in ("textures", "sounds"):
        root = os.path.join(CC_DIR, sub)
        for dp, _, fs in os.walk(root):
            for fn in fs:
                full = os.path.join(dp, fn)
                rel = os.path.relpath(full, CC_DIR).replace(os.sep, "/")
                out[f"assets/{CC_NS}/{rel}"] = open(full, "rb").read()
    out[f"assets/{CC_NS}/sounds.json"] = open(os.path.join(CC_DIR, "sounds.json"), "rb").read()
    return out

def M_span(m):
    xs = [p for e in m.el for p in (e["from"][0], e["to"][0])]
    ys = [p for e in m.el for p in (e["from"][1], e["to"][1])]
    return max(max(xs) - min(xs), max(ys) - min(ys))

# 26.3 istemci "max_format": 999 olan paketi gecersiz sayip hic yuklemiyordu
PACK_MAX = int(os.environ.get("PISAN_PACK_MAX", "120"))

RELOAD_OFFSET = 500   # plugin sarjor degistirirken CustomModelData'yi bu kadar arttirir

def reload_display(d, tilt=25):
    """Sarjor degistirme pozu: 1. sahista silah asagi iner, namlu yukari kalkar (26.3'te goruntuyle secildi)."""
    out = json.loads(json.dumps(d))
    for k, sign in (("firstperson_righthand", 1), ("firstperson_lefthand", -1)):
        if k in out:
            r = list(out[k].get("rotation", [0, 0, 0])); t = list(out[k].get("translation", [0, 0, 0]))
            out[k]["rotation"] = [r[0], r[1], sign * tilt]
            out[k]["translation"] = [t[0], t[1] - 2.5, t[2]]
    return out

# Modeli dikey (Y ekseninde) olan ve dokusu bozulmasin diye dondurulmeyen esyalar: el acilarina
# model ekseninde (ilk uygulanan z) bu kadar derece eklenir. Bicagin ucu modelde -Y yonunde.
ZOFF = {"knife": 90}

def apply_zoff(d, zoff, fp_zoff=None):
    """fp_zoff: 1. sahis icin ayri deger (1.20.1'de 1. sahis 26.x'e gore ters donuyor)."""
    if not zoff:
        return d
    out = json.loads(json.dumps(d))
    for k, v in out.items():
        if isinstance(v, dict) and "rotation" in v:
            add = fp_zoff if (fp_zoff is not None and k.startswith("firstperson")) else zoff
            r = list(v["rotation"]); r[2] = r[2] + add; v["rotation"] = r
    return out

def gui_full(mj, m):
    """Envanter/menu ikonu: modelin en uzun kenari slotu (16 px) doldurur, isik onden gelir."""
    out = dict(mj)
    xs = [v for e in mj["elements"] for v in (e["from"][0], e["to"][0])] if mj.get("elements") else [0, 16]
    ys = [v for e in mj["elements"] for v in (e["from"][1], e["to"][1])] if mj.get("elements") else [0, 16]
    span = max(max(xs) - min(xs), max(ys) - min(ys), 1.0)
    sc = round(min(1.6, 15.0 / span), 4)
    disp = json.loads(json.dumps(out["display"]))
    cx = (max(xs) + min(xs)) / 2 - 8; cy = (max(ys) + min(ys)) / 2 - 8
    gui = dict(disp.get("gui", {})); gui["scale"] = [sc] * 3
    gui["translation"] = [round(-cx * sc, 3), round(-cy * sc, 3), 0]
    disp["gui"] = gui
    out["display"] = disp
    out["gui_light"] = "front"
    return out

def new_display(d):
    """1.21.4+ istemciler icin el tutma acilari (26.3'te goruntuyle dogrulandi).
    Sadece el gorunumleri doner; envanter (gui) vb. ust modelden miras kalir."""
    out = {k: v for k, v in json.loads(json.dumps(d)).items() if k.startswith(("firstperson", "thirdperson"))}
    rot = {"firstperson_righthand": [0, -84, 0], "firstperson_lefthand": [0, 84, 0],
           "thirdperson_righthand": [0, 0, -90], "thirdperson_lefthand": [0, 0, 90]}
    for k, r in rot.items():
        if k in out:
            out[k]["rotation"] = r
    return out

# ------------------------------------------------------------------ ozel alim menusu (CS2 tarzi)
# Sandik menusu basligina ozel yazi tipiyle tam boy bir arka plan cizilir (mod gerektirmez).
MENU_ROWS = [((214, 140, 60), "pistol"), ((86, 160, 222), "smg"), ((222, 92, 70), "rifle"),
             ((150, 110, 210), "heavy"), ((96, 190, 120), "gear")]

def _picto(d, kind, x, y, c):
    """14x14 kucuk kategori simgesi."""
    if kind == "pistol":
        d.rectangle([x + 2, y + 4, x + 12, y + 6], fill=c); d.rectangle([x + 3, y + 7, x + 5, y + 11], fill=c)
    elif kind == "smg":
        d.rectangle([x + 1, y + 4, x + 13, y + 6], fill=c); d.rectangle([x + 4, y + 7, x + 5, y + 11], fill=c); d.rectangle([x + 8, y + 7, x + 9, y + 12], fill=c)
    elif kind == "rifle":
        d.rectangle([x, y + 5, x + 13, y + 6], fill=c); d.rectangle([x, y + 4, x + 3, y + 8], fill=c); d.rectangle([x + 6, y + 7, x + 7, y + 11], fill=c); d.rectangle([x + 9, y + 7, x + 10, y + 10], fill=c)
    elif kind == "heavy":
        d.rectangle([x, y + 5, x + 13, y + 7], fill=c); d.rectangle([x + 4, y + 3, x + 9, y + 4], fill=c); d.rectangle([x + 5, y + 8, x + 8, y + 12], fill=c)
    else:
        d.ellipse([x + 3, y + 4, x + 10, y + 12], fill=c); d.rectangle([x + 5, y + 1, x + 8, y + 4], fill=c)

def buy_menu_png():
    from PIL import ImageDraw
    W, H = 176, 222
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # ust panel (sandigin 6 satiri) - alt kisim (oyuncu envanteri) seffaf kalir, oyunun kendi dokusu gorunur
    d.rounded_rectangle([0, 0, W - 1, 126], radius=4, fill=(24, 27, 32, 255), outline=(10, 11, 13, 255))
    d.rectangle([1, 1, W - 2, 15], fill=(16, 18, 22, 255))
    d.rectangle([1, 15, W - 2, 16], fill=(230, 140, 40, 255))
    for r in range(6):
        y = 17 + r * 18
        for c in range(9):
            x = 7 + c * 18
            if r < len(MENU_ROWS) and c == 0:
                col, kind = MENU_ROWS[r]
                d.rectangle([x, y, x + 17, y + 17], fill=tuple(int(v * 0.35) for v in col) + (255,), outline=col + (255,))
                _picto(d, kind, x + 2, y + 2, col + (255,))
            elif r == 5:
                d.rectangle([x, y, x + 17, y + 17], fill=(30, 34, 40, 255), outline=(44, 50, 58, 255))
            else:
                col = MENU_ROWS[r][0] if r < len(MENU_ROWS) else (60, 60, 60)
                d.rectangle([x, y, x + 17, y + 17], fill=(36, 41, 48, 255), outline=(52, 59, 68, 255))
                d.line([x + 1, y + 16, x + 16, y + 16], fill=tuple(int(v * 0.55) for v in col) + (255,))
    buf = io.BytesIO(); img.save(buf, "PNG"); return buf.getvalue()

def menu_font_json():
    return {"providers": [
        {"type": "space", "advances": {"\uf801": -1, "\uf808": -8, "\uf8b1": -177}},
        {"type": "bitmap", "file": f"{NS}:font/buy_menu.png", "ascent": 13, "height": 222, "chars": ["\ue000"]},
    ]}

def model_json(m, kind):
    span = m.fit()
    return {"credit": "Pisan Strike", "texture_size": [16, 16],
            "textures": {"p": f"{NS}:item/palette", "particle": f"{NS}:item/palette"},
            "elements": m.el, "display": display(kind, span)}

# ---------------------------------------------------------------- vanilla 1.20.1 item modelleri (override tabani)
VANILLA = {
    "crossbow": {"parent": "item/generated", "textures": {"layer0": "item/crossbow_standby"},
                 "display": {"thirdperson_righthand": {"rotation": [-90, 0, -60], "translation": [2, 0.1, -3], "scale": [0.9, 0.9, 0.9]},
                             "thirdperson_lefthand": {"rotation": [-90, 0, 30], "translation": [2, 0.1, -3], "scale": [0.9, 0.9, 0.9]},
                             "firstperson_righthand": {"rotation": [-90, 0, -55], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]},
                             "firstperson_lefthand": {"rotation": [-90, 0, 35], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]}},
                 "overrides": [{"predicate": {"pulling": 1}, "model": "item/crossbow_pulling_0"},
                               {"predicate": {"pulling": 1, "pull": 0.58}, "model": "item/crossbow_pulling_1"},
                               {"predicate": {"pulling": 1, "pull": 1.0}, "model": "item/crossbow_pulling_2"},
                               {"predicate": {"charged": 1}, "model": "item/crossbow_arrow"},
                               {"predicate": {"charged": 1, "firework": 1}, "model": "item/crossbow_firework"}]},
    "iron_horse_armor": {"parent": "item/generated", "textures": {"layer0": "item/iron_horse_armor"}},
    "iron_sword": {"parent": "item/handheld", "textures": {"layer0": "item/iron_sword"}},
    "shears": {"parent": "item/generated", "textures": {"layer0": "item/shears"}},
    "lightning_rod": {"parent": "block/lightning_rod"},
    "firework_star": {"parent": "item/generated", "textures": {"layer0": "item/firework_star", "layer1": "item/firework_star_overlay"}},
    "glowstone_dust": {"parent": "item/generated", "textures": {"layer0": "item/glowstone_dust"}},
    "light_gray_dye": {"parent": "item/generated", "textures": {"layer0": "item/light_gray_dye"}},
    "fire_charge": {"parent": "item/generated", "textures": {"layer0": "item/fire_charge"}},
    "blaze_powder": {"parent": "item/generated", "textures": {"layer0": "item/blaze_powder"}},
    "tnt_minecart": {"parent": "item/generated", "textures": {"layer0": "item/tnt_minecart"}},
    "carved_pumpkin": {"parent": "block/carved_pumpkin"},
}

def build(out):
    files = {}
    files[f"assets/{NS}/textures/item/palette.png"] = palette_png()
    files["assets/minecraft/textures/misc/pumpkinblur.png"] = scope_overlay_png()
    files[f"assets/{NS}/textures/font/buy_menu.png"] = buy_menu_png()
    files[f"assets/{NS}/font/menu.json"] = json.dumps(menu_font_json(), indent=1).encode()
    # durbun acikken basa takilan balkabagi baskalarina gorunmesin: bos model
    files[f"assets/{NS}/models/item/scope_hidden.json"] = json.dumps({"textures": {"particle": f"{NS}:item/palette"}, "elements": []}).encode()
    # silahlar demir at zirhi uzerinde: arbalet gibi "germe" animasyonu olmadigi icin istemci
    # sag tik basiliyken duzenli sinyal yollar (arbalette tek atislik silahlar takiliyordu)
    groups = {GUN_ITEM: {cmd: v for cmd, v in GUNS.items()}}
    groups.update(OTHER)
    for item, entries in groups.items():
        base = json.loads(json.dumps(VANILLA[item]))
        base.setdefault("overrides", [])
        cases = []
        reload_cases = []
        for cmd in sorted(entries):
            name, m = entries[cmd]
            kind = "gun" if item in (GUN_ITEM, "iron_sword", "lightning_rod") else "thing"
            ccm = None
            if item in (GUN_ITEM, "iron_sword") and hq_available() and m is not None:
                ccm = hq_model(name, M_span(m))
            if ccm is None and item == GUN_ITEM and cc_available():
                ccm = cc_model(name, M_span(m) if (hq_available() and m is not None) else None)
            mj = ccm if ccm is not None else (model_json(m, kind) if m is not None else None)
            zoff = ZOFF.get(name, 0) if ccm is not None else 0
            orig_display = mj.get("display") if mj is not None else None
            if mj is not None and zoff:
                # 1.20.1: 1. sahis 26.x'e gore ters doner (ikisi de oyunda goruntuyle dogrulandi)
                mj = dict(mj); mj["display"] = apply_zoff(orig_display, zoff, fp_zoff=-zoff)
            if mj is not None and kind == "gun" and "display" in mj:
                mj = gui_full(mj, m)
            if mj is not None:
                files[f"assets/{NS}/models/item/{name}.json"] = json.dumps(mj, separators=(",", ":")).encode()
            base["overrides"].append({"predicate": {"custom_model_data": cmd}, "model": f"{NS}:item/{name}"})
            new_model = f"{NS}:item/{name}"
            reload_models = None
            if item == GUN_ITEM and mj is not None and "display" in mj:
                # sarjor degistirme pozu (eski ve yeni istemci icin ayri acilar)
                files[f"assets/{NS}/models/item/r/{name}.json"] = json.dumps(
                    {"parent": f"{NS}:item/{name}", "display": reload_display(mj["display"], tilt=25)}, separators=(",", ":")).encode()
                files[f"assets/{NS}/models/item/rn/{name}.json"] = json.dumps(
                    {"parent": f"{NS}:item/{name}", "display": reload_display(new_display(mj["display"]), tilt=-25)}, separators=(",", ":")).encode()
                reload_models = (cmd + RELOAD_OFFSET, f"{NS}:item/r/{name}", f"{NS}:item/rn/{name}")
            if kind == "gun" and mj is not None and "display" in mj:
                # 1.21.4+ / 26.x istemciler elde tutma acisini 180 derece ters uyguluyor (26.3'te ekran
                # goruntusuyle dogrulandi): ayni modeli sadece acilari degisen bir alt modelle kullan
                files[f"assets/{NS}/models/item/n/{name}.json"] = json.dumps(
                    {"parent": f"{NS}:item/{name}", "display": apply_zoff(new_display(orig_display), zoff)}, separators=(",", ":")).encode()
                new_model = f"{NS}:item/n/{name}"
            cases.append((cmd, new_model))
            if reload_models:
                reload_cases.append(reload_models)
        for rcmd, old_m, new_m in sorted(reload_cases):
            base["overrides"].append({"predicate": {"custom_model_data": rcmd}, "model": old_m})
            cases.append((rcmd, new_m))
        cases.sort()
        files[f"assets/minecraft/models/item/{item}.json"] = json.dumps(base, indent=1).encode()
        # 1.21.4+ : items/<item>.json. Eski istemciler "items" klasorunu tanimaz, o yuzden dogrudan ana
        # bolume konur (overlay gerekmez; 26.3 overlay/999 bicimindeki paketi tamamen reddediyordu).
        fallback = {"type": "minecraft:model", "model": f"minecraft:item/{item}"}
        entries_new = [{"threshold": cmd, "model": {"type": "minecraft:model", "model": mdl}} for cmd, mdl in cases]
        files[f"assets/minecraft/items/{item}.json"] = json.dumps(
            {"model": {"type": "minecraft:range_dispatch", "property": "minecraft:custom_model_data", "index": 0,
                       "fallback": fallback, "entries": entries_new}}, indent=1).encode()
    if cc_available():
        files.update(cc_files())
    if hq_available():
        files.update(hq_files())
    files["pack.mcmeta"] = json.dumps({
        "pack": {"pack_format": 15, "supported_formats": [15, PACK_MAX], "min_format": 15, "max_format": PACK_MAX,
                 "description": "§6§lPISAN STRIKE §f3D silahlar"}}, indent=1).encode()
    files["pack.png"] = open(os.path.join(os.path.dirname(__file__), "pack.png"), "rb").read() if os.path.exists(
        os.path.join(os.path.dirname(__file__), "pack.png")) else palette_png()
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for p in sorted(files):
            zi = zipfile.ZipInfo(p, date_time=(2026, 1, 1, 0, 0, 0)); zi.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(zi, files[p])
    data = buf.getvalue()
    open(out, "wb").write(data)
    print(out, len(data), "bayt  sha1", hashlib.sha1(data).hexdigest())
    return files

if __name__ == "__main__":
    build(sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "PisanStrike-pack.zip"))
