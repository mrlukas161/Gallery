// Testy čistých funkcií webu „Galéria+ Sync“ (app/src/main/assets/gallery.html, blok GCORE).
// Spustenie: node --test app/src/test/js/
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const html = fs.readFileSync(path.join(__dirname, "../../main/assets/gallery.html"), "utf8");
const code = html.slice(html.indexOf("/*GCORE-BEGIN*/"), html.indexOf("/*GCORE-END*/"));
const ctx = { module: { exports: {} } };
vm.runInNewContext(code + "\nmodule.exports = GCore;", ctx);
const C = ctx.module.exports;

// objekty z vm kontextu majú iné prototypy -> porovnávať ako obyčajné JSON hodnoty
const plain = (x) => JSON.parse(JSON.stringify(x));
const near = (a, b, eps = 1e-9) => assert.ok(Math.abs(a - b) < eps, `${a} ≉ ${b}`);

test("slovenské tvary: 1 fotka, 2 fotky, 5 fotiek", () => {
  assert.equal(C.photos(1), "1 fotka");
  assert.equal(C.photos(2), "2 fotky");
  assert.equal(C.photos(4), "4 fotky");
  assert.equal(C.photos(5), "5 fotiek");
  assert.equal(C.photos(0), "0 fotiek");
  assert.equal(C.photos(22), "22 fotiek");
  assert.equal(C.groupsTxt(3), "3 skupiny");
  assert.equal(C.files(1), "1 súbor");
});

test("veľkosti s desatinnou čiarkou", () => {
  assert.equal(C.fmtBytes(0), "0 B");
  assert.equal(C.fmtBytes(512), "512 B");
  assert.equal(C.fmtBytes(1536), "1,5 kB");
  assert.equal(C.fmtBytes(3.4 * 1024 * 1024), "3,4 MB");
  assert.equal(C.fmtBytes(25 * 1024 * 1024 * 1024), "25 GB");
});

test("rozmery podľa otočenia a megapixely", () => {
  assert.deepEqual({ ...C.dispDims({ w: 4000, h: 3000, orient: 90 }) }, { w: 3000, h: 4000 });
  assert.deepEqual({ ...C.dispDims({ w: 4000, h: 3000, orient: 180 }) }, { w: 4000, h: 3000 });
  assert.equal(C.fmtDims(4000, 3000), "4000×3000 (12 MP)");
  assert.equal(C.fmtDims(1920, 1080), "1920×1080 (2,1 MP)");
  assert.equal(C.fmtDims(0, 0), "");
  assert.equal(C.fmtExp(1 / 125), "1/125 s");
  assert.equal(C.fmtExp(2), "2 s");
  assert.equal(C.fmtDur(75000), "1:15");
});

test("relatívny zoom: rel -> abs -> rel je identita", () => {
  const rel = { cx: 0.3, cy: 0.7, z: 2.5 };
  const fit = C.fitScale(800, 600, 4000, 3000);
  near(fit, 0.2);
  const a = C.absFromRel(rel, 4000, 3000, fit);
  const back = C.relFromAbs(a, 4000, 3000, fit);
  near(back.cx, 0.3); near(back.cy, 0.7); near(back.z, 2.5);
});

test("rovnaký rel ukáže rovnaké miesto pri rôznom rozlíšení", () => {
  // tá istá scéna, raz 4000×3000, raz 2000×1500: bod v 30 % šírky musí byť na obrazovke na rovnakom mieste
  const rel = { cx: 0.4, cy: 0.5, z: 3 };
  const pos = (nw, nh) => {
    const fit = C.fitScale(800, 600, nw, nh);
    const a = C.absFromRel(rel, nw, nh, fit);
    return (0.3 - 0.5) * nw * a.scale + a.x; // x bodu na 30 % šírky voči stredu plochy
  };
  near(pos(4000, 3000), pos(2000, 1500), 1e-6);
});

test("zoom okolo kurzora drží bod pod kurzorom", () => {
  const nw = 4000, nh = 3000, fit = C.fitScale(800, 600, nw, nh);
  let rel = { cx: 0.5, cy: 0.5, z: 2 };
  const px = 150, py = -80;
  const imgPoint = (r) => { const a = C.absFromRel(r, nw, nh, fit); return [(px - a.x) / a.scale, (py - a.y) / a.scale]; };
  const before = imgPoint(rel);
  rel = C.zoomAt(rel, nw, nh, fit, px, py, 3.2);
  const after = imgPoint(rel);
  near(before[0], after[0], 1e-6); near(before[1], after[1], 1e-6);
  near(rel.z, 3.2);
});

test("pri prispôsobení je fotka vycentrovaná a stred neodíde mimo fotky", () => {
  assert.deepEqual({ ...C.clampRel({ cx: 0.9, cy: 0.1, z: 1 }) }, { cx: 0.5, cy: 0.5, z: 1 });
  const r = C.clampRel({ cx: 1.4, cy: -0.2, z: 4 });
  assert.equal(r.cx, 1); assert.equal(r.cy, 0);
  assert.equal(C.clampRel({ cx: 0.5, cy: 0.5, z: 100 }, 0.5, 8).z, 8);
  const p = C.panBy({ cx: 0.5, cy: 0.5, z: 1 }, 4000, 3000, 0.2, 300, 0);
  assert.equal(p.cx, 0.5); // pri z=1 sa neposúva
});

test("1:1 a kedy načítať originál", () => {
  near(C.oneToOneZ(800, 600, 4000, 3000), 5);
  // náhľad 2560×1920 zobrazený nad 100 % a originál 4000×3000 je väčší -> treba
  const fit = C.fitScale(800, 600, 2560, 1920);
  assert.equal(C.wantsFull({ cx: 0.5, cy: 0.5, z: 1 }, fit, 2560, 1920, 4000, 3000), false);
  assert.equal(C.wantsFull({ cx: 0.5, cy: 0.5, z: 4 }, fit, 2560, 1920, 4000, 3000), true);
  // náhľad už má plné rozlíšenie -> netreba
  assert.equal(C.wantsFull({ cx: 0.5, cy: 0.5, z: 8 }, C.fitScale(800, 600, 1600, 1200), 1600, 1200, 1600, 1200), false);
});

test("zoznam: poradie podľa dátumu (fotky aj videá) a zmeny od generácie", () => {
  const items = [{ id: 1, taken: 100 }, { id: 2, taken: 300 }, { id: 3, taken: 200 }];
  const out = C.applyDelta(items, [{ id: 4, taken: 250 }, { id: 1, taken: 400 }], [2]);
  assert.deepEqual(plain(out.map((x) => x.id)), [1, 4, 3]);
});

test("filter zoznamu", () => {
  const items = [
    { id: 1, name: "a.jpg", bucket: "Camera", mime: "image/jpeg" },
    { id: 2, name: "b.mp4", bucket: "Camera", mime: "video/mp4" },
    { id: 3, name: "c.jpg", bucket: "WhatsApp", mime: "image/jpeg" },
  ];
  const marked = new Set([3]), synced = new Map([[1, 5]]);
  assert.deepEqual(C.filterItems(items, "", "marked", marked, synced).map((x) => x.id), [3]);
  assert.deepEqual(C.filterItems(items, "", "new", marked, synced).map((x) => x.id), [2, 3]);
  assert.deepEqual(C.filterItems(items, "", "videos", marked, synced).map((x) => x.id), [2]);
  assert.deepEqual(C.filterItems(items, "whats", "all", marked, synced).map((x) => x.id), [3]);
});

test("formáty pre prehliadač a názov zmenšenej kópie", () => {
  assert.equal(C.browserNative("image/jpeg"), true);
  assert.equal(C.browserNative("image/heic"), false);
  assert.equal(C.browserNative("image/x-adobe-dng"), false);
  assert.equal(C.dlName("IMG_1.HEIC", true), "IMG_1.jpg");
  assert.equal(C.dlName("IMG_1.HEIC", false), "IMG_1.HEIC");
});

const groups = [
  { key: "a1", kind: "burst", ids: [1, 2, 3], bucket: "Camera", to: 300, reclaim: 10 },
  { key: "b2", kind: "exact", ids: [4, 5], bucket: "Download", to: 500, reclaim: 50 },
  { key: "c3", kind: "similar", ids: [6, 7, 8, 9], bucket: "Camera", to: 100, reclaim: 30 },
];

