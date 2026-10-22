#!/usr/bin/env python3
"""imgfix - precise, scriptable retouching of (AI-generated) images.

Built so that Claude can edit images without driving Photoshop through
screenshots: every edit is a command with exact pixel coordinates, every step
can be previewed as an image, and the whole edit is a reproducible recipe.

Quick start:
    python imgfix.py setup                      # check deps, download LaMa model
    python imgfix.py info  img.png              # size, metadata, AI tags, noise
    python imgfix.py grid  img.png -o g.png     # coordinate grid to look at
    python imgfix.py zoom  img.png 800,600,200,150 -o z.png
    python imgfix.py sel   img.png "rect=820,610,90,40 grow=4" -o s.png
    python imgfix.py apply img.png out.png -e "inpaint rect=820,610,90,40" \\
                                           -e "adjust saturation=10"
    python imgfix.py compare img.png out.png -o cmp.png

Run `python imgfix.py ops` for the list of edit operations and their keys.
"""
from __future__ import annotations

import argparse
import json
import math
import os
import shlex
import sys
import urllib.request
from pathlib import Path

import numpy as np
from PIL import Image, ImageColor, ImageDraw, ImageFont, ImageOps

try:
    import cv2
except ImportError:  # pragma: no cover - checked in setup
    cv2 = None

VERSION = "1.0.0"
LAMA_URL = ("https://media.githubusercontent.com/media/opencv/opencv_zoo/main/"
            "models/inpainting_lama/inpainting_lama_2025jan.onnx")
LAMA_SIZE = 512
CACHE_DIR = Path(os.environ.get("IMGFIX_HOME", Path.home() / ".cache" / "imgfix"))
LAMA_PATH = CACHE_DIR / "lama.onnx"


class ImgfixError(Exception):
    pass


def need_cv2():
    if cv2 is None:
        raise ImgfixError("OpenCV missing: pip install opencv-python-headless")


# --------------------------------------------------------------------------
# Document: pixels + the metadata we want to keep when saving
# --------------------------------------------------------------------------

class Doc:
    def __init__(self, rgb: np.ndarray, alpha: np.ndarray | None = None,
                 info: dict | None = None, fmt: str | None = None):
        self.rgb = np.ascontiguousarray(rgb, dtype=np.uint8)
        self.alpha = alpha
        self.info = info or {}
        self.fmt = fmt

    @property
    def h(self):
        return self.rgb.shape[0]

    @property
    def w(self):
        return self.rgb.shape[1]

    def copy(self):
        return Doc(self.rgb.copy(), None if self.alpha is None else self.alpha.copy(),
                   dict(self.info), self.fmt)

    def pil(self) -> Image.Image:
        if self.alpha is not None:
            return Image.fromarray(np.dstack([self.rgb, self.alpha]), "RGBA")
        return Image.fromarray(self.rgb, "RGB")


def load(path) -> Doc:
    im = Image.open(path)
    fmt = im.format
    info = {"icc_profile": im.info.get("icc_profile"), "exif": im.info.get("exif"),
            "dpi": im.info.get("dpi"), "text": {k: v for k, v in im.info.items()
                                               if isinstance(v, str)}}
    im = ImageOps.exif_transpose(im)
    if im.mode in ("RGBA", "LA", "PA") or (im.mode == "P" and "transparency" in im.info):
        im = im.convert("RGBA")
        a = np.asarray(im)
        alpha = a[..., 3].copy()
        if alpha.min() == 255:
            alpha = None
        return Doc(a[..., :3], alpha, info, fmt)
    return Doc(np.asarray(im.convert("RGB")), None, info, fmt)


def save(doc: Doc, path, quality: int = 95, strip: bool = False, keep_text: bool = True):
    path = Path(path)
    ext = path.suffix.lower()
    im = doc.pil()
    kw = {}
    if not strip:
        if doc.info.get("icc_profile"):
            kw["icc_profile"] = doc.info["icc_profile"]
        if doc.info.get("exif") and ext in (".jpg", ".jpeg", ".webp", ".png", ".tif", ".tiff"):
            kw["exif"] = doc.info["exif"]
        if doc.info.get("dpi"):
            kw["dpi"] = doc.info["dpi"]
    if ext in (".jpg", ".jpeg"):
        if im.mode == "RGBA":
            bg = Image.new("RGB", im.size, (255, 255, 255))
            bg.paste(im, mask=im.split()[3])
            im = bg
        kw.update(quality=quality, subsampling=0 if quality >= 90 else 2, optimize=True)
    elif ext == ".webp":
        kw.update(quality=quality, method=6)
    elif ext == ".png":
        if keep_text and not strip and doc.info.get("text"):
            from PIL.PngImagePlugin import PngInfo
            pi = PngInfo()
            for k, v in doc.info["text"].items():
                pi.add_text(k, v)
            kw["pnginfo"] = pi
        kw["optimize"] = False
    path.parent.mkdir(parents=True, exist_ok=True)
    im.save(path, **kw)


# --------------------------------------------------------------------------
# Parameter parsing ("op key=value key=value ...")
# --------------------------------------------------------------------------

class Params:
    """Op parameters. Keys may repeat (e.g. two rect= -> union)."""

    def __init__(self, op: str, kv: dict[str, list[str]], pos: list[str], w: int, h: int):
        self.op, self.kv, self.pos, self.w, self.h = op, kv, pos, w, h
        self.used = set()

    def has(self, k):
        return k in self.kv

    def all(self, k) -> list[str]:
        self.used.add(k)
        return self.kv.get(k, [])

    def str(self, k, default=None):
        v = self.all(k)
        return v[-1] if v else default

    def num(self, k, default=None):
        v = self.str(k)
        if v is None:
            return default
        try:
            return float(v)
        except ValueError:
            raise ImgfixError(f"{self.op}: {k}={v} is not a number")

    def int(self, k, default=None):
        v = self.num(k)
        return default if v is None else int(round(v))

    def bool(self, k, default=False):
        v = self.str(k)
        if v is None:
            return default
        return v.lower() in ("1", "true", "yes", "on", "y")

    def coord(self, tok: str, axis: int) -> float:
        tok = tok.strip()
        size = self.w if axis == 0 else self.h
        if tok.endswith("%"):
            return float(tok[:-1]) / 100.0 * size
        v = float(tok)
        return v

    def nums(self, s: str, n: int | None = None, coords=True) -> list[float]:
        parts = [p for p in s.replace(";", ",").replace(" ", ",").split(",") if p]
        vals = [self.coord(p, i % 2) if coords else float(p) for i, p in enumerate(parts)]
        if n is not None and len(vals) != n:
            raise ImgfixError(f"{self.op}: expected {n} numbers, got '{s}'")
        return vals

    def rect(self, k="rect"):
        v = self.str(k)
        if v is None:
            return None
        x, y, w, h = self.nums(v, 4)
        return clamp_rect(x, y, w, h, self.w, self.h)

    def point(self, k):
        v = self.str(k)
        return None if v is None else tuple(self.nums(v, 2))

    def color(self, k, default=None):
        v = self.str(k)
        return default if v is None else parse_color(v)

    def check_unused(self):
        unknown = set(self.kv) - self.used
        if unknown:
            raise ImgfixError(f"{self.op}: unknown key(s) {sorted(unknown)} "
                              f"(see `imgfix.py ops`)")


def parse_color(s: str):
    s = s.strip()
    if "," in s:
        vals = [int(float(v)) for v in s.split(",")]
        return tuple(vals[:3])
    if not s.startswith("#") and all(c in "0123456789abcdefABCDEF" for c in s) and len(s) in (3, 6):
        s = "#" + s
    return ImageColor.getrgb(s)[:3]


def clamp_rect(x, y, w, h, W, H):
    x0, y0 = max(0, int(round(x))), max(0, int(round(y)))
    x1, y1 = min(W, int(round(x + w))), min(H, int(round(y + h)))
    if x1 <= x0 or y1 <= y0:
        raise ImgfixError(f"rect {x},{y},{w},{h} is outside the {W}x{H} image")
    return x0, y0, x1 - x0, y1 - y0


def parse_op(text: str) -> tuple[str, dict, list]:
    toks = shlex.split(text, comments=False, posix=True)
    if not toks:
        raise ImgfixError("empty op")
    name, kv, pos = toks[0].lower(), {}, []
    for t in toks[1:]:
        if "=" in t and not t.startswith("="):
            k, v = t.split("=", 1)
            kv.setdefault(k.lower(), []).append(v)
        else:
            pos.append(t)
    return name, kv, pos


def parse_op_json(d: dict) -> tuple[str, dict, list]:
    d = dict(d)
    name = d.pop("op").lower()
    pos = d.pop("_", [])
    if isinstance(pos, str):
        pos = [pos]
    kv = {}
    for k, v in d.items():
        # a list of lists = repeated key (e.g. several rects -> union)
        vals = v if isinstance(v, list) and v and isinstance(v[0], (list, tuple)) else [v]
        kv[k.lower()] = [",".join(map(str, x)) if isinstance(x, (list, tuple)) else str(x)
                         for x in vals]
    return name, kv, list(pos)


def read_recipe(path) -> list[tuple[str, dict, list]]:
    text = Path(path).read_text(encoding="utf-8")
    if text.lstrip().startswith("["):
        return [parse_op_json(d) for d in json.loads(text)]
    ops = []
    for line in text.splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            ops.append(parse_op(line))
    return ops


# --------------------------------------------------------------------------
# Selections -> float mask in [0,1]
# --------------------------------------------------------------------------

SEL_KEYS = {"rect", "ellipse", "poly", "mask", "color", "wand", "brush", "bright", "dark",
            "sub_rect", "sub_ellipse", "sub_poly"}
SEL_MODS = {"tol", "grow", "feather", "invert", "width", "within"}


def has_selection(P: Params) -> bool:
    return any(P.has(k) for k in SEL_KEYS)


