// Prehliadkový test ovládania webu „Galéria+ Sync“ v skutočnom prehliadači proti falošnému serveru.
// Spustenie: node app/src/test/js/e2e/fake_server.js, v prehliadači otvoriť http://localhost:8787/ a v konzole
//   await fetch("/__keys_check.js").then(r => r.text()).then(eval)
// Vráti zoznam {name, ok, detail}. Klávesy sú syntetické (KeyboardEvent), preto to, čo robí až samotný prehliadač
// (posun stránky medzerníkom, stlačenie tlačidla), sa overuje zvlášť skutočnými klávesmi — viď správu k verzii.
(async function () {
  "use strict";
  var R = [];
  function ok(name, cond, detail) { R.push({ name: name, ok: !!cond, detail: cond ? "" : String(detail === undefined ? "" : detail) }); }
  var sleep = function (ms) { return new Promise(function (r) { setTimeout(r, ms); }); };
  var $ = function (s, r) { return (r || document).querySelector(s); };
  var $$ = function (s, r) { return Array.prototype.slice.call((r || document).querySelectorAll(s)); };

  // sťahovanie nezačne naozaj: zachytávame kliky na <a download>
  var dl = [];
  var origClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () { if (this.download) { dl.push({ href: this.getAttribute("href"), name: this.download }); return; } return origClick.call(this); };

  function key(k, opt, target) {
    opt = opt || {};
    var t = target || document.activeElement || document.body;
    var ev = new KeyboardEvent("keydown", { key: k, bubbles: true, cancelable: true, shiftKey: !!opt.shift, ctrlKey: !!opt.ctrl, altKey: !!opt.alt, repeat: !!opt.repeat });
    t.dispatchEvent(ev);
    var up = new KeyboardEvent("keyup", { key: k, bubbles: true, cancelable: true, shiftKey: !!opt.shift, ctrlKey: !!opt.ctrl });
    t.dispatchEvent(up);
    return ev;
  }
  function click(el, opt) {
    opt = opt || {};
    el.dispatchEvent(new MouseEvent("click", { bubbles: true, cancelable: true, shiftKey: !!opt.shift, ctrlKey: !!opt.ctrl, detail: 1 }));
  }
  function cell(i) { return $('.cell[data-i="' + i + '"]'); }
  function curIdx() { var c = $(".cell.cur"); return c ? Number(c.dataset.i) : -1; }
  function selIdx() { return $$(".cell.sel").map(function (c) { return Number(c.dataset.i); }).sort(function (a, b) { return a - b; }); }
  function overlays() { return $$(".ov.on").map(function (o) { return o.id; }); }
  async function closeAll() { for (var k = 0; k < 6 && overlays().length; k++) { key("Escape"); await sleep(30); } key("Escape"); await sleep(30); }
  function helpCur() { var s = $("#help section.cur"); return s ? s.dataset.s : null; }

  // ---------- štart ----------
  for (var w = 0; w < 50 && !cell(0); w++) await sleep(100);
  ok("mriežka sa načítala", cell(0), "bez .cell");
  document.activeElement && document.activeElement.blur && document.activeElement.blur();
  window.scrollTo(0, 0);
  await sleep(100);

  // ---------- 3. mriežka: kurzor ----------
  var e1 = key("ArrowRight");
  ok("→ posunie kurzor na prvú fotku", curIdx() === 0 && e1.defaultPrevented, curIdx());
  key("ArrowRight");
  ok("→ druhá", curIdx() === 1, curIdx());
  var e2 = key("ArrowRight", { ctrl: true });
  ok("Ctrl+→ posunie kurzor (a nepustí ho prehliadaču)", curIdx() === 2 && e2.defaultPrevented, curIdx());
  var cols = Math.round((cell(1).getBoundingClientRect().left - cell(0).getBoundingClientRect().left) > 0 ? $$(".cell").filter(function (c) { return c.getBoundingClientRect().top === cell(0).getBoundingClientRect().top; }).length : 1);
  key("ArrowDown", { ctrl: true });
  ok("Ctrl+↓ o riadok (" + cols + " stĺpcov)", curIdx() === 2 + cols, curIdx());
  for (var d = 0; d < 8; d++) { key("ArrowDown", { ctrl: true }); await sleep(10); }
  await sleep(150);
  var cc = $(".cell.cur"), rr = cc && cc.getBoundingClientRect();
  ok("stránka sa posunie za kurzorom", window.scrollY > 0 && rr && rr.top >= 0 && rr.bottom <= window.innerHeight, "scrollY=" + window.scrollY + " rect=" + (rr && (rr.top + "/" + rr.bottom)));
  key("Home"); await sleep(100);
  ok("Home = prvá a stránka späť hore", curIdx() === 0 && window.scrollY === 0, curIdx() + "/" + window.scrollY);

  // ---------- 2. medzerník v mriežke ----------
  key("ArrowRight"); key("x"); await sleep(200); key("Home"); await sleep(100); // čip „na zmazanie“ už v hlavičke je (jeho vznik by hlavičku zväčšil a bunky posunul)
  var y0 = cell(0).getBoundingClientRect().top;
  var sp = key(" ");
  await sleep(150);
  ok("medzerník označí fotku pod kurzorom na zmazanie", cell(0).classList.contains("ismark") && sp.defaultPrevented, cell(0).className);
  ok("medzerník neposunul mriežku", Math.abs(cell(0).getBoundingClientRect().top - y0) < 1, y0 + " -> " + cell(0).getBoundingClientRect().top);
  key(" "); await sleep(150);
  ok("druhý medzerník zruší označenie", !cell(0).classList.contains("ismark"), cell(0).className);
  key(" ", { repeat: true }); key(" ", { repeat: true }); await sleep(150);
  ok("držaný medzerník (repeat) neprepína", !cell(0).classList.contains("ismark"), cell(0).className);
  $("#selBtn").focus();
  var pr = $("#selBtn").getAttribute("aria-pressed");
  var sp2 = key(" "); await sleep(150);
  ok("medzerník s fokusom na tlačidle ho nestlačí a označí fotku", $("#selBtn").getAttribute("aria-pressed") === pr && sp2.defaultPrevented && cell(0).classList.contains("ismark"), pr + "/" + $("#selBtn").getAttribute("aria-pressed"));
  key(" "); await sleep(150);
  document.activeElement.blur();
  // myš: bunka pod myšou sa stane kurzorom
  cell(7).dispatchEvent(new MouseEvent("mousemove", { bubbles: true, clientX: 400 + Math.random() * 50, clientY: 300 + Math.random() * 50 }));
  ok("myš nad bunkou presunie kurzor", curIdx() === 7, curIdx());

  // ---------- 3. výber intervalu ----------
  click(cell(2), { ctrl: true });
  ok("Ctrl+klik prepne jednu", JSON.stringify(selIdx()) === "[2]", JSON.stringify(selIdx()));
  click(cell(6), { shift: true });
  ok("Shift+klik = interval od poslednej kliknutej (2–6)", JSON.stringify(selIdx()) === "[2,3,4,5,6]", JSON.stringify(selIdx()));
  click(cell(9), { shift: true, ctrl: true });
  ok("Ctrl+Shift+klik rozšíri interval (2–9), pridá sa k výberu", JSON.stringify(selIdx()) === "[2,3,4,5,6,7,8,9]", JSON.stringify(selIdx()));
  click(cell(0), { shift: true });
  ok("Shift+klik na druhú stranu kotvy (0–2 pridané)", JSON.stringify(selIdx()) === "[0,1,2,3,4,5,6,7,8,9]", JSON.stringify(selIdx()));
  click(cell(12), { ctrl: true });
  ok("Ctrl+klik pridá jednu a presunie kotvu", selIdx().indexOf(12) >= 0 && selIdx().length === 11, JSON.stringify(selIdx()));
  click(cell(14), { shift: true });
  ok("Shift+klik od novej kotvy (12–14)", JSON.stringify(selIdx()) === "[0,1,2,3,4,5,6,7,8,9,12,13,14]", JSON.stringify(selIdx()));
  key("Escape"); await sleep(50);
  ok("Esc zruší výber", selIdx().length === 0 && !$("#selbar").classList.contains("on"), JSON.stringify(selIdx()));

  // Shift+šípky
  click(cell(20), { ctrl: true }); click(cell(20), { ctrl: true }); // kotva = 20, výber prázdny
  key("Escape"); await sleep(30);
  cell(20).dispatchEvent(new MouseEvent("mousemove", { bubbles: true, clientX: 10, clientY: 10 }));
  key("ArrowRight"); // kurzor 21, kotva 21
  key("ArrowRight", { shift: true }); key("ArrowRight", { shift: true }); key("ArrowRight", { shift: true });
  ok("Shift+→ ×3 rozšíri výber od miesta, kde sa začalo (21–24)", JSON.stringify(selIdx()) === "[21,22,23,24]" && curIdx() === 24, JSON.stringify(selIdx()) + "/" + curIdx());
  key("ArrowLeft", { shift: true });
  ok("Shift+← výber zmenší (21–23)", JSON.stringify(selIdx()) === "[21,22,23]", JSON.stringify(selIdx()));
  key("ArrowDown", { shift: true, ctrl: true });
  ok("Ctrl+Shift+↓ rozšíri o riadok", selIdx().length > 4 && selIdx()[0] === 21, JSON.stringify(selIdx()));
  key("Escape"); await sleep(30);

  // ---------- Shift+medzerník = výber ----------
  key("ArrowRight"); var ci = curIdx();
  key(" ", { shift: true });
  ok("Shift+medzerník pridá do výberu (nie na zmazanie)", selIdx().length === 1 && !cell(ci).classList.contains("ismark"), JSON.stringify(selIdx()));
  key("Escape"); await sleep(30);

  // ---------- 6. Do chatu ----------
  dl.length = 0;
  click(cell(0), { ctrl: true }); click(cell(1), { ctrl: true }); click(cell(4), { ctrl: true }); // 4 = video
  key("D", { shift: true }); await sleep(2500);
  ok("Shift+D: 2 fotky (video sa preskočí)", dl.length === 2, JSON.stringify(dl));
  ok("názvy majú predponu Claude_ a príponu .jpg", dl.every(function (x) { return /^Claude_IMG_\d+\.jpg$/.test(x.name); }), JSON.stringify(dl));
  ok("zmenšené JPEG, dlhá strana 2048 px", dl.every(function (x) { return /max=2048/.test(x.href) && /q=\d+/.test(x.href) && /dl=1/.test(x.href); }), JSON.stringify(dl));
  ok("toast s pokynom", /2 fotky pre chat v Stiahnutých súboroch – pretiahni ich do Claude/.test($("#toast").textContent), $("#toast").textContent);
  dl.length = 0;
  click($("#selChat")); await sleep(2500);
  ok("tlačidlo 💬 Do chatu v lište výberu", dl.length === 2, JSON.stringify(dl));
  key("Escape"); await sleep(30);
  // bez výberu: fotka pod kurzorom
  dl.length = 0;
  cell(3).dispatchEvent(new MouseEvent("mousemove", { bubbles: true, clientX: 111, clientY: 222 }));
  key("D", { shift: true }); await sleep(900);
  ok("Shift+D bez výberu = fotka pod kurzorom", dl.length === 1 && dl[0].name === "Claude_IMG_0004.jpg", JSON.stringify(dl));
  // viac ako 20
  dl.length = 0;
  click(cell(0), { ctrl: true }); click(cell(22), { shift: true });
  key("D", { shift: true }); await sleep(200);
  ok("nad 20 fotiek sa pýta", $("#ask").classList.contains("on") && /Naozaj stiahnuť všetky/.test($("#askM").textContent) && dl.length === 0, $("#askM").textContent);
  $("#askNo").click(); await sleep(1200);
  ok("Zrušiť = nič sa nestiahlo", dl.length === 0, dl.length);
  key("D", { shift: true }); await sleep(200);
  $("#askOk").click();
  for (var wd = 0; wd < 40 && dl.length < 22; wd++) await sleep(500);
  ok("Stiahnuť všetky = 22 fotiek", dl.length === 22 && dl.every(function (x) { return x.name.indexOf("Claude_") === 0; }), dl.length);
  await sleep(300);
  await closeAll();
  $("#selOff").click(); await sleep(50);

  // ---------- 2. prehliadač fotky ----------
  cell(0).dispatchEvent(new MouseEvent("mousemove", { bubbles: true, clientX: 55, clientY: 66 }));
  key("Enter"); await sleep(300);
  ok("Enter otvorí prehliadač", overlays().indexOf("viewer") >= 0, overlays());
  key(" "); await sleep(200);
  ok("medzerník v prehliadači označí aktuálnu fotku", /na zmazanie/.test($("#vState").textContent), $("#vState").textContent);
  $("#vNext").focus();
  var info0 = $("#vInfo").textContent;
  key(" "); await sleep(200);
  ok("medzerník nestlačí fokusované tlačidlo › a zruší označenie", $("#vInfo").textContent === info0 && !/na zmazanie/.test($("#vState").textContent), $("#vState").textContent);
  key("ArrowRight"); await sleep(100);
  ok("→ ďalšia fotka", /2 \//.test($("#vInfo").textContent), $("#vInfo").textContent);
  dl.length = 0; key("D", { shift: true }); await sleep(900);
  ok("Shift+D v prehliadači = do chatu", dl.length === 1 && /^Claude_/.test(dl[0].name), JSON.stringify(dl));
  var wv = new WheelEvent("wheel", { ctrlKey: true, deltaY: -100, bubbles: true, cancelable: true });
  $("#vStage").dispatchEvent(wv);
  ok("Ctrl+koliesko v prehliadači nezoomuje stránku", wv.defaultPrevented, "");
  // pomocník z prehliadača
  click($("#viewer .hbtn")); await sleep(100);
  ok("⌨ ? v prehliadači otvorí pomocníka so sekciou prehliadača navrchu", helpCur() === "viewer" && overlays().indexOf("help") >= 0, helpCur());
  key("Escape"); await sleep(60);
  ok("zatvorenie pomocníka vráti na prehliadač", overlays().join() === "viewer", overlays());
  key("?"); await sleep(100);
  ok("kláves ? v prehliadači", helpCur() === "viewer", helpCur());
  key("?"); await sleep(60);
  ok("? znova pomocníka zatvorí", overlays().join() === "viewer", overlays());
  await closeAll();

  // ---------- 4. pomocník z každej obrazovky ----------
  key("?"); await sleep(60);
  ok("? v mriežke → sekcia Mriežka navrchu", helpCur() === "grid", helpCur());
  key("Escape"); await sleep(40);
  click($("#helpBtn")); await sleep(60);
  ok("tlačidlo ⌨ ? na hlavnej lište", helpCur() === "grid", helpCur());
  key("Escape"); await sleep(40);
  var inp = $("#q"); inp.focus();
  key("?", {}, inp); await sleep(60);
  ok("? pri písaní do poľa pomocníka neotvorí", overlays().indexOf("help") < 0, overlays());
  inp.blur();
  key("g"); await sleep(400);
  ok("G otvorí Podobné a duplikáty", overlays().indexOf("groups") >= 0, overlays());
  click($("#groups .hbtn")); await sleep(60);
  ok("⌨ ? v Podobné a duplikáty → sekcia groups", helpCur() === "groups", helpCur());
  key("Escape"); await sleep(40);
  key("?"); await sleep(60);
  ok("? v Podobné a duplikáty", helpCur() === "groups", helpCur());
  key("Escape"); await sleep(40);
  // zoznam skupín: ↓ a Home/End
  key("ArrowDown"); await sleep(30);
  ok("↓ v zozname skupín fokusuje riadok", document.activeElement && document.activeElement.classList.contains("grow"), document.activeElement && document.activeElement.className);
  key("End"); await sleep(30);
  var rows = $$("#gBody .grow");
  ok("End = posledná skupina", document.activeElement === rows[rows.length - 1], "");
  key("Home"); await sleep(30);
  ok("Home = prvá skupina", document.activeElement === rows[0], "");
  await closeAll();
  key("m"); await sleep(200);
  click($("#marked .hbtn")); await sleep(60);
  ok("⌨ ? v Na zmazanie → sekcia marked", helpCur() === "marked" && overlays().indexOf("marked") >= 0, helpCur());
  key("Escape"); await sleep(40); await closeAll();
  key("b"); await sleep(300);
  click($("#bin .hbtn")); await sleep(60);
  ok("⌨ ? v Koši → sekcia bin", helpCur() === "bin", helpCur());
  key("Escape"); await sleep(40); await closeAll();

  // ---------- 1. porovnávač ----------
  dl.length = 0;
  click(cell(0), { ctrl: true }); click(cell(1), { ctrl: true }); click(cell(2), { ctrl: true }); click(cell(3), { ctrl: true });
  key("c"); await sleep(500);
  ok("C otvorí porovnávač", overlays().indexOf("cmp") >= 0, overlays());
  function pName(p) { return $('.pane[data-p="' + p + '"] .nm').textContent; }
  function active() { return $$(".pane.active").map(function (x) { return Number(x.dataset.p); }); }
  ok("2 panely: 1 a 2, aktívny je 1", pName(0) === "IMG_0001.jpg" && pName(1) === "IMG_0002.jpg" && active().join() === "0", pName(0) + "," + pName(1) + " act=" + active());
  var a0 = $(".pane.active").getBoundingClientRect();
  var cs = getComputedStyle($(".pane.active"));
  ok("aktívny panel je zreteľne vyznačený (rám 3 px + štítok „aktívny“)", cs.outlineWidth === "3px" && cs.outlineStyle === "solid" && getComputedStyle($(".pane.active .act")).display !== "none" && getComputedStyle($(".pane:not(.active) .act")).display === "none", cs.outline);
  key("ArrowRight"); await sleep(250);
  ok("→ mení LEN aktívny panel (1→3, druhý ostáva 2)", pName(0) === "IMG_0003.jpg" && pName(1) === "IMG_0002.jpg", pName(0) + "," + pName(1));
  key("ArrowRight"); await sleep(250);
  ok("→ ďalšia (4)", pName(0) === "IMG_0004.jpg" && pName(1) === "IMG_0002.jpg", pName(0) + "," + pName(1));
  key("ArrowRight"); await sleep(250);
  ok("→ na konci zostane", pName(0) === "IMG_0004.jpg", pName(0));
  key("2"); await sleep(50);
  ok("kláves 2 urobí aktívnym druhý panel", active().join() === "1", active());
  key("ArrowLeft"); await sleep(250);
  ok("← v druhom paneli mení len druhý (2→1), prvý ostáva 4", pName(1) === "IMG_0001.jpg" && pName(0) === "IMG_0004.jpg", pName(0) + "," + pName(1));
  // medzerník
  key(" "); await sleep(250);
  ok("medzerník označí fotku aktívneho panela", /na zmazanie/.test($('.pane[data-p="1"] .st').textContent) && $('.pane[data-p="0"] .st').textContent.indexOf("na zmazanie") < 0, $('.pane[data-p="1"] .st').textContent);
  key(" "); await sleep(250);
  ok("druhý medzerník označenie zruší", $('.pane[data-p="1"] .st').textContent.indexOf("na zmazanie") < 0, $('.pane[data-p="1"] .st').textContent);
  // Ctrl+koliesko
  var st0 = $('.pane[data-p="0"] .stage'), n0 = pName(0);
  var cw = new WheelEvent("wheel", { ctrlKey: true, deltaY: -100, bubbles: true, cancelable: true, clientX: 200, clientY: 300 });
  st0.dispatchEvent(cw); await sleep(300);
  ok("Ctrl+koliesko nad panelom 1: zoom stránky zablokovaný", cw.defaultPrevented, "");
  ok("Ctrl+koliesko hore = predošlá fotka v tom paneli (4→3), panel sa stal aktívnym, druhý nezmenený", active().join() === "0" && n0 === "IMG_0004.jpg" && pName(0) === "IMG_0003.jpg" && pName(1) === "IMG_0001.jpg", n0 + " -> " + pName(0) + "," + pName(1) + " act=" + active());
  var back = new WheelEvent("wheel", { ctrlKey: true, deltaY: 100, bubbles: true, cancelable: true });
  st0.dispatchEvent(back); await sleep(300);
  ok("Ctrl+koliesko dole = ďalšia (3→4)", pName(0) === "IMG_0004.jpg", pName(0));
  var cw2 = new WheelEvent("wheel", { ctrlKey: true, deltaY: 100, bubbles: true, cancelable: true });
  $("#strip").dispatchEvent(cw2);
  ok("Ctrl+koliesko mimo panelov tiež nezoomuje stránku", cw2.defaultPrevented, "");
  // koliesko = zoom do bodu pod kurzorom
  var im = $('.pane[data-p="0"] .stage img.vimg'), tr0 = im.style.transform;
  var pw = new WheelEvent("wheel", { deltaY: -200, bubbles: true, cancelable: true, clientX: st0.getBoundingClientRect().left + 40, clientY: st0.getBoundingClientRect().top + 40 });
  st0.dispatchEvent(pw); await sleep(150);
  var scale = Number((im.style.transform.match(/scale\(([\d.]+)\)/) || [])[1]);
  ok("koliesko približuje a posúva do bodu pod kurzorom", pw.defaultPrevented && im.style.transform !== tr0 && scale > 0, tr0 + " -> " + im.style.transform);
  var sc2 = Number(($('.pane[data-p="1"] .stage img.vimg').style.transform.match(/scale\(([\d.]+)\)/) || [])[1]);
  ok("spoločné priblíženie: druhý panel sleduje relatívne", sc2 > 0, sc2);
  // Shift+D
  dl.length = 0; key("D", { shift: true }); await sleep(900);
  ok("Shift+D v porovnávači = do chatu (aktívny panel)", dl.length === 1 && /^Claude_/.test(dl[0].name) && /max=2048/.test(dl[0].href), JSON.stringify(dl));
  // ★ vyhodnotiť, neistá
  key("q"); await sleep(1800);
  var qt = $("#cQual").textContent;
  ok("★ neistá: percentá všetkých fotiek", /Skóre sú takmer rovnaké \(91 %, 90 %, 89 % a 88 %\)/.test(qt), qt);
  ok("★ neistá: vysvetlenie prečo", /★ dostala staršia fotka ako pravdepodobný originál – kvalitou sa nelíšia, rozhodni sám\./.test(qt), qt);
  ok("starý text „neistá – rozdiel je malý, rozhodni sám“ je preč", qt.indexOf("rozdiel je malý – neistá") < 0, qt);
  // pomocník z porovnávača
  click($("#cmp .hbtn")); await sleep(80);
  ok("⌨ ? v porovnávači → sekcia cmp navrchu", helpCur() === "cmp" && overlays().indexOf("cmp") >= 0, helpCur());
  var firstSec = $("#hBody section.cur");
  ok("sekcia aktuálnej obrazovky je prvá (order) a zvýraznená", getComputedStyle(firstSec).order === "-1", getComputedStyle(firstSec).order);
  key("Escape"); await sleep(50);
  ok("po zatvorení pomocníka sme späť v porovnávači", overlays().join() === "cmp", overlays());
  key("Escape"); await sleep(100);
  await closeAll();
  $("#selOff").click(); await sleep(50);

  // presné kópie
  key("g"); await sleep(400);
  var exactRow = $$("#gBody .grow").filter(function (r) { return /Presné kópie/.test(r.textContent); })[0];
  if (exactRow) { click(exactRow); await sleep(500); }
  ok("presné kópie: oznam, že sú to rovnaké fotky", /sú to rovnaké fotky/.test($("#cQual").textContent) && !$("#cQual").hidden, $("#cQual").textContent);
  await closeAll();

  // ---------- Ctrl+koliesko mimo porovnávača ----------
  var gw = new WheelEvent("wheel", { ctrlKey: true, deltaY: 100, bubbles: true, cancelable: true });
  document.body.dispatchEvent(gw);
  ok("Ctrl+koliesko v mriežke: zoom stránky zablokovaný", gw.defaultPrevented, "");

  HTMLAnchorElement.prototype.click = origClick;
  var bad = R.filter(function (x) { return !x.ok; });
  console.log("keys_check: " + (R.length - bad.length) + " / " + R.length + " v poriadku");
  bad.forEach(function (x) { console.log("ZLYHALO: " + x.name + " — " + x.detail); });
  return { total: R.length, failed: bad.length, bad: bad, all: R.map(function (x) { return (x.ok ? "OK    " : "ZLYHA ") + x.name; }) };
})();