test("triedenie skupín", () => {
  assert.deepEqual(C.sortGroups(groups, "date").map((g) => g.key), ["b2", "a1", "c3"]);
  assert.deepEqual(C.sortGroups(groups, "count").map((g) => g.key), ["c3", "a1", "b2"]);
  assert.deepEqual(C.sortGroups(groups, "place").map((g) => g.key), ["a1", "c3", "b2"]);
  assert.deepEqual(C.sortGroups(groups, "reclaim").map((g) => g.key), ["b2", "c3", "a1"]);
  assert.equal(groups[0].key, "a1"); // pôvodné pole sa nemení
});

test("stav skupín: filter, ďalšia nespracovaná, priebeh", () => {
  const states = { a1: { s: "done", t: 1 }, c3: { s: "skip", t: 2 } };
  const marked = new Set([2, 3]);
  const f = (state, kind, bucket) => C.filterGroups(groups, { state, kind: kind || "all", bucket: bucket || "" }, states, marked).map((g) => g.key);
  assert.deepEqual(f("open"), ["b2"]);
  assert.deepEqual(f("done"), ["a1"]);
  assert.deepEqual(f("skip"), ["c3"]);
  assert.deepEqual(f("marked"), ["a1"]);
  assert.deepEqual(f("all", "similar"), ["c3"]);
  assert.deepEqual(f("all", "all", "Camera"), ["a1", "c3"]);
  assert.equal(C.nextOpen(groups, states, 1), 1); // jediná nespracovaná je na indexe 1 (kruhovo)
  assert.equal(C.nextOpen(groups, { a1: {s: "done"}, b2: {s: "done"}, c3: {s: "skip"} }, 0), -1);
  const p = C.progress(groups, states, marked, (id) => id * 100);
  assert.deepEqual({ ...p }, { done: 2, total: 3, marked: 2, bytes: 500 });
});

test("ponechať len túto odznačí ponechanú fotku", () => {
  const plan = C.keepPlan([1, 2, 3], 2, new Set([2, 3]));
  assert.deepEqual([...plan.mark], [1]);
  assert.deepEqual([...plan.unmark], [2]);
});

test("undo vráti predošlé značky", () => {
  const u = new C.Undo(2);
  u.push({ marks: { 1: false, 2: true } });
  u.push({ marks: { 3: false } });
  u.push({ marks: { 4: true } });
  assert.equal(u.size(), 2);
  const plan = C.revertMarks(u.pop().marks);
  assert.deepEqual([...plan.on], [4]);
  assert.deepEqual([...plan.off], []);
  assert.deepEqual(plain(C.revertMarks({ 1: false, 2: true })), { on: [2], off: [1] });
});

test("EXIF: formátovanie a rozdiely medzi panelmi", () => {
  const a = { make: "Google", model: "Pixel 8", fnum: 1.68, exp: 0.008, iso: 50, focal: 6.9, focal35: 25, w: 4080, h: 3072, gps: true, format: "JPEG" };
  const b = { ...a, iso: 400, gps: false };
  const rows = C.metaRows(a);
  const v = (k) => rows.find((r) => r.k === k).v;
  assert.equal(v("camera"), "Google Pixel 8");
  assert.equal(v("fnum"), "f/1,7");
  assert.equal(v("exp"), "1/125 s");
  assert.equal(v("focal"), "6,9 mm (25 mm)");
  assert.equal(v("gps"), "áno");
  const d = C.metaDiff([a, b]);
  assert.deepEqual(Object.keys(d).sort(), ["gps", "iso"]);
  assert.deepEqual(Object.keys(C.metaDiff([a])), []);
});

// ---- kvalita z mobilu a hromadné „Ponechať najlepšiu" (porovnavac-W2) ----

test("zdôvodnenie skóre ako v mobile", () => {
  assert.equal(C.reasonText({ code: "sharper", value: 18 }), "ostrejšia o 18 %");
  assert.equal(C.reasonText({ code: "all_eyes_open", value: 3 }), "všetci 3 majú otvorené oči");
  assert.equal(C.reasonText({ code: "all_eyes_open", value: 1 }), "1 tvár, oči otvorené");
  assert.equal(C.reasonText({ code: "all_eyes_open", value: 6 }), "všetkých 6 má otvorené oči");
  assert.equal(C.reasonText({ code: "eyes_closed", value: 2 }), "2 majú zavreté oči");
  assert.equal(C.reasonText({ code: "fewer_faces", value: 2, total: 3 }), "chýba tvár (2 z 3)");
  assert.equal(C.reasonText({ code: "higher_resolution", value: 122 }), "vyššie rozlíšenie (12,2 Mpx)");
  assert.equal(C.reasonText({ code: "smiles", value: 5 }), "5 úsmevov");
  assert.equal(C.reasonText({ code: "neznamy" }), "");
});

test("riadok skóre: text zo servera má prednosť, inak z dôvodov", () => {
  assert.equal(C.scoreLine({ rank: 0, percent: 92, text: "ostrejšia o 18 %, všetci 3 majú otvorené oči" }),
    "★ 92 % · ostrejšia o 18 %, všetci 3 majú otvorené oči");
  assert.equal(C.scoreLine({ rank: 1, score: 0.714, details: [{ code: "eyes_closed", value: 1 }] }), "71 % · 1 má zavreté oči");
  assert.equal(C.scoreLine({ rank: 0, percent: 80, details: [] }), "★ 80 % · najvyššie skóre");
  assert.equal(C.scoreLine(null), "");
});

test("dávky pre /api/quality v rámci limitov", () => {
  const r = C.qualityChunks([[1, 2], [2, 3], [4, 5, 6], [7, 8, 9, 10, 11]], 5, 10);
  // [1,2]+[2,3] = 3 rôzne, +[4,5,6] by bolo 6 > 5 -> nová dávka; skupina s 5 id ide sama
  assert.deepEqual(plain(r.chunks), [[0, 1], [2], [3]]);
  assert.deepEqual(plain(r.tooBig), []);
  const big = C.qualityChunks([[1, 2, 3, 4, 5, 6], [7, 8]], 5, 10);
  assert.deepEqual(plain(big.tooBig), [0]);
  assert.deepEqual(plain(big.chunks), [[1]]);
  const many = C.qualityChunks([[1, 2], [3, 4], [5, 6]], 100, 2);
  assert.deepEqual(plain(many.chunks), [[0, 1], [2]]);
});

const item = (key, kind, ids, keep, extra) => Object.assign({ key, kind, ids, keep, keeper: ids[0], uncertain: false, scored: ids }, extra || {});

test("hromadné: v každej istej skupine ostane ★, neisté sa preskočia", () => {
  const list = [
    item("a", "burst", [1, 2, 3], 2),
    item("b", "similar", [4, 5], null, { uncertain: true }),
    item("c", "exact", [6, 7], 7),
  ];
  const p = C.bulkPlan(list, new Set(), []);
  assert.deepEqual(plain(p.all), [1, 3, 6]);
  assert.deepEqual(plain(p.mark), [1, 3, 6]);
  assert.deepEqual(plain(p.affected), ["a", "c"]);
  assert.equal(p.uncertain, 1);
  assert.equal(p.unknown, 0);
  assert.equal(C.bulkSummary(p, 3 * 1024 * 1024), "Na zmazanie sa označí 3 fotky z 2 skupín (3,0 MB). 1 skupina preskočená – neistá.");
});

test("hromadné: nezmeraná fotka sa neoznačí, ponechaná označená sa odznačí", () => {
  // fotka 3 nemá skóre (nedala sa prečítať), 2 = ★ bola označená ručne
  const p = C.bulkPlan([item("a", "similar", [1, 2, 3], 2, { scored: [1, 2] })], new Set([2]), []);
  assert.deepEqual(plain(p.mark), [1]);
  assert.deepEqual(plain(p.unmark), [2]);
  // skupina bez výsledku = bez údajov
  const q = C.bulkPlan([item("b", "burst", [4, 5], null)], new Set(), []);
  assert.equal(q.unknown, 1);
  assert.equal(q.all.length, 0);
  assert.equal(C.bulkSummary(q, 0), "Nie je čo označiť. 1 skupina preskočená – bez údajov.");
});

