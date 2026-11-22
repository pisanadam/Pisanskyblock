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
    1004: ("dualies", None),
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
GUNS[1004] = ("dualies", dual())

# ---------------------------------------------------------------- gosterim (display) ayarlari
def display(kind, span):
    s = min(1.0, 16 / max(span, 1))
    if kind == "gun":
        return {
            "firstperson_righthand": {"rotation": [0, -90, 0], "translation": [0, 2.5, 1.5], "scale": [0.9 * s * 1.3] * 3},
            "firstperson_lefthand": {"rotation": [0, 90, 0], "translation": [0, 2.5, 1.5], "scale": [0.9 * s * 1.3] * 3},
            "thirdperson_righthand": {"rotation": [0, -90, 90], "translation": [0, 3, 1], "scale": [0.85 * s * 1.2] * 3},
            "thirdperson_lefthand": {"rotation": [0, 90, -90], "translation": [0, 3, 1], "scale": [0.85 * s * 1.2] * 3},
            "gui": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [s] * 3},
            "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.5 * s] * 3},
            "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [s] * 3},
            "head": {"rotation": [0, 0, 0], "translation": [0, 10, 0], "scale": [s] * 3},
        }
    return {
        "firstperson_righthand": {"rotation": [0, -90, 0], "translation": [0, 3, 1], "scale": [0.9 * s] * 3},
        "firstperson_lefthand": {"rotation": [0, 90, 0], "translation": [0, 3, 1], "scale": [0.9 * s] * 3},
        "thirdperson_righthand": {"rotation": [0, -90, 90], "translation": [0, 3, 1], "scale": [0.8 * s] * 3},
        "thirdperson_lefthand": {"rotation": [0, 90, -90], "translation": [0, 3, 1], "scale": [0.8 * s] * 3},
        "gui": {"rotation": [20, -30, 0], "translation": [0, 0, 0], "scale": [s] * 3},
        "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.5 * s] * 3},
        "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [s] * 3},
    }

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
    "iron_sword": {"parent": "item/handheld", "textures": {"layer0": "item/iron_sword"}},
    "shears": {"parent": "item/generated", "textures": {"layer0": "item/shears"}},
    "lightning_rod": {"parent": "block/lightning_rod"},
    "firework_star": {"parent": "item/generated", "textures": {"layer0": "item/firework_star", "layer1": "item/firework_star_overlay"}},
    "glowstone_dust": {"parent": "item/generated", "textures": {"layer0": "item/glowstone_dust"}},
    "light_gray_dye": {"parent": "item/generated", "textures": {"layer0": "item/light_gray_dye"}},
    "fire_charge": {"parent": "item/generated", "textures": {"layer0": "item/fire_charge"}},
    "blaze_powder": {"parent": "item/generated", "textures": {"layer0": "item/blaze_powder"}},
    "tnt_minecart": {"parent": "item/generated", "textures": {"layer0": "item/tnt_minecart"}},
}

def build(out):
    files = {}
    files[f"assets/{NS}/textures/item/palette.png"] = palette_png()
    groups = {"crossbow": {cmd: v for cmd, v in GUNS.items()}}
    groups.update(OTHER)
    for item, entries in groups.items():
        base = json.loads(json.dumps(VANILLA[item]))
        base.setdefault("overrides", [])
        cases = []
        for cmd in sorted(entries):
            name, m = entries[cmd]
            kind = "gun" if item == "crossbow" else "thing"
            files[f"assets/{NS}/models/item/{name}.json"] = json.dumps(model_json(m, kind), separators=(",", ":")).encode()
            base["overrides"].append({"predicate": {"custom_model_data": cmd}, "model": f"{NS}:item/{name}"})
            cases.append((cmd, f"{NS}:item/{name}"))
        files[f"assets/minecraft/models/item/{item}.json"] = json.dumps(base, indent=1).encode()
        # 1.21.4+ : items/<item>.json (overlay)
        fallback = {"type": "minecraft:model", "model": f"minecraft:item/{item}"}
        if item == "crossbow":
            fallback = {"type": "minecraft:model", "model": "minecraft:item/crossbow"}
        entries_new = [{"threshold": cmd, "model": {"type": "minecraft:model", "model": mdl}} for cmd, mdl in cases]
        files[f"ov_1214/assets/minecraft/items/{item}.json"] = json.dumps(
            {"model": {"type": "minecraft:range_dispatch", "property": "minecraft:custom_model_data", "index": 0,
                       "fallback": fallback, "entries": entries_new}}, indent=1).encode()
        # 1.21.4+ istemcide vanilla crossbow modeli override'siz olmali: ayni yolda sade modeli overlay'e koy
        files[f"ov_1214/assets/minecraft/models/item/{item}.json"] = json.dumps(
            {k: v for k, v in VANILLA[item].items() if k != "overrides"}, indent=1).encode()
    files["pack.mcmeta"] = json.dumps({
        "pack": {"pack_format": 15, "supported_formats": [15, 999], "min_format": 15, "max_format": 999,
                 "description": "§6§lPISAN STRIKE §f3D silahlar"},
        "overlays": {"entries": [{"formats": [46, 999], "min_format": 46, "max_format": 999, "directory": "ov_1214"}]}},
        indent=1).encode()
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
