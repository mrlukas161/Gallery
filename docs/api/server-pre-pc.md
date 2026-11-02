# LAN server Galérie+ — API pre PC (verzia 3)

Server beží v mobile (Nastavenia → PC server, služba `HttpSyncService`, kód `sync/MediaServer.kt`),
predvolene na porte **8089**. Je určený pre samostatný PC porovnávač a pre web „Galéria+ Sync“
(`app/src/main/assets/gallery.html`, otvára sa na `http://<IP mobilu>:8089/`).

Verzia 3 je spätne kompatibilná s verziou 2 — staré volania fungujú ďalej. Rozdiely sú v časti
„Zmeny oproti verzii 2“ na konci.

## Prihlásenie

Každý **ručný** štart servera vygeneruje nový 6-miestny PIN (ukáže ho notifikácia v mobile). Keď systém
službu sám reštartuje (napr. po uvoľnení pamäte), server beží ďalej s **tým istým PIN-om aj `epoch`** a prihlásenia
prehliadačov (cookie) ostanú platné — web ani PC program sa neodhlásia. Nový PIN je až po výslovnom zastavení
servera (Nastavenia, tlačidlo v notifikácii) alebo po zamknutí pre hádanie PIN-u.

| Klient | Ako sa prihlási |
|---|---|
| PC program | hlavička `X-Auth-Token: <PIN>` pri každej požiadavke (odporúčané) alebo `?token=<PIN>` v adrese |
| prehliadač | `POST /api/login` s `{"pin":"<PIN>"}` → cookie `gp_sid` (`HttpOnly; SameSite=Strict`) |

- Po 20 **rôznych** zlých PIN-och sa server zamkne (`403 {"error":"locked"}`) a služba sa zastaví.
  Nový štart v mobile = nový PIN.
- Bez prihlásenia: `403 {"error":"forbidden"}`. Bez prihlásenia ide len `GET /` (stránka bez dát),
  `POST /api/login` a `/favicon.ico`.
- Session žije len v pamäti servera (12 h nečinnosti, najviac 64 naraz). `POST /api/logout` ju zruší.

## Bezpečnosť

- **Zmeny len cez POST** (`/api/mark`, `/api/synced`, `/api/clearmarks`, `/api/delete`, `/api/restore`,
  `/api/groupstate` pri zápise, `/api/logout`, `/api/quality`, `/api/favorite`, `/api/zip`; `/api/upload` aj PUT). GET na ne vráti
  `405 {"error":"use POST"}`.
  **Dočasne (0.67):** `/api/mark`, `/api/synced` a `/api/clearmarks` prijmú aj starší tvar GET `?id=&del=`,
  ale len s PIN tokenom priamo v požiadavke (`X-Auth-Token` alebo `?token=`). Po prechode PC programu na POST sa to zruší.
- **Origin**: ak požiadavka pošle hlavičku `Origin`, musí byť `http://<Host>` (inak `403 {"error":"bad_origin"}`).
  Rovnako `Sec-Fetch-Site` musí byť `same-origin` alebo `none`. PC program tieto hlavičky neposiela → prejde.
  `Origin: null` neprejde nikdy. Stránka webu má preto `Referrer-Policy: same-origin` (hlavička aj `<meta>`) —
  pri `no-referrer` by Chrome formulárový POST (`/api/zip`) poslal s `Origin: null`. Ostatné odpovede majú
  `Referrer-Policy: no-referrer`.
- Každá odpoveď má `X-Content-Type-Options: nosniff`, `Referrer-Policy` (viď vyššie), `X-Frame-Options: DENY`,
  `Cross-Origin-Resource-Policy: same-origin`. Stránka má `Content-Security-Policy` s jednorazovým nonce.
- **Chyby sú vždy JSON** `{"error":"<kód>"}` s HTTP stavom (400, 403, 404, 405, 409, 411, 413, 416, 429, 500).
- Telá JSON požiadaviek: `Content-Type: application/json; charset=utf-8` a povinná `Content-Length`
  (bez nej `411`, nad limit `413` a spojenie sa zavrie).
- Server sprístupní **len** MediaStore obrázky a videá mimo zamknutých a vylúčených priečinkov
  (IndexScope). Klient adresuje súbory len cez `id` (MediaStore `_ID`), nikdy cestou.

## Prehľad

| Metóda | Cesta | Na čo |
|---|---|---|
| GET | `/api/ping` | stav servera, generácie, `features` |
| GET | `/api/stats` | diagnostika rýchlosti (časy, cache, stav telefónu) |
| POST | `/api/login`, `/api/logout` | session pre prehliadač |
| GET | `/api/list` | zoznam fotiek a videí (celý alebo len zmeny) |
| GET | `/api/file?id=` | originál (Range, ETag) alebo zmenšené JPEG |
| GET | `/api/thumb?id=` | náhľad JPEG (aj HEIC/DNG/video) |
| GET | `/api/meta?ids=` | EXIF pre porovnávač (aj dĺžka videa pohyblivej fotky) |
| GET | `/api/motion?id=` | video z pohyblivej fotky (Range) |
| GET | `/api/favorites` | obľúbené (rovnaké ako v appke) |
| POST | `/api/favorite` | pridať / odobrať obľúbené |
| POST | `/api/zip` | viac súborov naraz ako jeden ZIP (formulár) |
| GET | `/api/groups` | série a duplikáty (rovnaké ako v mobile) |
| POST | `/api/quality` | skóre kvality skupiny (ostrosť, expozícia, tváre, oči) — počíta mobil |
| GET/POST | `/api/groupstate` | stav prechádzania skupín |
| GET | `/api/state` | značky na zmazanie a stiahnuté do PC |
| POST | `/api/mark`, `/api/synced`, `/api/clearmarks` | značky (návrh) a evidencia stiahnutých |
| POST | `/api/delete` | zmazanie do Koša Galérie+ (s `dryRun`) |
| GET | `/api/bin` | Kôš Galérie+ |
| POST | `/api/restore` | obnova z Koša |
| POST/PUT | `/api/upload` | nahratie súboru do mobilu |

## `GET /api/ping`

