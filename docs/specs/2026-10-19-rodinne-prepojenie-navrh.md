# Rodinné prepojenie dvoch telefónov — návrh (spec, 20.10.2026)

Stav: **všetky časti prebraté s Lukášom, čaká na finálne schválenie** → potom implementačný plán.

Lukáš (Xiaomi 15) ↔ manželka (pravdepodobne POCO F8, HyperOS). Cieľ: lokálne zdieľanie fotiek, prenos tvárí a mien. Lukášov pokyn: **„nech nemusia procesory robiť dvakrát“**, appka ľahká a spoľahlivá na oboch telefónoch.

## Rozhodnutia Lukáša (brainstorming 19.–20.10.2026)
- Režim je **zmiešaný**. Osoby, mená a odtlačky tvárí sa synchronizujú samé. Fotky sa posielajú ručne a idú s nimi hotové výsledky.
- **Obaja** môžu meniť mená a osoby a synchronizuje sa to oboma smermi. Pri rozpore sa nič neprepisuje a nič sa nemaže automaticky.
- Spojenie: **rovnaká Wi-Fi**, mimo domu hotspot jedného z telefónov. Žiadny internet ani cloud.
- Jej telefón má **ľahký režim**. Svoje fotky spracuje len pri nabíjaní a len tváre a polohu. Text, CLIP a duplikáty sú vypnuté, kým ich nezapne.
- Prístup **A**: vlastné prepojenie cez Wi-Fi postavené na existujúcom serveri, párovanie QR kódom. Výsledky sa párujú podľa **obsahu** fotky, takže ich nájde aj fotka poslaná hocijako (Quick Share, WhatsApp).
- Implementácia **v cloude** (Lukášove cloud kredity).

## Časť 1 — Prepojenie a spojenie (SCHVÁLENÉ 19.10.)
- Samostatný modul „Rodinné prepojenie“ s vlastnou malou DB `family.db`. Do people.db, faces.db ani extras.db nezasahuje, ich schéma sa nemení.
- Párovanie sa robí raz: Nastavenia → „Prepojiť telefón“ → QR kód (identita zariadenia + 256-bitový tajný kľúč) → druhá appka ho naskenuje. Prepojenie sa dá zrušiť.
- Telefóny sa nájdu na Wi-Fi bez zadávania IP (Android NSD/mDNS). Komunikácia je šifrovaná kľúčom z QR (AES-GCM), takže nikto iný na sieti nič neprečíta ani nepošle.
- Kedy sa synchronizuje:
  1. pri otvorení appky krátky pokus;
  2. doma na Wi-Fi a pri nabíjaní je appka až 1 h pripravená a sama skúša synchronizovať (v noci sa telefóny stretnú);
  3. „Poslať manželke“: ak nie je dostupná, ide to do fronty, odíde pri najbližšom stretnutí a príde notifikácia.
- Nič nebeží trvalo na pozadí.

## Časť 2 — Čo sa prenáša (odpovede 19.–20.10.)

### 2.1 Identita fotky podľa obsahu (zvolené: odtlačok + záloha)
- **Obsahový kľúč** `contentKey` = veľkosť súboru + SHA-256 z prvých a posledných 64 kB. Je to rýchle aj pre 10 000 fotiek, plný hash by bol príliš ťažký.
- Kľúč žije v `family.db` v tabuľke `content_keys(path, size, mtime, content_key)`. Prepočíta sa len pri zmene veľkosti alebo mtime.
- **Záloha** pre prekomprimované kópie (WhatsApp mení súbor): zhoda podľa pHash (Hammingova vzdialenosť ≤ 6, rovnaký prah ako PhashGrouper) **a** zároveň podľa dátumu nasnímania (± 2 s, pri chýbajúcom EXIF len pHash ≤ 3).
- Zhoda len podľa zálohy prenesie tváre a priradenia, ale neprenesie OCR (pri zmenšení môže text zmiznúť).
- Toto zároveň rieši otvorený bod revízie 3: tváre pri presune fotky. Po presune ostane `contentKey` rovnaký, takže sa výsledky preklopia na novú cestu bez nového spracovania.

