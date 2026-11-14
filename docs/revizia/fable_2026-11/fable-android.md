# Galéria+ 0.67.3 – nezávislá revízia Android appky (Fable)

Vetva `revizia-2026-11`, commit `6d3c306`. Rozsah: celá appka okrem `sync/*` (web a server pre PC) a `family/*`.
Cesty sú relatívne k `app/src/main/kotlin/org/fossify/gallery/`.

**Ako som pracoval:** len čítanie kódu, v repe som nič nemenil. Každý nález nižšie som overil priamo v kóde
(súbor:riadok), nič som nespúšťal na zariadení ani v emulátore – správanie na Xiaomi 15 / HyperOS 3 je teda
odvodené z kódu, nie odmerané. Kde nález stojí na predpoklade, je to pri ňom napísané. Staršie revízie
v `docs/revizia/` som zámerne nečítal, aby bol pohľad nezávislý – niektoré body sa preto môžu prekrývať.

Nestihol som prejsť do hĺbky: `EditActivity`, `VideoPlayerActivity`/`VideoFragment`, `PhotoFragment`,
`MediaFetcher`, `DirectoryAdapter`/`MediaAdapter` (prevažne pôvodný Fossify kód), `RefreshFaces`,
`FaceReembedV2`, `PersonGrouper`/`KnnModel` (matematika rozpoznávania) a `TextSelectActivity`.

## Zhrnutie

| Závažnosť | Počet | Čo to znamená |
|---|---|---|
| P1 | 4 | strata dát alebo únik zamknutých fotiek pri bežnom použití |
| P2 | 11 | zlý výsledok hlavnej funkcie, slabina záchrannej siete, súkromie v užšom scenári |
| P3 | 14 | menšie chyby, súbehy, batéria, krehkosť |

Najdôležitejšie zistenia:

1. **Vysypanie Koša zmaže celý súkromný priečinok appky** – s ním výrezy z Picasy (podklad pre budúci prepočet
   vzorov osôb), stiahnuté CLIP modely a dáta Tesseractu.
2. **Tri obrazovky ukazujú fotky zo zamknutých a vylúčených priečinkov bez PIN-u**: hľadanie v Ľuďoch,
   Spomienky a Špeciálne.
3. **OCR aj CLIP spracúvajú fotky bez otočenia podľa EXIF** – dokument odfotený na výšku sa preto v Dokumentoch
   ani v hľadaní neobjaví a prázdny výsledok sa uloží natrvalo.

---

## 1. Chyby

### P1

#### N1 – „Vysypať Kôš“ zmaže celý `filesDir` appky
- **Kde:** `extensions/Context.kt:150` (`val Context.recycleBin: File get() = filesDir`),
  `extensions/Activity.kt:495` (`recycleBin.deleteRecursively()`); volané zo
  `SettingsActivity.kt:827`, `MediaActivity.kt:627/635`, `DirectoryAdapter.kt:419/430`.
- **Čo tam leží navyše oproti pôvodnému Fossify:** `filesDir/picasa_crops/` (`faces/PicasaImportService.kt:237`),
  `filesDir/clip/` (`clip/ClipModels.kt:25`), `filesDir/clip_ml/` (`clip/ClipMlModels.kt:25`),
  `filesDir/tessdata/` (`faces/OcrEngine.kt:15`).
- **Scenár:** Lukáš vysype Kôš (alebo Kôš vypne – `emptyAndDisableTheRecycleBin`). Zmiznú:
  - výrezy z Picasy, ktoré si appka podľa komentára drží práve preto, „aby sa vzory dali pri budúcej zmene
    odtlačkov prepočítať bez nového ZIP-u“ – pri ďalšej zmene modelu tvárí sa vzory z Picasy už prepočítať nedajú;
  - CLIP modely (150 MB a viac). `IndexingService.autoTasks()` pridá CLIP len pri `ClipModels.bothPresent`
    (`services/IndexingService.kt:176`), takže nové fotky sa potichu prestanú indexovať a hľadanie podľa obsahu
    vracia prázdno, kým si to Lukáš nevšimne a model znova nestiahne;
  - rozpracované sťahovanie modelu (`*.part`) – skončí chybou.
- **Predpoklad:** `recycleBinPath` z knižnice commons je `filesDir.absolutePath`. Zdroják commons v repe nie je,
  ale kód appky to potvrdzuje: kópie sa ukladajú do `File(recycleBinPath, source)` a vysypanie maže `filesDir`.
- **Oprava:** pri vysypaní mazať len obsah Koša, nie koreň – teda podstromy zodpovedajúce úložiskám
  (`filesDir/storage`, prípadne ďalšie korene z `mediaDB.getDeletedMedia()`), alebo vymenovať a vynechať
  vlastné priečinky. Dlhodobo presunúť vlastné dáta do `noBackupFilesDir`/`getDir("models")` mimo Koša.
  Doplniť test, ktorý po `emptyTheRecycleBin` overí, že `clip/`, `tessdata/` a `picasa_crops/` existujú.