```json
{"name":"Galéria+","version":"3","epoch":"q3J_x9Zk","count":10512,"gen":7,"stateGen":42,"favGen":1730369000123,
 "newest":1730370000,"allFiles":true,"recycleBin":true,
 "features":["session","sinceGen","etag","range","groupsV2","groupState","meta","markBatch",
             "syncedBatch","deleteDryRun","bin","restore","thumbMax","dlMark","quality","stateSince","stats",
             "zip","favorites","motion","thumbFull"]}
```

- `version` — `"3"`; podľa `features` sa rozhoduj, ktoré API použiť (staré servery pole nemajú).
- `epoch` — náhodné id behu servera. Iný `epoch` = server sa reštartoval → načítaj zoznam celý. Pri reštarte
  služby systémom ostáva `epoch` rovnaký; `gen` vtedy pokračuje s odstupom (nikdy sa nevráti na číslo, ktoré klient
  už videl s iným obsahom), takže `sinceGen` vráti `full:true`.
- `gen` — generácia zoznamu, zvýši sa pri každej zmene (pribudnutá, zmazaná, upravená fotka). Nie je to počítadlo
  od nuly — porovnávaj len na rovnosť.
- `stateGen` — zvýši sa pri každej zmene značiek, stiahnutých alebo stavu skupín (aj z mobilu).
- `favGen` — zmení sa pri zmene obľúbených (z PC hneď, z mobilu najneskôr pri ďalšej obnove zoznamu alebo
  `GET /api/favorites`). Porovnávaj len na rovnosť.
- `allFiles` — appka má „Prístup ku všetkým súborom“ → funguje `/api/delete` a `/api/restore`.
- `recycleBin` — Kôš je zapnutý (mazanie ide do Koša, inak natrvalo).

Odporúčaný cyklus PC programu: `ping` každé 2–4 s; pri zmene `gen` → `/api/list?sinceGen=<starý gen>`,
pri zmene `stateGen` → `/api/state?since=<starý stateGen>` (a `/api/groupstate`), pri zmene `epoch` → celý zoznam.
`ping` (a `/api/stats`) sa **nerátajú ako práca servera** — otvorená karta, ktorá len pinguje, nebrzdí spracovanie
knižnice v mobile ani nedrží mobil prebudený.

## `GET /api/list`

Bez parametrov (pôvodný formát) — pole položiek, zoradené **podľa dátumu, najnovšie prvé** (fotky aj videá spolu):

```json
[{"id":1234,"relpath":"Camera/IMG_1.jpg","name":"IMG_1.jpg","size":3456789,"mtime":1730370000,
  "taken":1730369990123,"takenReal":true,"bucket":"Camera","mime":"image/jpeg",
  "w":4000,"h":3000,"orient":90,"marked":false,"synced":0,"dur":15000}]
```

| Pole | Význam |
|---|---|
| `mtime` | čas zmeny súboru v **sekundách** |
| `taken` | dátum snímania v ms; ak chýba, náhradne `mtime*1000` |
| `takenReal` | `true` = `taken` je skutočný dátum snímania (nie náhrada) |
| `w`, `h`, `orient` | rozmery **uložených** pixelov a otočenie v stupňoch (0/90/180/270); pri 90/270 si ich vymeň. 0 = neznáme |
| `dur` | dĺžka videa v ms (len videá) |
| `marked` | označené na zmazanie (z webu/PC) |
| `synced` | čas zápisu „✓ v PC“ v ms, 0 = nie |
| `fav` | `true` len pre obľúbenú (rovnaké obľúbené ako v appke); inak pole chýba |
| `motion` | `true` len pre pohyblivú fotku, ktorú mobil už pozná z prehľadávania „Špeciálnych fotiek“ (bez čítania súboru); inak pole chýba — over cez `/api/meta` |

- `?since=<mtime>` — len položky s `mtime >= since` (pôvodné, nevidí zmazané; radšej `sinceGen`).
- `?sinceGen=<N>` — len zmeny od generácie N:

```json
{"gen":9,"epoch":"q3J_x9Zk","stateGen":42,"full":false,
 "upserts":[{…položka…}],"removed":[1200,1201]}
```

  `full:true` = história nestačí (príliš stará generácia, iný beh servera, `N=-1`) — `upserts` je celý
  zoznam a starý treba zahodiť. `upserts` = nové aj zmenené položky.
- Odpoveď má `ETag`; s `If-None-Match` vráti `304` bez tela.

## `GET /api/file?id=<id>`

- Bez ďalších parametrov: **originál** – súbor sa streamuje z MediaStore bez prekódovania (bajt po bajte).
  `Content-Length` je skutočná veľkosť súboru.
  - `ETag: "f-<id>-<mtime>-<size>"`, `Cache-Control: private, no-cache`, `If-None-Match` → `304`.
  - `Accept-Ranges: bytes`; `Range: bytes=a-b`, `bytes=a-`, `bytes=-n` → `206` + `Content-Range`;
    mimo súboru `416` + `Content-Range: bytes */<size>`; viac rozsahov naraz → celý súbor (200).
    `If-Range` s iným ETagom → celý súbor. Video v prehliadači sa tak dá posúvať.
  - `Content-Disposition: inline; filename="<ASCII náhrada>"; filename*=UTF-8''<presný názov>`.
- `&dl=1` — `Content-Disposition: attachment`. Server zapíše **„✓ v PC“ sám**, až keď odošle celý
  originál (prerušené sťahovanie ani `Range` sa nezapíšu). PC program môže namiesto toho volať `/api/synced`.
- `&q=<30–100>[&max=<256–4096>]` — **zmenšené JPEG** (dlhá strana najviac `max`, predvolene 2048), správne otočené.
  Funguje aj pre HEIC/DNG. Názov v `Content-Disposition` má príponu `.jpg`. Nezapisuje „✓ v PC“.
  Pri `max ≥ 1024` sa výsledok drží v diskovej cache mobilu (druhé stiahnutie je hneď). Pri veľmi veľkých fotkách
  (nad ~25 Mpx, napr. HEIC 50 Mpx) môže byť dlhá strana o kúsok menšia ako `max` (8160 px → 4080 namiesto 4096) —
  mobil tak nedekóduje celých ~200 MB. Pri preťažení `503 {"error":"busy"}` + `Retry-After: 1` (viď nižšie).