### 2.2 Osoby (zvolené: rodinné UUID + meno + do ~20 odtlačkov)
- `family.db` drží tabuľku `family_persons(family_uuid, local_person_id, name_version, …)`. Lokálne `PersonEntity.id` (Long) ostáva bez zmeny a rodinné UUID je len mapa nad ním.
- Prenáša sa: UUID, meno, skupiny, zlúčenia a premenovania (ako záznamy operácií, nie ako stav) a **do ~20 reprezentatívnych odtlačkov** na osobu. Ide o rôzne roky a rôzne pózy a výber robí existujúci mechanizmus `AnchorEmbeddingEntity`.
- Odtlačky sa na prijímateľovi ukladajú ako `AnchorEmbeddingEntity(source = "family:<deviceId>")`. Vďaka tomu jej telefón sám navrhne mená na jej vlastných fotkách. Tabuľka už existuje, schéma sa nemení.
- **Kompatibilita modelov:** každý balík nesie `faceModelId` (verzia embeddera). Ak sa na prijímateľovi líši, odtlačky sa zahodia a prijímateľ si ich vypočíta sám. Nikdy sa nemiešajú vektory rôznych modelov.

### 2.3 Výsledky k fotke (zvolené: všetko, čo je hotové)
- Balík k `contentKey` obsahuje:
  - **tváre** v **relatívnych súradniciach** 0–1 voči vzpriamenému obrázku (sedia aj na zmenšenej kópii), so skóre a odtlačkom;
  - **priradenia** (rodinné UUID osoby, ručné/automatické);
  - **text (OCR)**, **polohu**, **pHash** a **CLIP vektor** s `clipModelId`;
  - **kvalitu** a **QR**.
- Relatívne súradnice sa počítajú na oboch stranách tým istým `UprightDecoder`, takže sedia aj pri chybe EXIF 5/7 (revízia bod 10). Rámčeky, pri ktorých sa nedá určiť, či sú raw alebo upright (staršie záznamy, pozri `RefreshFaces`), sa neposielajú. Prijímateľ si také tváre vypočíta sám.
- Prijímateľ výsledky prepočíta na absolútne súradnice svojej kópie a zapíše ich do existujúcich tabuliek bez zmeny ich schémy. Fotku označí ako spracovanú (`indexed_photos` a obdoba v ostatných indexoch), takže ju indexery preskočia. **Procesor nerobí nič druhýkrát.**
- CLIP: ak prijímateľ ešte CLIP nemá, vektory sa uložia a pri zapnutí CLIP si jej telefón stiahne **rovnaký model** ako odosielateľ (podľa `clipModelId`). Ak má iný model, prijatý CLIP vektor sa nezapíše.
- Rovnaký formát slúži aj ako **export/záloha osôb** (otvorený bod revízie 4): súbor `.galeria-family` na SD/PC.

### 2.4 Rozpory (zvolené: bezpečné potichu, rozpory sa opýta)
- **Potichu sa prevezme:** nová osoba, nové priradenie tváre na fotke, ktorú druhá strana nemá priradenú, nová skupina, nové odtlačky.
- **Návrh v Ľuďoch** („Máš 2 návrhy z telefónu <názov zariadenia>“; názov sa zadá pri párovaní) dostane:
  - tá istá osoba premenovaná na oboch stranách inak → „prevziať / ponechať“;
  - dve osoby s rovnakým menom alebo veľmi podobnou tvárou → „zlúčiť?“;
  - druhá strana tvár odobrala alebo preradila na inú osobu → „prijať?“;
  - zlúčenie, ktoré spravila druhá strana.
- **Mazanie sa neprenáša nikdy.** Zmazanie osoby na jednom telefóne sa na druhom ukáže len ako návrh.
- Ručné priradenie (`is_manual`) má vždy prednosť pred automatickým, aj keby prišlo neskôr.
- Op-log: každá zmena má `(deviceId, lamport, opId)`, takže sa každá operácia aplikuje práve raz a v deterministickom poradí. Kto ju poslal, vidno v histórii návrhu.