test("hromadné: ★ jednej skupiny sa neoznačí, aj keď ju iná skupina navrhuje", () => {
  const p = C.bulkPlan([item("s", "burst", [1, 2], 2), item("e", "exact", [2, 3], 3)], new Set(), []);
  assert.deepEqual(plain(p.all), [1]);
});

test("hromadné: iná skupina nikdy neostane bez fotky (aj s ručnými značkami)", () => {
  // nevybraná skupina {1, 4}: 1 ide preč zo skupiny „a“, 4 je už ručne označená
  const all = [{ key: "x", kind: "similar", ids: [1, 4], keeper: 1 }];
  const p = C.bulkPlan([item("a", "burst", [1, 2], 2)], new Set([4]), all);
  assert.deepEqual(plain(p.all), []);
  assert.deepEqual(plain(p.spared), [1]);
  assert.equal(C.bulkSummary(p, 0), "Nie je čo označiť. 1 fotka ostane navyše, aby iná skupina neprišla o všetky fotky.");
});

test("hromadné: náhodný test invariantu", () => {
  // mulberry32 — opakovateľné náhodné čísla
  let seed = 20261031;
  const rnd = (n) => {
    seed = (seed + 0x6D2B79F5) | 0;
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return (((t ^ (t >>> 14)) >>> 0) % n);
  };
  for (let round = 0; round < 1500; round++) {
    const n = 2 + rnd(25);
    const pick = (k) => { const s = new Set(); while (s.size < Math.min(k, n)) s.add(1 + rnd(n)); return [...s]; };
    const list = [], all = [];
    for (let i = 0, g = 1 + rnd(10); i < g; i++) {
      const ids = pick(2 + rnd(4));
      const r = rnd(4);
      const keep = r === 0 ? null : ids[rnd(ids.length)];
      const scored = r === 1 ? ids.filter(() => rnd(2)) : ids;
      list.push(item("g" + i, ["burst", "exact", "similar"][rnd(3)], ids, keep, { scored, uncertain: r === 0 && rnd(2) === 1 }));
    }
    for (let i = 0, g = rnd(5); i < g; i++) { const ids = pick(2 + rnd(3)); all.push({ key: "o" + i, kind: "similar", ids, keeper: ids[0] }); }
    const marked = new Set(pick(rnd(4)));
    const p = C.bulkPlan(list, marked, all);
    const final = new Set([...marked, ...p.mark].filter((id) => !p.unmark.includes(id)));
    list.forEach((g) => { if (g.keep != null) assert.ok(!final.has(g.keep), "★ " + g.keep + " označená"); });
    list.concat(all).forEach((g) => {
      if (g.ids.some((id) => p.all.includes(id))) assert.ok(g.ids.some((id) => !final.has(id)), "skupina " + g.key + " prišla o všetko");
    });
    assert.equal(new Set(p.all).size, p.all.length);
  }
});

// ---- 0.67.1: ovládanie (mriežka, výber intervalu, Do chatu, ★ neistá) ----
test("mriežka: pohyb kurzora šípkami, Home/End, hranice", () => {
  assert.equal(C.gridMove(-1, "ArrowLeft", 5, 20), 0, "bez kurzora prvá fotka");
  assert.equal(C.gridMove(7, "ArrowRight", 5, 20), 8);
  assert.equal(C.gridMove(7, "ArrowLeft", 5, 20), 6);
  assert.equal(C.gridMove(7, "ArrowDown", 5, 20), 12);
  assert.equal(C.gridMove(7, "ArrowUp", 5, 20), 2);
  assert.equal(C.gridMove(2, "ArrowUp", 5, 20), 0, "hore sa zastaví na prvej");
  assert.equal(C.gridMove(18, "ArrowDown", 5, 20), 19, "dole na poslednej");
  assert.equal(C.gridMove(7, "Home", 5, 20), 0);
  assert.equal(C.gridMove(7, "End", 5, 20), 19);
  assert.equal(C.gridMove(7, "x", 5, 20), -1);
  assert.equal(C.gridMove(0, "ArrowDown", 5, 0), -1, "prázdna mriežka");
});

test("výber intervalu: od kotvy po kliknutú, v oboch smeroch, vrátane krajných", () => {
  const view = [10, 11, 12, 13, 14, 15].map((id) => ({ id }));
  assert.deepEqual(plain(C.rangeIds(view, 1, 3)), [11, 12, 13]);
  assert.deepEqual(plain(C.rangeIds(view, 4, 2)), [12, 13, 14]);
  assert.deepEqual(plain(C.rangeIds(view, 2, 2)), [12]);
  assert.deepEqual(plain(C.rangeIds(view, -5, 99)), [10, 11, 12, 13, 14, 15]);
  assert.deepEqual(plain(C.rangeIds([], 0, 3)), []);
});

test("Shift+šípky: výber sa rozširuje a pri pohybe späť zmenšuje, doterajší výber ostane", () => {
  const view = [1, 2, 3, 4, 5, 6, 7].map((id) => ({ id }));
  const sel = new Set([7]); // vybrané skôr (Ctrl+klik)
  const added = new Set();
  C.extendSelection(sel, added, C.rangeIds(view, 2, 2)); // kotva = kurzor (index 2)
  assert.deepEqual([...sel].sort(), [3, 7]);
  C.extendSelection(sel, added, C.rangeIds(view, 2, 4));
  assert.deepEqual([...sel].sort(), [3, 4, 5, 7]);
  C.extendSelection(sel, added, C.rangeIds(view, 2, 3)); // späť o jedna
  assert.deepEqual([...sel].sort(), [3, 4, 7]);
  C.extendSelection(sel, added, C.rangeIds(view, 2, 0)); // za kotvu na druhú stranu
  assert.deepEqual([...sel].sort(), [1, 2, 3, 7]);
  assert.ok(sel.has(7), "pôvodný výber sa nikdy nezmenšil");
});

test("Do chatu: názov s predponou Claude_ a príponou .jpg, len fotky, nad 20 sa pýta", () => {
  assert.equal(C.chatName("IMG_001.HEIC"), "Claude_IMG_001.jpg");
  assert.equal(C.chatName("foto.v2.png"), "Claude_foto.v2.jpg");
  assert.equal(C.chatName(""), "Claude_foto.jpg");
  const imgs = Array.from({ length: 21 }, (_, i) => ({ id: i, mime: "image/jpeg" }));
  const p = C.chatPlan(imgs.concat([{ id: 99, mime: "video/mp4" }]));
  assert.equal(p.send.length, 21);
  assert.equal(p.skipped, 1);
  assert.equal(p.tooMany, true);
  const p2 = C.chatPlan(imgs.slice(0, 20));
  assert.equal(p2.tooMany, false, "presne 20 sa nepýta");
  assert.equal(C.chatPlan([{ id: 1, mime: "video/mp4" }]).send.length, 0);
  assert.equal(C.CHAT_MAX, 20);
});

test("zoznam percent: 2, 3 a veľa fotiek", () => {
  assert.equal(C.pctList([91, 90]), "91 % a 90 %");
  assert.equal(C.pctList([91, 90, 87]), "91 %, 90 % a 87 %");
  assert.equal(C.pctList([88]), "88 %");
  assert.equal(C.pctList([9, 8, 7, 6, 5, 4]), "9 %, 8 %, 7 %, 6 %, 5 % …");
});