## `GET /api/thumb?id=<id>`

- `&s=<64–1024>` (predvolene 300) — malý náhľad (mriežka). Do 512 px z `ContentResolver.loadThumbnail`
  (Android 10+), cache v pamäti servera podľa id + mtime + veľkosti.
- `&max=<64–8192>` — väčší náhľad, napr. `max=2560` pre porovnávač (dekóduje sa raz cez ImageDecoder priamo na
  cieľovú veľkosť, EXIF otočenie je uplatnené; HEIC, DNG aj video snímka). Náhľad sa nikdy nezväčšuje nad
  originál — **„Plná kvalita“** HEIC/DNG = `max` ≥ dlhá strana originálu (feature `thumbFull`). Kvalita JPEG:
  do 512 px 80, do 2560 px 85, nad tým 92. Nad 4096 px sa dekóduje **len jeden obrázok naraz**; medzivýsledok
  má najviac 8192² px (50 Mpx sa dekóduje celé, 200 Mpx so vzorkovaním 2). Náhľady ≥ 1024 px sú aj v diskovej
  cache mobilu (`cacheDir/pc_thumbs`, ~300 MB, najdlhšie nepoužité sa mažú) — prežijú reštart servera.
- Veľké dekódovania (> 512 px) idú najviac **2 naraz** (nad 4096 px jedno výhradne), ďalších najviac **6 čaká**;
  ďalšia požiadavka dostane hneď
  `503 {"error":"busy"}` + `Retry-After: 1` → skús znova o sekundu. Keď klient spojenie zavrie (prehliadač zrušil
  načítanie obrázka), mobil náhľad, ktorý ešte nezačal dekódovať, zahodí.
- `&v=<mtime>` — keď sedí s aktuálnym `mtime`, odpoveď má `Cache-Control: private, max-age=604800, immutable`;
  inak `private, no-cache` + `ETag`.
- `?bin=<key>&s=…` — náhľad fotky z Koša (`key` z `/api/bin`).

## `GET /api/meta?ids=1,2,3`

Najviac 8 id. Vráti objekt podľa id (neznáme id chýbajú). Čísla sú surové, chýbajúce polia sa vynechajú:

```json
{"1234":{"id":1234,"format":"JPEG","w":4000,"h":3000,"orient":90,"size":3456789,
         "make":"Google","model":"Pixel 8","lens":"…","focal":6.9,"focal35":25,"fnum":1.68,
         "exp":0.008,"iso":50,"flash":false,"gps":true,"software":"HDR+ 1.0","taken":"2024:10:31 12:00:00"}}
```

`exp` je čas v sekundách, `gps` = poloha v EXIF áno/nie (súradnice sa neposielajú). Video: `format`, `w`, `h`, `dur`.
Pohyblivá fotka má navyše `"motion":{"len":<bajty videa>}` (video stiahneš cez `/api/motion`).

## `GET /api/motion?id=<id>`

Video vložené v pohyblivej fotke (Xiaomi/HyperOS, Samsung, Pixel) ako `video/mp4` — výrez súboru
`[videoOffset, videoOffset+len)`. Feature `motion`.

- `Accept-Ranges: bytes`, `Range` voči **dĺžke výrezu** (`bytes=a-b`, `bytes=a-`, `bytes=-n`) → `206` +
  `Content-Range: bytes a-b/<len>`; mimo výrezu `416` + `Content-Range: bytes */<len>`.
- `ETag`, `If-None-Match` → `304`, `Cache-Control: private, max-age=3600`.
- Nie je pohyblivá (alebo je to video) → `404 {"error":"not_motion"}`; neznáme id `404 {"error":"unknown id"}`.
- Nezapisuje „✓ v PC“.

## Obľúbené (`/api/favorites`, `/api/favorite`)

Rovnaké obľúbené ako v appke (DB obľúbených + príznak v knižnici appky). Feature `favorites`.

- `GET /api/favorites` → `{"ids":[11,12],"gen":1730369000124}` — id z indexu servera (bez zamknutých
  a vylúčených priečinkov); `gen` = `favGen` z `ping`.
- `POST /api/favorite` `{"ids":[11,12],"fav":true}` (najviac **2000** id, telo do 64 kB) →
  `{"changed":[12],"fav":true,"gen":1730369000125}` — `changed` = id, ktorým sa stav naozaj zmenil (neznáme id
  a tie, čo už stav mali, sa vynechajú). Chyby: `400 {"error":"too_many"}`, `400 {"error":"bad ids"}`,
  `400 {"error":"bad fav"}` (chýba `fav`). Na Androide 11+ sa skúsi aj príznak obľúbenej v MediaStore (pre iné
  galérie) — ak to systém bez dialógu nedovolí, potichu sa vynechá.
- Zmena posunie `favGen` aj `gen` zoznamu: `/api/list?sinceGen` vráti zmenené položky v `upserts` s novým `fav`.

## `POST /api/zip`

Viac súborov ako **jeden ZIP** (prehliadač inak blokuje hromadné sťahovanie). Feature `zip`.

- Telo `application/x-www-form-urlencoded` (formulár): `ids=1,2,3` (najviac **500** rôznych id; pole sa môže
  opakovať) a voliteľne `token=<PIN>` pre klienta bez cookie (overí sa ako `X-Auth-Token`, zlý PIN sa do
  ochrany pred hádaním nezaráta). POST + kontrola `Origin` ako pri ostatných zmenách (`Origin: null` neprejde).
- Odpoveď `200 application/zip`, `Content-Disposition: attachment; filename="Galeria-RRRRMMDD-HHMM.zip"`
  (miestny čas mobilu), `Transfer-Encoding: chunked` — ZIP sa skladá priebežne, nič sa nedrží v pamäti.
  Položky bez kompresie, mená ako v mobile (duplicity `IMG_1 (2).jpg`), čas záznamu = čas zmeny súboru.
