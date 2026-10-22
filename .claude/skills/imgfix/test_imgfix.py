"""Smoke tests: python test_imgfix.py  (synthetic images, no network needed)."""
import sys
import tempfile
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).parent))
import imgfix  # noqa: E402


def scene(w=320, h=240):
    yy, xx = np.mgrid[0:h, 0:w]
    rgb = np.dstack([xx * 255 // w, yy * 255 // h, np.full_like(xx, 120)]).astype(np.uint8)
    rng = np.random.default_rng(0)
    return np.clip(rgb + rng.normal(0, 3, rgb.shape), 0, 255).astype(np.uint8)


def run(doc, *ops):
    return imgfix.run_ops(doc, [imgfix.parse_op(o) for o in ops], verbose=False)


def test_inpaint_removes_blob():
    rgb = scene()
    clean = rgb.copy()
    rgb[100:130, 150:190] = (255, 0, 255)
    doc = run(imgfix.Doc(rgb), "inpaint rect=150,100,40,30 method=telea grain=0")
    err = np.abs(doc.rgb[100:130, 150:190].astype(int) - clean[100:130, 150:190]).mean()
    assert err < 20, err
    if imgfix.lama_available():
        doc = run(imgfix.Doc(rgb), "inpaint rect=150,100,40,30")
        err = np.abs(doc.rgb[100:130, 150:190].astype(int) - clean[100:130, 150:190]).mean()
        assert err < 20, err


def test_outside_selection_untouched():
    rgb = scene()
    doc = run(imgfix.Doc(rgb.copy()), "adjust exposure=1 rect=10,10,50,50")
    assert np.array_equal(doc.rgb[100:, 100:], rgb[100:, 100:])
    assert doc.rgb[30, 30].mean() > rgb[30, 30].mean()


def test_selection_keys():
    doc = imgfix.Doc(scene())
    P = imgfix.Params("t", imgfix.parse_op("x rect=0,0,10,10 rect=50%,50%,10,10 sub_rect=0,0,5,5")[1],
                      [], doc.w, doc.h)
    m = imgfix.selection(doc, P)
    assert m[2, 2] == 0 and m[7, 7] == 1 and m[125, 165] == 1 and m[200, 10] == 0


def test_bright_text_mask():
    rgb = scene()
    doc = run(imgfix.Doc(rgb), "text 'MARK' x=200 y=200 size=24 color=#ffffff")
    P = imgfix.Params("t", {"bright": ["30"], "within": ["190,190,130,50"]}, [], doc.w, doc.h)
    m = imgfix.selection(doc, P)
    assert m.sum() > 50 and m[:150].sum() == 0


def test_geometry_and_text():
    doc = run(imgfix.Doc(scene()), "crop rect=10,10,200,100", "resize w=100", "rotate deg=90",
              "rotate deg=5", "extend right=10 fill=edge", "text 'Hi' box=0,0,50,20",
              "curves pts=0,0;128,140;255,255", "wb method=grayworld", "sharpen", "denoise")
    assert (doc.w, doc.h) == (60, 100), (doc.w, doc.h)


def test_save_roundtrip_and_cutout():
    with tempfile.TemporaryDirectory() as d:
        doc = run(imgfix.Doc(scene()), "cutout rect=40,40,200,150")
        p = Path(d) / "o.png"
        imgfix.save(doc, p)
        assert Image.open(p).mode == "RGBA"
        assert imgfix.main(["apply", str(p), str(p), "-e", "flatten"]) == 2  # refuses overwrite


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_"):
            fn()
            print("ok ", name)
    print("all passed")
