# LAN server Galérie+ — API pre PC (verzia 3)

Server beží v mobile (Nastavenia → PC server, služba `HttpSyncService`, kód `sync/MediaServer.kt`),
predvolene na porte **8089**. Je určený pre samostatný PC porovnávač a pre web „Galéria+ Sync“
(`app/src/main/assets/gallery.html`, otvára sa na `http://<IP mobilu>:8089/`).

Verzia 3 je spätne kompatibilná s verziou 2 — staré volania fungujú ďalej. Rozdiely sú v časti
„Zmeny oproti verzii 2“ na konci.

## Prihlásenie

Každý štart servera vygeneruje nový 6-miestny PIN (ukáže ho notifikácia v mobile).

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
  `/api/groupstate` pri zápise, `/api/logout`, `/api/quality`; `/api/upload` aj PUT). GET na ne vráti
  `405 {"error":"use POST"}`.
  **Dočasne (0.67):** `/api/mark`, `/api/synced` a `/api/clearmarks` prijmú aj starší tvar GET `?id=&del=`,
  ale len s PIN tokenom priamo v požiadavke (`X-Auth-Token` alebo `?token=`). Po prechode PC programu na POST sa to zruší.
- **Origin**: ak požiadavka pošle hlavičku `Origin`, musí byť `http://<Host>` (inak `403 {"error":"bad_origin"}`).
  Rovnako `Sec-Fetch-Site` musí byť `same-origin` alebo `none`. PC program tieto hlavičky neposiela → prejde.
- Každá odpoveď má `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, `X-Frame-Options: DENY`,
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
| POST | `/api/login`, `/api/logout` | session pre prehliadač |
| GET | `/api/list` | zoznam fotiek a videí (celý alebo len zmeny) |
| GET | `/api/file?id=` | originál (Range, ETag) alebo zmenšené JPEG |
| GET | `/api/thumb?id=` | náhľad JPEG (aj HEIC/DNG/video) |
| GET | `/api/meta?ids=` | EXIF pre porovnávač |
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
{"name":"Galéria+","version":"3","epoch":"q3J_x9Zk","count":10512,"gen":7,"stateGen":42,
 "newest":1730370000,"allFiles":true,"recycleBin":true,
 "features":["session","sinceGen","etag","range","groupsV2","groupState","meta","markBatch",
             "syncedBatch","deleteDryRun","bin","restore","thumbMax","dlMark","quality"]}
```

- `version` — `"3"`; podľa `features` sa rozhoduj, ktoré API použiť (staré servery pole nemajú).
- `epoch` — náhodné id behu servera. Iný `epoch` = server sa reštartoval, `gen` začína od nuly → načítaj zoznam celý.
- `gen` — generácia zoznamu, zvýši sa pri každej zmene (pribudnutá, zmazaná, upravená fotka).
- `stateGen` — zvýši sa pri každej zmene značiek, stiahnutých alebo stavu skupín (aj z mobilu).
- `allFiles` — appka má „Prístup ku všetkým súborom“ → funguje `/api/delete` a `/api/restore`.
- `recycleBin` — Kôš je zapnutý (mazanie ide do Koša, inak natrvalo).

Odporúčaný cyklus PC programu: `ping` každé 2–4 s; pri zmene `gen` → `/api/list?sinceGen=<starý gen>`,
pri zmene `stateGen` → `/api/state` (a `/api/groupstate`), pri zmene `epoch` → celý zoznam.

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

- Bez ďalších parametrov: **originál**. `Content-Length` je skutočná veľkosť súboru.
  - `ETag: "f-<id>-<mtime>-<size>"`, `Cache-Control: private, no-cache`, `If-None-Match` → `304`.
  - `Accept-Ranges: bytes`; `Range: bytes=a-b`, `bytes=a-`, `bytes=-n` → `206` + `Content-Range`;
    mimo súboru `416` + `Content-Range: bytes */<size>`; viac rozsahov naraz → celý súbor (200).
    `If-Range` s iným ETagom → celý súbor. Video v prehliadači sa tak dá posúvať.
  - `Content-Disposition: inline; filename="<ASCII náhrada>"; filename*=UTF-8''<presný názov>`.
- `&dl=1` — `Content-Disposition: attachment`. Server zapíše **„✓ v PC“ sám**, až keď odošle celý
  originál (prerušené sťahovanie ani `Range` sa nezapíšu). PC program môže namiesto toho volať `/api/synced`.
- `&q=<30–100>[&max=<256–4096>]` — **zmenšené JPEG** (dlhá strana `max`, predvolene 2048), správne otočené.
  Funguje aj pre HEIC/DNG. Názov v `Content-Disposition` má príponu `.jpg`. Nezapisuje „✓ v PC“.

## `GET /api/thumb?id=<id>`

- `&s=<64–1024>` (predvolene 300) — malý náhľad (mriežka). Do 512 px z `ContentResolver.loadThumbnail`
  (Android 10+), cache v pamäti servera podľa id + mtime + veľkosti.
- `&max=<64–4096>` — väčší náhľad, napr. `max=2560` pre porovnávač (dekóduje sa raz cez ImageDecoder,
  EXIF otočenie je uplatnené; HEIC, DNG aj video snímka).
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

- `GET /api/state` → `{"marked":[id,…],"synced":{"<id>":<ms>},"stateGen":42}`
- `POST /api/mark` `{"ids":[1,2,3],"del":true}` (dávkovo, najviac 20 000 id, telo do 512 kB)
  → `{"ok":true,"marked":<počet všetkých>,"changed":<koľkým sa zmenil stav>,"stateGen":43}`.
  `del:false` značku zruší. Pôvodné `POST /api/mark?id=<id>&del=1|0` funguje ďalej.
- `POST /api/synced` `{"ids":[…]}` (alebo `?id=`) — zapíše „✓ v PC“ → `{"ok":true,"count":n}`.
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

Bez zmeny oproti verzii 2: `multipart/form-data` (prvá časť = súbor, názov z časti) alebo surové telo
s hlavičkami `X-Filename` (URL-kódovaný názov), voliteľne `X-Mime`, a `Content-Length`.
Ukladá sa do `Pictures/Galéria` resp. `Movies/Galéria`. Strop 4 GB a rezerva voľného miesta.
→ `{"saved":"<názov>","uri":"content://…"}`.

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
5. Skupiny z `/api/groups?v=2`, stav cez `/api/groupstate` (zdieľaný s webom), ★ = `best ?? keeper`.
   „Vyhodnotiť skupinu“: `POST /api/quality {"ids":[…]}`, pri `202`/`429` opakuj po `retryMs`.
   Hromadné „Ponechať najlepšiu“: presné kópie podľa `keep`, ostatné podľa `/api/quality` (`groups[key].best`,
   preskoč `uncertain` a nikdy neoznač `unscored` ani celú skupinu).
6. EXIF z `/api/meta?ids=…` (po 8).
7. Mazanie: `dryRun` → potvrdenie s `freedBytes` → `/api/delete` po 1000 → ponúkni „Vrátiť“ cez `/api/restore {"batchId"}`.