#### N2 – Hľadanie v Ľuďoch ukazuje fotky zo zamknutých a vylúčených priečinkov
- **Kde:** `activities/PeopleSearchActivity.kt:268–296` (`searchPeoplePaths`, `searchTextPaths`),
  otvorenie `:346–352` so `SKIP_AUTHENTICATION = true`. V súbore nie je `FaceVisibility` ani `IndexScope`
  (ostatné obrazovky osôb ich majú: `PeopleActivity.kt:66`, `PersonActivity.kt:72`, `FaceTaggingActivity.kt:70`).
- **Scenár:** tváre sa spracovali, potom Lukáš priečinok zamkne PIN-om. Tváre z neho sa zámerne nemažú
  („UI ich len skryje“, `faces/FaceIndexer.kt:79`). V Ľudia → Hľadať napíše meno: výsledky idú priamo
  z `PeopleIndex.photoPersons` (všetky tváre), takže sa zobrazia náhľady fotiek zo zamknutého priečinka a ťuk ich
  otvorí bez PIN-u. To isté platí pre vylúčené priečinky a pre prepínač „text“ (ocr.db) do najbližšieho behu OCR.
  Vedľajší efekt: vo výsledkoch sú aj dávno zmazané fotky (prázdne dlaždice).
- **Oprava:** výsledky prefiltrovať cez `FaceVisibility.isVisible` (rozsah + existencia súboru) pred
  `showResults`; rovnaký filter použiť pre zoznam posielaný na mapu (`:129`).

#### N3 – Spomienky ignorujú zamknuté a vylúčené priečinky
- **Kde:** `helpers/Memories.kt:130–164` (`loadShots` číta celý MediaStore bez `IndexScope`),
  `activities/MemoriesActivity.kt:69` → `PhotoGridActivity.kt:63–68` (`SKIP_AUTHENTICATION = true`).
- **Scenár:** fotky zo zamknutého priečinka sú v MediaStore ako každé iné. „Pred rokom“, „Výlet“ a „Vydarený
  deň“ ich zaradia, karta ukáže náhľad a mriežka ich otvorí bez PIN-u.
- **Oprava:** v `loadShots` vyradiť cesty, ktoré neprejdú `IndexScope(context).allows(path)`.

#### N4 – Špeciálne fotky ignorujú zamknuté a vylúčené priečinky
- **Kde:** `activities/SpecialActivity.kt:155–170` (`queryImages` bez filtra), otvorenie `:236–240`
  so `SKIP_AUTHENTICATION = true`.
- **Scenár:** panoráma, RAW, HDR alebo pohyblivá fotka v zamknutom priečinku sa objaví v albume Špeciálne
  aj v počte na karte Preskúmať (`special_count`) a otvorí sa bez PIN-u.
- **Oprava:** rovnaký filter `IndexScope.allows` hneď po `queryImages()`.

### P2

#### N5 – OCR spracúva fotky neotočené, dokumenty fotené na výšku sa nenájdu
- **Kde:** `faces/OcrIndexer.kt:318–334` (`decodeDownscaled` cez `BitmapFactory.decodeFile`, bez EXIF),
  uloženie prázdneho výsledku `:225–246` („fotka sa už nebude OCR-ovať znova“). Live Text to robí správne
  (`activities/TextSelectActivity.kt:62`, `UprightDecoder`).
- **Scenár:** foťák uloží fotku na výšku ako ležaté pixely + EXIF orientácia 90°. Tesseract dostane text
  otočený o 90°, nerozpozná nič alebo má nízku istotu → uloží sa prázdny text. Bloček či zmluva sa v Dokumentoch
  ani v hľadaní nikdy neobjaví, a keďže riadok existuje, fotka sa už neskúša znova.
- **Predpoklad:** fotoaparát Xiaomi 15 zapisuje orientáciu do EXIF (nie otočené pixely). Podporuje to komentár
  vo `faces/FaceIndexer.kt:264` („VŽDY na stojato (EXIF) — inak detektor na fotkách na výšku tváre nenájde“),
  na telefóne som to neoveroval.
- **Oprava:** použiť `UprightDecoder.decode(path, MAX_DECODE)`. Jednorazovo znova zaradiť fotky, ktoré majú
  prázdny text a EXIF orientáciu inú než normálnu (zmazať ich riadky v `ocr_text`).

#### N6 – CLIP počíta vektory z neotočených fotiek
- **Kde:** `clip/ClipIndexer.kt:198–211`.
- **Scenár:** rovnaká príčina ako N5. CLIP nie je odolný voči otočeniu o 90°, takže fotky na výšku majú
  horšie vektory a hľadanie typu „pes“, „pláž“ ich nájde menej spoľahlivo.
