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