test("★ neistá: percentá všetkých fotiek a vysvetlenie, prečo ju dostala", () => {
  const g = {
    kind: "similar", quality: true, uncertain: true, best: 1,
    qscores: {
      1: { percent: 91, rank: 0, details: [{ code: "older_file" }] },
      2: { percent: 90, rank: 1, details: [] }
    }
  };
  const n = C.starNote(g, "IMG_1.jpg");
  assert.equal(n.warn, true);
  assert.equal(n.head, "★ neistá: IMG_1.jpg");
  assert.ok(n.text.includes("Skóre sú takmer rovnaké (91 % a 90 %), ★ dostala staršia fotka ako pravdepodobný originál – kvalitou sa nelíšia, rozhodni sám."), n.text);
  // tri fotky, ★ z priečinka Fotoaparát
  const g3 = {
    kind: "burst", quality: true, uncertain: true, best: 5,
    qscores: { 5: { percent: 80, rank: 0, details: [{ code: "original_folder" }] }, 6: { percent: 79, rank: 1 }, 7: { percent: 74, rank: 2 } }
  };
  assert.ok(C.starNote(g3, "a.jpg").text.includes("(80 %, 79 % a 74 %), ★ dostala fotka z priečinka Fotoaparát ako pravdepodobný originál – kvalitou sa nelíšia"));
  // ★ vyhrala skutočným (malým) rozdielom, nie rozhodnutím pri zhode
  const gs = {
    kind: "similar", quality: true, uncertain: true, best: 1,
    qscores: { 1: { percent: 70, rank: 0, details: [{ code: "sharper", value: 3 }] }, 2: { percent: 68, rank: 1 } }
  };
  const ns = C.starNote(gs, "b.jpg");
  assert.ok(ns.text.includes("(70 % a 68 %), ★ dostala fotka s o niečo vyšším skóre – rozdiel v kvalite je malý, rozhodni sám."), ns.text);
  // percentá z `score`, ak server `percent` nepošle
  const gp = { kind: "similar", quality: true, uncertain: true, best: 1, qscores: { 1: { score: 0.913, rank: 0 }, 2: { score: 0.9, rank: 1 } } };
  assert.ok(C.starNote(gp, "c.jpg").text.includes("(91 % a 90 %)"));
});

test("★ istá: bez poplašnej vety; presné kópie: sú to rovnaké fotky; bez skóre ticho", () => {
  const ok = { kind: "similar", quality: true, uncertain: false, best: 1, qscores: { 1: { percent: 92, rank: 0, text: "ostrejšia o 18 %" }, 2: { percent: 60, rank: 1 } } };
  const n = C.starNote(ok, "x.jpg");
  assert.equal(n.warn, false);
  assert.equal(n.head, "★ najlepšia: x.jpg");
  assert.equal(n.text, " · ostrejšia o 18 %");
  const ex = C.starNote({ kind: "exact", ids: [1, 2] }, "y.jpg");
  assert.ok(ex.text.includes("sú to rovnaké fotky"));
  assert.equal(ex.warn, false);
  assert.equal(C.starNote({ kind: "similar", best: 1, uncertain: false }, "z"), null);
  assert.equal(C.starNote(null, ""), null);
  const cached = C.starNote({ kind: "similar", best: 1, uncertain: true }, "z.jpg");
  assert.ok(cached.warn && cached.text.includes("rozhodni sám"));
});

// ---------------- 0.67.2: koliesko, miniatúry, plná kvalita, náhľad, Porovnať s…, menu, intervaly, sťahovanie ----------------

test("koliesko: rozhodnutie podľa modifikátorov a miesta", () => {
  const m = (o) => Object.assign({ ctrlKey: false, metaKey: false, shiftKey: false }, o);
  // nad fotkou: koliesko listuje, Ctrl (aj Cmd, aj pinch na touchpade = wheel s ctrlKey) zoomuje, Shift posúva
  assert.equal(C.wheelAction(m({}), "stage"), "page");
  assert.equal(C.wheelAction(m({ ctrlKey: true }), "stage"), "zoom");
  assert.equal(C.wheelAction(m({ metaKey: true }), "stage"), "zoom");
  assert.equal(C.wheelAction(m({ shiftKey: true }), "stage"), "pan");
  assert.equal(C.wheelAction(m({ ctrlKey: true, shiftKey: true }), "stage"), "zoom");
  // zoznam: koliesko scrolluje (prehliadač), Ctrl mení veľkosť miniatúr
  assert.equal(C.wheelAction(m({}), "list"), "scroll");
  assert.equal(C.wheelAction(m({ ctrlKey: true }), "list"), "thumbs");
  // vodorovný pás: koliesko posúva do strany, Shift nechá prehliadaču, Ctrl = miniatúry
  assert.equal(C.wheelAction(m({}), "strip"), "hscroll");
  assert.equal(C.wheelAction(m({ shiftKey: true }), "strip"), "scroll");
  assert.equal(C.wheelAction(m({ ctrlKey: true }), "strip"), "thumbs");
  // lišta vrstvy s fotkami: Ctrl nesmie zoomovať stránku; pomocník a dialógy: zoom stránky ostáva
  assert.equal(C.wheelAction(m({ ctrlKey: true }), "bar"), "block");
  assert.equal(C.wheelAction(m({}), "bar"), "scroll");
  assert.equal(C.wheelAction(m({ ctrlKey: true }), "none"), "native");
});

test("koliesko: akumulácia – zárez myši = 1 fotka, touchpad = 1 fotka na pohyb prstov", () => {
  // myš: zárezy po 300 ms -> každý jedna fotka
  let st = {}, n = 0;
  for (let i = 0; i < 4; i++) n += C.wheelStep(st, 0, 100, 0, i * 300);
  assert.equal(n, 4);
  // Firefox (riadky): 3 riadky na zárez
  st = {};
  assert.equal(C.wheelStep(st, 0, -3, 1, 0), -1);
  // rýchle točenie (zárez každých 50 ms): nie 10 fotiek, ale najviac jedna za 120 ms
  st = {}; n = 0;
  for (let i = 0; i < 10; i++) n += C.wheelStep(st, 0, 100, 0, i * 50);
  assert.ok(n >= 3 && n <= 5, "rýchle točenie: " + n);
  // touchpad: 40 malých udalostí po 16 ms (pohyb + zotrvačnosť) = jedna fotka
  st = {}; n = 0;
  for (let i = 0; i < 40; i++) n += C.wheelStep(st, 0, Math.max(1, 30 - i), 0, i * 16);
  assert.equal(n, 1);
  // po pauze ďalší pohyb prstov = ďalšia fotka
  for (let i = 0; i < 20; i++) n += C.wheelStep(st, 0, 20, 0, 2000 + i * 16);
  assert.equal(n, 2);
  // vodorovný ťah na touchpade (deltaX) tiež listuje; späť = záporný smer
  st = {};
  let d = 0;
  for (let i = 0; i < 10; i++) d += C.wheelStep(st, -15, 2, 0, i * 16);
  assert.equal(d, -1);
  // malý pohyb pod prahom nič nespraví
  st = {};
  assert.equal(C.wheelStep(st, 0, 20, 0, 0) + C.wheelStep(st, 0, 20, 0, 16), 0);
});

test("koliesko: zoom – zárez myši ~14 %, pinch sleduje prsty", () => {
  near(C.wheelZoom(-100, 0), Math.exp(0.15), 1e-9);
  near(C.wheelZoom(100, 0), Math.exp(-0.15), 1e-9);
  near(C.wheelZoom(-3, 1), Math.exp(0.18), 1e-9); // Firefox: riadky
  near(C.wheelZoom(-10, 0), Math.exp(0.1), 1e-9); // pinch: malé pixelové delty
  assert.equal(C.wheelPx(2, 1), 80);
  assert.equal(C.wheelPx(1, 2), 800);
});

test("veľkosť miniatúr: rozsah, krok a väčší náhľad zo servera pri väčšej miniatúre", () => {
  assert.equal(C.thumbClamp("grid", 50), 80);
  assert.equal(C.thumbClamp("grid", 999), 400);
  assert.equal(C.thumbClamp("grid", null), 140);
  assert.equal(C.thumbClamp("grid", "x"), 140);
  assert.equal(C.thumbClamp("groups", 10), 40);
  assert.equal(C.thumbClamp("strip", 500), 240);
  near(C.thumbClamp("grid", 140.04), 140, 1e-9);
  near(C.thumbClamp("grid", 140.36), 140.4, 1e-9); // desatiny: pomalý pinch sa sčíta
  near(C.thumbKey("grid", 140, 1), 161, 1e-9);
  near(C.thumbKey("grid", 140, -1), 121.7, 1e-9);
  assert.equal(C.thumbKey("grid", 400, 1), 400);
  // náhľad zo servera: kratšia strana 4:3 musí pokryť bunku (cover), pevné stupne, strop 1024
  assert.equal(C.thumbReq(140, 1), 192);
  assert.equal(C.thumbReq(80, 1), 128);
  assert.equal(C.thumbReq(140, 2), 384);
  assert.equal(C.thumbReq(300, 1), 512);
  assert.equal(C.thumbReq(400, 1), 768);
  assert.equal(C.thumbReq(400, 2), 1024);
  assert.ok(C.thumbReq(260, 1) > C.thumbReq(140, 1), "väčšia miniatúra = väčší náhľad");
});