- **Oprava:** `UprightDecoder`; fotky s EXIF otočením preindexovať pri nabíjaní.

#### N7 – „Presné kópie“ nie sú presné, a hromadné čistenie ich maže bez posúdenia kvality
- **Kde:** `faces/PhashGrouper.kt:76–78` a `:104–111` (kópia = priemerový hash 8×8 líšiaci sa najviac o 2 bity
  + rovnaké rozmery alebo pomer strán), `compare/CompareBulkKeep.kt:77–78` a `:128–150` (`cleanExact`:
  „rýchla akcia bez analýzy tvárí“), pravidlo `helpers/CompareBulk.kt:52–53, 76–81`.
- **Scenár:** dva zábery tej istej scény po sebe (rovnaké rozlíšenie, niekto žmurkne; dve podobné strany
  dokumentu) majú 64-bitový hash takmer zhodný. Skončia v záložke Presné kópie a „Vyčistiť presné kópie“ nechá
  podľa pravidla staršiu/z lepšieho priečinka – nie ostrejšiu ani tú s otvorenými očami. Zmierňuje to prehľad
  s náhľadmi a Kôš, ale názov sľubuje istotu, ktorú algoritmus nemá.
- **Oprava:** za presnú kópiu považovať len zhodu obsahu – v appke už existuje `family/ContentKey`
  (SHA-256 veľkosti + prvých a posledných 64 kB). Ostatné blízke dvojice presunúť do „Podobné“, kde sa
  rozhoduje podľa kvality.

#### N8 – Automatická záloha po poškodení DB zálohuje prázdnu databázu a tú potom ponúkne na obnovu
- **Kde:** `safety/AutoBackup.kt:72–97` (beh nekontroluje `DbSafetyState.corruptions()`), rotácia `:155–161`,
  ponuka poslednej zálohy `safety/DataSafetyUi.kt:77`, kontrola kópie `safety/DbRestore.kt:95–102`
  (len hlavička a verzia).
- **Scenár:** `people.db` sa poškodí. Prvý, kto ju otvorí, môže byť týždenný `PeopleBackupJob` na pozadí:
  `SafeOpenHelperFactory` ju správne odloží ako `.corrupt-…`, SQLite vytvorí prázdnu a záloha pokračuje –
  zapíše F6 s nula osobami a ZIP s prázdnou DB. Pri ďalšom otvorení appky dialóg ponúkne „poslednú zálohu“,
  teda práve túto prázdnu. Ak Lukáš zvolí „Neskôr“, každý ďalší týždeň vytlačí jednu dobrú zálohu; po štyroch
  týždňoch nezostane žiadna.
- **Oprava:** kým je zaznamenané neuzavreté poškodenie, zálohu nerobiť (alebo aspoň nerotovať). Pri obnove
  ponúknuť zoznam záloh s počtom osôb, nie len najnovšiu. Nerotovať, keď nová záloha má výrazne menej osôb
  než predošlá. Pri `stage` spustiť `PRAGMA quick_check`.

#### N9 – Mriežka z mapy a zo Spomienok je po otočení telefónu prázdna
- **Kde:** `activities/PhotoGridActivity.kt:32–33` (zoznam zo statického `PathTransfer.forGrid`, hneď sa
  vynuluje), manifest `AndroidManifest.xml:718–721` (`configChanges="orientation"` bez `screenSize`).
- **Scenár:** otvorený zhluk z mapy alebo spomienka → otočenie telefónu (aktivita sa znova vytvorí, lebo
  `screenSize` nie je deklarované), prepnutie tmavého režimu pri západe slnka, alebo návrat po tom, čo HyperOS
  appku zabil → mriežka ukáže „0“.
- **Oprava:** uložiť cesty do `onSaveInstanceState` (do 2000 ciest sa zmestí) alebo držať zoznam vo
  `ViewModel`-i; `forGrid` nulovať až v `onDestroy` pri `isFinishing`.

#### N10 – Skryté fotky a priečinky ostávajú v Ľuďoch, na mape a v Dokumentoch
- **Kde:** `faces/IndexScope.kt:46–56` a `faces/FaceVisibility.kt:25–33` (kontroluje sa len zamknutie,
  vylúčenie a existencia súboru – skrytie nie), `activities/MapActivity.kt:190–199`,
  `helpers/DocClassifier.kt:47–59`.
- **Scenár:** Lukáš skryje priečinok (`.nomedia`) alebo fotku (`.meno`) až po spracovaní. Hlavná galéria ju
  schová, ale tváre ostanú v Ľuďoch (pri `.meno` ich `FaceRelinker` dokonca preklopí na novú cestu), bod ostane
  na mape a text v Dokumentoch. Denné upratovanie riadky nezmaže, lebo súbor existuje
  (`helpers/IndexStatus.kt:159–172`). Ochrana skrytých položiek heslom sa tým obchádza.