def _shape_mask(P: Params, prefix: str) -> np.ndarray:
    H, W = P.h, P.w
    m = np.zeros((H, W), np.uint8)
    for s in P.all(prefix + "rect"):
        x, y, w, h = P.nums(s, 4)
        x0, y0 = int(round(x)), int(round(y))
        m[max(0, y0):max(0, int(round(y + h))), max(0, x0):max(0, int(round(x + w)))] = 255
    for s in P.all(prefix + "ellipse"):
        cx, cy, rx, ry = P.nums(s, 4)
        cv2.ellipse(m, (int(round(cx)), int(round(cy))), (int(round(rx)), int(round(ry))),
                    0, 0, 360, 255, -1, cv2.LINE_AA)
    for s in P.all(prefix + "poly"):
        v = P.nums(s)
        if len(v) < 6 or len(v) % 2:
            raise ImgfixError("poly needs x1,y1,x2,y2,x3,y3,...")
        pts = np.array(v, np.float32).reshape(-1, 2)
        cv2.fillPoly(m, [np.round(pts * 16).astype(np.int32)], 255, cv2.LINE_AA, shift=4)
    return m


def selection(doc: Doc, P: Params, default_all=True) -> np.ndarray | None:
    """Return float32 mask (H,W) or None for 'whole image'."""
    need_cv2()
    H, W = doc.h, doc.w
    any_sel = has_selection(P)
    if not any_sel and not P.bool("invert"):
        P.all("grow"), P.all("feather"), P.all("tol"), P.all("width"), P.all("within")
        return None if default_all else np.zeros((H, W), np.float32)
    m = _shape_mask(P, "")
    for s in P.all("mask"):
        mm = Image.open(s).convert("L").resize((W, H), Image.BILINEAR)
        m = np.maximum(m, np.asarray(mm))
    tol = P.num("tol", 30.0)
    for s in P.all("color"):
        m = np.maximum(m, color_mask(doc.rgb, parse_color(s), tol))
    for s in P.all("wand"):
        x, y = P.nums(s, 2)
        m = np.maximum(m, wand_mask(doc.rgb, int(x), int(y), tol))
    width = P.num("width", 12.0)
    for s in P.all("brush"):
        v = P.nums(s)
        pts = np.round(np.array(v, np.float32).reshape(-1, 2)).astype(np.int32)
        if len(pts) == 1:
            cv2.circle(m, tuple(pts[0]), max(1, int(width / 2)), 255, -1, cv2.LINE_AA)
        else:
            cv2.polylines(m, [pts], False, 255, max(1, int(round(width))), cv2.LINE_AA)
    for key, sign in (("bright", 1), ("dark", -1)):
        for s in P.all(key):
            m = np.maximum(m, contrast_mask(doc.rgb, float(s or 20), sign))
    sub = _shape_mask(P, "sub_")
    m = np.where(sub > 0, 0, m).astype(np.uint8)
    within = P.str("within")
    if within:  # restrict e.g. a color selection to a region
        x, y, w, h = P.nums(within, 4)
        r = np.zeros_like(m)
        r[max(0, int(y)):int(y + h), max(0, int(x)):int(x + w)] = 255
        m = np.minimum(m, r)
    grow = P.num("grow", 0.0)
    if grow:
        k = int(abs(grow)) * 2 + 1
        ker = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k))
        m = cv2.dilate(m, ker) if grow > 0 else cv2.erode(m, ker)
    mf = m.astype(np.float32) / 255.0
    feather = P.num("feather", 0.0)
    if feather > 0:
        mf = cv2.GaussianBlur(mf, (0, 0), feather / 2.0)
    if P.bool("invert"):
        mf = 1.0 - mf
    return mf


def color_mask(rgb, color, tol) -> np.ndarray:
    lab = cv2.cvtColor(rgb, cv2.COLOR_RGB2LAB).astype(np.float32)
    ref = cv2.cvtColor(np.uint8([[color]]), cv2.COLOR_RGB2LAB).astype(np.float32)[0, 0]
    d = np.sqrt(((lab - ref) ** 2).sum(-1))
    # soft edge: full inside tol, fade over 25% beyond
    soft = np.clip((tol * 1.25 - d) / max(tol * 0.25, 1e-3), 0, 1)
    return (soft * 255).astype(np.uint8)