- Neznáme / nedostupné id (zamknuté, vylúčené, zmazané) a súbory, ktoré sa nedali otvoriť, sa preskočia
  a na koniec ZIP-u pribudne `chybajuce.txt` so zoznamom.
- „✓ v PC“ sa zapíše až keď ZIP odišiel celý a bez chyby, len pre súbory, ktoré v ňom celé sú. Pri chybe sa
  prenos preruší (prehliadač ukáže nedokončené sťahovanie) a nič sa neoznačí.
- Chyby: `400 {"error":"too_many","max":500}`, `400 {"error":"bad ids"}`, `403 forbidden/bad_origin/locked`,
  `405 use POST`, `411`/`413` (telo bez dĺžky / nad 64 kB, spojenie sa zavrie), `503 {"error":"busy"}` +
  `Retry-After: 2` (naraz najviac **2 ZIP-y**; každý zaberá jedno spojenie z limitu 20).

## `GET /api/groups`

Skupiny sú **tie isté ako v mobile** (Podobné a duplikáty, `helpers/CompareRepository`): série len
zo skutočného dátumu snímania, presné kópie a podobné bez prekryvu, bez zamknutých/vylúčených priečinkov
a bez skupín skrytých v mobile („Ponechať všetky“ / „Nie sú to duplikáty“). Séria a duplikáty sa môžu
prekrývať (fotka zo série môže mať aj presnú kópiu inde). Len obrázky.

`?v=2`:

```json
{"gen":7,"groups":[{"key":"3f9a0c1b2d4e5f60","kind":"burst","ids":[11,12,13],"keeper":12,
  "bytes":9000000,"reclaim":6000000,"from":1730369990000,"to":1730369994000,"bucket":"Camera",
  "best":13,"confidence":0.12,"reasons":["sharper","all_eyes_open"],"uncertain":false,"keep":13}]}
```

| Pole | Význam |
|---|---|
| `key` | stabilný kľúč: prvých 16 hex znakov SHA-1 zo zoradených ciest spojených `\n` (rovnaký naprieč reštartmi) |
| `kind` | `burst` (séria), `exact` (presné kópie), `similar` (podobné) |
| `ids` | poradie na porovnanie: séria chronologicky, duplikáty od najväčšieho rozlíšenia |
| `keeper` | fotka, ktorá by ostala podľa rozlíšenia/veľkosti (vždy) |
| `bytes`, `reclaim` | súčet veľkostí; koľko sa uvoľní, keď ostane `keeper` |
| `from`, `to` | najstarší / najnovší dátum v skupine (ms) |
| `bucket` | najčastejší priečinok |
| `best`, `confidence`, `reasons` | najlepšia podľa kvality (ostrosť, oči, expozícia…) z cache porovnávača v mobile — **vždy, keď sú všetky fotky skupiny spočítané** (porovnávač v mobile, hromadné „Ponechať najlepšiu“ alebo `/api/quality`); fotky, ktoré sa nedajú prečítať, sa vynechajú. Inak `null`/`[]`. `/api/groups` sám nič nepočíta. `confidence` = náskok skóre ★ pred najlepšou z ostatných (0–1), `reasons` = kódy `Reason.code` |
| `uncertain` | `true` = ★ je neistá: náskok pod 0,05, alebo pod 0,15, keď sa tváre nehodnotili (model bol obsadený), alebo je zmeraná len jedna fotka. `null` = ešte nespočítané. Hromadné „Ponechať najlepšiu“ takú skupinu preskočí |
| `keep` | čo by ponechalo hromadné „Ponechať najlepšiu“: pri presných kópiách **vždy** podľa pravidla vyššie rozlíšenie → Fotoaparát/DCIM → starší súbor (bez analýzy), inak `best`, ak nie je neistá; inak `null` |

Zoradené od najnovšej skupiny. Odpoveď má `ETag` (`If-None-Match` → 304).

Bez `v` (pôvodný formát): `{"burst":[[id,…],…],"similar":[[id,…],…]}` z tých istých skupín
(`similar` = presné kópie aj podobné).

## `POST /api/quality`

Skóre kvality pre skupinu fotiek — rovnaké ako ★ v porovnávači v mobile. Počíta **mobil** (ostrosť,
expozícia, tváre, otvorené oči, úsmevy) cez `CompareAnalysis`; model tvárí ide cez HeavyGate (nebeží naraz
so spracovaním knižnice — keď ho knižnica drží dlhšie ako ~10 s, dopočíta sa bez tvárí). Výsledky sa
pamätajú (cache v mobile), takže druhý raz je odpoveď hneď a objavia sa aj v `/api/groups` (`best`).

Telo (najviac **300 rôznych id** a **50 skupín** v jednej požiadavke, telo do 64 kB):

- `{"ids":[11,12,13]}` — jedna skupina (skóre sa porovnáva v rámci daných fotiek),
- `{"groups":[[11,12,13],[20,21]]}` — viac skupín v jednej úlohe (hromadné „Ponechať najlepšiu“).

Odpovede:

| Stav | Kedy | Telo |
|---|---|---|
| `200` | všetko spočítané | výsledok (nižšie) |
| `202` | mobil práve počíta túto požiadavku | `{"state":"running","done":12,"total":40,"waiting":false,"retryMs":1000}` |
| `429` | mobil počíta **inú** požiadavku (jedna úloha naraz) | `{"error":"busy","done":3,"total":9,"retryMs":2000}` + `Retry-After: 2` |
| `400` | zlé telo / nad limit | `{"error":"bad ids"}` alebo `{"error":"too_many_ids","maxIds":300,"maxGroups":50}` |

Pri `202`/`429` pošli **tú istú** požiadavku znova o `retryMs` (prvá požiadavka úlohu spustí, ďalšie len
pýtajú priebeh). `done`/`total` = zmerané / všetky rôzne fotky. `waiting:true` = čaká sa, kým spracovanie
knižnice uvoľní model tvárí. Keď sa klient ~30 s nepýta, úloha sa sama zruší; dlhšie ako 5 min nebeží nikdy.

Výsledok (`200`):