- **Poznámka:** pravidlo projektu výslovne menuje zamknuté a vylúčené priečinky; skryté sú tá istá trieda
  súkromia, preto to uvádzam ako nález, nie ako porušenie pravidla.
- **Oprava:** do `IndexScope` doplniť skrytie (segment cesty začínajúci bodkou, priečinok s `.nomedia`),
  s výnimkou, keď je zapnuté „zobraziť skryté“.

#### N11 – Zamknutie priečinka sa v Dokumentoch prejaví až po ďalšom behu OCR
- **Kde:** riadky mimo rozsahu maže len indexer pri štarte (`IndexScope.keepAllowed`,
  `faces/OcrIndexer.kt:64`), zobrazenie nefiltruje: `activities/DocsActivity.kt:86–111` +
  `helpers/DocClassifier.kt:47–59`, otvorenie `:178–186` so `SKIP_AUTHENTICATION`.
- **Scenár:** Lukáš zamkne priečinok s naskenovanými dokladmi. Kým znova nezbehne OCR (najskôr o 6 hodín pri
  otvorení appky, v Ľahkom profile vôbec), sú doklady v albume Dokumenty viditeľné a otvoria sa bez PIN-u.
  Mapa je na tom lepšie – `GeoIndexer` sa spúšťa pri jej otvorení – ale prvé vykreslenie ide ešte zo starých
  riadkov (`MapActivity.kt:128–143`).
- **Oprava:** filtrovať pri zobrazení (`IndexScope.allows`), nespoliehať sa na čistenie pri indexovaní.
  Navyše po zamknutí/vylúčení priečinka hneď zmazať jeho riadky z prestavateľných indexov.

#### N12 – Mapa ukazuje zmazané fotky; upratovanie stíha 500 riadkov denne
- **Kde:** `activities/MapActivity.kt:190–199` (žiadna kontrola existencie), `helpers/IndexStatus.kt:34`
  (`CLEANUP_BATCH = 500` na tabuľku a deň), spúšťané len z prehľadu na Domove a v Nastaveniach (`:50`).
- **Scenár:** Lukáš zmaže 2 000 duplikátov. Body na mape a počty v zhlukoch ostávajú; pri knižnici 40 000
  fotiek trvá jedno kolo upratovania 80 dní. Rovnako dlho ostáva v `ocr.db` text zmazaných dokladov
  a v `qr.db` obsah kódov.
- **Oprava:** riadky mazať hneď pri mazaní fotky v appke (jedno miesto volané z `deleteFiles`,
  `CompareTrash`, `PhotoPathsAdapter`). V `loadPoints` vyradiť cesty, ktoré MediaStore nepozná. Dávku
  upratovania zväčšiť – test `reallyMissing` je lacný.

#### N13 – Obľúbené nie sú v žiadnej zálohe
- **Kde:** tabuľka `favorites` je v `gallery.db` (`databases/GalleryDatabase.kt:12`), ktorá má
  `fallbackToDestructiveMigration()` (`:33`) a nie je v `res/xml/backup_rules.xml` ani v automatickej zálohe
  (`safety/AutoBackup.kt:124–128`).
- **Scenár:** nový telefón, preinštalovanie alebo budúca zmena schémy `gallery.db` bez migrácie → obľúbené sú
  preč. Zostáva len ručný export v Nastaveniach.
- **Oprava:** zoznam obľúbených ciest pridať do týždennej zálohy (stačí textový súbor v ZIP-e) a ponúknuť
  jeho obnovu; `gallery.db` nechať prestavateľnú.

#### N14 – CLIP modely sa sťahujú z cudzieho účtu bez kontrolného súčtu
- **Kde:** `clip/ClipModels.kt:14–21` (prvá adresa `huggingface.co/IacobIonut01/Gallery/…`), kontrola len
  minimálnej veľkosti `:22–23, 62`.
- **Scenár:** majiteľ repozitára súbor vymení alebo zmaže. Appka buď stiahne iný model (vektory nových fotiek
  prestanú sedieť so starými a hľadanie sa potichu pokazí), alebo prejde na záložnú adresu s inou veľkosťou
  (335 + 242 MB). Natívny parser ONNX dostáva neoverený vstup.
- **Oprava:** pripnúť SHA-256 oboch súborov v kóde a overiť po stiahnutí; ideálne modely priložiť
  k vlastnému GitHub Release.

#### N15 – „Zvyšok pri nabíjaní“ sa na pozadí v praxi nespustí
- **Kde:** `jobs/FamilySyncJob.kt:88–92` – job volá `IndexingService.start`, čo je spustenie foreground služby
  z pozadia; komentár sám hovorí, že Android 12+ to nedovolí (okrem vypnutej optimalizácie batérie).
