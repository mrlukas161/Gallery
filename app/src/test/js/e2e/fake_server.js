// Falošný server pre web „Galéria+ Sync“ (bez mobilu): servíruje app/src/main/assets/gallery.html a ukážkové API
// (zoznam fotiek, skupiny, značky, kvalita), aby sa ovládanie dalo vyskúšať v prehliadači na PC.
//   node app/src/test/js/e2e/fake_server.js [port]      -> http://localhost:8787/
//   ukončenie: otvoriť http://localhost:8787/__quit
//   v konzole stránky:  await (await fetch("/__keys_check.js")).text().then(eval)   -> prehliadkový test skratiek
// Nič sa nikam nezapisuje a k mobilu sa nepripája; fotky sú obrázky SVG s číslom.
"use strict";
const http = require("node:http");
const fs = require("node:fs");
const path = require("node:path");

const PORT = Number(process.argv[2]) || 8787;
const HTML = path.join(__dirname, "../../../main/assets/gallery.html");

const N = 60;
const items = [];
for (let i = 1; i <= N; i++) {
  items.push({
    id: i, name: "IMG_" + String(i).padStart(4, "0") + ".jpg", mime: "image/jpeg", size: 2_000_000 + i * 1000,
    taken: Date.UTC(2026, 9, 1) + (N - i) * 3600_000, mtime: 1, w: 4000, h: 3000, orient: 0,
    bucket: i % 2 ? "Camera" : "WhatsApp", relpath: "DCIM/" + (i % 2 ? "Camera" : "WhatsApp") + "/", marked: false, synced: 0
  });
}
items[4].mime = "video/mp4"; items[4].name = "VID_0005.mp4"; items[4].dur = 12000;
const marked = new Set();
let stateGen = 1;
// Obľúbené (ako v telefóne) a živé fotky: #3 je živá podľa zoznamu, #7 len podľa /api/meta (zoznam to nevie)
const favs = new Set([2]);
let gen = 1, favGen = 100;
const MOTION_LIST = new Set([3]), MOTION_META = new Set([3, 7]);
const gstates = {};
const groups = [
  { key: "g-burst", kind: "burst", ids: [1, 2, 3], keeper: 1, bytes: 6e6, reclaim: 4e6, from: 1, to: 2, bucket: "Camera", best: null, confidence: null, reasons: [], uncertain: null, keep: null },
  { key: "g-exact", kind: "exact", ids: [10, 11], keeper: 10, bytes: 4e6, reclaim: 2e6, from: 1, to: 2, bucket: "Camera", best: null, confidence: null, reasons: [], uncertain: null, keep: 10 },
  { key: "g-sim", kind: "similar", ids: [20, 21, 22, 23, 24], keeper: 20, bytes: 1e7, reclaim: 8e6, from: 1, to: 2, bucket: "Camera", best: null, confidence: null, reasons: [], uncertain: null, keep: null }
];
const requests = []; // /__log: posledné požiadavky na súbory (kontrola „Do chatu“)
const api = []; // /__api: obľúbené, meta a video živej fotky (kontrola Obľúbených a živých fotiek)

function svg(id, w, h) {
  const hue = (id * 47) % 360;
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}"><rect width="100%" height="100%" fill="hsl(${hue},45%,35%)"/>` +
    `<text x="50%" y="50%" fill="#fff" font-size="${Math.round(h / 4)}" text-anchor="middle" dominant-baseline="middle" font-family="sans-serif">#${id}</text></svg>`;
}
function json(res, obj, status) { res.writeHead(status || 200, { "Content-Type": "application/json; charset=utf-8" }); res.end(JSON.stringify(obj)); }
function body(req) { return new Promise((r) => { let b = ""; req.on("data", (c) => (b += c)); req.on("end", () => { try { r(JSON.parse(b || "{}")); } catch (e) { r({}); } }); }); }