```json
{"state":"done",
 "scores":{"13":{"sharpRel":1.0,"exposure":0.82,"faces":3,"eyesOpen":3,"smiles":2,"score":0.91,"percent":91,
                 "rank":0,"reasons":["sharper","all_eyes_open"],
                 "details":[{"code":"sharper","value":18,"total":0},{"code":"all_eyes_open","value":3,"total":0}],
                 "text":"ostrejšia o 18 %, všetci 3 majú otvorené oči","facesChecked":true,"groupKey":"3f9a0c1b2d4e5f60"},
           "12":{"sharpRel":0.84,"exposure":0.8,"faces":3,"eyesOpen":2,"smiles":1,"score":0.72,"percent":72,"rank":1,
                 "reasons":["eyes_closed","blurrier"],"details":[…],"text":"1 má zavreté oči, menej ostrá o 16 %",
                 "facesChecked":true,"groupKey":"3f9a0c1b2d4e5f60"}},
 "groups":{"3f9a0c1b2d4e5f60":{"index":0,"ids":[11,12,13],"order":[13,12],"unscored":[11],"best":13,
                               "confidence":0.19,"uncertain":false,"facesChecked":true}},
 "unknown":[]}
```

| Pole | Význam |
|---|---|
| `scores.<id>` | skóre fotky v rámci jej skupiny: `rank` 0 = ★, `score`/`percent` 0–1 / %, `sharpRel` ostrosť voči najostrejšej v skupine, `exposure` 0–1, `faces`/`eyesOpen`/`smiles` (0, keď sa tváre nehodnotili), `reasons` = kódy `Reason.code`, `details` = kód s číslom (`value`: percentá, počet tvárí, desatiny Mpx; `total`: „z koľkých“), `text` = slovenské zdôvodnenie ako v mobile, `groupKey` = skupina |
| `groups.<key>` | `key` = 16 hex znakov SHA-1 zo zoradených ciest (ako v `/api/groups`, ak pošleš celú skupinu), `index` = poradie v požiadavke, `order` = id so skóre od najlepšej, `unscored` = fotky, ktoré sa nedali prečítať (**nikdy ich nemaž automaticky**), `best`, `confidence`, `uncertain`, `facesChecked` ako v `/api/groups` |
| `unknown` | id, ktoré server nepozná alebo nie sú fotky (videá sa nehodnotia) |

Ak je to isté id vo viacerých skupinách jednej požiadavky, `scores.<id>` je z poslednej z nich — pre
hromadné rozhodovanie používaj `groups.<key>.order` a `best`.

## `GET/POST /api/groupstate`

Stav prechádzania skupín (web: „Spracované X z Y“), uložený v mobile (prefs `galeria_pcsync`).

- `GET` → `{"states":{"<key>":{"s":"done","t":1730370000000}},"stateGen":42}`
- `POST {"key":"<key>","state":"done"|"skip"|""}` alebo `POST {"states":{"<key>":"done",…}}`
  (`""` stav zmaže). Neplatné kľúče sa ignorujú. Drží sa najviac 5000 skupín.

## Značky na zmazanie a stiahnuté

Značka je len **návrh**. Zmazanie potvrdí používateľ v mobile (Galéria+ sa opýta pri otvorení a presunie
fotky do Koša Galérie+, pri vypnutom Koši natrvalo) alebo PC/web cez `/api/delete`.

- `GET /api/state` → `{"marked":[id,…],"synced":{"<id>":<ms>},"stateGen":42,"full":true}`
- `GET /api/state?since=<stateGen>` — **len zmeny** od danej generácie (feature `stateSince`):
  `{"full":false,"since":41,"marked":[id…],"unmarked":[id…],"synced":{"<id>":<ms>},"stateGen":42}` —
  `marked`/`unmarked` = fotky, ktorým sa zmenila značka (nastav/zruš), `synced` = nové „✓ v PC“. Ak mobil
  históriu nemá (stará generácia, reštart servera, „zrušiť všetky značky“), vráti celý stav s `full:true` — vtedy
  starý stav zahoď. Generácie zmenené len stavom skupín vrátia prázdne zmeny.
- `POST /api/mark` `{"ids":[1,2,3],"del":true}` (dávkovo, najviac 20 000 id, telo do 512 kB)
  → `{"ok":true,"marked":<počet všetkých>,"changed":<koľkým sa zmenil stav>,"stateGen":43}`.
  `del:false` značku zruší. Pôvodné `POST /api/mark?id=<id>&del=1|0` funguje ďalej.
- `POST /api/synced` `{"ids":[…]}` (alebo `?id=`) — zapíše „✓ v PC“ → `{"ok":true,"count":n}`.
- „✓ v PC“ po `/api/file?dl=1` sa v mobile zapíše hneď do pamäti (vidno ho v `/api/list` aj `/api/state`),
  do úložiska ide dávkou o ~3 s; `stateGen` sa zvýši **raz za dávku**, nie po každom súbore.
- `POST /api/clearmarks` — zruší všetky značky.

## `POST /api/delete`

Telo `{"ids":[…], "dryRun":false}` (najviac 1000 id, telo do 64 kB).

- `dryRun:true` nič nezmaže: `{"dryRun":true,"count":12,"freedBytes":45678901,"unknown":[…],"recycleBin":true,"allFiles":true}`.
- Inak potrebuje „Prístup ku všetkým súborom“ (`allFiles`), inak `409 {"error":"no_all_files_access"}`
  (telo sa aj tak prečíta). Výsledok:

```json
{"deleted":[1,2],"failed":{"3":"no space"},"recycleBin":true,"freedBytes":7000000,"batchId":"bm2x9k3a1b"}
```

  Pri zapnutom Koši sa fotky skopírujú do Koša Galérie+ (záznam v jej DB, dajú sa obnoviť v appke aj cez
  `/api/restore`), potom sa zmaže originál. `batchId` (len pri Koši) identifikuje toto mazanie pre „Vrátiť“.
  Zmazané fotky stratia značku.

## `GET /api/bin`

```json
{"enabled":true,"locked":false,"allFiles":true,
 "items":[{"key":"9c1e…","name":"IMG_1.jpg","bucket":"Camera","size":3456789,
           "deletedTs":1730370000000,"video":false,"batchId":"bm2x9k3a1b"}]}
```

