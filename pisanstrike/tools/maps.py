"""Pisan Strike harita tasarimlari: dogrulama + onizleme + Java kodu uretimi.
Kullanim: python3 tools/maps.py <onizleme.png>   (Java kodunu stdout'a yazar)"""
import sys
from collections import deque

def r(a, b, c, d): return (a, b, c, d)

class Map:
    def __init__(self, id, name, desc, icon, mats, w=31, h=31):
        self.id, self.name, self.desc, self.icon, self.mats, self.w, self.h = id, name, desc, icon, mats, w, h
        self.open_ = [[False] * h for _ in range(w)]; self.solid = [[0] * h for _ in range(w)]
        self.calls = []; self.siteA = self.siteB = self.tZone = self.ctZone = None
    def open(self, a, b, c, d):
        self.calls.append(f".open({a}, {b}, {c}, {d})")
        for x in range(a, c + 1):
            for y in range(b, d + 1): self.open_[x][y] = True
        return self
    def close(self, a, b, c, d):
        self.calls.append(f".close({a}, {b}, {c}, {d})")
        for x in range(a, c + 1):
            for y in range(b, d + 1): self.open_[x][y] = False
        return self
    def crate(self, x, y):
        self.calls.append(f".crate({x}, {y})"); self.solid[x][y] = 2; return self
    def container(self, a, b, c, d, i):
        self.calls.append(f".container({a}, {b}, {c}, {d}, {i})")
        for x in range(a, c + 1):
            for y in range(b, d + 1): self.solid[x][y] = 3
        return self
    def sites(self, A, B):
        self.siteA, self.siteB = A, B
        self.calls.append(f".sites(r{A}, r{B})".replace("r(", "r(")); self.open(*A); self.open(*B); self.calls.pop(); self.calls.pop(); return self
    def spawns(self, T, CT):
        self.tZone, self.ctZone = T, CT
        self.calls.append(f".spawns(r{T}, r{CT})"); self.open(*T); self.open(*CT); self.calls.pop(); self.calls.pop(); return self

    # --- dogrulama
    def walk(self, x, y): return 0 <= x < self.w and 0 <= y < self.h and self.open_[x][y] and self.solid[x][y] == 0
    def bfs(self, start):
        dist = {start: 0}; q = deque([start])
        while q:
            x, y = q.popleft()
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                n = (x + dx, y + dy)
                if n not in dist and self.walk(*n): dist[n] = dist[(x, y)] + 1; q.append(n)
        return dist
    def center(self, z): return ((z[0] + z[2]) // 2, (z[1] + z[3]) // 2)
    def free_in(self, z): return [(x, y) for x in range(z[0], z[2] + 1) for y in range(z[1], z[3] + 1) if self.walk(x, y)]
    def check(self):
        assert self.open_[0] == [False] * self.h or True
        for x in range(self.w):
            for y in (0, self.h - 1):
                assert not self.open_[x][y], (self.id, "kenar acik", x, y)
        for y in range(self.h):
            for x in (0, self.w - 1):
                assert not self.open_[x][y], (self.id, "kenar acik", x, y)
        t = self.free_in(self.tZone)[0]; ct = self.free_in(self.ctZone)[0]
        d = self.bfs(t)
        cells = [(x, y) for x in range(self.w) for y in range(self.h) if self.walk(x, y)]
        unreach = [c for c in cells if c not in d]
        assert not unreach, (self.id, "ulasilamayan hucre", unreach[:5])
        assert ct in d
        out = {"T->CT": d[ct]}
        dct = self.bfs(ct)
        for nm, s in (("A", self.siteA), ("B", self.siteB)):
            if s:
                tgt = min(self.free_in(s), key=lambda c: d[c]); out[f"T->{nm}"] = d[tgt]
                tgt2 = min(self.free_in(s), key=lambda c: dct[c]); out[f"CT->{nm}"] = dct[tgt2]
                assert len(self.free_in(s)) >= 12, (self.id, nm, "bolge cok dolu")
        assert len(self.free_in(self.tZone)) >= 5 and len(self.free_in(self.ctZone)) >= 5
        return out

    def java(self):
        mats = ", ".join("Material." + m for m in self.mats)
        lines = [f'      var0 = new StrikeMap(\n         "{self.id}",\n         "{self.name}",\n         "{self.desc}",\n'
                 f'         Material.{self.icon},\n         {self.w},\n         {self.h},\n         false,\n         {mats}\n      );']
        body = "var0" + "\n         ".join(self.calls).replace("r(", "r(")
        lines.append("      " + body + ";")
        lines.append("      ALL.put(var0.id, var0);")
        return "\n".join(lines)

M = []

# ----------------------------------------------------------- FABRIKA: sanayi tesisi
m = Map("fabrika", "Fabrika", "Makine hollerinden geçen orta koridor, uzun A boru hattı ve B depo tüneli.",
        "IRON_BLOCK", ["SMOOTH_STONE", "GRAY_CONCRETE", "IRON_BLOCK", "BARREL", "YELLOW_TERRACOTTA", "STONE",
                       "CYAN_TERRACOTTA", "LIGHT_GRAY_TERRACOTTA", "BROWN_TERRACOTTA"])
m.sites(r(21, 1, 29, 8), r(1, 1, 9, 8)).spawns(r(12, 26, 18, 29), r(12, 1, 18, 3))
(m.open(19, 27, 28, 28)          # T -> uzun A
  .open(26, 9, 28, 26)           # uzun A boru hatti
  .open(2, 27, 11, 28)           # T -> B
  .open(2, 9, 4, 26)             # B tuneli
  .open(14, 4, 16, 25)           # orta
  .open(12, 4, 13, 7)            # CT -> B (dirsekli gecit)
  .open(10, 6, 11, 7)
  .open(19, 2, 20, 3)            # CT -> A
  .open(17, 9, 20, 10)           # kisa A (orta -> A)
  .open(17, 15, 25, 16)          # orta -> uzun A baglantisi
  .open(5, 18, 13, 19)           # B orta gecidi
  .open(6, 12, 8, 17)            # B arka depo
  .close(15, 12, 15, 13))        # orta kolon
(m.container(23, 3, 24, 4, 0).container(26, 6, 27, 6, 1).crate(22, 7).crate(28, 2)
  .container(3, 3, 4, 4, 2).container(6, 6, 7, 6, 0).crate(8, 2).crate(2, 7)
  .crate(14, 18).crate(16, 22).crate(27, 18).crate(3, 20).crate(9, 28).crate(21, 28).crate(7, 14))
M.append(m)

# ----------------------------------------------------------- KAR USSU: CT ussu kuzeydogu, T guneybatidan uzun yol
m = Map("kar", "Kar Üssü", "Kutuptaki askeri üs. Uzun batı koridoru, güney çevre yolu, buzlu orta.",
        "SNOW_BLOCK", ["SNOW_BLOCK", "POLISHED_ANDESITE", "PACKED_ICE", "SPRUCE_PLANKS", "BLUE_ICE", "WHITE_CONCRETE",
                       "LIGHT_BLUE_CONCRETE", "WHITE_TERRACOTTA", "GRAY_CONCRETE"])
m.sites(r(9, 1, 16, 7), r(22, 10, 29, 17)).spawns(r(1, 25, 6, 29), r(24, 1, 29, 4))
(m.open(17, 2, 23, 3)            # CT -> A
  .open(26, 5, 28, 9)            # CT -> B
  .open(2, 6, 4, 24)             # bati koridoru (T -> A)
  .open(2, 3, 8, 5)              # kuzeybati oda -> A
  .open(7, 27, 25, 28)           # guney cevre yolu (T -> B)
  .open(23, 18, 25, 26)          # guney -> B
  .open(5, 19, 21, 20)           # orta yatay
  .open(12, 8, 14, 18)           # orta dikey -> A
  .open(15, 13, 21, 14)          # orta -> B baglantisi
  .open(17, 22, 21, 26)          # guney hangar
  .close(13, 15, 13, 16))        # orta kolon
(m.container(11, 2, 12, 3, 0).container(14, 5, 15, 5, 1).crate(16, 2).crate(9, 6)
  .container(24, 12, 25, 13, 2).container(27, 15, 28, 15, 0).crate(23, 16).crate(28, 11)
  .crate(3, 14).crate(19, 24).crate(10, 28).crate(24, 21).crate(18, 19).crate(13, 10).crate(6, 4))
M.append(m)

# ----------------------------------------------------------- TAPINAK: cember yol + avlu
m = Map("tapinak", "Tapınak", "Orman tapınağı. Ortada avlu, iki yanda bomba bölgeleri, çevrede yosunlu patikalar.",
        "MOSSY_COBBLESTONE", ["MOSS_BLOCK", "MOSSY_STONE_BRICKS", "CHISELED_STONE_BRICKS", "JUNGLE_PLANKS",
                              "CRACKED_STONE_BRICKS", "COARSE_DIRT", "JUNGLE_LOG", "MOSSY_COBBLESTONE"])
m.sites(r(23, 8, 29, 15), r(1, 8, 7, 15)).spawns(r(12, 26, 18, 29), r(12, 1, 18, 3))
(m.open(4, 27, 11, 28)           # T -> sol patika
  .open(4, 16, 5, 26)            # sol patika -> B
  .open(4, 4, 12, 5)             # CT -> sol ust
  .open(4, 6, 5, 7)              # sol ust -> B
  .open(19, 24, 26, 25)          # T -> sag patika
  .open(25, 16, 26, 23)          # sag patika -> A
  .open(18, 4, 24, 5)            # CT -> sag ust
  .open(23, 6, 24, 7)            # sag ust -> A
  .open(19, 26, 19, 26)          # T spawn cikisi
  .open(11, 10, 19, 20)          # avlu
  .open(14, 4, 16, 9)            # CT -> avlu
  .open(14, 21, 16, 25)          # T -> avlu
  .open(8, 14, 10, 16)           # avlu -> B pencere
  .open(20, 15, 22, 16)          # avlu -> A pencere
  .close(13, 13, 17, 17)         # avlu ortasinda tapinak
  .open(15, 13, 15, 17))         # tapinaktan gecen dar yol
(m.container(25, 10, 26, 11, 0).crate(28, 9).crate(24, 14).crate(28, 14)
  .container(3, 12, 4, 13, 1).crate(2, 9).crate(6, 14).crate(6, 9)
  .crate(12, 11).crate(18, 19).crate(12, 19).crate(18, 11).crate(5, 24).crate(25, 21).crate(8, 5).crate(22, 4))
M.append(m)

if __name__ == "__main__":
    for mp in M:
        print("//", mp.id, mp.check(), file=sys.stderr)
    if len(sys.argv) > 1:
        from PIL import Image, ImageDraw
        S = 9; img = Image.new("RGB", (len(M) * (31 * S + 20), 31 * S + 30), (240, 240, 240)); d = ImageDraw.Draw(img)
        for i, mp in enumerate(M):
            ox = i * (31 * S + 20) + 10
            for x in range(mp.w):
                for y in range(mp.h):
                    c = (60, 60, 60)
                    if mp.open_[x][y]:
                        c = (225, 215, 190)
                        for z, col in ((mp.siteA, (240, 120, 120)), (mp.siteB, (120, 160, 240)), (mp.tZone, (230, 180, 60)), (mp.ctZone, (90, 170, 220))):
                            if z and z[0] <= x <= z[2] and z[1] <= y <= z[3]: c = col
                        if mp.solid[x][y] == 2: c = (140, 90, 40)
                        if mp.solid[x][y] == 3: c = (70, 120, 90)
                    d.rectangle([ox + x * S, 20 + y * S, ox + x * S + S - 1, 20 + y * S + S - 1], fill=c)
            d.text((ox, 4), f"{mp.name}  (A=kirmizi B=mavi T=sari CT=acik mavi)", fill=(0, 0, 0))
        img.save(sys.argv[1])
    print("\n".join(mp.java() for mp in M))