test("plná kvalita: výber zdroja podľa voľby a typu súboru", () => {
  const jpg = { id: 1, mime: "image/jpeg", name: "a.jpg", w: 4032, h: 3024, mtime: 9 };
  let p = C.srcPlan(jpg, false);
  assert.deepEqual(plain(p.preview), { path: "/api/thumb", params: { id: 1, max: 2560, v: 9 } });
  assert.deepEqual(plain(p.full), { path: "/api/file", params: { id: 1 } }); // originál bez prekódovania
  assert.equal(p.fullFirst, false); // bez „Plnej kvality“ až pri priblížení
  assert.equal(C.srcPlan(jpg, true).fullFirst, true); // s ňou hneď
  // HEIC: prehliadač ho nezobrazí -> JPEG zo servera s max = dlhá strana originálu
  const heic = { id: 2, mime: "image/heic", name: "IMG_2.HEIC", w: 4032, h: 3024, mtime: 5 };
  p = C.srcPlan(heic, true);
  assert.deepEqual(plain(p.full), { path: "/api/thumb", params: { id: 2, max: 4032, v: 5 } });
  assert.equal(p.native, false);
  assert.equal(p.fmt, "HEIC");
  assert.equal(p.fullFirst, true);
  // 50 Mpx HEIC: strop servera 4096 px, rozmer plnej verzie zodpovedá
  p = C.srcPlan({ id: 3, mime: "image/heic", name: "b.heic", w: 8160, h: 6120, mtime: 1 }, false);
  assert.equal(p.full.params.max, 4096);
  assert.equal(p.fullW, 4096); assert.equal(p.fullH, 3072);
  // malý HEIC: náhľad 2560 už je plné rozlíšenie -> netreba nič ďalšie
  p = C.srcPlan({ id: 4, mime: "image/heic", name: "c.heic", w: 2000, h: 1500, mtime: 1 }, true);
  assert.equal(p.full, null); assert.equal(p.fullFirst, false);
  // DNG bez rozmerov: plná verzia so stropom servera
  p = C.srcPlan({ id: 5, mime: "image/x-adobe-dng", name: "r.dng", w: 0, h: 0, mtime: 1 }, false);
  assert.equal(p.full.params.max, 4096); assert.equal(p.fmt, "DNG");
  // otočená fotka (90°): rozmery podľa zobrazenia
  p = C.srcPlan({ id: 6, mime: "image/jpeg", name: "d.jpg", w: 4000, h: 3000, orient: 90, mtime: 1 }, false);
  assert.equal(p.origW, 3000); assert.equal(p.origH, 4000);
  assert.equal(C.fmtOf({ name: "x", mime: "image/x-sony-arw" }), "SONY-ARW");
});

test("plná kvalita: štítok „čo práve vidíš“ v paneli", () => {
  const b = { ow: 4032, oh: 3024 };
  assert.equal(C.qualLabel(Object.assign({ kind: "prev", w: 2560, h: 1920, native: true }, b)), "náhľad 2560 px");
  assert.equal(C.qualLabel(Object.assign({ kind: "prev", w: 2560, h: 1920, native: true, loading: true }, b)), "náhľad 2560 px · načítavam originál…");
  assert.equal(C.qualLabel(Object.assign({ kind: "full", w: 4032, h: 3024, native: true }, b)), "originál 4032 × 3024");
  assert.equal(C.qualLabel(Object.assign({ kind: "full", w: 4032, h: 3024, native: false, fmt: "HEIC" }, b)), "HEIC → JPEG v plnom rozlíšení 4032 × 3024");
  assert.equal(C.qualLabel(Object.assign({ kind: "prev", w: 2560, h: 1920, native: false, fmt: "HEIC", loading: true }, b)), "náhľad 2560 px · načítavam plné rozlíšenie…");
  assert.equal(C.qualLabel({ kind: "full", w: 4096, h: 3072, ow: 8160, oh: 6120, native: false, fmt: "HEIC" }), "HEIC → JPEG 4096 × 3072 (originál 8160 × 6120)");
  assert.equal(C.qualLabel({ kind: "prev", w: 2000, h: 1500, ow: 2000, oh: 1500, native: false, fmt: "DNG" }), "DNG → JPEG v plnom rozlíšení 2000 × 1500");
  assert.equal(C.qualLabel(Object.assign({ kind: "prev", w: 2560, h: 1920, native: true, failed: true }, b)), "náhľad 2560 px · originál sa nepodarilo načítať");
  assert.equal(C.qualLabel({ kind: "prev", w: 0, h: 0, loading: true }), "načítavam…");
});

test("canvas: výrez fotky na celé pixely zariadenia, nikdy mimo fotky", () => {
  // prispôsobené: 4000×3000 v ploche 800×600 -> celá plocha, celá fotka
  let r = C.drawRect(800, 600, 4000, 3000, { scale: 0.2, x: 0, y: 0 }, 1);
  assert.deepEqual(plain(r), { sx: 0, sy: 0, sw: 4000, sh: 3000, dx: 0, dy: 0, dw: 800, dh: 600 });
  // priblížené a posunuté s neceločíselnými hodnotami: cieľ celé čísla, zdroj vo vnútri fotky, rovnaká mierka
  let drawn = 0;
  for (const [sc, x, y, dpr] of [[1.37, 123.4, -77.7, 1], [3.333, -999.5, 400.25, 1.25], [0.513, 10.5, 3.3, 2], [7.9, 15000, -9000, 1.5]]) {
    r = C.drawRect(1013, 677, 8000, 6000, { scale: sc, x, y }, dpr);
    if (!r) continue;
    drawn++;
    for (const k of ["dx", "dy", "dw", "dh"]) assert.ok(Number.isInteger(r[k]), k + " " + r[k]);
    assert.ok(r.sx >= 0 && r.sy >= 0 && r.sx + r.sw <= 8000 + 1e-6 && r.sy + r.sh <= 6000 + 1e-6, JSON.stringify(r));
    assert.ok(r.dx >= 0 && r.dy >= 0 && r.dx + r.dw <= Math.round(1013 * dpr) && r.dy + r.dh <= Math.round(677 * dpr), JSON.stringify(r));
    near(r.dw / r.sw, sc * dpr, 0.02);
  }
  assert.ok(drawn >= 3);
  // fotka celkom mimo plochy -> nič
  assert.equal(C.drawRect(800, 600, 4000, 3000, { scale: 1, x: 99999, y: 0 }, 1), null);
});

test("dávkové značenie: Delete/X s výberom označí všetky naraz, bez výberu aktuálnu", () => {
  const marked = new Set([3]);
  assert.deepEqual(plain(C.markPlan([1, 2, 3], 9, marked)), { ids: [1, 2, 3], del: true });
  assert.deepEqual(plain(C.markPlan([3], 9, marked)), { ids: [3], del: false }); // všetky už označené -> zrušiť
  assert.deepEqual(plain(C.markPlan([], 9, marked)), { ids: [9], del: true });
  assert.deepEqual(plain(C.markPlan([], 3, marked)), { ids: [3], del: false });
  assert.deepEqual(plain(C.markPlan([], null, marked)), { ids: [], del: false });
});

