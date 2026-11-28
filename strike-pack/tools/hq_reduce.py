"""Yuksek detayli (binlerce kupluk) silah modellerini eski cihazlarda calisacak hale indirger.

Kullanim:
  python3 strike-pack/tools/hq_reduce.py <hq_kaynak> <cikti> [--grid 64] [--colors 24] [--names ak47,awp]

<hq_kaynak> icinde models/item/hq/<ad>.json ve textures/item/hq/<ad>.png beklenir.
Adimlar:
  1. Her orijinal kupun yuzlerindeki doku bolgelerinden ortalama renk alinir.
  2. Model kaba bir izgaraya (grid x grid x grid, 16 birimlik kup icinde) yeniden ornekelenir:
     her hucreye dusen kup hacmi ve hacim agirlikli renk toplanir.
  3. Dolu hucreler belirlenir, kabugun ici (disaridan ulasilamayan bos hucreler) doldurulur.
  4. Renkler silah basina sinirli bir palete indirilir (median cut).
  5. Ayni renkli komsu hucreler 3B acgozlu birlestirme ile buyuk kutulara toplanir.
  6. Her silah icin 16x16 piksellik bir palet dokusu ve az elemanli bir model yazilir.
"""
import argparse, json, os
from collections import deque
import numpy as np
from PIL import Image

def face_color(img, uv):
    W, H = img.size
    x0, y0, x1, y1 = uv
    a, b = sorted((x0, x1)); c, d = sorted((y0, y1))
    px0, px1 = int(a / 16 * W), max(int(a / 16 * W) + 1, int(np.ceil(b / 16 * W)))
    py0, py1 = int(c / 16 * H), max(int(c / 16 * H) + 1, int(np.ceil(d / 16 * H)))
    reg = np.asarray(img.crop((px0, py0, min(px1, W), min(py1, H))), dtype=np.float32)
    reg = reg.reshape(-1, 4)
    reg = reg[reg[:, 3] > 10] if (reg[:, 3] > 10).any() else reg
    return reg[:, :3].mean(axis=0) if len(reg) else None

def reduce(model_path, tex_path, grid, ncolors):
    d = json.load(open(model_path))
    img = Image.open(tex_path).convert("RGBA")
    E = d.get("elements", [])
    cell = 16.0 / grid
    vol = np.zeros((grid, grid, grid), np.float32)
    col = np.zeros((grid, grid, grid, 3), np.float32)
    for e in E:
        cs = [face_color(img, f["uv"]) for f in e.get("faces", {}).values() if "uv" in f]
        cs = [c for c in cs if c is not None]
        if not cs:
            continue
        c = np.mean(cs, axis=0)
        lo = [max(0.0, min(16.0, v)) for v in e["from"]]; hi = [max(0.0, min(16.0, v)) for v in e["to"]]
        idx = []
        for ax in range(3):
            i0, i1 = int(lo[ax] / cell), min(grid - 1, int(hi[ax] / cell - 1e-9))
            ov = []
            for i in range(i0, i1 + 1):
                ov.append(max(0.0, min(hi[ax], (i + 1) * cell) - max(lo[ax], i * cell)))
            idx.append((i0, np.array(ov, np.float32)))
        (x0, ox), (y0, oy), (z0, oz) = idx
        w = ox[:, None, None] * oy[None, :, None] * oz[None, None, :]
        sl = (slice(x0, x0 + len(ox)), slice(y0, y0 + len(oy)), slice(z0, z0 + len(oz)))
        vol[sl] += w
        col[sl] += w[..., None] * c
    occ = vol >= 0.10 * cell ** 3
    color = np.where(vol[..., None] > 0, col / np.maximum(vol, 1e-9)[..., None], 0)
    # kabugun icini doldur: disaridan ulasilamayan bos hucreler
    outside = np.zeros_like(occ); q = deque()
    for x in range(grid):
        for y in range(grid):
            for z in (0, grid - 1):
                for p in ((x, y, z), (x, z, y), (z, x, y)):
                    if not occ[p] and not outside[p]:
                        outside[p] = True; q.append(p)
    while q:
        x, y, z = q.popleft()
        for dx, dy, dz in ((1,0,0),(-1,0,0),(0,1,0),(0,-1,0),(0,0,1),(0,0,-1)):
            n = (x + dx, y + dy, z + dz)
            if 0 <= n[0] < grid and 0 <= n[1] < grid and 0 <= n[2] < grid and not occ[n] and not outside[n]:
                outside[n] = True; q.append(n)
    interior = ~occ & ~outside
    solid = occ | interior
    # renk paleti
    cells = np.argwhere(occ)
    px = color[occ].clip(0, 255).astype(np.uint8)
    pal_img = Image.fromarray(px.reshape(-1, 1, 3)).quantize(colors=min(ncolors, 256), method=Image.Quantize.MEDIANCUT)
    labels = np.full((grid, grid, grid), -1, np.int32)
    labels[occ] = np.asarray(pal_img).reshape(-1)
    palette = np.array(pal_img.getpalette()[: 3 * ncolors]).reshape(-1, 3)
    # ic hucreler: komsu bir rengi al (birlesmeyi kolaylastirir, gorunmezler)
    todo = deque(map(tuple, np.argwhere(interior)))
    guard = 0
    while todo and guard < 10 * len(todo) + 10000:
        guard += 1
        p = todo.popleft()
        for dx, dy, dz in ((1,0,0),(-1,0,0),(0,1,0),(0,-1,0),(0,0,1),(0,0,-1)):
            n = (p[0] + dx, p[1] + dy, p[2] + dz)
            if 0 <= min(n) and max(n) < grid and labels[n] >= 0:
                labels[p] = labels[n]; break
        else:
            todo.append(p)
    labels[(labels < 0) & solid] = 0
    # 3B acgozlu kutu birlestirme
    done = np.zeros_like(solid)
    boxes = []
    for x in range(grid):
        for y in range(grid):
            for z in range(grid):
                if not solid[x, y, z] or done[x, y, z]:
                    continue
                L = labels[x, y, z]
                def ok(xa, xb, ya, yb, za, zb):
                    s = (slice(xa, xb), slice(ya, yb), slice(za, zb))
                    return solid[s].all() and not done[s].any() and (labels[s] == L).all()
                x1 = x + 1
                while x1 < grid and ok(x1, x1 + 1, y, y + 1, z, z + 1): x1 += 1
                y1 = y + 1
                while y1 < grid and ok(x, x1, y1, y1 + 1, z, z + 1): y1 += 1
                z1 = z + 1
                while z1 < grid and ok(x, x1, y, y1, z1, z1 + 1): z1 += 1
                done[x:x1, y:y1, z:z1] = True
                boxes.append((x, y, z, x1, y1, z1, int(L)))
    return boxes, palette, d, solid