- **Scenár:** voľba „Nové fotky hneď, zvyšok pri nabíjaní“ alebo Ľahký profil: veľká dávka sa odloží, telefón
  je celú noc na nabíjačke, ráno nie je spracované nič. Dobehne to až vtedy, keď Lukáš otvorí appku
  s pripojenou nabíjačkou (`IndexingService.autoScanIfStale`, `services/IndexingService.kt:904–916`).
- **Oprava:** odloženú prácu robiť priamo v behu jobu (segmenty do 9 minút, ako už robí synchronizácia),
  bez foreground služby. Do vtedy to v Nastaveniach pri voľbe aspoň napísať.

### P3

#### N16 – „Zastaviť“ v šetrnom režime len preskočí na ďalšiu úlohu
- **Kde:** `services/IndexingService.kt:119–125` (zastaví sa len to, čo je v `launched`), `:340–352`
  (`chain` po skončení úlohy spustí ďalšiu).
- **Scenár:** režim výkonu „šetrne“ alebo „naplno len pri nabíjaní“ bez nabíjačky. Ťuk na Zastaviť ukončí
  tváre, hneď sa rozbehne poloha, potom QR, OCR… Treba ťukať pri každej úlohe. V predvolenom režime Max sa
  to neprejaví.
- **Oprava:** príznak `userStopped`, ktorý `chain` rešpektuje (ako `timedOut`).

#### N17 – Súbeh pri zastavení a opätovnom spustení indexerov
- **Kde:** `faces/GeoIndexer.kt:17–28, 78–87`, rovnako `QrIndexer`, `PhashIndexer`, `OcrIndexer`,
  `ClipIndexer` (`@Volatile var isRunning`). `FaceIndexer` to má opravené tokenom behu (`faces/FaceIndexer.kt:26–33`).
- **Scenár:** stop a hneď nový štart (zmena batérie, ručné spustenie): starý beh uvidí `isRunning == true`
  a pokračuje, bežia dva naraz; ktorý skončí prvý, vo `finally` zastaví aj druhý.
- **Oprava:** rovnaký vzor `owner.compareAndSet` ako vo `FaceIndexer`.

#### N18 – CLIP si nezapamätá fotky, ktoré sa nedali spracovať
- **Kde:** `clip/ClipIndexer.kt:86–95` (zápis len pri úspechu). Podobne `faces/FaceIndexer.kt:136–164` pri výnimke.
- **Scenár:** RAW, poškodené alebo nepodporované súbory zostávajú „nové“. Každý automatický sken (po 6
  hodinách pri otvorení appky) kvôli nim načíta ONNX model, skúsi ich a zahodí.
- **Oprava:** uložiť riadok s prázdnym vektorom a časom pokusu; skúsiť znova až po zmene súboru.

#### N19 – Hľadanie: meno osoby sa porovnáva ako podreťazec
- **Kde:** `helpers/SmartSearch.kt:46` (`n.contains(t) || t.contains(n)`), následok `:76–79`.
- **Scenár:** dopyt „les“ sedí na osobu „Aleš“ → vrátia sa len jeho fotky a CLIP sa vôbec nespustí. Jedno
  písmeno sedí takmer na každého.
- **Oprava:** porovnávať začiatky slov v mene, najmenej 2–3 znaky; pri zhode mena aj neprázdnom CLIP výsledku
  ukázať oboje oddelene.

#### N20 – Spomienky: prelom roka a zloženie „vydarených dní“
- **Kde:** `helpers/Memories.kt:41–43` (porovnanie `DAY_OF_YEAR` ±2), `:104–128`.
- **Scenár:** 1. januára sa nenájdu fotky z 30.–31. decembra; po 29. februári sa okno posunie o deň.
  „Vydarené dni“ rátajú všetky obrázky z MediaStore, takže vyhrá deň so 150 snímkami obrazovky alebo
  obrázkami z WhatsAppu.
- **Oprava:** porovnávať mesiac a deň; do spomienok brať len fotky s dátumom nasnímania z EXIF.

#### N21 – Náhľady na mape sú pri fotkách na výšku otočené
- **Kde:** `activities/MapActivity.kt:611–623` (`decodeImage` bez EXIF). Rovnaký predpoklad ako N5.
- **Oprava:** `UprightDecoder` alebo Glide (ten orientáciu rieši a má aj cache).

#### N22 – Posledné: vylúčený priečinok sa porovnáva ako predpona
- **Kde:** `activities/MainActivity.kt:1133` (`parent.startsWith(it)`), bez zohľadnenia zahrnutých priečinkov.
- **Scenár:** vylúčenie `…/Pictures/Scr` skryje aj `…/Pictures/Screenshots`; zahrnutý podpriečinok vylúčeného
  rodiča sa v Posledných neukáže, hoci v galérii áno.