test("interval: výber aj odznačenie podľa kotvy, značky na zmazanie rovnako, Ctrl+Z celý naraz", () => {
  const view = [1, 2, 3, 4, 5, 6].map((id) => ({ id }));
  // kotva vybraná -> interval sa vyberie
  let p = C.rangePlan(view, 1, 4, 2, new Set([2]));
  assert.deepEqual(plain(p), { ids: [3, 4, 5], on: true, all: [2, 3, 4, 5] });
  // kotva NIE je vybraná a v intervale sú vybrané -> celý interval sa odznačí
  p = C.rangePlan(view, 1, 4, 2, new Set([3, 4, 6]));
  assert.deepEqual(plain(p), { ids: [3, 4], on: false, all: [2, 3, 4, 5] });
  // nič vybrané -> vybrať (odznačiť nie je čo)
  p = C.rangePlan(view, 4, 0, 5, new Set());
  assert.deepEqual(plain(p.ids), [1, 2, 3, 4, 5]); assert.equal(p.on, true);
  // značky na zmazanie: to isté pravidlo
  const marked = new Set([1, 2, 3]);
  p = C.rangePlan(view, 0, 2, 1, marked); // kotva označená
  assert.equal(p.on, true); assert.deepEqual(plain(p.ids), []);
  marked.delete(1); // kotvu som odznačil -> interval sa odznačí
  p = C.rangePlan(view, 0, 2, 1, marked);
  assert.equal(p.on, false); assert.deepEqual(plain(p.ids), [2, 3]);
  // Ctrl+Z vráti celý interval naraz: prev = {id: predošlý stav}
  const sel = new Set([3, 4]), prev = {};
  p = C.rangePlan(view, 1, 4, 2, sel);
  p.all.forEach((id) => { if (sel.has(id) !== p.on) { prev[id] = !p.on; if (p.on) sel.add(id); else sel.delete(id); } });
  assert.deepEqual([...sel], []);
  const back = C.revertMarks(prev);
  back.on.forEach((id) => sel.add(id)); back.off.forEach((id) => sel.delete(id));
  assert.deepEqual([...sel].sort(), [3, 4]);
});

test("kotva intervalu: index v zobrazení aj keď sa kotva práve skryla", () => {
  const items = [1, 2, 3, 4, 5, 6].map((id) => ({ id }));
  const view = items.filter((x) => x.id !== 3); // 3 je označená a skrytá
  assert.equal(C.anchorIndex(view, items, 4), 2);
  assert.equal(C.anchorIndex(view, items, 3), 2); // miesto, kde by bola (pred 4)
  assert.equal(C.anchorIndex(view, items, 6), 4);
  assert.equal(C.anchorIndex(view, items, 99), -1);
  assert.equal(C.anchorIndex(view, items, null), -1);
  assert.equal(C.anchorIndex([], items, 1), -1);
});

test("skryť označené na zmazanie: zmiznú zo zoznamu, filter „Na zmazanie“ ich ukáže", () => {
  const items = [
    { id: 1, name: "a.jpg", mime: "image/jpeg" }, { id: 2, name: "b.jpg", mime: "image/jpeg" }, { id: 3, name: "c.mp4", mime: "video/mp4" }
  ];
  const marked = new Set([2, 3]), synced = new Map();
  assert.deepEqual(plain(C.filterItems(items, "", "all", marked, synced, true).map((x) => x.id)), [1]);
  assert.deepEqual(plain(C.filterItems(items, "", "all", marked, synced, false).map((x) => x.id)), [1, 2, 3]);
  assert.deepEqual(plain(C.filterItems(items, "", "marked", marked, synced, true).map((x) => x.id)), [2, 3]);
  assert.deepEqual(plain(C.filterItems(items, "", "images", marked, synced, true).map((x) => x.id)), [1]);
});

test("Porovnať s…: kandidáti vpravo, začiatok za pripnutou, po zmene tá istá alebo nasledujúca", () => {
  const base = [
    { id: 1, mime: "image/jpeg" }, { id: 2, mime: "video/mp4" }, { id: 3, mime: "image/jpeg" },
    { id: 4, mime: "image/jpeg" }, { id: 5, mime: "image/heic" }, { id: 6, mime: "image/jpeg" }
  ];
  let r = C.freeList(base, 3, new Set([4]), true);
  assert.deepEqual(plain(r), { ids: [1, 5, 6], start: 1 }); // bez videa, pripnutej a označenej; vpravo fotka za pripnutou
  r = C.freeList(base, 3, new Set([4]), false);
  assert.deepEqual(plain(r), { ids: [1, 4, 5, 6], start: 1 });
  r = C.freeList(base, 6, new Set(), true);
  assert.equal(r.start, 3); // pripnutá je posledná -> vpravo predošlá
  r = C.freeList([{ id: 7, mime: "image/jpeg" }], 7, new Set(), true);
  assert.deepEqual(plain(r), { ids: [], start: -1 });
  // označená vpravo zmizla -> príde tá, ktorá sa posunula na jej miesto; inak ostane tá istá
  assert.equal(C.reindex([1, 4, 5, 6], 1, [1, 5, 6]), 1);
  assert.equal(C.reindex([1, 4, 5, 6], 2, [1, 5, 6]), 1);
  assert.equal(C.reindex([1, 4, 5, 6], 3, [1, 4, 5]), 2);
  assert.equal(C.reindex([1, 4], 1, []), -1);
});

test("zoznam náhľadu: šípky podľa orientácie", () => {
  assert.equal(C.listMove(5, "ArrowDown", 3, 20, false), 8); // vľavo v stĺpcoch: ↓ o riadok
  assert.equal(C.listMove(5, "ArrowRight", 3, 20, false), 6);
  assert.equal(C.listMove(5, "ArrowRight", 1, 20, true), 6); // dole v páse: → ďalšia
  assert.equal(C.listMove(5, "ArrowLeft", 1, 20, true), 4);
  assert.equal(C.listMove(5, "ArrowDown", 1, 20, true), 6);
  assert.equal(C.listMove(-1, "ArrowUp", 1, 20, true), 0);
  assert.equal(C.listMove(19, "End", 1, 20, true), 19);
});

test("Ctrl skratky: stránka si nechá len tie, ktoré obsluhuje", () => {
  for (const k of ["d", "D", "a", "ArrowDown", "+", "=", "-"]) assert.ok(C.ctrlAllowed("grid", k), k);
  for (const k of ["f", "t", "w", "n", "0", "p", "s"]) assert.ok(!C.ctrlAllowed("grid", k), k);
  assert.ok(C.ctrlAllowed("preview", "d"));
  assert.ok(C.ctrlAllowed("cmp", "+") && !C.ctrlAllowed("cmp", "d"));
  assert.ok(C.ctrlAllowed("groups", "-") && !C.ctrlAllowed("groups", "a"));
  assert.ok(!C.ctrlAllowed("viewer", "+"), "v prehliadači fotky ostáva zoom stránky prehliadaču");
});

test("kontextové menu: položky podľa miesta, výberu a intervalu", () => {
  const ids = (c) => C.ctxItems(c).map((x) => x.id);
  const base = { sel: 0, inSel: false, selected: false, marked: false, selMarked: false, image: true, range: null };
  assert.deepEqual(plain(ids(Object.assign({ where: "grid" }, base))), ["mark", "sel", "selall", "selnone", "with", "preview", "dl", "copy"]);
  assert.deepEqual(plain(ids(Object.assign({ where: "list" }, base))), ["mark", "sel", "selall", "selnone", "with", "full", "dl", "copy"]);
  assert.deepEqual(plain(ids(Object.assign({ where: "viewer" }, base))), ["mark", "with", "preview", "dl", "copy"]);
  assert.deepEqual(plain(ids(Object.assign({ where: "cmp" }, base))), ["mark", "with", "preview", "dl", "copy"]);
  let it = C.ctxItems(Object.assign({}, base, { where: "grid", sel: 3, inSel: true, selected: true }));
  assert.equal(it[0].label, "🗑 Označiť vybrané na zmazanie (3)");
  assert.equal(it[1].label, "Zrušiť výber tejto fotky");
  assert.equal(it.find((x) => x.id === "dl").label, "⬇ Stiahnuť originály (3)");
  assert.equal(it.find((x) => x.id === "copy").label, "Kopírovať názvy (3)");
  it = C.ctxItems(Object.assign({}, base, { where: "grid", sel: 3, inSel: true, selMarked: true }));
  assert.equal(it[0].label, "↩ Zrušiť značku vybraným (3)");
  it = C.ctxItems(Object.assign({}, base, { where: "grid", marked: true }));
  assert.equal(it[0].label, "↩ Zrušiť značku na zmazanie");
  assert.ok(C.ctxItems(Object.assign({}, base, { where: "grid" })).find((x) => x.id === "selnone").disabled);
  assert.ok(C.ctxItems(Object.assign({}, base, { where: "viewer", image: false })).find((x) => x.id === "with").disabled);
  // interval od kotvy: len položky, ktoré niečo zmenia
  it = C.ctxItems(Object.assign({}, base, { where: "grid", sel: 2, range: { n: 5, selOn: true, selOff: true, markOn: false, markOff: true } }));
  assert.deepEqual(plain(it.filter((x) => /^r/.test(x.id)).map((x) => x.label)), ["Vybrať interval (5)", "Odznačiť interval (5)", "↩ Zrušiť značku zmazania v intervale (5)"]);
  assert.ok(it.find((x) => x.id === "rsel").sep);
  it = C.ctxItems(Object.assign({}, base, { where: "grid", range: { n: 1, selOn: true } }));
  assert.ok(!it.some((x) => /^r/.test(x.id)), "interval z jednej fotky sa neponúka");
  assert.ok(!C.ctxItems(Object.assign({}, base, { where: "viewer", range: { n: 4, selOn: true } })).some((x) => /^r/.test(x.id)));
});

