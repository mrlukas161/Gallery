# Revízia 0.65 — výsledky a otvorené body (19.10.2026)

Vetva `revizia-0.65` = zlúčené `revizia-0.65-c1..c5` (merge bez konfliktov, commit e8924d5d1).
Agenti bežali LOKÁLNE (remote izolácia nebola dostupná). Kompilácia po častiach: C1, C3, C5 BUILD SUCCESSFUL; C2, C4 nedokončené (RAM). **Zlúčený celok ešte nebol skompilovaný — prvý krok.**
Build tipy: KSP padá „different roots" (projekt D:, gradle cache C:) → `-Pksp.incremental=false`; málo RAM → `-Dorg.gradle.jvmargs=-Xmx3g`, `-Pkotlin.compiler.execution.strategy=in-process`. Lokálne `GRADLE_USER_HOME=D:\Claude Cowork\Galeria\.gradlehome`.

## Opravené (stav)
- **C1 dáta tvárí/indexy: 16/16** — upratovanie nikdy nemaže tváre; people/faces/extras bez destruktívneho fallbacku (+ `FacesDatabase.ensureIdsAboveReferences`); RefreshFaces páruje raw aj upright bboxy; doplnenie chýbajúcich odtlačkov; indexery rešpektujú zamknuté/vylúčené priečinky (`faces/IndexScope.kt`); CLIP bez zbytočného modelu, sťahovanie zatvára spojenie; PC server sa zamkne po 20 zlých tokenoch, upload strop 4 GB + voľné miesto; IndexingService `onTimeout` (Android 15).
- **C2 osoby UI: 12/12** — `@Transaction` zlúčenie (bez pádu na cannot_links), skupiny premenovať/zmazať, výberový režim v Ľuďoch a tvárach osoby, potvrdenia, edge-to-edge, témy, filter `media_path`.
- **C3 prehliadač/porovnávač:** 13 opravených, o5-5 vyvrátené, o5-4 dokončené s C2 — porovnávač maže cez Kôš + potvrdenie; pohyblivá fotka sa zastaví; výber textu rešpektuje rotáciu; uzavretý set sa obnoví; info panel témovaný.
- **C4 hlavná/mriežky:** všetko okrem časti k2-7 — PhotoPathsAdapter pri koši maže aj originál (vážna chyba z 0.63!), UI len na hlavnom vlákne, odobrať z obľúbených, kopírovať/presunúť/otvoriť v/upraviť/nastaviť ako; DiffUtil súbeh; hľadanie s generation guardom; ExploreActivity a UpdateResultReceiver zmazané; changelog vo farbách témy; Späť v podpriečinkoch.
- **C5 nastavenia/texty/stock:** hľadanie v Nastaveniach, duplicitný riadok, sekcie, prepínač auto-aktualizácií, jednotná terminológia, úvodzovky, SAF dialóg na SD/OTG (anti-fork spoof), poll v Nastaveniach.

## Otvorené body (na rozhodnutie / ďalšiu fázu)
1. **Manifest:** `ACCESS_NETWORK_STATE` má `tools:node="remove"` → `ClipModels.isMeteredNetwork()` vždy false → CLIP (150–580 MB) sa stiahne aj na mobilných dátach. Opraviť.
2. **Súkromie — ROZHODNUTÉ 20.10.: zamknuté/vylúčené vôbec nespracúvať.** FaceIndexer a PC server filtrujú cez IndexScope; tváre sa nemažú, UI ich skryje (q2). Pôvodne: FaceIndexer spracúva aj zamknuté/vylúčené priečinky (tváre v Ľuďoch bez PIN-u); PC server sprístupní celý MediaStore vrátane zamknutých.
3. Tváre presunutých/zmazaných fotiek ostávajú na starej ceste → UI (FaceTagging/Person/People) má skryť neexistujúce súbory; trvalé riešenie = identita fotky podľa obsahu (súvisí s rodinným prepojením).
4. Žiadna záloha people.db → periodický export osôb a priradení (súvisí s rodinným prepojením — rovnaký formát).
5. `AppUpdater`: zlyhaná sieť ukáže „Máš najnovšiu verziu".
6. Natvrdo texty: `clip/ClipIndexer.kt` (WiFi, „CLIP indexujem"), `ClipMlModels.kt`, kanál „Indexovanie" v IndexingService, notifikácie indexerov — zjednotiť podľa slovníka.
7. k2-7 zvyšok: premenovať, otočiť, skryť, zmenšiť, opraviť dátum v PhotoPathsAdapter.
8. `PeopleSearchActivity.runSearch` — over generation guard; `DatePickerDialog` bez témy.
9. Mŕtvy kód: `FaceClusterer`, `FaceDao.getAllEmbeddings`, `menu_compare_list.xml`, smart albumy, nepoužité stringy (index_all_title, index_all_summary, face_indexing_*, ocr_starting/progress/error, indexing_reembed, delete_confirm, copy_text, sort_newest/oldest, person_long_press_hint, map_gps_suspicious, smart_search_title, show_smart_albums).
10. EXIF 5/7 v UprightDecoder prehodené oproti Glide (zriedkavé; oprava by zmenila priestor uložených rámčekov).
11. **Slovník pojmov:** spracovanie knižnice · rozpoznávanie tvárí/textu (OCR) · Ľudia/osoba/tvár · Dokumenty · Pohyblivé fotky · Spomienky · Miesta · Preskúmať · Podobné a duplikáty (Série, Duplikáty) · Kôš · výber textu · Wi-Fi · Server pre PC · úvodzovky „…“ · tykanie.
12. `D:\tmp\gradle_home_c4` (~1 GB, vytvoril agent C4) — zmazať len so súhlasom Lukáša.

## Test na zariadení/emulátore (od agentov, skrátene)
Inštalácia cez 0.64 (Ľudia, skupiny celé) · presun pomenovanej fotky + vynútené upratovanie (pref `index_cleanup_at`) · Prepracovať tváre 2× · zamknutý priečinok s dokladom zmizne z Dokumentov · porovnávač: mazanie s/bez koša, Enter · pohyblivá fotka po Domov · výber textu po otočení · Posledné/Dokumenty: zmazanie s košom zmaže originál · Nastavenia: hľadanie „ocr" a zmazanie · SD/OTG dialóg prístupu · svetlá/tmavá téma všetkých obrazoviek.