- **Oprava:** použiť `IndexScope` (bez podmienky zamknutia navyše nič nechýba) alebo pôvodné
  `isThisOrParentExcluded`.

#### N23 – Aktualizácie: zlyhaná kontrola sa ráta ako vykonaná, sťahovanie bez poistky
- **Kde:** `helpers/AppUpdater.kt:55` (čas kontroly sa zapíše aj po chybe), `:98–120`.
- **Scenár:** appka sa otvorí bez siete → ďalších 24 hodín sa aktualizácia nehľadá. Dva ťuky na „Stiahnuť“
  spustia dve sťahovania do toho istého súboru. APK (okolo 58 MB) ostáva v cache.
- **Oprava:** čas zapisovať len po úspechu, sťahovanie chrániť príznakom, súbor po inštalácii zmazať.
  Povolenie `UPDATE_PACKAGES_WITHOUT_USER_ACTION` v manifeste sa už nepoužíva.

#### N24 – `getPackageName()` zostavuje stack trace pri každom volaní
- **Kde:** `activities/SimpleActivity.kt:46–56`.
- **Dôsledok:** `getPackageName()` volá framework veľmi často (zdroje, nastavenia, layouty). Každé volanie
  vyrobí `Throwable().stackTrace` – merateľná réžia na hlavnom vlákne vo všetkých obrazovkách. Zároveň je to
  krehké: stačí zmena názvu metódy v commons a dialógy SAF sa potichu prestanú zobrazovať.
- **Oprava:** spoof zapínať len cielene okolo volaní SAF metód (obal `withoutForkWarning` už existuje),
  nie skúmaním zásobníka.

#### N25 – Prehliadač je exportovaný a verí príznaku „preskoč overenie“ z cudzieho intentu
- **Kde:** `activities/ViewPagerActivity.kt:517`, manifest `exported="true"`.
- **Scenár:** iná appka (alebo skratka) pošle intent s cestou do zamknutého priečinka a `skip_authentication`.
  Fotka sa otvorí bez PIN-u. Je to zdedené z Fossify a vyžaduje appku nainštalovanú v telefóne, preto P3.
- **Oprava:** príznak prijímať len od vlastného balíka (napr. cez jednorazový token v pamäti procesu).

#### N26 – Kontrola „dáta z novšej verzie“ je len v hlavnej obrazovke
- **Kde:** `safety/DataSafetyUi.kt:35–49`, volané z `MainActivity.kt:276`.
- **Scenár:** po inštalácii staršej appky otvorí Lukáš fotku zo správcu súborov alebo Nastavenia cez systém.
  Kontrola neprebehne a prvý prístup do `people.db` skončí výnimkou Room. Dáta ostanú nedotknuté, ale appka
  môže spadnúť.
- **Oprava:** kontrolu presunúť do spoločného predka aktivít alebo do `App.onCreate`.

#### N27 – Dočasné videá z pohyblivých fotiek sa nemažú
- **Kde:** `activities/ViewPagerActivity.kt:1565`, `activities/PhotoVideoActivity.kt:482`.
- **Dôsledok:** každá prehratá pohyblivá fotka nechá v cache MP4 (jednotky MB), aj zo zamknutého priečinka.
  Názov je len podľa mena súboru, takže dve fotky s rovnakým menom si súbor prepíšu.
- **Oprava:** jeden pevný dočasný súbor, zmazať pri zastavení prehrávania.

#### N28 – IndexingService: prehltnutá chyba pri štarte a okno pri ukončení
- **Kde:** `services/IndexingService.kt:782–795` (`startForegroundCompat` zahodí každú výnimku),
  `:359–361` + `:744–780` (`finish()` z vlákna indexera bez zámku voči `onStartCommand`).
- **Scenár:** ak `startForeground` zlyhá, služba pokračuje bez neho a systém appku po pár sekundách ukončí
  („did not then call startForeground“). Druhé: nový štart príde presne medzi kontrolou `active <= 0`
  a `stopSelf()` → práca beží ďalej bez foreground služby a bez notifikácie. Obe sú málo pravdepodobné.
- **Oprava:** pri zlyhaní `startForeground` službu hneď ukončiť a nič nespúšťať; stav služby meniť len
  na hlavnom vlákne a používať `stopSelf(startId)`.

#### N29 – Obnova z Koša nechá kópiu v úložisku appky
- **Kde:** `extensions/Activity.kt:414–489` (po skopírovaní späť sa zdroj nemaže), používa aj
  `compare/CompareTrash.kt:88–100`.
- **Dôsledok:** po „Späť“ v porovnávači alebo po obnove z Koša zaberá fotka miesto dvakrát; veľkosť Koša
  v Nastaveniach ju neráta (číta z DB). Zmizne až pri vysypaní Koša – čo je práve operácia z N1.
  Ďalej: fotka zmazaná dvakrát z tej istej cesty prepíše staršiu kópiu v Koši (`:391`), a položky staršie
  než 30 dní sa mažú automaticky bez upozornenia (`MainActivity.kt:2646–2665`).