Najnovšie zmazané prvé (najviac 3000). Bez fotiek zo zamknutých/vylúčených priečinkov; zamknutý Kôš
(`locked:true`) vráti prázdny zoznam. `key` = 16 hex znakov SHA-1 z pôvodnej cesty.

## `POST /api/restore`

Telo `{"batchId":"…"}` (celé mazanie z `/api/delete`) alebo `{"keys":["…",…]}` (z `/api/bin`).
Potrebuje `allFiles` (inak `409`). Fotka sa vráti na pôvodné miesto (obsadený názov → `foto_1.jpg`;
kam sa nedá zapisovať → priečinok Obrázky), riadok v DB Galérie+ sa vráti a súbor sa naskenuje do MediaStore.

```json
{"restored":["9c1e…"],"failed":{"ab12…":"not in bin"}}
```

## `POST /api/upload`

`multipart/form-data` (prvá časť = súbor, názov z časti) alebo surové telo s hlavičkami `X-Filename`
(URL-kódovaný názov), voliteľne `X-Mime`, a `Content-Length`.
Ukladá sa do `Pictures/Galéria` resp. `Movies/Galéria`. Strop 4 GB a rezerva voľného miesta.
→ `{"saved":"<názov>","uri":"content://…","id":1234}` — `id` = MediaStore `_ID` novej položky (hneď ju vráti
`/api/list?sinceGen` aj `/api/file?id=`; `null`, ak sa nedalo zistiť).

**PC program: posielaj `PUT /api/upload` so surovým telom** (`X-Filename`, `Content-Length`). Multipart mobil
zapisuje na disk 3× (NanoHTTPD celé telo, potom časť, potom MediaStore), surové telo ide rovno do MediaStore.
Neposielaj `Expect: 100-continue` (server ho nepozná a klient by zbytočne čakal).

## Rýchlosť servera (server-rychlost)

Server beží v tom istom procese ako spracovanie knižnice (tváre, OCR, CLIP…), mobil má pri zhasnutej obrazovke
uspávané CPU aj Wi-Fi. Preto:

- **Spracovanie knižnice ustupuje serveru.** Kým PC/web niečo sťahuje (každá požiadavka okrem `ping` a `stats`),
  automatické spracovanie sa nespustí (60 s po poslednej požiadavke) a bežiace úlohy po dokončení aktuálnej fotky
  počkajú, kým je server 30 s ticho (notifikácia „… čaká – PC práve sťahuje“). Ručne spustené úlohy sa spustia,
  ale tiež ustupujú. `/api/quality` model tvárí dostane prednostne (ustupujúci indexer ho uvoľní).
- **WakeLock + WifiLock** len počas požiadaviek a ~90 s po poslednej (s časovým limitom ako poistkou) — bez
  požiadaviek sa do ~2 min uvoľnia. Pomáha aj výnimka z optimalizácie batérie (Nastavenia → Server pre PC ukáže
  odkaz, ak chýba).
- **Prenos**: `TCP_NODELAY`, výstup cez 64 kB buffer, čítanie požiadavky s časovým limitom 25 s. Najviac
  **20 súbežných spojení**; ďalšie dostane hneď `503 {"error":"busy"}` + `Retry-After: 1` a spojenie sa zavrie.
  Keep-alive spojenia drž rozumne (prehliadač ich má 6).
- **Index**: nová / zmazaná / obnovená fotka sa do zoznamu dostane bez prestavby celej knižnice v požiadavke
  (upload a mazanie hneď, obnova a zmeny z mobilu do ~2,5 s, najviac 10 s).
- **`/api/groups`**: ETag vzniká z kľúča (index, skupiny v mobile, skryté skupiny, cache skóre) — `If-None-Match`
  vráti `304` bez akéhokoľvek výpočtu; inak telo z cache servera.

### Hlavička `Server-Timing`

Každá odpoveď ju má, napr. `Server-Timing: app;dur=85.2, q;dur=40.1, dec;dur=38.0, jpg;dur=6.3, cache;desc=miss`
(časy v ms, prehliadač ich ukáže v DevTools → Network → Timing):

| Metrika | Význam |
|---|---|
| `app` | celá obsluha v mobile (bez odoslania tela) |
| `q` | čakanie vo fronte dekódovaní (iné veľké náhľady) |
| `dec` | dekódovanie obrázka (HEIC/JPEG/video snímka) |
| `jpg` | kompresia do JPEG |
| `cache` | `mem` / `disk` = náhľad z cache, `miss` = dekódoval sa |

### `GET /api/stats`

Diagnostika „prečo je server pomalý“ (s prihlásením ako ostatné API). Nič nemení, nerobí sa z nej práca servera.

```json
{"now":1730370000000,"client":"lan","epoch":"q3J_x9Zk","uptimeMs":3600000,
 "requests":{"inFlight":2,"connections":7,"maxConnections":20,"rejectedConnections":0,"total":5321,"usb":0,"lan":5321},
 "endpoints":{"/api/thumb":{"count":4100,"p50":4,"p95":180,"max":2400,"p50Total":6,"p95Total":260}},
 "slow":[{"at":1730369990000,"path":"/api/thumb?id=1234&max=2560","status":200,"ms":812,"totalMs":905,
          "bytes":812345,"via":"lan"}],
 "index":{"count":10512,"gen":157,"rebuilds":4,"rebuildLastMs":310,"rebuildAvgMs":290,"rebuildMaxMs":420,
          "rebuildLastAt":1730369000000},
 "thumbs":{"mem":{"hits":3900,"misses":200,"smallBytes":15000000,"largeBytes":30000000},
           "disk":{"hits":120,"misses":80,"files":350,"bytes":290000000,"maxBytes":314572800},
           "decode":{"large":{"running":2,"waiting":3,"parallel":2,"maxWaiting":6,"done":300,"rejected":0,"cancelled":12},
                     "small":{"running":0,"waiting":0,"parallel":4,"maxWaiting":64,"done":900,"rejected":0,"cancelled":40}}},
 "groups":{"hits":30,"misses":2},
 "synced":{"pending":0},
 "processing":{"running":["faces"],"pausedForPc":["faces"],"heavyBusy":true,"heavyWaiting":0,
               "serverIdleMs":0,"autoStartAllowed":false},
 "device":{"charging":true,"batteryPct":81,"batteryTempC":36.5,"screenOn":false,"powerSave":false,
           "deviceIdle":false,"ignoringBatteryOptimizations":false,"thermalStatus":0},
 "wifi":{"rssi":-58,"linkMbps":866,"freqMHz":5180},
 "locks":{"wake":true,"wifi":true,"wifiMode":"low_latency","heldMs":120000,"idleMs":2000,"holdMs":90000}}
```

