# Bakes star catalog + Milky Way map from d3-celestial (BSD-3, (c) Olaf Frohn) into mod resources.
import json, struct, sys, math
import numpy as np
from PIL import Image, ImageDraw, ImageFilter
src, out = sys.argv[1], sys.argv[2]
stars = json.load(open(f"{src}/stars.8.json"))["features"]
rows = []
for f in stars:
    ra, dec = f["geometry"]["coordinates"]
    mag = float(f["properties"]["mag"])
    try: bv = float(f["properties"]["bv"])
    except: bv = 0.6
    ra = math.radians(ra % 360.0); dec = math.radians(dec)
    rows.append((math.cos(dec)*math.cos(ra), math.cos(dec)*math.sin(ra), math.sin(dec), mag, bv))
rows.sort(key=lambda r: r[3])
with open(f"{out}/stars.bin", "wb") as fh:
    fh.write(struct.pack(">i", len(rows)))
    for r in rows: fh.write(struct.pack(">5f", *r))
print("stars", len(rows))
# Milky Way: 5 nested brightness levels. Rasterized in GALACTIC equirect coords (u = l/360, v = (90-b)/180)
# so the band lies along the middle row. Rings that wrap all the way around in longitude are closed via the
# north galactic pole; with even-odd filling, the region between two such rings comes out right.
EQ2GAL = np.array([[-0.0548755604, -0.8734370902, -0.4838350155],
                   [ 0.4941094279, -0.4448296300,  0.7469822445],
                   [-0.8676661490, -0.1980763734,  0.4559837762]])
def to_gal(ra, dec):
    ra, dec = math.radians(ra), math.radians(dec)
    v = EQ2GAL @ np.array([math.cos(dec)*math.cos(ra), math.cos(dec)*math.sin(ra), math.sin(dec)])
    return math.degrees(math.atan2(v[1], v[0])) % 360.0, math.degrees(math.asin(max(-1, min(1, v[2]))))
W, H = 2048, 1024
acc = np.zeros((H, W), np.float32)
mw = json.load(open(f"{src}/milkyway.json"))["features"]
for feat in mw:
    mask = np.zeros((H, W), bool)
    g = feat["geometry"]
    polys = g["coordinates"] if g["type"] == "MultiPolygon" else [g["coordinates"]]
    for poly in polys:
        for ring in poly:
            pts = []; prev = None; off = 0.0
            for ra, dec in ring:
                l, b = to_gal(ra, dec)
                if prev is not None:
                    dl = l - prev
                    if dl > 180: off -= 360
                    elif dl < -180: off += 360
                prev = l
                pts.append((l + off, b))
            span = pts[-1][0] - pts[0][0]
            if abs(span) > 180:  # wraps around: close via north pole
                pts = pts + [(pts[-1][0], 90.0), (pts[0][0], 90.0)]
            img = Image.new("1", (W, H), 0); d = ImageDraw.Draw(img)
            for shift in (-720, -360, 0, 360, 720):
                xy = [((l + shift) / 360.0 * W, (90 - b) / 180.0 * H) for l, b in pts]
                d.polygon(xy, fill=1)
            mask ^= np.asarray(img, bool)
    acc += mask
acc /= len(mw)
im = Image.fromarray((np.clip(acc, 0, 1) * 255).astype(np.uint8))
im = im.filter(ImageFilter.GaussianBlur(2.5))
im.save(f"{out}/milkyway.png", optimize=True)
print("mw", len(mw), float(acc.mean()))