test("sťahovanie: jeden súbor, ZIP alebo po jednom; hlásenia", () => {
  assert.equal(C.dlPlan(0, true, false), "none");
  assert.equal(C.dlPlan(1, true, false), "single");
  assert.equal(C.dlPlan(3, true, false), "zip");
  assert.equal(C.dlPlan(3, false, false), "seq");
  assert.equal(C.dlPlan(3, true, true), "seq"); // zmenšené JPEG (Do chatu) ZIP nevie
  assert.deepEqual(plain(C.zipChunks([1, 2, 3, 4, 5], 2)), [[1, 2], [3, 4], [5]]);
  assert.equal(C.zipChunks(Array.from({ length: 1200 }, (_, i) => i)).length, 3);
  assert.equal(C.dlViaFetch(5e6), true);
  assert.equal(C.dlViaFetch(800e6), false);
  assert.equal(C.dlHoldMs(0), 600);
  assert.equal(C.dlHoldMs(40 * 1024 * 1024), 10600);
  assert.equal(C.dlHoldMs(1e12), 180000);
  assert.equal(C.dlReason(403), "prihlásenie vypršalo – zadaj PIN znova");
  assert.equal(C.dlReason(404), "súbor už v mobile nie je");
  assert.equal(C.dlReason(0), "spojenie s mobilom zlyhalo");
  assert.equal(C.dlReason(500), "server odmietol (HTTP 500)");
  assert.equal(C.dlDoneText(12, {}), "Stiahnuté: 12 súborov → priečinok Stiahnuté súbory v Chrome (Ctrl+J)");
  assert.equal(C.dlDoneText(1, { "súbor už v mobile nie je": 1 }), "Stiahnuté: 1 súbor → priečinok Stiahnuté súbory v Chrome (Ctrl+J) · nepodarilo sa 1: súbor už v mobile nie je");
  assert.equal(C.dlDoneText(0, { "prihlásenie vypršalo – zadaj PIN znova": 2 }), "Sťahovanie zlyhalo: prihlásenie vypršalo – zadaj PIN znova (2×)");
  assert.equal(C.dlDoneText(3, {}, "Fotky"), "Uložené do priečinka „Fotky“: 3 súbory");
});

test("diagnostika servera: ľubovoľný JSON ako riadky tabuľky", () => {
  const rows = plain(C.statsRows({ uptimeMs: 1234.567, thumbs: { hits: 10, avgMs: 12.345 }, ok: true, last: null, tags: ["a", "b"], deep: { a: { b: { c: { d: 1 } } } } }));
  const m = Object.fromEntries(rows.map((r) => [r.k, r.v]));
  assert.equal(m.uptimeMs, "1234,57");
  assert.equal(m["thumbs.hits"], "10");
  assert.equal(m["thumbs.avgMs"], "12,35");
  assert.equal(m.ok, "áno");
  assert.equal(m.last, "–");
  assert.equal(m.tags, "a, b");
  assert.equal(m["deep.a.b.c"], '{"d":1}'); // hlboké vnorenie ako JSON
  assert.deepEqual(plain(C.statsRows(5)), [{ k: "hodnota", v: "5" }]);
});

// ---------- web-0.67.2-fav: Obľúbené, živé fotky, pretiahnutie do inej aplikácie, schránka ----------
test("Obľúbené: filter „Len obľúbené“ / „Živé fotky“ a poradie filtrov pre F", () => {
  const items = [
    { id: 1, name: "a.jpg", mime: "image/jpeg" },
    { id: 2, name: "b.jpg", mime: "image/jpeg", motion: true },
    { id: 3, name: "c.mp4", mime: "video/mp4" }
  ];
  const none = new Set(), fav = new Set([1, 3]);
  assert.deepEqual(plain(C.filterItems(items, "", "fav", none, new Map(), false, fav).map((x) => x.id)), [1, 3]);
  assert.deepEqual(plain(C.filterItems(items, "", "fav", none, new Map()).map((x) => x.id)), [], "bez množiny obľúbených nič");
  assert.deepEqual(plain(C.filterItems(items, "", "motion", none, new Map(), false, fav).map((x) => x.id)), [2]);
  // skryté označené platia aj pri obľúbených
  assert.deepEqual(plain(C.filterItems(items, "", "fav", new Set([1]), new Map(), true, fav).map((x) => x.id)), [3]);
  assert.deepEqual(plain(C.filterOrder(false, false)), ["all", "new", "synced", "marked", "images", "videos"]);
  assert.deepEqual(plain(C.filterOrder(true, true)).slice(-2), ["fav", "motion"]);
  assert.deepEqual(plain(C.filterOrder(false, true)).slice(-1), ["motion"]);
});

test("Obľúbené: dávka (výber naraz, inak kurzor), po 2000, Ctrl+Z len zmenené", () => {
  const fav = new Set([2]);
  assert.deepEqual(plain(C.favPlan([], 5, fav)), { ids: [5], fav: true });
  assert.deepEqual(plain(C.favPlan([], 2, fav)), { ids: [2], fav: false });
  assert.deepEqual(plain(C.favPlan([1, 2, 3], 9, fav)), { ids: [1, 2, 3], fav: true }, "výber má prednosť, nie všetky obľúbené → pridať");
  assert.deepEqual(plain(C.favPlan([2], 9, fav)), { ids: [2], fav: false }, "všetky vybrané obľúbené → odobrať");
  assert.deepEqual(plain(C.favPlan([], null, fav)), { ids: [], fav: false });
  assert.equal(C.FAV_MAX, 2000);
  const ch = C.favChunks(Array.from({ length: 4500 }, (_, i) => i));
  assert.deepEqual(plain(ch.map((c) => c.length)), [2000, 2000, 500]);
  // odpoveď servera: changed = len tie, ktorým sa stav naozaj zmenil (a len z poslaných)
  assert.deepEqual(plain(C.favChanged([1, 2, 3], { changed: [3, 1, 99], fav: true, gen: 5 })), [3, 1]);
  assert.deepEqual(plain(C.favChanged([1, 2], { changed: ["2"] })), [2], "id ako text");
  assert.deepEqual(plain(C.favChanged([1, 2], {})), [1, 2], "bez changed = všetky poslané");
  assert.deepEqual(plain(C.favPrev([1, 3], true)), { 1: false, 3: false });
  assert.deepEqual(plain(C.revertMarks(C.favPrev([1, 3], false))), { on: [1, 3], off: [] }, "Ctrl+Z odobratia = znova pridať");
});

test("Obľúbené: zo zoznamu (pole fav chýba = nie je obľúbená) a kedy treba /api/favorites", () => {
  const s = new Set([1, 2, 9]);
  C.absorbFav(s, [{ id: 1 }, { id: 2, fav: true }, { id: 3, fav: true }], false);
  assert.deepEqual([...s].sort(), [2, 3, 9], "delta mení len svoje položky");
  C.absorbFav(s, [{ id: 4, fav: true }], true);
  assert.deepEqual([...s], [4], "celý zoznam = nová množina");
  const known = { favGen: 10, gen: 7 };
  assert.equal(C.favNeedsFetch({ favGen: 10, gen: 7 }, known), false);
  assert.equal(C.favNeedsFetch({ favGen: 11, gen: 8 }, known), false, "zmena z telefónu posunie aj gen → stačí delta zoznamu");
  assert.equal(C.favNeedsFetch({ favGen: 11, gen: 7 }, known), true, "favGen bez zmeny zoznamu → /api/favorites");
  assert.equal(C.favNeedsFetch({ gen: 7 }, known), false, "server bez favGen");
  assert.equal(C.favNeedsFetch({ favGen: 3, gen: 7 }, { favGen: null, gen: 7 }), true);
});