Ako to čítať:

| Kde | Čo hľadať |
|---|---|
| `endpoints.<cesta>` | `p50`/`p95`/`max` = čas obsluhy v mobile (ms) z posledných 256 požiadaviek, `p50Total`/`p95Total` aj s odoslaním. Vysoké `p95` pri `/api/thumb` a `/api/file` = dekódovanie (pozri `thumbs.decode`), vysoké `p95Total` pri malých odpovediach = sieť |
| `slow` | posledných 20 požiadaviek nad 500 ms (obsluha, alebo malá odpoveď s pomalým prenosom). `via`: `usb` = 127.0.0.1 (adb), `lan` = Wi-Fi |
| `requests` | `inFlight` bežiace požiadavky, `connections` otvorené spojenia (aj nečinné keep-alive), `rejectedConnections` odmietnuté nad limit |
| `index` | prestavby zoznamu (počet a trvanie); veľa prestavieb = v mobile sa práve veľa mení |
| `thumbs` | úspešnosť cache (`hits`/`misses`), fronta veľkých dekódovaní (`waiting`, `rejected` = 503, `cancelled` = zrušené klientom) |
| `processing` | čo spracovanie knižnice práve robí; `pausedForPc` = úlohy, ktoré serveru ustúpili; `heavyBusy` = model (tváre/OCR/CLIP) je načítaný |
| `device` | `charging`, `batteryTempC` (nad ~42 °C mobil spomaľuje CPU), `thermalStatus` (0 = OK, 3+ = obmedzenie), `deviceIdle` (Doze), `powerSave` (šetrič), `ignoringBatteryOptimizations` (false = systém môže sieť pri zhasnutej obrazovke brzdiť), `screenOn` |
| `wifi` | `rssi` (dBm; pod −70 slabý signál), `linkMbps`, `freqMHz` (2,4 GHz vs 5 GHz); `null` = mobil nie je na Wi-Fi (USB) |
| `locks` | `wake`/`wifi` = zámky držané počas prenosu; po ~90 s bez požiadaviek majú byť `false` |

## Zmeny oproti verzii 2

- `version` `"3"`, nové `features`, `epoch`, `stateGen` v `/api/ping`.
- `/api/list`: zoradené podľa dátumu bez ohľadu na typ, nové polia `takenReal`, `w`, `h`, `orient`, `dur`,
  `marked`, `synced`; `ETag`; `?sinceGen`.
- `/api/groups`: skupiny z `CompareRepository` (bez falošných sérií z času súboru), `?v=2`.
- `/api/mark`, `/api/synced`, `/api/clearmarks` **už neprijímajú GET** (405) — používaj POST.
- `/api/file`, `/api/thumb`: `ETag`, `Cache-Control: private`, Range (aj video), `filename*=UTF-8''`,
  `dl=1` zapisuje „✓ v PC“ po celom odoslaní; zmenšené JPEG má príponu `.jpg`.
- `/api/delete`: `dryRun`, `freedBytes`, `batchId`.
- Nové: `/api/login`, `/api/logout`, `/api/meta`, `/api/groupstate`, `/api/bin`, `/api/restore`.
- Chyby vždy ako JSON `{"error":…}`, kontrola `Origin` pri zmenách.

## Novinky (server-rychlost)

- `features` obsahuje `stateSince` (`/api/state?since=`), `stats` (`/api/stats`), `zip` (`POST /api/zip`),
  `favorites` (`/api/favorites`, `/api/favorite`, `fav` v zozname, `favGen` v ping), `motion` (`/api/motion`,
  `motion` v zozname a v `/api/meta`) a `thumbFull` (`/api/thumb?max` až 8192). Všetko je spätne kompatibilné.
- Každá odpoveď má `Server-Timing`. Nové kódy: `503 {"error":"busy"}` + `Retry-After` (fronta dekódovaní alebo
  limit spojení).
- PIN, `epoch` aj prihlásenia prežijú reštart služby systémom; `gen` po reštarte pokračuje s odstupom.
- `/api/upload` vracia aj `id`; pre PC program odporúčame `PUT` so surovým telom.

## Novinky (porovnávač 0.67, vlna 2)

- `POST /api/quality` (feature `quality`) — skóre kvality skupiny počíta mobil, jedna úloha naraz, `202` s priebehom.
- `/api/groups?v=2`: nové `uncertain` a `keep`; `best`/`confidence` sú tam vždy, keď je skupina spočítaná
  (aj po `/api/quality` alebo hromadnom „Ponechať najlepšiu“ v mobile).
- `/api/groupstate` prijíma aj viac skupín naraz `{"states":{…}}` (bolo aj predtým, web to teraz používa).

## Úprava PC porovnávača (kód nie je v repe) — kontrolný zoznam

1. Posielaj PIN v hlavičke `X-Auth-Token` (nie v adrese) a nikdy neposielaj `Origin`.
2. Zmeny (`mark`, `synced`, `clearmarks`) prepni na POST s JSON telom `{"ids":[…]}` — jedna požiadavka na celý výber.
3. Živý zoznam: `ping` → pri zmene `gen` `/api/list?sinceGen=…`, pri zmene `epoch` celý zoznam.
4. Porovnanie: najprv `/api/thumb?id=…&max=2560&v=<mtime>`, originál `/api/file?id=…` až pri priblížení
   (HEIC/DNG: `/api/file?id=…&q=92&max=4096`). Zoom drž v relatívnych súradniciach (stred ako podiel, mierka voči „prispôsobiť“).
   Na `503 busy` odpovedz novým pokusom po `Retry-After`; naraz nepýtaj viac ako ~4 veľké náhľady.