http.createServer(async (req, res) => {
  const u = new URL(req.url, "http://x");
  const p = u.pathname, q = u.searchParams;
  if (p === "/") { res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" }); res.end(fs.readFileSync(HTML, "utf8").replace(/__CSP_NONCE__/g, "n1")); return; }
  if (p === "/__keys_check.js") { res.writeHead(200, { "Content-Type": "text/javascript; charset=utf-8" }); res.end(fs.readFileSync(path.join(__dirname, "keys_check.js"))); return; }
  if (p === "/__log") { json(res, requests); return; }
  if (p === "/__api") { json(res, api); return; }
  if (p === "/__quit") { res.end("koniec"); setTimeout(() => process.exit(0), 100); return; } // ukončenie bez hľadania PID
  if (p === "/api/login") { json(res, { ok: true }); return; }
  if (p === "/api/ping") { json(res, { epoch: "e1", gen, stateGen, favGen, allFiles: true, recycleBin: true, features: ["favorites", "motion"] }); return; }
  if (p === "/api/list") {
    const out = (it) => Object.assign({ ...it, marked: marked.has(it.id) }, favs.has(it.id) ? { fav: true } : {}, MOTION_LIST.has(it.id) ? { motion: true } : {});
    json(res, { gen, epoch: "e1", stateGen, full: true, upserts: items.map(out), removed: [] }); return;
  }
  if (p === "/api/favorites") { json(res, { ids: [...favs], gen: favGen }); return; }
  if (p === "/api/favorite") {
    const b = await body(req);
    if (!Array.isArray(b.ids)) { json(res, { error: "bad ids" }, 400); return; }
    if (!("fav" in b)) { json(res, { error: "bad fav" }, 400); return; }
    const changed = b.ids.filter((id) => favs.has(id) !== !!b.fav);
    changed.forEach((id) => (b.fav ? favs.add(id) : favs.delete(id)));
    if (changed.length) { favGen++; gen++; }
    api.push({ favorite: b.ids, fav: !!b.fav });
    json(res, { changed, fav: !!b.fav, gen: favGen }); return;
  }
  if (p === "/api/motion") {
    // video živej fotky – tu len prázdne telo (prehliadkový test overuje adresu a prekrytie)
    if (!MOTION_META.has(Number(q.get("id")))) { json(res, { error: "not_motion" }, 404); return; }
    api.push({ motion: Number(q.get("id")) });
    res.writeHead(200, { "Content-Type": "video/mp4", "Cache-Control": "no-store" }); res.end(); return;
  }
  if (p === "/api/state") { json(res, { marked: [...marked], synced: {}, stateGen }); return; }
  if (p === "/api/groupstate") {
    if (req.method === "POST") { const b = await body(req); if (b.key) gstates[b.key] = { s: b.state }; Object.entries(b.states || {}).forEach(([k, s]) => (gstates[k] = { s })); stateGen++; json(res, { states: gstates, stateGen }); return; }
    json(res, { states: gstates, stateGen }); return;
  }
  if (p === "/api/groups") { json(res, { gen: 1, groups }); return; }
  if (p === "/api/mark") {
    const b = await body(req);
    (b.ids || []).forEach((id) => (b.del ? marked.add(id) : marked.delete(id)));
    stateGen++; json(res, { ok: true, stateGen }); return;
  }
  if (p === "/api/meta") {
    const o = {}; (q.get("ids") || "").split(",").filter(Boolean).forEach((id) => {
      o[id] = { make: "Test", model: "Fake", w: 4000, h: 3000, iso: 100 + Number(id) };
      if (MOTION_META.has(Number(id))) o[id].motion = { len: 1234567 };
    });
    api.push({ meta: q.get("ids") });
    json(res, o); return;
  }
  if (p === "/api/quality") {
    const b = await body(req);
    const lists = b.groups || [b.ids];
    const out = { state: "done", scores: {}, groups: {}, unknown: [] };
    lists.forEach((ids, index) => {
      const key = "q" + index + "-" + ids.join("-");
      // neistá ★: skóre takmer rovnaké, ★ dostáva starší súbor
      ids.forEach((id, r) => {
        out.scores[id] = {
          percent: 91 - r, score: (91 - r) / 100, rank: r, groupKey: key, facesChecked: true, reasons: r ? [] : ["older_file"],
          details: r ? [] : [{ code: "older_file", value: 0, total: 0 }], text: r ? "" : "starší súbor – pravdepodobne originál"
        };
      });
      out.groups[key] = { index, ids, order: ids, unscored: [], best: ids[0], confidence: 0.01, uncertain: true, facesChecked: true };
    });
    json(res, out); return;
  }
  if (p === "/api/bin") { json(res, { items: [], allFiles: true, locked: false }); return; }
  if (p === "/api/thumb") {
    const id = Number(q.get("id")) || 0, big = Number(q.get("max")) > 0;
    res.writeHead(200, { "Content-Type": "image/svg+xml", "Cache-Control": "no-store" });
    res.end(svg(id, big ? 1600 : 300, big ? 1200 : 225)); return;
  }
  if (p === "/api/file") {
    requests.push({ id: Number(q.get("id")), q: q.get("q"), max: q.get("max"), dl: q.get("dl") });
    res.writeHead(200, { "Content-Type": "image/svg+xml", "Cache-Control": "no-store" });
    res.end(svg(Number(q.get("id")) || 0, 4000, 3000)); return;
  }
  res.writeHead(404); res.end("nie");
}).listen(PORT, () => console.log("Galéria+ (falošný server): http://localhost:" + PORT + "/"));