### 2.5 Súkromie
- Zamknuté a vylúčené priečinky a skrytá galéria sa **nikdy** neposielajú. Neposielajú sa ani výsledky z nich, rovnaký filter ako `faces/IndexScope.kt`.
- Toto nadväzuje na otvorené rozhodnutie revízie 2: tváre a PC server zo zamknutých priečinkov. Rodinné prepojenie sa ním riadi tak, že čo je zamknuté, neodíde.

## Časť 3 — Ľahkosť a spoľahlivosť (odpovede 20.10.)
- **Profily**:
  - **Ľahký** (jej telefón): tváre a poloha, len pri nabíjaní, ostatné vypnuté.
  - **Plný** (tvoj telefón): všetko.
- **Tvoj telefón:** spracovanie ostáva **ako doteraz** (spustí sa ručne alebo pri nových fotkách). V Nastaveniach pribudne voľba **„Nové fotky hneď, zvyšok pri nabíjaní“** (pri batérii pod 20 % sa všetko zastaví).
- Kľúče fotiek (`contentKey`) sa počítajú lenivo: pri prvom poslaní, pri synchronizácii a pri nabíjaní. Nie pri štarte appky.
- Synchronizácia prenáša len rozdiely od poslednej zhody (podľa lamport hodiny) a nikdy nie celú databázu.
- Modely sa načítajú až na požiadanie a po skončení sa uvoľnia (už to tak čiastočne je).
- Fáza 4 (samostatne) zmeria APK, RAM a batériu na oboch profiloch. Assety (tváre, CLIP, OCR) majú ~12 MB, takže veľkosť určujú hlavne natívne knižnice.

## Časť 4 — Chyby a testovanie (odpovede 20.10.)
- **Test: emulátor `galeria` na PC + Lukášov Xiaomi** (menej RAM ako dva AVD, skutočná Wi-Fi na jednej strane).
- NSD v emulátore nefunguje, preto pribudne **ladiaci prepínač na ručnú IP:port** (len v debug buildoch) a presmerovanie portu `adb forward`.
- Spoľahlivosť prenosu:
  - každý balík je samostatne overený (AES-GCM tag);
  - prerušený prenos sa obnoví od posledného potvrdeného balíka;
  - fronta prežije reštart appky (je vo `family.db`);
  - opakovaný balík sa neaplikuje dvakrát (`opId`).
- **Unit testy** (bežia v CI v `testFossDebugUnitTest`):
  - výpočet `contentKey`;
  - prepočet relatívnych a absolútnych súradníc vrátane EXIF rotácie;
  - zlúčenie op-logu (poradie, idempotencia, rozpory → návrhy, mazanie sa neprenáša);
  - odmietnutie iného `faceModelId` a `clipModelId`;
  - filter zamknutých priečinkov.
- **Ručný test:** párovanie, poslanie fotky cez Galériu aj cez WhatsApp (záloha podľa pHash), premenovanie na oboch stranách → návrh, zrušenie párovania, výpadok Wi-Fi uprostred prenosu.

## Železné pravidlá platné pre modul
- people.db, faces.db, extras.db: žiadna zmena entít ani verzie bez Room Migration. Rodinné prepojenie ich nemení vôbec, všetko svoje drží vo `family.db`.
- Ovládanie podľa zvyklostí appky: dialógy cez getAlertDialogBuilder+setupDialogStuff, výberový režim dlhým podržaním, pojmy podľa slovníka (revízia bod 11).

## Mimo rozsahu (zámerne)
- PC ako tretí účastník rodinného prepojenia (PC zatiaľ ostáva na existujúcom Serveri pre PC).
- Synchronizácia cez internet alebo cloud.
- Automatické posielanie všetkých fotiek (fotky sa posielajú len ručne).