5. Skupiny z `/api/groups?v=2`, stav cez `/api/groupstate` (zdieľaný s webom), ★ = `best ?? keeper`.
   „Vyhodnotiť skupinu“: `POST /api/quality {"ids":[…]}`, pri `202`/`429` opakuj po `retryMs`.
   Hromadné „Ponechať najlepšiu“: presné kópie podľa `keep`, ostatné podľa `/api/quality` (`groups[key].best`,
   preskoč `uncertain` a nikdy neoznač `unscored` ani celú skupinu).
6. EXIF z `/api/meta?ids=…` (po 8).
7. Mazanie: `dryRun` → potvrdenie s `freedBytes` → `/api/delete` po 1000 → ponúkni „Vrátiť“ cez `/api/restore {"batchId"}`.
8. Nahrávanie: `PUT /api/upload` so surovým telom (`X-Filename`, `Content-Length`), bez `Expect: 100-continue`.
9. Stav značiek: `/api/state?since=<stateGen>` (pri `full:true` stav nahraď celý). Pri pomalosti pozri `/api/stats`.

## Web „Galéria+ Sync“ — ovládanie (0.67.2)

Web je `app/src/main/assets/gallery.html` (čisté funkcie v bloku `GCORE`, testy `app/src/test/js/*.test.js`,
prehliadkový test `app/src/test/js/e2e/keys_check.js`). Pomocník (kláves `?`) vzniká z `GCore.HELP` a test ho
porovnáva s obsluhou klávesov.

**Zobrazenia:** mriežka, **náhľad** (zoznam vľavo alebo dole + veľká fotka; `V` prepína, `L` poloha zoznamu,
pod 900 px vždy dole) a „Podobné a duplikáty“ (`G`). Voľby sa pamätajú v `localStorage` (`gmode`, `gpvpos`,
`gth.*` veľkosti miniatúr, `ghidem` skryť označené, `gfq` plná kvalita, `gfscope` rozsah „Porovnať s…“).

**Koliesko (všade rovnako):**

| kde | koliesko | Ctrl + koliesko (aj pinch na touchpade) | Shift + koliesko |
|---|---|---|---|
| fotka (prehliadač, porovnávač, náhľad, „Porovnať s…“) | predošlá / ďalšia fotka (v porovnávači v paneli pod kurzorom; touchpad: jedna za pohyb prstov) | zoom ku kurzoru | posun do strany |
| mriežka, zoznam náhľadu, zoznam skupín, Na zmazanie, Kôš | scroll | veľkosť miniatúr | scroll |
| pás miniatúr (porovnávač, „Porovnať s…“, zoznam náhľadu dole) | posun do strany | veľkosť miniatúr | posun |
| lišta vrstvy s fotkami | – | nič (stránka sa nezoomuje) | – |

Zoom celej stránky prehliadača ostáva (menu, `Ctrl+0`, pomocník a dialógy). Veľkosť miniatúr: aj `Ctrl` + `+` / `−`
a posuvník −/+ v hlavičke (80–400 px); pri väčšej miniatúre web žiada väčší náhľad (`/api/thumb?s=` 128–1024).

**Plná kvalita (`O`):** bez nej náhľad `max=2560` a originál (`/api/file?id=`) až pri priblížení nad rozlíšenie
náhľadu; s ňou originál hneď. HEIC/DNG (prehliadač ich nevie) ako `/api/thumb?id=&max=<dlhá strana originálu, najviac 4096>`.
Štítok vpravo hore v paneli: „náhľad 2560 px“, „originál 4032 × 3024“, „HEIC → JPEG v plnom rozlíšení …“,
„… · načítavam originál…“. Fotka sa kreslí do `<canvas>` (len viditeľný výrez na celé pixely – bez čiar na hranách
dlaždíc). Prednačíta sa len ďalšia fotka, až keď je aktuálna načítaná (pri listovaní sa zruší).

**„Porovnať s…“ (`C`, 📌 v porovnávači skupiny, kontextové menu):** vľavo pripnutá, vpravo ľubovoľná fotka
(rozsah `R`: aktuálny filter / celá knižnica / tento priečinok / táto skupina), pás miniatúr hore, `T` vymení strany,
`P` alebo Alt + klik pripne inú, spoločný zoom v relatívnych súradniciach, EXIF rozdiely, `X` a `Ctrl+Z`.

**Výber a značky:** `Delete`/`X` s výberom = jeden `POST /api/mark {ids, del}`; Shift + klik / Shift + medzerník =
interval od kotvy (kotva vybraná → vybrať, inak odznačiť), `Shift+X` to isté pre značky; `Ctrl+Z` vráti celý
interval aj dávku; `Ctrl+D` a `Esc` zrušia výber. Označené na zmazanie sú predvolene skryté (`H`), počítadlo
„Na zmazanie: N (X MB)“ otvorí prehľad. Pravé tlačidlo / kláves Menu / Shift+F10 = vlastné menu (`role=menu`),
Shift + pravé tlačidlo = menu prehliadača.

**Sťahovanie:** viac originálov = jeden formulár `POST /api/zip` (`application/x-www-form-urlencoded`, `ids=1,2,3`
najviac 500, voliteľne `token=` bez cookie; server s funkciou `zip` v `/api/ping`) – prihlásenie sa overí vopred
`/api/ping`. Inak po jednom, každý až po dokončení predošlého: do ~200 MB `fetch` → Blob → `<a download>`
(stav odpovede = príčina chyby: 403 prihlásenie, 404 súbor zmizol, sieť), väčšie najprv `Range: bytes=0-0`
a potom priamy odkaz (token v adrese, ak nie je cookie). Stránka má `<meta name="referrer" content="same-origin">`,
aby formulár poslal skutočný `Origin` (pri `no-referrer` by bol `Origin: null` a server ho odmietne). Na localhoste
(USB) „📁 Uložiť do priečinka…“ cez `showDirectoryPicker`.

**Diagnostika:** ak server má `GET /api/stats`, pomocník ponúkne „Diagnostika servera“ (JSON ako tabuľka).