test("živé fotky: zo zoznamu hneď, inak podľa /api/meta (motion.len)", () => {
  const jpg = { id: 1, mime: "image/jpeg" };
  assert.equal(C.liveState(Object.assign({ motion: true }, jpg), undefined), true);
  assert.equal(C.liveState(jpg, undefined), null, "meta ešte nie je");
  assert.equal(C.liveState(jpg, null), false, "meta načítaná bez údajov");
  assert.equal(C.liveState(jpg, { format: "JPEG" }), false);
  assert.equal(C.liveState(jpg, { motion: { len: 2345678 } }), true);
  assert.equal(C.liveState(jpg, { motion: { len: 0 } }), false);
  assert.equal(C.liveState({ id: 2, mime: "video/mp4", motion: true }, undefined), false, "video nie je živá fotka");
});

test("pretiahnutie do inej aplikácie: DownloadURL = mime:meno:absolútna URL ako ⬇", () => {
  const jpg = { id: 7, name: "IMG_7.jpg", mime: "image/jpeg" }, heic = { id: 8, name: "IMG_8.HEIC", mime: "image/heic" };
  const vid = { id: 9, name: "VID_9.mp4", mime: "video/mp4" };
  let d = plain(C.dragData(jpg, { origin: "http://192.168.1.20:8089" }));
  assert.equal(d.downloadUrl, "image/jpeg:IMG_7.jpg:http://192.168.1.20:8089/api/file?id=7&dl=1");
  assert.equal(d.url, "http://192.168.1.20:8089/api/file?id=7&dl=1");
  d = plain(C.dragData(heic, { origin: "http://h:1", reduced: true, q: 85 }));
  assert.equal(d.downloadUrl, "image/jpeg:IMG_8.jpg:http://h:1/api/file?id=8&dl=1&q=85&max=2048", "zmenšené = JPEG 2048 px s kvalitou, prípona .jpg");
  assert.equal(C.dragData(heic, { origin: "http://h:1" }).downloadUrl, "image/heic:IMG_8.HEIC:http://h:1/api/file?id=8&dl=1", "originál bez prekódovania");
  assert.equal(C.dragData(vid, { origin: "http://h:1", reduced: true }).downloadUrl, "video/mp4:VID_9.mp4:http://h:1/api/file?id=9&dl=1", "video sa nezmenšuje");
  assert.equal(C.dragData(jpg, { origin: "http://h:1", token: "123456" }).url, "http://h:1/api/file?id=7&dl=1&token=123456", "bez cookie token v adrese ako pri priamom odkaze");
  assert.equal(C.dragData({ id: 1, name: "a:b/c\\d.jpg", mime: "image/png" }, { origin: "" }).name, "a_b_c_d.jpg", "dvojbodka by rozbila formát");
  assert.equal(C.dragData({ id: 1, name: "", mime: "" }, {}).downloadUrl, "application/octet-stream:foto:/api/file?id=1&dl=1");
  // rovnaké parametre ako sťahovanie tlačidlom ⬇ (dlJob)
  assert.deepEqual(plain(C.fileParams(jpg, true, 70)), { id: 7, dl: 1, q: 70, max: 2048 });
  assert.deepEqual(plain(C.fileParams(vid, true, 70)), { id: 9, dl: 1 });
  assert.equal(C.query({ id: 1, a: null, b: "x y" }, "t"), "?id=1&b=x%20y&token=t");
  assert.equal(C.query({}, ""), "");
  assert.match(C.DRAG_MULTI_TXT, /^Do inej aplikácie sa dá z prehliadača pretiahnuť len 1 fotka naraz\. Viac fotiek: 📁 Uložiť do priečinka \(cez USB\)/);
});

test("schránka (Ctrl+C): čo sa načíta, tip chrome://flags", () => {
  const jpg = { id: 7, name: "a.jpg", mime: "image/jpeg", w: 4000, h: 3000, mtime: 5 };
  const heic = { id: 8, name: "b.heic", mime: "image/heic", w: 8160, h: 6120, orient: 90, mtime: 6 };
  assert.deepEqual(plain(C.clipSrc(jpg, false)), { path: "/api/file", params: { id: 7 } });
  assert.deepEqual(plain(C.clipSrc(jpg, true, 85)), { path: "/api/file", params: { id: 7, q: 85, max: 2048 } });
  assert.deepEqual(plain(C.clipSrc(heic, false)), { path: "/api/thumb", params: { id: 8, max: C.FULL_MAX, v: 6 } }, "HEIC ako JPEG v plnom rozlíšení (strop)");
  assert.match(C.flagsTip("http://192.168.1.20:8089"), /chrome:\/\/flags\/#unsafely-treat-insecure-origin-as-secure.*\(http:\/\/192\.168\.1\.20:8089\).*Uložiť do priečinka/);
  assert.match(C.flagsTip(""), /voliteľné, platí len pre tento počítač/);
  assert.match(C.CLIP_INSECURE_TXT, /chrome:\/\/flags/);
});

test("kontextové menu: Obľúbené (dávkovo), ▶ Prehrať, Kopírovať obrázok", () => {
  const base = { sel: 0, inSel: false, selected: false, marked: false, selMarked: false, image: true, range: null };
  const ids = (c) => plain(C.ctxItems(Object.assign({}, base, c)).map((x) => x.id));
  assert.deepEqual(ids({ where: "grid", favs: true, canClip: true }), ["mark", "fav", "sel", "selall", "selnone", "with", "preview", "dl", "copyimg", "copy"]);
  assert.deepEqual(ids({ where: "viewer", favs: true, live: true }), ["mark", "fav", "play", "with", "preview", "dl", "copy"]);
  assert.ok(!ids({ where: "grid", canClip: true, image: false }).includes("copyimg"), "video do schránky nejde");
  const lab = (c) => C.ctxItems(Object.assign({}, base, c)).find((x) => x.id === "fav").label;
  assert.equal(lab({ where: "grid", favs: true }), "★ Pridať do obľúbených");
  assert.equal(lab({ where: "grid", favs: true, fav: true }), "☆ Odobrať z obľúbených");
  assert.equal(lab({ where: "grid", favs: true, sel: 3, inSel: true }), "★ Pridať do obľúbených (3)");
  assert.equal(lab({ where: "grid", favs: true, sel: 3, inSel: true, selFav: true }), "☆ Odobrať z obľúbených (3)");
});

test("ZIP: najviac 2 naraz (server inak odpovie 503 busy)", () => {
  assert.equal(C.ZIP_PARALLEL, 2);
  assert.equal(C.zipFree([], 1000), 2);
  assert.equal(C.zipFree([1000], 2000), 1);
  assert.equal(C.zipFree([1000, 1500], 2000), 0);
  assert.equal(C.zipFree([1000, 1500], 1000 + 60000), 2, "staré ZIP-y sa už nerátajú");
});

test("náhľady: opakovanie pri 503 busy a plné rozlíšenie podľa thumbFull", () => {
  assert.equal(C.thumbRetry("/api/thumb?id=5&s=1024&v=1", 0), "/api/thumb?id=5&s=1024&v=1&r=1");
  assert.equal(C.thumbRetry("/api/thumb?id=5&s=1024&v=1&r=1", 1), "/api/thumb?id=5&s=1024&v=1&r=2");
  assert.equal(C.thumbRetry("/api/thumb?id=5&s=1024&v=1&r=2", 2), "", "najviac 2 pokusy navyše");
  assert.equal(C.thumbRetry("/api/file?id=5", 0), "", "originály sa neopakujú");
  assert.equal(C.thumbRetry("", 0), "");
  const heic = { id: 9, mime: "image/heic", name: "a.heic", w: 8160, h: 6120, mtime: 1 };
  try {
    assert.equal(C.setFullMax(true), 8192);
    assert.equal(C.srcPlan(heic, true).full.params.max, 8160, "nový server: HEIC v plnom rozlíšení");
    assert.equal(C.setFullMax(false), 4096);
    assert.equal(C.srcPlan(heic, true).full.params.max, 4096, "starší server: strop 4096");
  } finally {
    C.setFullMax(false);
  }
});