def contrast_mask(rgb, thr, sign) -> np.ndarray:
    """Pixels lighter (sign=1) or darker (-1) than their local background by > thr.
    Picks out text, watermarks, logos, specks - combine with within=x,y,w,h."""
    g = cv2.cvtColor(rgb, cv2.COLOR_RGB2GRAY).astype(np.float32)
    k = max(15, (min(rgb.shape[:2]) // 40) | 1)
    bg = cv2.medianBlur(g.astype(np.uint8), min(k, 255)).astype(np.float32)
    d = (g - bg) * sign
    return (np.clip((d - thr) / max(thr * 0.5, 1) + 0.5, 0, 1) * 255).astype(np.uint8)


def wand_mask(rgb, x, y, tol) -> np.ndarray:
    H, W = rgb.shape[:2]
    if not (0 <= x < W and 0 <= y < H):
        raise ImgfixError(f"wand point {x},{y} outside image")
    img = cv2.cvtColor(rgb, cv2.COLOR_RGB2LAB)
    ff = np.zeros((H + 2, W + 2), np.uint8)
    t = int(tol / 2)
    cv2.floodFill(img.copy(), ff, (x, y), 0, (t, t, t), (t, t, t),
                  4 | cv2.FLOODFILL_MASK_ONLY | cv2.FLOODFILL_FIXED_RANGE | (255 << 8))
    return ff[1:-1, 1:-1]


def mask_bbox(m: np.ndarray, thr=0.01):
    ys, xs = np.nonzero(m > thr)
    if len(xs) == 0:
        return None
    return int(xs.min()), int(ys.min()), int(xs.max() - xs.min() + 1), int(ys.max() - ys.min() + 1)


def blend(base: np.ndarray, new: np.ndarray, m: np.ndarray | None) -> np.ndarray:
    if m is None:
        return np.clip(new, 0, 255).astype(np.uint8) if new.dtype != np.uint8 else new
    mm = m[..., None]
    out = base.astype(np.float32) * (1 - mm) + new.astype(np.float32) * mm
    return np.clip(np.round(out), 0, 255).astype(np.uint8)


# --------------------------------------------------------------------------
# Inpainting (LaMa neural model, OpenCV fallback)
# --------------------------------------------------------------------------

_LAMA = None


def lama_available() -> bool:
    return LAMA_PATH.exists() and LAMA_PATH.stat().st_size > 50_000_000


def download_lama(verbose=True):
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    tmp = LAMA_PATH.with_suffix(".part")
    if verbose:
        print(f"downloading LaMa model (~92 MB) -> {LAMA_PATH}")
    with urllib.request.urlopen(LAMA_URL) as r, open(tmp, "wb") as f:
        while True:
            b = r.read(1 << 20)
            if not b:
                break
            f.write(b)
    tmp.replace(LAMA_PATH)


def _lama_runner():
    global _LAMA
    if _LAMA is not None:
        return _LAMA
    if not lama_available():
        raise ImgfixError("LaMa model not installed - run `imgfix.py setup` (or use method=telea)")
    try:
        import onnxruntime as ort
        so = ort.SessionOptions()
        so.log_severity_level = 3
        sess = ort.InferenceSession(str(LAMA_PATH), so, providers=ort.get_available_providers())

        def run(img, mask):
            return sess.run(None, {"image": img, "mask": mask})[0]
    except ImportError:
        net = cv2.dnn.readNetFromONNX(str(LAMA_PATH))

        def run(img, mask):
            net.setInput(img, "image")
            net.setInput(mask, "mask")
            return net.forward()
    _LAMA = run
    return run


def _lama_crop(rgb: np.ndarray, mask: np.ndarray) -> np.ndarray:
    """Inpaint one crop (any size) with LaMa; mask uint8 0/255."""
    run = _lama_runner()
    h, w = mask.shape
    s = LAMA_SIZE / max(h, w)
    nw, nh = max(1, round(w * s)), max(1, round(h * s))
    interp = cv2.INTER_AREA if s < 1 else cv2.INTER_CUBIC
    img = cv2.resize(rgb, (nw, nh), interpolation=interp)
    msk = cv2.resize(mask, (nw, nh), interpolation=cv2.INTER_NEAREST)
    # pad to the fixed 512x512 input with reflected content (keeps textures)
    pw, ph = LAMA_SIZE - nw, LAMA_SIZE - nh
    img = cv2.copyMakeBorder(img, 0, ph, 0, pw, cv2.BORDER_REFLECT_101)
    msk = cv2.copyMakeBorder(msk, 0, ph, 0, pw, cv2.BORDER_CONSTANT, value=0)
    bgr = img[..., ::-1].astype(np.float32) / 255.0
    x = np.ascontiguousarray(bgr.transpose(2, 0, 1)[None])
    m = (msk > 127).astype(np.float32)[None, None]
    out = run(x, m)[0].transpose(1, 2, 0)
    out = np.clip(out, 0, 255).astype(np.uint8)[..., ::-1][:nh, :nw]
    return cv2.resize(out, (w, h), interpolation=cv2.INTER_CUBIC if s < 1 else cv2.INTER_AREA)


def _regions(mask: np.ndarray, W: int, H: int, context: float):
    """Group mask blobs into crops that each get their own model pass."""
    n, _, stats, _ = cv2.connectedComponentsWithStats((mask > 0).astype(np.uint8))
    boxes = []
    for i in range(1, n):
        x, y, w, h, _ = stats[i]
        pad = int(max(48, context * max(w, h)))
        boxes.append([x - pad, y - pad, x + w + pad, y + h + pad])
    merged = True
    while merged:
        merged = False
        out = []
        for b in boxes:
            for o in out:
                if b[0] < o[2] and o[0] < b[2] and b[1] < o[3] and o[1] < b[3]:
                    o[:] = [min(o[0], b[0]), min(o[1], b[1]), max(o[2], b[2]), max(o[3], b[3])]
                    merged = True
                    break
            else:
                out.append(list(b))
        boxes = out
    res = []
    for x0, y0, x1, y1 in boxes:
        # square-ish crops give LaMa a natural aspect ratio
        cw, ch = x1 - x0, y1 - y0
        if cw < ch:
            x0 -= (ch - cw) // 2
            x1 = x0 + ch
        elif ch < cw:
            y0 -= (cw - ch) // 2
            y1 = y0 + cw
        res.append((max(0, x0), max(0, y0), min(W, x1), min(H, y1)))
    return res


def noise_sigma(rgb: np.ndarray, region: np.ndarray) -> float:
    g = cv2.cvtColor(rgb, cv2.COLOR_RGB2GRAY).astype(np.float32)
    hp = g - cv2.GaussianBlur(g, (0, 0), 1.2)
    vals = hp[region > 0]
    if vals.size < 50:
        return 0.0
    mad = np.median(np.abs(vals - np.median(vals)))
    return float(1.4826 * mad)


def match_grain(orig: np.ndarray, out: np.ndarray, hard: np.ndarray, soft: np.ndarray, seed=1):
    """Add noise inside the filled area so it matches the surrounding grain."""
    k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (31, 31))
    ring = cv2.dilate(hard, k) & ~cv2.dilate(hard, np.ones((5, 5), np.uint8))
    target = noise_sigma(orig, ring)
    have = noise_sigma(out, hard)
    add = math.sqrt(max(0.0, target ** 2 - have ** 2))
    if add < 0.3:
        return out
    rng = np.random.default_rng(seed)
    n = rng.normal(0, add, out.shape[:2]).astype(np.float32)
    n = cv2.GaussianBlur(n, (0, 0), 0.6) * 1.25
    noisy = out.astype(np.float32) + n[..., None]
    return blend(out, noisy, soft)


def inpaint(rgb: np.ndarray, mask_u8: np.ndarray, method="auto", context=1.0,
            radius=5) -> np.ndarray:
    need_cv2()
    if method == "auto":
        method = "lama" if lama_available() else "telea"
    if method in ("telea", "ns"):
        flag = cv2.INPAINT_TELEA if method == "telea" else cv2.INPAINT_NS
        return cv2.inpaint(rgb, mask_u8, radius, flag)
    if method != "lama":
        raise ImgfixError(f"inpaint: unknown method {method} (lama|telea|ns)")
    H, W = mask_u8.shape
    out = rgb.copy()
    for x0, y0, x1, y1 in _regions(mask_u8, W, H, context):
        crop_m = mask_u8[y0:y1, x0:x1]
        if not crop_m.any():
            continue
        res = _lama_crop(out[y0:y1, x0:x1], crop_m)
        sel = crop_m > 0
        out[y0:y1, x0:x1][sel] = res[sel]
    return out


# --------------------------------------------------------------------------
# Tone / colour helpers
# --------------------------------------------------------------------------

def to_f(rgb):
    return rgb.astype(np.float32) / 255.0


def to_u8(f):
    return np.clip(np.round(f * 255.0), 0, 255).astype(np.uint8)


def srgb_to_lin(x):
    return np.where(x <= 0.04045, x / 12.92, ((x + 0.055) / 1.055) ** 2.4)


def lin_to_srgb(x):
    x = np.clip(x, 0, None)
    return np.where(x <= 0.0031308, x * 12.92, 1.055 * np.power(x, 1 / 2.4) - 0.055)


def luminance(f):
    return f[..., 0] * 0.2126 + f[..., 1] * 0.7152 + f[..., 2] * 0.0722


def smoothstep(a, b, x):
    t = np.clip((x - a) / (b - a), 0, 1)
    return t * t * (3 - 2 * t)


def curve_lut(points: list[tuple[float, float]]) -> np.ndarray:
    pts = sorted(points)
    xs = np.array([p[0] for p in pts], np.float64)
    ys = np.array([p[1] for p in pts], np.float64)
    x = np.arange(256, dtype=np.float64)
    if len(pts) >= 3:  # monotone cubic (PCHIP) - smooth like Photoshop curves
        d = np.diff(ys) / np.maximum(np.diff(xs), 1e-9)
        m = np.zeros_like(ys)
        m[0], m[-1] = d[0], d[-1]
        for i in range(1, len(ys) - 1):
            if d[i - 1] * d[i] > 0:
                w1, w2 = 2 * (xs[i + 1] - xs[i]) + (xs[i] - xs[i - 1]), \
                         (xs[i + 1] - xs[i]) + 2 * (xs[i] - xs[i - 1])
                m[i] = (w1 + w2) / (w1 / d[i - 1] + w2 / d[i])
        idx = np.clip(np.searchsorted(xs, x) - 1, 0, len(xs) - 2)
        hk = xs[idx + 1] - xs[idx]
        t = np.clip((x - xs[idx]) / hk, 0, 1)
        y = ((2 * t ** 3 - 3 * t ** 2 + 1) * ys[idx] + (t ** 3 - 2 * t ** 2 + t) * hk * m[idx]
             + (-2 * t ** 3 + 3 * t ** 2) * ys[idx + 1] + (t ** 3 - t ** 2) * hk * m[idx + 1])
        y = np.where(x < xs[0], ys[0], np.where(x > xs[-1], ys[-1], y))
    else:
        y = np.interp(x, xs, ys)
    return np.clip(np.round(y), 0, 255).astype(np.uint8)


# --------------------------------------------------------------------------
# Fonts
# --------------------------------------------------------------------------

FONT_DIRS = [Path("C:/Windows/Fonts"), Path.home() / "AppData/Local/Microsoft/Windows/Fonts",
             Path("/usr/share/fonts"), Path("/usr/local/share/fonts"), Path.home() / ".fonts",
             Path.home() / ".local/share/fonts", Path("/Library/Fonts"),
             Path("/System/Library/Fonts"), Path.home() / "Library/Fonts"]


def all_fonts() -> list[Path]:
    out = []
    for d in FONT_DIRS:
        if d.exists():
            out += [p for p in d.rglob("*") if p.suffix.lower() in (".ttf", ".otf", ".ttc")]
    return sorted(set(out))


def find_font(name: str | None) -> str:
    if name and Path(name).exists():
        return name
    fonts = all_fonts()
    want = (name or "").lower().replace(" ", "").replace("-", "")
    prefs = [want] if want else ["dejavusans", "arial", "helvetica", "liberationsans", "inter"]
    for p in prefs:
        exact = [f for f in fonts if f.stem.lower().replace("-", "").replace(" ", "") == p]
        if exact:
            return str(exact[0])
        part = sorted((f for f in fonts if p in f.stem.lower().replace("-", "").replace(" ", "")),
                      key=lambda f: len(f.stem))
        if part:
            return str(part[0])
    if name:
        raise ImgfixError(f"font '{name}' not found - run `imgfix.py fonts` to list")
    raise ImgfixError("no fonts found; pass font=path/to/font.ttf")


# --------------------------------------------------------------------------
# Operations
# --------------------------------------------------------------------------

OPS = {}


def op(name, help_text):
    def deco(fn):
        OPS[name] = (fn, help_text)
        return fn
    return deco


SEL_HELP = ("selection keys (any op marked [sel]): rect=x,y,w,h ellipse=cx,cy,rx,ry "
            "poly=x1,y1,x2,y2,... brush=x1,y1,x2,y2,...(width=) color=#hex wand=x,y (tol=30) "
            "bright=20 / dark=20 (pixels lighter/darker than local background by N - text, "
            "watermarks, specks; combine with within=) "
            "mask=file.png sub_rect=/sub_ellipse=/sub_poly= (subtract) within=x,y,w,h "
            "grow=px (neg=shrink) feather=px invert=1. Keys can repeat (union). "
            "Numbers may be % of width/height, e.g. rect=90%,90%,10%,10%.")


@op("inpaint", "[sel] remove objects/watermarks/artifacts. method=auto|lama|telea|ns "
               "grow=4 (default) context=1.0 grain=auto|0|<sigma> radius=5 (telea/ns)")
def op_inpaint(doc, P):
    if not P.has("grow"):
        P.kv["grow"] = ["4"]
    soft = selection(doc, P, default_all=False)
    hard = (soft > 0.02).astype(np.uint8) * 255
    if not hard.any():
        raise ImgfixError("inpaint: empty selection")
    method = P.str("method", "auto")
    res = inpaint(doc.rgb, hard, method, P.num("context", 1.0), P.int("radius", 5))
    grain = P.str("grain", "auto")
    if grain == "auto":
        res = match_grain(doc.rgb, res, hard, (hard > 0).astype(np.float32))
    elif float(grain) > 0:
        rng = np.random.default_rng(1)
        n = rng.normal(0, float(grain), res.shape[:2]).astype(np.float32)
        res = blend(res, res.astype(np.float32) + n[..., None], hard / 255.0)
    # soft edge back into the original
    edge = cv2.GaussianBlur(hard.astype(np.float32) / 255.0, (0, 0), 1.0)
    edge = np.maximum(edge, hard / 255.0)
    doc.rgb = blend(doc.rgb, res, edge)


@op("clone", "[sel] clone stamp: copy pixels from offset dx,dy into the selection. "
             "from=dx,dy (relative source)  feather recommended")
def op_clone(doc, P):
    dx, dy = P.nums(P.str("from") or "0,0", 2, coords=False)
    m = selection(doc, P, default_all=False)
    M = np.float32([[1, 0, dx], [0, 1, dy]])
    src = cv2.warpAffine(doc.rgb, M, (doc.w, doc.h), flags=cv2.INTER_LINEAR | cv2.WARP_INVERSE_MAP,
                         borderMode=cv2.BORDER_REFLECT_101)
    doc.rgb = blend(doc.rgb, src, m)


@op("heal", "[sel] healing brush: like clone but Poisson-blended so light/colour match. "
            "from=dx,dy mode=normal|mixed")
def op_heal(doc, P):
    dx, dy = P.nums(P.str("from") or "0,0", 2, coords=False)
    mode = cv2.MIXED_CLONE if P.str("mode", "normal") == "mixed" else cv2.NORMAL_CLONE
    m = selection(doc, P, default_all=False)
    hard = (m > 0.02).astype(np.uint8) * 255
    bb = mask_bbox(hard)
    if bb is None:
        raise ImgfixError("heal: empty selection")
    x, y, w, h = bb
    sx, sy = int(round(x + dx)), int(round(y + dy))
    if sx < 0 or sy < 0 or sx + w > doc.w or sy + h > doc.h:
        raise ImgfixError("heal: source area falls outside the image")
    src = doc.rgb[sy:sy + h, sx:sx + w]
    sm = hard[y:y + h, x:x + w]
    # seamlessClone needs a 1px border inside the destination
    pad = 2
    big = cv2.copyMakeBorder(doc.rgb, pad, pad, pad, pad, cv2.BORDER_REFLECT_101)
    center = (x + pad + w // 2, y + pad + h // 2)
    res = cv2.seamlessClone(np.ascontiguousarray(src), big, sm, center, mode)[pad:-pad, pad:-pad]
    doc.rgb = blend(doc.rgb, res, np.maximum(m, 0) if P.has("feather") else hard / 255.0)


@op("fill", "[sel] fill selection. color=#hex | mode=avg (mean of surrounding ring) | "
            "mode=blur (heavily blurred surroundings). opacity=1")
def op_fill(doc, P):
    m = selection(doc, P, default_all=True)
    mode = P.str("mode", "color")
    if mode == "avg":
        hard = (m > 0.02).astype(np.uint8) * 255 if m is not None else None
        if hard is None:
            raise ImgfixError("fill mode=avg needs a selection")
        ring = cv2.dilate(hard, np.ones((15, 15), np.uint8)) & ~hard
        c = doc.rgb[ring > 0].mean(0)
        new = np.broadcast_to(c, doc.rgb.shape)
    elif mode == "blur":
        hard = (m > 0.02).astype(np.uint8) * 255
        tmp = cv2.inpaint(doc.rgb, hard, 3, cv2.INPAINT_TELEA)
        new = cv2.GaussianBlur(tmp, (0, 0), max(4, P.num("radius", 20)))
    else:
        c = P.color("value", (255, 255, 255))
        new = np.broadcast_to(np.array(c, np.uint8), doc.rgb.shape)
    op_ = P.num("opacity", 1.0)
    mm = (np.ones(doc.rgb.shape[:2], np.float32) if m is None else m) * op_
    doc.rgb = blend(doc.rgb, new, mm)


def _fill_params_fix(P: Params):
    # `fill color=#fff` must not be read as a colour *selection*
    if P.op == "fill" and P.has("color"):
        P.kv["value"] = P.kv.pop("color")


@op("adjust", "[sel] exposure=EV brightness contrast saturation vibrance (-100..100) "
              "hue=deg temp tint (-100..100) shadows highlights whites blacks (-100..100) "
              "gamma=1.0 opacity=1")
def op_adjust(doc, P):
    m = selection(doc, P)
    f = to_f(doc.rgb)
    ev = P.num("exposure", 0)
    if ev:
        f = lin_to_srgb(srgb_to_lin(f) * 2.0 ** ev)
    g = P.num("gamma", 1.0)
    if g != 1.0:
        f = np.power(np.clip(f, 0, 1), 1.0 / g)
    b = P.num("brightness", 0)
    if b:
        f = np.power(np.clip(f, 0, 1), 2.0 ** (-b / 100.0))
    c = P.num("contrast", 0)
    if c:
        k = 1.0 + c / 100.0 if c > 0 else 1.0 + c / 100.0 * 0.9
        f = (f - 0.5) * k + 0.5
    bl, wh = P.num("blacks", 0), P.num("whites", 0)
    if bl:
        f = f + (bl / 100.0) * 0.15 * (1 - smoothstep(0.0, 0.35, f))
    if wh:
        f = f + (wh / 100.0) * 0.15 * smoothstep(0.65, 1.0, f)
    sh, hl = P.num("shadows", 0), P.num("highlights", 0)
    if sh or hl:
        L = cv2.GaussianBlur(luminance(np.clip(f, 0, 1)).astype(np.float32), (0, 0),
                             max(doc.w, doc.h) / 150.0)[..., None]
        if sh:
            w = 1 - smoothstep(0.0, 0.55, L)
            a = sh / 100.0 * 0.6
            f = f + (a * w * (1 - f) if a > 0 else a * w * f)
        if hl:
            w = smoothstep(0.45, 1.0, L)
            a = hl / 100.0 * 0.6
            f = f + (a * w * (1 - f) if a > 0 else a * w * f)
    t, ti = P.num("temp", 0), P.num("tint", 0)
    if t or ti:
        f = f * np.array([1 + t / 100 * 0.15, 1 - ti / 100 * 0.12, 1 - t / 100 * 0.15],
                         np.float32)
    s, vib = P.num("saturation", 0), P.num("vibrance", 0)
    if s or vib:
        L = luminance(f)[..., None]
        k = 1.0 + s / 100.0
        if vib:
            sat = (f.max(-1, keepdims=True) - f.min(-1, keepdims=True))
            k = k + vib / 100.0 * (1 - np.clip(sat * 1.5, 0, 1))
        f = L + (f - L) * k
    hue = P.num("hue", 0)
    if hue:
        hsv = cv2.cvtColor(np.clip(f, 0, 1).astype(np.float32), cv2.COLOR_RGB2HSV)
        hsv[..., 0] = (hsv[..., 0] + hue) % 360.0
        f = cv2.cvtColor(hsv, cv2.COLOR_HSV2RGB)
    opac = P.num("opacity", 1.0)
    if m is None and opac != 1.0:
        m = np.full(doc.rgb.shape[:2], opac, np.float32)
    elif m is not None:
        m = m * opac
    doc.rgb = blend(doc.rgb, to_u8(np.clip(f, 0, 1)), m)


@op("levels", "[sel] in=black,white (0-255) gamma=1.0 out=black,white ch=rgb|r|g|b")
def op_levels(doc, P):
    m = selection(doc, P)
    ib, iw = P.nums(P.str("in") or "0,255", 2, coords=False)
    ob, ow = P.nums(P.str("out") or "0,255", 2, coords=False)
    g = P.num("gamma", 1.0)
    x = np.arange(256, dtype=np.float32)
    y = np.clip((x - ib) / max(iw - ib, 1e-3), 0, 1) ** (1 / g) * (ow - ob) + ob
    lut = np.clip(np.round(y), 0, 255).astype(np.uint8)
    doc.rgb = blend(doc.rgb, _apply_lut(doc.rgb, lut, P.str("ch", "rgb")), m)


def _apply_lut(rgb, lut, ch):
    out = rgb.copy()
    for i, c in enumerate("rgb"):
        if c in ch:
            out[..., i] = lut[rgb[..., i]]
    return out


@op("curves", "[sel] pts=x,y;x,y;... (0-255, e.g. pts=0,0;64,52;192,205;255,255) ch=rgb|r|g|b")
def op_curves(doc, P):
    m = selection(doc, P)
    v = P.nums(P.str("pts") or "0,0,255,255", coords=False)
    lut = curve_lut(list(zip(v[0::2], v[1::2])))
    doc.rgb = blend(doc.rgb, _apply_lut(doc.rgb, lut, P.str("ch", "rgb")), m)


@op("autolevels", "[sel] stretch histogram per channel. clip=0.3 (percent) "
                  "linked=1 (keep colour balance)")
def op_autolevels(doc, P):
    m = selection(doc, P)
    clip = P.num("clip", 0.3)
    src = doc.rgb if m is None else doc.rgb[m > 0.5]
    src = src.reshape(-1, 3)
    if P.bool("linked", True):
        lo, hi = np.percentile(src, clip), np.percentile(src, 100 - clip)
        los, his = [lo] * 3, [hi] * 3
    else:
        los = [np.percentile(src[:, i], clip) for i in range(3)]
        his = [np.percentile(src[:, i], 100 - clip) for i in range(3)]
    f = doc.rgb.astype(np.float32)
    for i in range(3):
        f[..., i] = (f[..., i] - los[i]) / max(his[i] - los[i], 1) * 255
    doc.rgb = blend(doc.rgb, np.clip(f, 0, 255), m)


@op("wb", "[sel] white balance. ref=x,y (pixel that should be neutral grey/white, r=5 "
          "sampling radius) or method=grayworld. strength=1")
def op_wb(doc, P):
    m = selection(doc, P)
    ref = P.point("ref")
    if P.str("method", "ref" if ref else "grayworld") not in ("ref", "grayworld"):
        raise ImgfixError("wb: method=grayworld or ref=x,y")
    if ref:
        r = P.int("r", 5)
        x, y = int(ref[0]), int(ref[1])
        patch = doc.rgb[max(0, y - r):y + r + 1, max(0, x - r):x + r + 1].reshape(-1, 3)
        c = patch.astype(np.float32).mean(0)
    else:
        c = doc.rgb.reshape(-1, 3).astype(np.float32).mean(0)
    target = c.mean()
    gains = target / np.maximum(c, 1)
    gains = 1 + (gains - 1) * P.num("strength", 1.0)
    lin = srgb_to_lin(to_f(doc.rgb))
    lg = srgb_to_lin(np.clip(c * gains / 255, 0, 1)) / np.maximum(srgb_to_lin(c / 255), 1e-6)
    doc.rgb = blend(doc.rgb, to_u8(lin_to_srgb(lin * lg)), m)


@op("recolor", "[sel] shift selected colours to a target: to=#hex (keeps shading) or "
               "hue=deg sat=-100..100. Pair with color=#src tol=.. to pick the source")
def op_recolor(doc, P):
    m = selection(doc, P)
    f = to_f(doc.rgb)
    hsv = cv2.cvtColor(f, cv2.COLOR_RGB2HSV)
    target = P.color("to")
    if target:
        th, ts, tv = cv2.cvtColor(np.float32([[np.array(target) / 255.0]]),
                                  cv2.COLOR_RGB2HSV)[0, 0]
        sel = m if m is not None else np.ones(f.shape[:2], np.float32)
        wts = sel > 0.5
        src_s = float(np.median(hsv[..., 1][wts])) if wts.any() else 1.0
        src_v = float(np.median(hsv[..., 2][wts])) if wts.any() else 1.0
        hsv[..., 0] = th
        hsv[..., 1] = np.clip(hsv[..., 1] * (ts / max(src_s, 1e-3)), 0, 1)
        if P.bool("value", True):
            hsv[..., 2] = np.clip(hsv[..., 2] * (tv / max(src_v, 1e-3)), 0, 1)
    hue = P.num("hue", 0)
    if hue:
        hsv[..., 0] = (hsv[..., 0] + hue) % 360
    sat = P.num("sat", 0)
    if sat:
        hsv[..., 1] = np.clip(hsv[..., 1] * (1 + sat / 100), 0, 1)
    doc.rgb = blend(doc.rgb, to_u8(cv2.cvtColor(hsv, cv2.COLOR_HSV2RGB)), m)


@op("blur", "[sel] radius=4 type=gauss|median|bilateral|box (bilateral = skin/surface smoothing)")
def op_blur(doc, P):
    m = selection(doc, P)
    r = P.num("radius", 4.0)
    t = P.str("type", "gauss")
    if t == "gauss":
        new = cv2.GaussianBlur(doc.rgb, (0, 0), max(r / 2, 0.3))
    elif t == "median":
        new = cv2.medianBlur(doc.rgb, int(r) * 2 + 1)
    elif t == "bilateral":
        new = cv2.bilateralFilter(doc.rgb, int(r) * 2 + 1, P.num("sigma", 40), r * 2)
    elif t == "box":
        new = cv2.blur(doc.rgb, (int(r) * 2 + 1,) * 2)
    else:
        raise ImgfixError(f"blur: unknown type {t}")
    doc.rgb = blend(doc.rgb, new, m)


@op("sharpen", "[sel] unsharp mask. amount=80 (%) radius=1.5 threshold=2")
def op_sharpen(doc, P):
    m = selection(doc, P)
    amt, r, thr = P.num("amount", 80) / 100, P.num("radius", 1.5), P.num("threshold", 2)
    f = doc.rgb.astype(np.float32)
    bl = cv2.GaussianBlur(f, (0, 0), r)
    d = f - bl
    if thr > 0:
        d = d * np.clip((np.abs(d) - thr) / max(thr, 1), 0, 1)
    doc.rgb = blend(doc.rgb, f + d * amt, m)


@op("denoise", "[sel] remove noise/JPEG/AI mottling. strength=6 detail=7")
def op_denoise(doc, P):
    m = selection(doc, P)
    s = P.num("strength", 6)
    new = cv2.fastNlMeansDenoisingColored(doc.rgb, None, s, s, P.int("detail", 7), 21)
    doc.rgb = blend(doc.rgb, new, m)


@op("grain", "[sel] add film/sensor grain. amount=4 (sigma, 0-255) size=0.7 mono=1")
def op_grain(doc, P):
    m = selection(doc, P)
    rng = np.random.default_rng(P.int("seed", 7))
    a, s = P.num("amount", 4), P.num("size", 0.7)
    shape = doc.rgb.shape[:2] if P.bool("mono", True) else doc.rgb.shape
    n = rng.normal(0, 1, shape).astype(np.float32)
    if s > 0:
        n = cv2.GaussianBlur(n, (0, 0), s)
        n /= max(n.std(), 1e-6)
    if n.ndim == 2:
        n = n[..., None]
    doc.rgb = blend(doc.rgb, doc.rgb.astype(np.float32) + n * a, m)


@op("vignette", "amount=30 (-100..100, negative = lighten edges) size=0.75 x=50% y=50%")
def op_vignette(doc, P):
    a = P.num("amount", 30) / 100
    size = P.num("size", 0.75)
    cx = P.coord(P.str("x", "50%"), 0)
    cy = P.coord(P.str("y", "50%"), 1)
    yy, xx = np.mgrid[0:doc.h, 0:doc.w].astype(np.float32)
    d = np.sqrt(((xx - cx) / (doc.w / 2)) ** 2 + ((yy - cy) / (doc.h / 2)) ** 2) / math.sqrt(2)
    w = smoothstep(size * 0.6, size * 1.35, d)[..., None]
    f = srgb_to_lin(to_f(doc.rgb)) * (1 - a * w * 0.85)
    doc.rgb = to_u8(lin_to_srgb(f))


@op("crop", "rect=x,y,w,h | aspect=16:9 (centered, or x= y= to anchor %)")
def op_crop(doc, P):
    r = P.rect()
    if r is None:
        asp = P.str("aspect")
        if not asp:
            raise ImgfixError("crop: rect= or aspect= required")
        a, b = (float(v) for v in asp.replace("/", ":").split(":"))
        tw = min(doc.w, doc.h * a / b)
        th = tw * b / a
        ax = P.num("x", 50) / 100
        ay = P.num("y", 50) / 100
        r = (int((doc.w - tw) * ax), int((doc.h - th) * ay), int(tw), int(th))
    x, y, w, h = r
    doc.rgb = doc.rgb[y:y + h, x:x + w].copy()
    if doc.alpha is not None:
        doc.alpha = doc.alpha[y:y + h, x:x + w].copy()


@op("resize", "w= h= (one keeps aspect) | scale=0.5. Lanczos; downscale uses area filter")
def op_resize(doc, P):
    sc = P.num("scale")
    w, h = P.num("w"), P.num("h")
    if sc:
        w, h = doc.w * sc, doc.h * sc
    elif w and not h:
        h = doc.h * w / doc.w
    elif h and not w:
        w = doc.w * h / doc.h
    if not (w and h):
        raise ImgfixError("resize: w=/h= or scale= required")
    w, h = int(round(w)), int(round(h))
    interp = cv2.INTER_AREA if w < doc.w else cv2.INTER_LANCZOS4
    doc.rgb = cv2.resize(doc.rgb, (w, h), interpolation=interp)
    if doc.alpha is not None:
        doc.alpha = cv2.resize(doc.alpha, (w, h), interpolation=interp)


@op("upscale", "scale=2 sharpen=60. Lanczos + edge-aware sharpening (no AI hallucination)")
def op_upscale(doc, P):
    sc = P.num("scale", 2)
    P.kv["scale"] = [str(sc)]
    op_resize(doc, P)
    amt = P.num("sharpen", 60)
    if amt > 0:
        f = doc.rgb.astype(np.float32)
        base = cv2.bilateralFilter(doc.rgb, 5, 25, 3).astype(np.float32)
        d = f - cv2.GaussianBlur(base, (0, 0), 0.8 * sc)
        doc.rgb = np.clip(f + d * amt / 100, 0, 255).astype(np.uint8)


@op("rotate", "deg=90 (counter-clockwise). Non-90 angles: mode=crop (default, straighten: "
              "rotate + crop to the clean inner area, same size) | expand (bigger canvas, "
              "fill=#hex|edge|mirror)")
def op_rotate(doc, P):
    deg = P.num("deg", 90)
    if deg % 90 == 0:
        k = int(deg // 90) % 4
        doc.rgb = np.ascontiguousarray(np.rot90(doc.rgb, k))
        if doc.alpha is not None:
            doc.alpha = np.ascontiguousarray(np.rot90(doc.alpha, k))
        return
    mode = P.str("mode", "crop")
    fill = P.str("fill", "edge")
    W, H = doc.w, doc.h
    t = math.radians(abs(deg) % 180)
    c, s_ = abs(math.cos(t)), abs(math.sin(t))
    if mode == "crop":
        zoom = 1.0 / min(W / (W * c + H * s_), H / (W * s_ + H * c))
        M = cv2.getRotationMatrix2D((W / 2, H / 2), deg, zoom)
    elif mode == "expand":
        M = cv2.getRotationMatrix2D((W / 2, H / 2), deg, 1.0)
        W, H = int(math.ceil(doc.h * s_ + doc.w * c)), int(math.ceil(doc.h * c + doc.w * s_))
        M[0, 2] += W / 2 - doc.w / 2
        M[1, 2] += H / 2 - doc.h / 2
    else:
        raise ImgfixError("rotate: mode=crop|expand")
    kw = dict(flags=cv2.INTER_CUBIC)
    if fill in ("edge", "mirror"):
        kw["borderMode"] = cv2.BORDER_REPLICATE if fill == "edge" else cv2.BORDER_REFLECT_101
    else:
        kw.update(borderMode=cv2.BORDER_CONSTANT, borderValue=parse_color(fill))
    doc.rgb = cv2.warpAffine(doc.rgb, M, (W, H), **kw)
    if doc.alpha is not None:
        doc.alpha = cv2.warpAffine(doc.alpha, M, (W, H), flags=cv2.INTER_CUBIC)


@op("flip", "dir=h|v")
def op_flip(doc, P):
    ax = 1 if P.str("dir", "h") == "h" else 0
    doc.rgb = np.ascontiguousarray(np.flip(doc.rgb, ax))
    if doc.alpha is not None:
        doc.alpha = np.ascontiguousarray(np.flip(doc.alpha, ax))


@op("perspective", "straighten a skewed plane: pts=x1,y1,x2,y2,x3,y3,x4,y4 (TL,TR,BR,BL) "
                   "w= h= (output size; default from pts)")
def op_perspective(doc, P):
    v = P.nums(P.str("pts") or "", 8)
    src = np.float32(v).reshape(4, 2)
    w = P.int("w") or int(max(np.linalg.norm(src[0] - src[1]), np.linalg.norm(src[3] - src[2])))
    h = P.int("h") or int(max(np.linalg.norm(src[0] - src[3]), np.linalg.norm(src[1] - src[2])))
    dst = np.float32([[0, 0], [w, 0], [w, h], [0, h]])
    M = cv2.getPerspectiveTransform(src, dst)
    doc.rgb = cv2.warpPerspective(doc.rgb, M, (w, h), flags=cv2.INTER_CUBIC,
                                  borderMode=cv2.BORDER_REPLICATE)
    if doc.alpha is not None:
        doc.alpha = cv2.warpPerspective(doc.alpha, M, (w, h), flags=cv2.INTER_CUBIC)


@op("extend", "grow the canvas (outpaint): left= top= right= bottom= (px or %) "
              "fill=lama|edge|mirror|#hex")
def op_extend(doc, P):
    l = int(P.coord(P.str("left", "0"), 0))
    t = int(P.coord(P.str("top", "0"), 1))
    r = int(P.coord(P.str("right", "0"), 0))
    b = int(P.coord(P.str("bottom", "0"), 1))
    fill = P.str("fill", "lama" if lama_available() else "mirror")
    if fill in ("edge", "mirror", "lama"):
        mode = cv2.BORDER_REPLICATE if fill == "edge" else cv2.BORDER_REFLECT_101
        new = cv2.copyMakeBorder(doc.rgb, t, b, l, r, mode)
    else:
        new = cv2.copyMakeBorder(doc.rgb, t, b, l, r, cv2.BORDER_CONSTANT,
                                 value=parse_color(fill))
    if fill == "lama":
        m = np.full(new.shape[:2], 255, np.uint8)
        m[t:t + doc.h, l:l + doc.w] = 0
        new = inpaint(new, m, "lama", context=0.6)
    if doc.alpha is not None:
        doc.alpha = cv2.copyMakeBorder(doc.alpha, t, b, l, r, cv2.BORDER_CONSTANT, value=255)
    doc.rgb = new


@op("text", "draw text: text=\"...\" (or positional) x= y= size=48 | box=x,y,w,h (auto-fit, "
            "centred) font=name|path color=#hex stroke=0 stroke_color=#000 anchor=la "
            "(PIL anchor, e.g. mm=centre) align=left|center|right spacing=4 opacity=1 "
            "angle=0 shadow=dx,dy shadow_color=#0008")
def op_text(doc, P):
    txt = P.str("text") or " ".join(P.pos)
    if not txt:
        raise ImgfixError("text: no text given")
    txt = txt.replace("\\n", "\n")
    font_path = find_font(P.str("font"))
    color = P.color("color", (255, 255, 255))
    opacity = P.num("opacity", 1.0)
    stroke = P.int("stroke", 0)
    stroke_c = P.color("stroke_color", (0, 0, 0))
    align = P.str("align", "center" if P.has("box") else "left")
    spacing = P.num("spacing", 4)
    box = P.str("box")
    anchor = P.str("anchor", "mm" if box else "la")
    if box:
        bx, by, bw, bh = P.nums(box, 4)
        lo, hi = 4, int(bh * 2)
        while lo < hi:  # largest size that fits the box
            mid = (lo + hi + 1) // 2
            f = ImageFont.truetype(font_path, mid)
            l_, t_, r_, b_ = ImageDraw.Draw(Image.new("L", (1, 1))).multiline_textbbox(
                (0, 0), txt, font=f, spacing=spacing, stroke_width=stroke, align=align)
            if r_ - l_ <= bw and b_ - t_ <= bh:
                lo = mid
            else:
                hi = mid - 1
        size = P.int("size", lo)
        x, y = bx + bw / 2, by + bh / 2
    else:
        size = P.int("size", 48)
        x, y = P.coord(P.str("x", "0"), 0), P.coord(P.str("y", "0"), 1)
    font = ImageFont.truetype(font_path, size)
    layer = Image.new("RGBA", (doc.w, doc.h), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    sh = P.str("shadow")
    if sh:
        sdx, sdy = P.nums(sh, 2, coords=False)
        scol = ImageColor.getrgb(P.str("shadow_color", "#00000088"))
        if len(scol) == 3:
            scol = (*scol, 136)
        d.multiline_text((x + sdx, y + sdy), txt, font=font, fill=scol, anchor=anchor,
                         align=align, spacing=spacing, stroke_width=stroke, stroke_fill=scol)
    d.multiline_text((x, y), txt, font=font, fill=(*color, 255), anchor=anchor, align=align,
                     spacing=spacing, stroke_width=stroke, stroke_fill=(*stroke_c, 255))
    ang = P.num("angle", 0)
    if ang:
        layer = layer.rotate(ang, resample=Image.BICUBIC, center=(x, y))
    a = np.asarray(layer).astype(np.float32)
    doc.rgb = blend(doc.rgb, a[..., :3], a[..., 3] / 255.0 * opacity)
    print(f"  text: font={Path(font_path).name} size={size}")


@op("line", "paint strokes: pts=x1,y1,x2,y2,... width=3 color=#hex soft=0 opacity=1 closed=0")
def op_line(doc, P):
    v = P.nums(P.str("pts") or "")
    pts = np.round(np.array(v, np.float32).reshape(-1, 2) * 16).astype(np.int32)
    m = np.zeros(doc.rgb.shape[:2], np.uint8)
    w = max(1, P.int("width", 3))
    cv2.polylines(m, [pts], P.bool("closed"), 255, w, cv2.LINE_AA, shift=4)
    mf = m.astype(np.float32) / 255
    soft = P.num("soft", 0)
    if soft > 0:
        mf = cv2.GaussianBlur(mf, (0, 0), soft)
    col = np.broadcast_to(np.array(P.color("color", (0, 0, 0)), np.uint8), doc.rgb.shape)
    doc.rgb = blend(doc.rgb, col, mf * P.num("opacity", 1.0))


@op("paste", "place another image: file=path x= y= w= (or scale=) opacity=1 "
             "blend=normal|multiply|screen|overlay feather=0")
def op_paste(doc, P):
    src = load(P.str("file") or (P.pos[0] if P.pos else ""))
    im = src.pil().convert("RGBA")
    sc = P.num("scale")
    w = P.num("w")
    if w:
        im = im.resize((int(w), int(round(im.height * w / im.width))), Image.LANCZOS)
    elif sc:
        im = im.resize((int(im.width * sc), int(im.height * sc)), Image.LANCZOS)
    x, y = int(P.coord(P.str("x", "0"), 0)), int(P.coord(P.str("y", "0"), 1))
    layer = np.zeros((doc.h, doc.w, 4), np.float32)
    a = np.asarray(im).astype(np.float32)
    x0, y0 = max(0, x), max(0, y)
    x1, y1 = min(doc.w, x + im.width), min(doc.h, y + im.height)
    if x1 <= x0 or y1 <= y0:
        raise ImgfixError("paste: image lands outside the canvas")
    layer[y0:y1, x0:x1] = a[y0 - y:y1 - y, x0 - x:x1 - x]
    alpha = layer[..., 3] / 255 * P.num("opacity", 1.0)
    fe = P.num("feather", 0)
    if fe > 0:
        alpha = cv2.GaussianBlur(cv2.erode(alpha, np.ones((int(fe) + 1,) * 2)), (0, 0), fe / 2)
    top, base = layer[..., :3] / 255, to_f(doc.rgb)
    mode = P.str("blend", "normal")
    if mode == "multiply":
        top = base * top
    elif mode == "screen":
        top = 1 - (1 - base) * (1 - top)
    elif mode == "overlay":
        top = np.where(base < 0.5, 2 * base * top, 1 - 2 * (1 - base) * (1 - top))
    doc.rgb = blend(doc.rgb, top * 255, alpha)


@op("cutout", "remove background -> transparent PNG. method=grabcut (rect=x,y,w,h around "
              "subject, iter=6) | rembg (if installed) | sel (use any selection as alpha). "
              "feather=1.5 bg=#hex (flatten onto colour instead)")
def op_cutout(doc, P):
    method = P.str("method", "grabcut")
    fe = P.num("feather", 1.5)
    if method == "rembg":
        try:
            from rembg import remove
        except ImportError:
            raise ImgfixError("rembg not installed: pip install rembg  (or method=grabcut)")
        a = np.asarray(remove(Image.fromarray(doc.rgb)))[..., 3].astype(np.float32) / 255
    elif method == "sel":
        a = selection(doc, P, default_all=False)
    else:
        r = P.rect()
        if r is None:
            raise ImgfixError("cutout grabcut: rect=x,y,w,h around the subject is required")
        gm = np.zeros(doc.rgb.shape[:2], np.uint8)
        bgd, fgd = np.zeros((1, 65), np.float64), np.zeros((1, 65), np.float64)
        cv2.grabCut(cv2.cvtColor(doc.rgb, cv2.COLOR_RGB2BGR), gm, r, bgd, fgd,
                    P.int("iter", 6), cv2.GC_INIT_WITH_RECT)
        a = np.where((gm == cv2.GC_FGD) | (gm == cv2.GC_PR_FGD), 1.0, 0.0).astype(np.float32)
        a = cv2.morphologyEx(a, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
    if fe > 0:
        a = cv2.GaussianBlur(a, (0, 0), fe)
    bg = P.color("bg")
    if bg:
        doc.rgb = blend(np.broadcast_to(np.array(bg, np.uint8), doc.rgb.shape).copy(), doc.rgb, a)
        doc.alpha = None
    else:
        doc.alpha = to_u8(a)


@op("flatten", "drop transparency onto a colour: bg=#ffffff")
def op_flatten(doc, P):
    if doc.alpha is not None:
        bg = np.broadcast_to(np.array(P.color("bg", (255, 255, 255)), np.uint8), doc.rgb.shape)
        doc.rgb = blend(bg.copy(), doc.rgb, doc.alpha / 255.0)
        doc.alpha = None


@op("strip", "forget all metadata (EXIF, ICC, PNG text incl. AI prompts) on save")
def op_strip(doc, P):
    doc.info = {}


def run_ops(doc: Doc, ops, steps_dir=None, verbose=True) -> Doc:
    for i, (name, kv, pos) in enumerate(ops, 1):
        if name not in OPS:
            raise ImgfixError(f"unknown op '{name}' - see `imgfix.py ops`")
        P = Params(name, kv, pos, doc.w, doc.h)
        _fill_params_fix(P)
        fn = OPS[name][0]
        if verbose:
            desc = " ".join(f"{k}={'|'.join(v)}" for k, v in kv.items())
            print(f"[{i}/{len(ops)}] {name} {' '.join(pos)} {desc}".rstrip())
        fn(doc, P)
        P.check_unused()
        if steps_dir:
            p = Path(steps_dir) / f"step{i:02d}_{name}.png"
            save(doc, p)
            if verbose:
                print(f"  -> {p}")
    return doc


# --------------------------------------------------------------------------
# Inspection tools (images Claude can look at)
# --------------------------------------------------------------------------

def _label_font(size=14):
    try:
        return ImageFont.truetype(find_font(None), size)
    except Exception:
        return ImageFont.load_default()


def nice_step(span: float, target: int = 10) -> int:
    raw = span / target
    p = 10 ** math.floor(math.log10(max(raw, 1)))
    for k in (1, 2, 5, 10):
        if raw <= k * p:
            return int(k * p)
    return int(10 * p)


def draw_grid(img: Image.Image, x0: float, y0: float, scale: float, step: int,
              color=(255, 0, 255)) -> Image.Image:
    """img shows original region starting at (x0,y0), magnified by `scale`.
    Grid lines + labels are in ORIGINAL pixel coordinates."""
    img = img.convert("RGB")
    d = ImageDraw.Draw(img, "RGBA")
    W, H = img.size
    f = _label_font(max(11, min(16, int(W / 70))))
    gx = math.ceil(x0 / step) * step
    while (gx - x0) * scale < W:
        px = (gx - x0) * scale
        major = (gx // step) % 5 == 0
        d.line([(px, 0), (px, H)], fill=(*color, 200 if major else 110), width=1)
        lbl = str(int(gx))
        d.text((px + 2, 1), lbl, font=f, fill=(255, 255, 255, 255), stroke_width=2,
               stroke_fill=(0, 0, 0, 255))
        gx += step
    gy = math.ceil(y0 / step) * step
    while (gy - y0) * scale < H:
        py = (gy - y0) * scale
        major = (gy // step) % 5 == 0
        d.line([(0, py), (W, py)], fill=(*color, 200 if major else 110), width=1)
        d.text((2, py + 1), str(int(gy)), font=f, fill=(255, 255, 255, 255), stroke_width=2,
               stroke_fill=(0, 0, 0, 255))
        gy += step
    return img


def view(doc: Doc, region=None, max_side=1500, step=None, grid=True) -> Image.Image:
    x0, y0, w, h = region or (0, 0, doc.w, doc.h)
    crop = doc.pil().convert("RGB").crop((x0, y0, x0 + w, y0 + h))
    scale = max_side / max(w, h)
    if region is None:
        scale = min(scale, 1.0)
    nw, nh = max(1, round(w * scale)), max(1, round(h * scale))
    resample = Image.NEAREST if scale >= 3 else (Image.LANCZOS if scale < 1 else Image.BICUBIC)
    im = crop.resize((nw, nh), resample)
    if grid:
        im = draw_grid(im, x0, y0, nw / w, step or nice_step(max(w, h), 12))
    return im


def cmd_info(a):
    doc = load(a.image)
    im = Image.open(a.image)
    print(f"file      {a.image}  ({os.path.getsize(a.image) / 1024:.0f} KB, {im.format}, {im.mode})")
    print(f"size      {doc.w} x {doc.h}  (aspect {doc.w / doc.h:.4f})")
    print(f"alpha     {'yes' if doc.alpha is not None else 'no'}")
    print(f"icc       {'yes' if doc.info.get('icc_profile') else 'no'}   "
          f"exif {'yes' if doc.info.get('exif') else 'no'}   dpi {doc.info.get('dpi')}")
    raw = Path(a.image).read_bytes()
    marks = [m for m in (b"c2pa", b"jumb", b"jumd", b"SynthID", b"trainedAlgorithmicMedia",
                         b"openai", b"Midjourney", b"DALL", b"Firefly", b"Gemini", b"Imagen",
                         b"Stable Diffusion", b"ComfyUI", b"parameters") if m in raw]
    print(f"AI marks  {', '.join(m.decode() for m in marks) or 'none found in file bytes'}")
    for k, v in doc.info.get("text", {}).items():
        print(f"text[{k}] {v[:300]}{'...' if len(v) > 300 else ''}")
    f = doc.rgb.astype(np.float32)
    print(f"mean RGB  {f.reshape(-1, 3).mean(0).round(1).tolist()}  "
          f"lum min/median/max {np.percentile(luminance(f), [0.5, 50, 99.5]).round(0).tolist()}")
    need_cv2()
    full = np.ones(doc.rgb.shape[:2], np.uint8)
    print(f"noise     sigma~{noise_sigma(doc.rgb, full):.2f} (high-pass, 0-255)")
    small = cv2.resize(doc.rgb, (64, 64), interpolation=cv2.INTER_AREA).reshape(-1, 3)
    _, lab, cen = cv2.kmeans(small.astype(np.float32), 6, None,
                             (cv2.TERM_CRITERIA_EPS + cv2.TERM_CRITERIA_MAX_ITER, 20, 1), 3,
                             cv2.KMEANS_PP_CENTERS)
    cnt = np.bincount(lab.ravel(), minlength=6)
    order = np.argsort(-cnt)
    print("palette   " + "  ".join(f"#{int(cen[i][0]):02x}{int(cen[i][1]):02x}{int(cen[i][2]):02x}"
                                   f"({cnt[i] * 100 // len(lab)}%)" for i in order))


def cmd_grid(a):
    doc = load(a.image)
    im = view(doc, None, a.max, a.step)
    im.save(a.out)
    print(f"{a.out}: {im.width}x{im.height} preview of {doc.w}x{doc.h}; "
          f"labels are original pixel coordinates")


def _region_arg(s, doc):
    P = Params("region", {}, [], doc.w, doc.h)
    x, y, w, h = P.nums(s, 4)
    return clamp_rect(x, y, w, h, doc.w, doc.h)


def cmd_zoom(a):
    doc = load(a.image)
    r = _region_arg(a.region, doc)
    im = view(doc, r, a.max, a.step)
    im.save(a.out)
    print(f"{a.out}: region {r} magnified {im.width / r[2]:.2f}x")


def cmd_corners(a):
    """2x2 sheet of the four corners - where AI watermarks/logos usually sit."""
    doc = load(a.image)
    s = int(min(doc.w, doc.h) * a.frac)
    tiles = []
    for (x, y) in ((0, 0), (doc.w - s, 0), (0, doc.h - s), (doc.w - s, doc.h - s)):
        tiles.append(view(doc, (x, y, s, s), a.max // 2, None))
    tw, th = tiles[0].size
    sheet = Image.new("RGB", (tw * 2 + 6, th * 2 + 6), (40, 40, 40))
    for i, t in enumerate(tiles):
        sheet.paste(t, ((i % 2) * (tw + 6), (i // 2) * (th + 6)))
    sheet.save(a.out)
    print(f"{a.out}: corners TL TR / BL BR, {s}px each")


def cmd_pick(a):
    doc = load(a.image)
    P = Params("pick", {}, [], doc.w, doc.h)
    for s in a.points:
        x, y = (int(v) for v in P.nums(s, 2))
        r = a.r
        patch = doc.rgb[max(0, y - r):y + r + 1, max(0, x - r):x + r + 1].reshape(-1, 3)
        c = patch.astype(np.float32).mean(0)
        hx = "#%02x%02x%02x" % tuple(int(round(v)) for v in c)
        hsv = [int(v) for v in cv2.cvtColor(np.uint8([[c.round()]]), cv2.COLOR_RGB2HSV_FULL)[0, 0]]
        print(f"{x},{y}  {hx}  rgb={tuple(int(round(v)) for v in c)}  "
              f"hsv=({hsv[0] * 360 // 255},{hsv[1] * 100 // 255}%,{hsv[2] * 100 // 255}%)  "
              f"std={patch.std(0).mean():.1f}")


def cmd_sel(a):
    """Preview a selection: red overlay + bbox, so the mask can be checked first."""
    doc = load(a.image)
    name, kv, pos = parse_op("sel " + a.spec)
    P = Params("sel", kv, pos, doc.w, doc.h)
    m = selection(doc, P, default_all=False)
    P.check_unused()
    bb = mask_bbox(m)
    if bb is None:
        print("selection is EMPTY")
        return
    print(f"selection bbox {bb}  area {int((m > 0.5).sum())} px "
          f"({(m > 0.5).mean() * 100:.2f}% of image)")
    over = doc.rgb.astype(np.float32)
    red = np.array([255, 0, 60], np.float32)
    over = over * (1 - 0.55 * m[..., None]) + red * 0.55 * m[..., None]
    edge = cv2.Canny((m > 0.5).astype(np.uint8) * 255, 50, 150) > 0
    over[edge] = (255, 255, 0)
    vd = Doc(np.clip(over, 0, 255).astype(np.uint8))
    if a.full:
        region = None
    else:
        x, y, w, h = bb
        pad = int(max(w, h) * 0.6) + 20
        region = clamp_rect(x - pad, y - pad, w + 2 * pad, h + 2 * pad, doc.w, doc.h)
    view(vd, region, a.max, None).save(a.out)
    if a.mask:
        Image.fromarray(to_u8(m)).save(a.mask)
        print(f"mask saved -> {a.mask}")
    print(f"{a.out}: selection preview")


def cmd_find(a):
    """Locate regions of a colour -> bounding boxes (for logos, stray blobs, text)."""
    doc = load(a.image)
    m = color_mask(doc.rgb, parse_color(a.color), a.tol)
    m = cv2.morphologyEx(m, cv2.MORPH_CLOSE, np.ones((a.join, a.join), np.uint8))
    n, _, stats, _ = cv2.connectedComponentsWithStats((m > 127).astype(np.uint8))
    rows = sorted((stats[i] for i in range(1, n) if stats[i][4] >= a.min_area),
                  key=lambda s: -s[4])
    for x, y, w, h, area in rows[:a.limit]:
        print(f"rect={x},{y},{w},{h}  area={area}")
    if not rows:
        print("no matching regions")


def cmd_compare(a):
    A, B = load(a.a), load(a.b)
    if (A.w, A.h) != (B.w, B.h):
        B = Doc(cv2.resize(B.rgb, (A.w, A.h), interpolation=cv2.INTER_AREA))
        print("note: sizes differ, B resized for comparison")
    d = np.abs(A.rgb.astype(np.int16) - B.rgb.astype(np.int16)).max(-1)
    mse = ((A.rgb.astype(np.float32) - B.rgb.astype(np.float32)) ** 2).mean()
    psnr = 99.0 if mse == 0 else 10 * math.log10(255 ** 2 / mse)
    changed = d > a.thr
    bb = mask_bbox(changed.astype(np.float32))
    print(f"changed px {int(changed.sum())} ({changed.mean() * 100:.2f}%)  PSNR {psnr:.1f} dB  "
          f"bbox {bb}")
    region = None
    if a.region:
        region = _region_arg(a.region, A)
    elif a.auto and bb:
        x, y, w, h = bb
        pad = int(max(w, h) * 0.4) + 16
        region = clamp_rect(x - pad, y - pad, w + 2 * pad, h + 2 * pad, A.w, A.h)
    heat = np.clip(d.astype(np.float32) * 4, 0, 255).astype(np.uint8)
    heat = cv2.applyColorMap(heat, cv2.COLORMAP_INFERNO)[..., ::-1]
    rw, rh = (region[2], region[3]) if region else (A.w, A.h)
    side = a.max if rw > rh * 1.3 else a.max // 2
    tiles = [view(A, region, side, None), view(B, region, side, None),
             view(Doc(heat), region, side, None)]
    if not a.diff:
        tiles = tiles[:2]
    tw, th = tiles[0].size
    n = len(tiles)
    vertical = tw > th * 1.3  # wide crops read better stacked
    if vertical:
        sheet = Image.new("RGB", (tw, n * (th + 22)), (30, 30, 30))
        pos = [(0, i * (th + 22)) for i in range(n)]
    else:
        sheet = Image.new("RGB", (n * tw + 6 * (n - 1), th + 22), (30, 30, 30))
        pos = [(i * (tw + 6), 0) for i in range(n)]
    f = _label_font(14)
    dd = ImageDraw.Draw(sheet)
    for t, (px, py), lbl in zip(tiles, pos, ("before", "after", "diff x4")):
        sheet.paste(t, (px, py + 22))
        dd.text((px + 4, py + 3), lbl, font=f, fill=(230, 230, 230))
    sheet.save(a.out)
    print(f"{a.out}: side-by-side")


def cmd_apply(a):
    doc = load(a.image)
    ops = []
    for r in a.recipe or []:
        ops += read_recipe(r)
    for e in a.e or []:
        ops.append(parse_op(e))
    if not ops:
        raise ImgfixError("no operations: use -e \"op key=val\" or -r recipe.txt")
    out = Path(a.out)
    if out.resolve() == Path(a.image).resolve() and not a.inplace:
        raise ImgfixError("refusing to overwrite the input; pass --inplace if intended")
    run_ops(doc, ops, a.steps)
    save(doc, out, a.quality, a.strip)
    print(f"saved {out} ({doc.w}x{doc.h})")
    if a.log:
        with open(a.log, "a", encoding="utf-8") as f:
            for name, kv, pos in ops:
                parts = [name] + [shlex.quote(p) for p in pos] + \
                        [f"{k}={shlex.quote(v)}" for k, vs in kv.items() for v in vs]
                f.write(" ".join(parts) + "\n")
    if a.preview:
        cmd_compare(argparse.Namespace(a=a.image, b=str(out), out=a.preview, thr=6, region=None,
                                       auto=True, max=2400, diff=True))


def cmd_ops(a):
    print(SEL_HELP + "\n")
    for name, (_, h) in OPS.items():
        print(f"{name:12s} {h}")


def cmd_fonts(a):
    for f in all_fonts():
        if not a.filter or a.filter.lower() in f.name.lower():
            print(f"{f.stem:40s} {f}")


def cmd_setup(a):
    ok = True
    print(f"imgfix {VERSION}  python {sys.version.split()[0]}")
    print(f"numpy {np.__version__}  pillow {Image.__version__}  "
          f"opencv {cv2.__version__ if cv2 else 'MISSING'}")
    if cv2 is None:
        ok = False
    try:
        import onnxruntime
        print(f"onnxruntime {onnxruntime.__version__} (fast LaMa)")
    except ImportError:
        print("onnxruntime not installed (LaMa will run through OpenCV DNN - slower but fine)")
    try:
        import rembg  # noqa: F401
        print("rembg available (cutout method=rembg)")
    except ImportError:
        print("rembg not installed (optional: pip install rembg)")
    if lama_available():
        print(f"LaMa model OK: {LAMA_PATH}")
    elif a.no_download:
        print("LaMa model missing (inpaint falls back to telea)")
    else:
        try:
            download_lama()
            print(f"LaMa model OK: {LAMA_PATH}")
        except Exception as e:  # network may be blocked
            ok = False
            print(f"LaMa download failed: {e}\n  manual: save {LAMA_URL}\n  as {LAMA_PATH}")
    print(f"fonts found: {len(all_fonts())}")
    print("READY" if ok else "NOT READY")


def main(argv=None):
    p = argparse.ArgumentParser(prog="imgfix", description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("setup", help="check dependencies, download LaMa model")
    s.add_argument("--no-download", action="store_true")
    s.set_defaults(fn=cmd_setup)

    s = sub.add_parser("info", help="size, metadata, AI marks, noise, palette")
    s.add_argument("image")
    s.set_defaults(fn=cmd_info)

    s = sub.add_parser("grid", help="whole image with a labelled coordinate grid")
    s.add_argument("image")
    s.add_argument("-o", "--out", required=True)
    s.add_argument("--step", type=int)
    s.add_argument("--max", type=int, default=1500)
    s.set_defaults(fn=cmd_grid)

    s = sub.add_parser("zoom", help="magnified region x,y,w,h with grid")
    s.add_argument("image")
    s.add_argument("region")
    s.add_argument("-o", "--out", required=True)
    s.add_argument("--step", type=int)
    s.add_argument("--max", type=int, default=1200)
    s.set_defaults(fn=cmd_zoom)

    s = sub.add_parser("corners", help="2x2 sheet of the corners (watermark hunting)")
    s.add_argument("image")
    s.add_argument("-o", "--out", required=True)
    s.add_argument("--frac", type=float, default=0.18)
    s.add_argument("--max", type=int, default=1400)
    s.set_defaults(fn=cmd_corners)

    s = sub.add_parser("pick", help="sample colours at x,y points")
    s.add_argument("image")
    s.add_argument("points", nargs="+")
    s.add_argument("-r", type=int, default=2, help="sampling radius")
    s.set_defaults(fn=cmd_pick)

    s = sub.add_parser("sel", help="preview a selection spec as a red overlay")
    s.add_argument("image")
    s.add_argument("spec", help='e.g. "rect=10,10,50,20 grow=3"')
    s.add_argument("-o", "--out", required=True)
    s.add_argument("--mask", help="also save the mask PNG")
    s.add_argument("--full", action="store_true", help="show whole image, not a zoom")
    s.add_argument("--max", type=int, default=1200)
    s.set_defaults(fn=cmd_sel)

    s = sub.add_parser("find", help="bounding boxes of areas matching a colour")
    s.add_argument("image")
    s.add_argument("color")
    s.add_argument("--tol", type=float, default=25)
    s.add_argument("--min-area", type=int, default=20)
    s.add_argument("--join", type=int, default=5, help="close gaps of N px")
    s.add_argument("--limit", type=int, default=20)
    s.set_defaults(fn=cmd_find)

    s = sub.add_parser("compare", help="before/after/diff sheet + change stats")
    s.add_argument("a")
    s.add_argument("b")
    s.add_argument("-o", "--out", required=True)
    s.add_argument("--region")
    s.add_argument("--auto", action="store_true", help="zoom to the changed area")
    s.add_argument("--thr", type=int, default=6)
    s.add_argument("--no-diff", dest="diff", action="store_false")
    s.add_argument("--max", type=int, default=2400)
    s.set_defaults(fn=cmd_compare)

    s = sub.add_parser("apply", help="run edit ops and save")
    s.add_argument("image")
    s.add_argument("out")
    s.add_argument("-e", action="append", help='one op, e.g. -e "inpaint rect=1,2,3,4"')
    s.add_argument("-r", "--recipe", action="append", help="recipe file (.txt lines or .json)")
    s.add_argument("--steps", help="save every intermediate step into this folder")
    s.add_argument("--preview", help="also write a before/after/diff sheet")
    s.add_argument("--log", help="append the ops to this recipe file (history)")
    s.add_argument("-q", "--quality", type=int, default=95)
    s.add_argument("--strip", action="store_true", help="drop all metadata")
    s.add_argument("--inplace", action="store_true")
    s.set_defaults(fn=cmd_apply)

    s = sub.add_parser("ops", help="list edit operations")
    s.set_defaults(fn=cmd_ops)

    s = sub.add_parser("fonts", help="list installed fonts")
    s.add_argument("filter", nargs="?")
    s.set_defaults(fn=cmd_fonts)

    a = p.parse_args(argv)
    if hasattr(sys.stdout, "reconfigure"):  # Windows consoles default to cp125x
        sys.stdout.reconfigure(errors="replace")
    try:
        a.fn(a)
    except ImgfixError as e:
        print(f"error: {e}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