- **Oprava:** po overenej kópii zdroj zmazať; pri oprave N1 myslieť na tieto siroty.

---

## 2. Slabé miesta v architektúre a funkciách

**Cesta súboru ako identita fotky.** Šesť prestavateľných indexov (OCR, QR, poloha, pHash, CLIP, značka
spracovania) aj `faces.db` sú kľúčované cestou. Presun a premenovanie rieši len `FaceRelinker` pre tváre;
ostatné indexy fotku spracujú znova a starý riadok čaká na upratovanie. Obsahový kľúč (`ContentKey`) už
existuje, ale používa ho len rodinné prepojenie a relinker. Z toho priamo vyplývajú N7, N12 a zbytočné
opakované OCR po každom upratovaní priečinkov.

**Rozsah (zamknuté, vylúčené, skryté) sa vynucuje pri indexovaní, nie pri zobrazení.** Každá obrazovka si
číta MediaStore alebo index po svojom (sedem súkromných kópií `queryImages`) a filter si buď pridá
(`CompareRepository`, Posledné, Ľudia), alebo nie (N2, N3, N4, N10, N11). Chýba jedno miesto typu
„viditeľné fotky“ a test, ktorý by prešiel všetky obrazovky proti zamknutému priečinku.

**Kôš v koreni `filesDir`.** Zdedené z Fossify, kde tam nič iné nebolo. Fork tam pridal vlastné dáta (N1).

**Indexery sú kópie toho istého vzoru.** Šesť objektov so singleton stavom, vlastnou notifikáciou,
vlastným dekódovaním a vlastným `queryImages`. Rozchádzajú sa v detailoch, ktoré sú chybami: EXIF (N5, N6,
N21), zastavenie (N17), záznam neúspechu (N18). Spoločný základ (zdroj fotiek v rozsahu, vzpriamené
dekódovanie, token behu, záznam pokusu) by tieto triedy chýb odstránil naraz.

**IndexingService je stavový automat cez viac vlákien s ručnými príznakmi** (`active`, `stopped`, `timedOut`,
`deferred`, `autoRan`, `memoryLow`, štyri množiny úloh). Funguje, ale každá nová podmienka pridáva ďalší
príznak (N16, N28). Postupné a paralelné spúšťanie sú dve rôzne cesty s rôznym správaním tlačidla Zastaviť.

**Chyby sa zahadzujú.** V rozsahu revízie je 283 blokov `catch (…: Throwable)`, väčšina bez logu. Appka vďaka
tomu nepadá, ale chyba sa prejaví ako „nič sa nenašlo“ a nedá sa dohľadať. Jediný kanál je posledná chyba
indexovania v Nastaveniach.

**Celá knižnica v pamäti.** `PeopleIndex.build` načíta všetky tváre aj s odtlačkami
(`faces/FaceDao.kt` `getAllFaces`), `ClipSearch.vectors` všetky CLIP vektory (2 kB na fotku plus kópia
ako `FloatArray`). Manifest nemá `largeHeap`. Pri desiatkach tisíc tvárí a fotiek to ide do stoviek MB –
bez merania neviem, kde je hranica, ale rast je lineárny a nič ho neobmedzuje.

**Navigácia cez statické polia** (`helpers/PathTransfer.kt`). Rieši limit veľkosti intentu, ale stav neprežije
znovuvytvorenie aktivity ani zabitie procesu (N9; prehliadač po obnove stratí „uzavretý zoznam“ a listuje
celým priečinkom).

**Desať Room databáz s ručnými singletonmi.** `private var db` bez `@Volatile` pri dvojitej kontrole zámku
(napr. `faces/PeopleDatabase.kt:56–70`) – v praxi zriedkavé, ale formálne nesprávne. Väzby medzi databázami
(id tváre, id osoby) drží len disciplína kódu a poistky ako `ensureIdsAboveReferences`. Tie sú urobené
starostlivo; treba len vedieť, že obnova jednej DB zo staršej zálohy (N8) túto súdržnosť obchádza –
napr. id osôb v `extras.db` a `family.db` voči staršej `people.db`.

**Závislosť od vnútorností commons.** Anti-fork kontroly sa obchádzajú prepisom `getPackageName()` (N24)
a zápisom do cudzích preferencií (`App.kt:31–43`). Každá aktualizácia commons to môže potichu rozbiť.

**Zostavenie.** Vydanie je bez minifikácie (`app/build.gradle.kts`, `isMinifyEnabled = false`) a so štyrmi
ML runtime knižnicami (MediaPipe, TFLite, ONNX Runtime, Tesseract) – APK má okolo 58 MB, pomalší studený
štart. `galeria_faces` v SharedPreferences slúži ako sklad všetkého vrátane front a množín, ktoré len rastú
(`faces_relink_transferred`, `faces/FaceRelinker.kt:33`).

