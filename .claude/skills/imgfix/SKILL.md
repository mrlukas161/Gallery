---
name: imgfix
description: Precise retouching of images - especially AI-generated ones - without Photoshop. Use whenever the user wants to edit, fix, clean up or touch up an image (PNG/JPG/WEBP): remove a watermark, logo, signature or AI sparkle, erase objects or artifacts (extra fingers, stray blobs, garbled text), replace or fix text, retouch, clone/heal, change colours, white balance, exposure/contrast/curves, sharpen, denoise, crop, resize/upscale, straighten, extend the canvas (outpaint), remove the background, or strip AI metadata. Prefer this over driving Photoshop/GIMP through computer use - it is faster, exact to the pixel and reproducible. Triggers also in Slovak - "uprav obrázok", "odstráň vodoznak", "vymaž z fotky", "oprav text na obrázku", "retuš", "orež", "zväčši obrázok", "odstráň pozadie".
---

# imgfix - image retouching by command

`imgfix.py` (next to this file) is a single-file Python CLI. Every edit is an
op with exact pixel coordinates, every step can be rendered to an image you
look at with Read, and the edit is a replayable recipe. Never overwrite the
user's original: write to a new file (e.g. `name_edit.png`).

Run it as `python <skill-dir>/imgfix.py ...` (on Windows `py` may be needed
instead of `python`). First use on a machine: `python imgfix.py setup`
(checks numpy/Pillow/OpenCV, downloads the 92 MB LaMa inpainting model to
`~/.cache/imgfix`). If deps are missing: `pip install -r requirements.txt`.
Keep preview images in a scratch/temp folder, not next to the user's files.

## Workflow (look -> locate -> select -> apply -> verify)

1. **Look**: `info img` (size, AI metadata, noise, palette), then
   `grid img -o g.png` and Read it. Labels are ORIGINAL pixel coordinates even
   when the preview is scaled. `corners img -o c.png` zooms the 4 corners -
   where AI watermarks (Gemini sparkle, "Imagine with Meta", DALL-E colour bar,
   stock logos) usually sit.
2. **Locate precisely**: `zoom img x,y,w,h -o z.png` magnifies a region with
   a fine grid. Zoom until you can read coordinates to a few pixels. Use
   `pick img x,y` for exact colours, `find img '#hex' --tol 20` for boxes of a
   colour.
3. **Select & check**: `sel img "rect=... grow=3" -o s.png` draws the mask in
   red with a yellow outline. Always check the mask before a destructive op.
   Tighter masks = better inpainting.
4. **Apply**: `apply img out.png -e "op ..." -e "op ..." --preview cmp.png --log recipe.txt`
   (`--steps dir` saves every step). Ops run in order on one image.
5. **Verify**: Read `cmp.png` (before / after / diff x4, auto-zoomed to the
   change), or `compare img out.png --region x,y,w,h -o z.png` to inspect up
   close. Iterate by editing the recipe and re-running from the ORIGINAL
   (`apply img out.png -r recipe.txt`) - never stack lossy re-saves of JPEGs.

## Selections (shared by every op marked [sel])

`rect=x,y,w,h` `ellipse=cx,cy,rx,ry` `poly=x1,y1,x2,y2,...`
`brush=x1,y1,x2,y2,... width=12` (stroke along a path - thin lines, cracks)
`color=#hex tol=30` (similar colour) `wand=x,y tol=30` (contiguous region)
`bright=20` / `dark=20` (pixels lighter/darker than their local background ->
text, watermarks, specks, dust; always pair with `within=x,y,w,h`)
`mask=file.png` `sub_rect= sub_ellipse= sub_poly=` (subtract)
modifiers: `grow=px` (negative shrinks) `feather=px` `invert=1`.
Keys can repeat (union). Coordinates accept `%`: `rect=88%,90%,12%,10%`.

## Ops cheat-sheet (`imgfix.py ops` prints all keys)

| Task | Op |
|---|---|
| remove watermark/object/artifact | `inpaint` [sel] (LaMa neural fill, grow=4 default, grain matched automatically) |
| semi-transparent text watermark | `inpaint bright=25 within=<box>` (or `dark=` for dark text) |
| repeating texture / exact copy | `clone from=dx,dy` [sel] feather=4 |
| blemish, match light | `heal from=dx,dy` [sel] (Poisson blend) |
| flat area | `fill mode=avg` / `fill color=#hex` / `fill mode=blur` [sel] |
| fix garbled AI text | `inpaint rect=<text box>` then `text "Correct" box=x,y,w,h font=... color=#hex` (auto-fits) |
| tone & colour | `adjust exposure= contrast= shadows= highlights= saturation= vibrance= temp= tint= hue=` [sel] |
| precise tone | `curves pts=0,0;64,55;192,205;255,255 ch=rgb` , `levels in=10,245 gamma=1.1`, `autolevels` |
| colour cast | `wb ref=x,y` (pixel that should be neutral) or `wb method=grayworld` |
| recolour object | `recolor color=#src tol=25 within=<box> to=#target` or `recolor hue=30` [sel] |
| detail | `sharpen amount=80 radius=1.5`, `denoise strength=6`, `blur type=bilateral` (skin), `grain amount=3` |
| geometry | `crop rect=` / `crop aspect=16:9`, `resize w=`, `upscale scale=2`, `rotate deg=-1.5` (straighten+crop), `flip`, `perspective pts=TL,TR,BR,BL` |
| bigger canvas | `extend right=200 bottom=10% fill=lama` (outpaint) |
| background | `cutout rect=<subject box>` (GrabCut -> PNG alpha) or `method=rembg`, `bg=#fff`; `flatten` |
| overlays | `text`, `line pts=... width= color=`, `paste file= x= y= w= blend=` |
| privacy | `strip` op or `apply --strip` (drops EXIF/ICC/PNG text incl. AI prompts) |

## Tips for good results

- Inpaint small, separate regions in separate `inpaint` ops or let one op
  handle several blobs (they are processed per region at full resolution of
  a 512 px model crop). For huge areas (>~800 px) split into parts or expect
  softer detail; `context=1.5` gives the model more surroundings.
- After inpainting a very detailed/noisy area, `grain=auto` (default) adds
  matching noise; use `grain=0` for clean graphics/flat illustrations, where
  `method=telea` is often cleaner than LaMa for thin lines on flat colour.
- For AI hands/faces: you can fix extra fingers or blobs by inpainting them
  away, and fix asymmetric details with `clone`/`heal` from the good side.
  You cannot invent new complex structure (a whole new hand) - say so and
  suggest regenerating that part with the original AI tool.
- Match text to the scene: `pick` the original text colour, choose a font
  with `fonts <filter>`, use `box=` so the size fits, add `opacity=0.9`,
  `shadow=2,2` or a light `blur radius=0.6 rect=<box>` so it does not look pasted.
- JPEG output: `-q 95`. Keep PNG for anything with transparency.
- `apply` refuses to overwrite its input unless `--inplace`.
- Recipe files: one op per line, `#` comments; JSON list of
  `{"op": "...", "key": value}` also works (`"_": "text"` for positional).