def hidden(solid, box, face):
    """Kutunun bu yuzune bitisik butun hucreler doluysa yuz hic gorunmez."""
    g = solid.shape[0]
    x0, y0, z0, x1, y1, z1 = box
    if face == "east":  s = (slice(x1, x1 + 1), slice(y0, y1), slice(z0, z1)); edge = x1 >= g
    elif face == "west": s = (slice(x0 - 1, x0), slice(y0, y1), slice(z0, z1)); edge = x0 <= 0
    elif face == "up":   s = (slice(x0, x1), slice(y1, y1 + 1), slice(z0, z1)); edge = y1 >= g
    elif face == "down": s = (slice(x0, x1), slice(y0 - 1, y0), slice(z0, z1)); edge = y0 <= 0
    elif face == "south": s = (slice(x0, x1), slice(y0, y1), slice(z1, z1 + 1)); edge = z1 >= g
    else:                s = (slice(x0, x1), slice(y0, y1), slice(z0 - 1, z0)); edge = z0 <= 0
    return (not edge) and bool(solid[s].all())

def write(name, boxes, palette, src, grid, out, ns="tactical_arsenal", solid=None):
    cell = 16.0 / grid
    n = len(palette)
    tex = Image.new("RGB", (16, 16))
    for i, c in enumerate(palette[:256]):
        tex.putpixel((i % 16, i // 16), tuple(int(v) for v in c))
    os.makedirs(os.path.join(out, "textures", "item", "hq"), exist_ok=True)
    os.makedirs(os.path.join(out, "models", "item", "hq"), exist_ok=True)
    tex.save(os.path.join(out, "textures", "item", "hq", name + ".png"))
    els = []
    for x0, y0, z0, x1, y1, z1, L in boxes:
        u, v = L % 16, L // 16
        uv = [u + 0.25, v + 0.25, u + 0.75, v + 0.75]
        faces = {f: {"uv": uv, "texture": "#tex"} for f in ("north", "south", "east", "west", "up", "down")
                 if solid is None or not hidden(solid, (x0, y0, z0, x1, y1, z1), f)}
        if not faces:
            continue
        els.append({"from": [round(x0 * cell, 4), round(y0 * cell, 4), round(z0 * cell, 4)],
                    "to": [round(x1 * cell, 4), round(y1 * cell, 4), round(z1 * cell, 4)],
                    "faces": faces})
    t = f"{ns}:item/hq/{name}"
    model = {"textures": {"particle": t, "tex": t}, "elements": els}
    json.dump(model, open(os.path.join(out, "models", "item", "hq", name + ".json"), "w"), separators=(",", ":"))
    return len(els)

if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("src"); ap.add_argument("out")
    ap.add_argument("--grid", type=int, default=64); ap.add_argument("--colors", type=int, default=24)
    ap.add_argument("--names", default="")
    a = ap.parse_args()
    names = a.names.split(",") if a.names else sorted(f[:-5] for f in os.listdir(os.path.join(a.src, "models", "item", "hq")) if f.endswith(".json") and f != "display.json")
    total = 0
    for nm in names:
        mp = os.path.join(a.src, "models", "item", "hq", nm + ".json"); tp = os.path.join(a.src, "textures", "item", "hq", nm + ".png")
        if not (os.path.exists(mp) and os.path.exists(tp)):
            continue
        boxes, pal, src, solid = reduce(mp, tp, a.grid, a.colors)
        n = write(nm, boxes, pal, src, a.grid, a.out, solid=solid)
        total += n
        print(f"{nm}: {len(json.load(open(mp)).get('elements', []))} -> {n} kup")
    print("toplam", total)