**Čo je urobené dobre** (aby bol obraz úplný): ochrana `people.db`/`faces.db`/`extras.db` – migrácie bez
destruktívneho fallbacku, premenovanie poškodenej DB namiesto zmazania, strážca verzie, exportované schémy
s kontrolou v CI. Zlúčenie osôb v transakcii so záznamom na vrátenie. Otáčanie rámčekov tvárí na mieste.
`HeavyGate` pre jeden ťažký model naraz. Invarianty hromadného mazania v `CompareBulk`. Jednotné ovládanie
(výberový režim, Kôš + potvrdenie, `setupEdgeToEdge`, témované dialógy) je dodržané takmer všade – výnimkou je
len systémový `DatePickerDialog` v `PeopleSearchActivity.kt:149` a `NewerDataActivity` bez `setupEdgeToEdge`.
Nič v rozsahu nebeží trvalo na pozadí: služba spracovania končí sama, joby sú krátke.

---

## 3. Čo chýba, čo doladiť a nové nápady

Náročnosť: **S** do dňa, **M** niekoľko dní, **L** týždeň a viac.

### Najväčší prínos pre Lukáša

| # | Nápad | Prečo | Nár. |
|---|---|---|---|
| 1 | **Jeden filter viditeľnosti pre všetky obrazovky** + test „zamknutý priečinok sa neobjaví nikde“ | zatvára N2, N3, N4, N10, N11 naraz a bráni návratu tej istej chyby pri ďalšej obrazovke | S–M |
| 2 | **Nočné spracovanie priamo v jobe pri nabíjaní** (bez foreground služby, segmenty do 9 min) | fotky z dňa sú ráno spracované bez otvorenia appky; voľba „zvyšok pri nabíjaní“ začne platiť | M |
| 3 | **OCR nanovo: vzpriamené dekódovanie + plnotextový index (FTS)** | dokumenty fotené na výšku sa konečne nájdu; hľadanie v texte bude okamžité aj pri veľkej knižnici | M |
| 4 | **Miesta podľa názvu** – offline prevod GPS na obec/štát z malej databázy | hľadanie „Tatry“, „Chorvátsko“, názvy výletov v Spomienkach, albumy podľa miest | M |
| 5 | **Úplná záloha jedným súborom**: osoby + obľúbené + výrezy z Picasy + nastavenia, s výberom zálohy a počtom osôb pri obnove | dnes prežije nový telefón len časť; pri obnove sa ponúka naslepo najnovšia | M |

### Ďalšie nápady

- **Identita fotky podľa obsahu vo všetkých indexoch** (L). Presun a premenovanie prestanú spúšťať nové OCR
  a CLIP, „presné kópie“ budú naozaj presné, upratovanie indexov sa zjednoduší.
- **Okamžité odstránenie z indexov pri mazaní v appke** (S). Mapa, počty a Dokumenty sedia hneď.
- **Jedno hľadanie namiesto dvoch** (L). `SearchActivity` a `PeopleSearchActivity` majú rôzne pravidlá aj
  rôzne chyby. Jedno pole s čipmi (osoba, miesto, obdobie, text, QR) a kombináciami typu „Peter 2024 more“.
- **Spomienky s výberom** (M): len fotky z fotoaparátu, titulná podľa `PhotoQuality`, spomienky s osobou
  („Rok s Jankou“), karta „Dnes pred rokmi“ na Domove.
- **Kôš** (S): veľkosť vrátane sirôt, nastaviteľná lehota, upozornenie pred automatickým zmazaním.
- **Diagnostický balík jedným ťukom** (S): verzie DB, počty v indexoch, posledné chyby, stav jobov. Pri 283
  zahodených výnimkách je to jediný spôsob, ako sa dozvedieť, čo sa na telefóne deje.
- **Tváre a text z videí** (L): kľúčové snímky videa do tvárí a OCR – dnes sú videá pre Ľudí neviditeľné.
- **Menšie CLIP vektory** (M): 512 × float32 → float16 alebo int8 zmenší `clip.db` aj pamäť pri hľadaní 2–4×.
- **Minifikácia s presnými keep pravidlami + baseline profil** (M): menšie APK, rýchlejší štart.
- **Aktualizácie** (S): priebeh sťahovania, kontrolný súčet APK z popisu vydania, zmazanie po inštalácii.
- **Predvolený režim výkonu „naplno len pri nabíjaní“ pre veľké dávky** (S): prvé spracovanie knižnice dnes
  beží naplno aj na batérii (`helpers/IndexPerf.kt:19`, predvolené MAX).
- **Stav navigácie do `ViewModel`-u** (M): odstráni `PathTransfer` a celú triedu chýb ako N9.
