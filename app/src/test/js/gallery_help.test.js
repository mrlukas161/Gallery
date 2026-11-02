// Audit skratiek webu „Galéria+ Sync“: pomocník (GCore.HELP) musí presne zodpovedať obsluhe klávesov v kóde.
// Spustenie: node --test app/src/test/js/*.test.js
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const html = fs.readFileSync(path.join(__dirname, "../../main/assets/gallery.html"), "utf8");
const gcore = html.slice(html.indexOf("/*GCORE-BEGIN*/"), html.indexOf("/*GCORE-END*/"));
const ctx = { module: { exports: {} } };
vm.runInNewContext(gcore + "\nmodule.exports = GCore;", ctx);
const HELP = JSON.parse(JSON.stringify(ctx.module.exports.HELP));
const script = html.slice(html.indexOf('<script nonce="__CSP_NONCE__">'), html.lastIndexOf("</script>"));

// telo funkcie `function <name>(…) {…}` — zátvorky sa počítajú mimo reťazcov a komentárov
function bodyOf(name) {
  const start = script.indexOf("function " + name + "(");
  assert.ok(start >= 0, "funkcia " + name + " nenájdená");
  const open = script.indexOf("{", script.indexOf(")", start));
  let depth = 0, i = open, str = null;
  for (; i < script.length; i++) {
    const c = script[i], n = script[i + 1];
    if (str) {
      if (c === "\\") i++; else if (c === str) str = null;
      continue;
    }
    if (c === "/" && n === "/") { i = script.indexOf("\n", i); continue; }
    if (c === "/" && n === "*") { i = script.indexOf("*/", i) + 1; continue; }
    if (c === '"' || c === "'") { str = c; continue; }
    if (c === "{") depth++;
    else if (c === "}" && --depth === 0) break;
  }
  return script.slice(open, i + 1);
}
const norm = (k) => (k.length === 1 ? k.toLowerCase() : k);
const casesOf = (body) => new Set([...body.matchAll(/case "([^"]*)":/g)].map((m) => norm(m[1])));

test("pomocník: každá obrazovka s obsluhou klávesov má v pomocníkovi presne tie klávesy, ktoré kód spracuje", () => {
  for (const sc of HELP.filter((s) => s.fn)) {
    // fn môže byť aj zoznam funkcií (mriežka a náhľad majú spoločné listKeys)
    const code = new Set([].concat(sc.fn).flatMap((f) => [...casesOf(bodyOf(f))]));
    const doc = new Set(sc.rows.filter((r) => !r.g).flatMap((r) => r.ev || []).map(norm));
    assert.ok(code.size > 3, sc.fn + ": zle vyňatá obsluha");
    const missingInHelp = [...code].filter((k) => !doc.has(k));
    const missingInCode = [...doc].filter((k) => !code.has(k));
    assert.deepEqual(missingInHelp, [], sc.id + ": kód spracuje, pomocník nespomína");
    assert.deepEqual(missingInCode, [], sc.id + ": pomocník sľubuje, kód nespracuje");
  }
});

test("pomocník: globálne klávesy (?, Esc, Ctrl+Z) sú v hlavnom poslucháči", () => {
  const start = script.indexOf('document.addEventListener("keydown"');
  const main = script.slice(start, script.indexOf("function viewerKeys("));
  const need = { "?": 'e.key === "?"', Escape: 'e.key === "Escape"', z: 'e.key === "z"', ContextMenu: 'e.key === "ContextMenu"', F10: 'e.key === "F10"' };
  for (const sc of HELP) {
    for (const r of sc.rows.filter((x) => x.g)) {
      for (const k of r.ev) assert.ok(main.includes(need[k] || "NEZNAMY"), sc.id + ": globálny kláves " + k + " nie je v hlavnom poslucháči");
    }
    assert.ok(sc.rows.some((r) => r.g && r.ev.includes("?")), sc.id + ": chýba riadok „?“");
    assert.ok(sc.rows.some((r) => r.g && r.ev.includes("Escape")), sc.id + ": chýba riadok Esc");
  }
});

test("pomocník: riadky sú úplné a žiadna klávesa sa v obrazovke neopakuje", () => {
  for (const sc of HELP) {
    const seen = new Set();
    for (const r of sc.rows) {
      assert.ok(r.k && r.t, sc.id + ": prázdny riadok");
      assert.ok(r.ev || r.mouse, sc.id + ": riadok „" + r.k + "“ nemá ev ani mouse");
      for (const k of (r.ev || []).map(norm)) {
        assert.ok(!seen.has(k), sc.id + ": kláves " + k + " je dvakrát");
        seen.add(k);
      }
    }
  }
});

test("pomocník sa dá otvoriť z každej vrstvy: tlačidlo ⌨ ? v hlavičke a mapovanie obrazovky", () => {
  const ids = { grid: null, preview: null, viewer: "viewer", groups: "groups", cmp: "cmp", fcmp: "fcmp", marked: "marked", bin: "bin" };
  assert.deepEqual(HELP.map((s) => s.id).sort(), Object.keys(ids).sort());
  for (const [id, ov] of Object.entries(ids)) {
    if (!ov) { assert.ok(/id="helpBtn" data-help/.test(html), "tlačidlo v hlavnej lište"); continue; }
    const at = html.indexOf('id="' + ov + '" role="dialog"');
    assert.ok(at > 0, ov);
    const bar = html.slice(at, html.indexOf("</div>", at));
    assert.ok(bar.includes("data-help"), ov + ": chýba tlačidlo ⌨ ? v hlavičke");
  }
  const map = bodyOf("helpScreen");
  for (const v of ["cmp", "fcmp", "viewer", "groups", "marked", "bin", "preview", "grid"]) assert.ok(map.includes('"' + v + '"'), "helpScreen: " + v);
});

test("pomocník: nemúdrosti z myši — Shift+klik, koliesko, Do chatu, kontextové menu sú naozaj v kóde", () => {
  assert.ok(/grid\.addEventListener\("click"[\s\S]*?e\.shiftKey[\s\S]*?selectRangeTo/.test(script), "Shift+klik v mriežke");
  assert.ok(/pList\.addEventListener\("click"[\s\S]*?e\.shiftKey[\s\S]*?selectRangeTo/.test(script), "Shift+klik v zozname náhľadu");
  // koliesko nad fotkou: jedna obsluha vo Viewer (wheelAction "stage": page / zoom / pan), {passive:false}
  const viewer = bodyOf("Viewer");
  assert.ok(/stage\.addEventListener\("wheel"[\s\S]*?C\.wheelAction\(e, "stage"\)[\s\S]*?e\.preventDefault\(\)[\s\S]*?v\.onpage\(d\)[\s\S]*?\{ passive: false \}/.test(viewer), "koliesko vo Viewer");
  assert.ok(/a === "zoom"[\s\S]*?C\.wheelZoom/.test(viewer) && /a === "pan"[\s\S]*?C\.panBy/.test(viewer), "Ctrl = zoom, Shift = posun");
  assert.ok(/v\.onpage = function \(d\) \{ setActive\(pi\); cmpStep\(d\); \}/.test(script), "koliesko v paneli porovnávača listuje v ňom");
  assert.ok(/vv\.onpage = function \(d\) \{ stepViewer\(d/.test(script), "koliesko v prehliadači listuje");
  assert.ok(/PV\.v\.onpage = function/.test(script) && /P\.v\.onpage = function \(d\) \{ fcStep\(d/.test(script), "koliesko v náhľade a v „Porovnať s…“");
  // mimo fotky: Ctrl + koliesko nad zoznamom = veľkosť miniatúr, zoom stránky sa globálne nevypína
  assert.ok(/window\.addEventListener\("wheel", function \(e\) \{[\s\S]*?a === "thumbs"[\s\S]*?setThumb\([\s\S]*?\{ passive: false, capture: true \}/.test(script), "Ctrl+koliesko nad zoznamom");
  assert.ok(!/if \(e\.ctrlKey \|\| e\.metaKey\) e\.preventDefault\(\); \}, \{ passive: false/.test(script), "zoom stránky nie je vypnutý všade");
  assert.ok(script.includes("function toChat("), "Do chatu");
  for (const fn of ["listKeys", "viewerKeys", "cmpKeys", "fcmpKeys"]) assert.ok(/e\.shiftKey\) toChat\(/.test(bodyOf(fn)), fn + ": Shift+D → Do chatu");
  assert.ok(/document\.addEventListener\("contextmenu"[\s\S]*?if \(e\.shiftKey\) return;[\s\S]*?openCtx\(/.test(script), "pravé tlačidlo: vlastné menu, so Shift pôvodné");
  assert.ok(/role="menu"/.test(html) && /setAttribute\("role", "menuitem"\)/.test(script), "menu má role menu / menuitem");
});

test("medzerník: vo všetkých obsluhách ide cez spaceMark a nik iný ho nepoužíva na stlačenie tlačidla", () => {
  for (const fn of ["viewerKeys", "cmpKeys", "fcmpKeys"]) assert.ok(/case " ": spaceMark\(e, /.test(bodyOf(fn)), fn);
  assert.ok(bodyOf("listKeys").includes("spaceMark(e,"), "listKeys (mriežka aj náhľad)");
  const sm = bodyOf("spaceMark");
  assert.ok(sm.includes("e.preventDefault()") && sm.includes("e.repeat"), "spaceMark: preventDefault a e.repeat");
  assert.ok(!/onButton\(/.test(script), "starý onButton (stláčal medzerník tlačidlá) už nemá byť");
});
