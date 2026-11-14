# Celková revízia Galéria+ 0.67.3 (11/2026)

Podklady:
- nezávislá revízia modelom Fable v 3 častiach: `fable_2026-11/fable-android.md`, `fable-web-server.md`, `fable-rodina.md`;
- revízia zo 7. 11. (15 oblastí, overovanie prerušil limit) a hodnotenie nápadov – použité na krížovú kontrolu.

Body označené ✔ našli obe revízie nezávisle, alebo sú overené v kóde. P1 = strata dát, súkromie, bezpečnosť; P2 = zlé správanie; P3 = drobnosti.

## Balík 1 – Súkromie a bezpečnosť (S, odporúčam hneď)
| # | Čo sa pokazí (príklad) | Kde |
|---|---|---|
| 1 ✔ P1 | Zamknutý priečinok „Doklady“: v Ľuďoch → Hľadať osobu „Lukáš“ sa ukážu aj fotky z neho a otvoria sa bez PIN-u. | `PeopleSearchActivity.kt:195–296` |
| 2 ✔ P1 | To isté v **Spomienkach** a **Špeciálnych fotkách** (panoráma zo zamknutého priečinka je na karte). | `helpers/Memories.kt:130`, `SpecialActivity.kt:155` |
| 3 ✔ P1 | Ktokoľvek v sieti môže cez `/api/zip` skúšať PIN bez obmedzenia – 6 číslic sa dá uhádnuť. | `sync/MediaServer.kt:447–450` |
| 4 ✔ P1 | Osoba pomenovaná len na fotkách v zamknutom priečinku sa objaví v telefóne manželky (mená a skupiny idú bez filtra). | `family/FamilyRecorder.kt:105–163`, `FamilySync.kt:86` |
| 5 P2 | Server pre PC počúva aj na mobilných dátach/VPN; má prijímať len lokálne adresy. | `MediaServer.kt:52` |
| 6 P2 | Čerstvo zamknutý priečinok ostane v Dokumentoch a na mape, kým neprebehne spracovanie. | `IndexScope.kt`, `DocsActivity.kt:86` |
| 7 P2 | Párovací odkaz rodiny prijme ľubovoľnú (aj internetovú) adresu. | `family/PairingActivity.kt:100` |
| 8 P3 | Potvrdenie mazania z PC v mobile zmaže aj fotky z priečinka zamknutého až po označení. | `sync/PcMarkedPrompt.kt:46` |

Riešenie 1, 2, 6: jeden spoločný filter viditeľnosti pre všetky obrazovky + test „zamknutý priečinok sa neobjaví nikde“.

## Balík 2 – Ochrana dát (S–M)
| # | Čo sa pokazí | Kde |
|---|---|---|
| 9 ✔ P1 | **Vysypať Kôš** zmaže celý `filesDir` – aj stiahnuté CLIP modely, dáta rozpoznávania textu a výrezy z Picasy (vzory osôb). | `Context.kt:150` (`recycleBin = filesDir`), `Activity.kt:495` |
| 10 ✔ P2 | Po poškodení databázy sa ako „posledná záloha“ ponúkne už prázdna DB a o 4 týždne rotácia vytlačí dobré zálohy. | `safety/AutoBackup.kt:72–97`, `DataSafetyUi.kt:77` |
| 11 P2 | Prerušené nahrávanie z PC uloží useknutú fotku ako hotovú. | `sync/ServerUpload.kt:139` |
| 12 P2 | Obľúbené nie sú v žiadnej zálohe. | safety/* |

## Balík 3 – Rodinné prepojenie spoľahlivo (M)
| # | Čo sa pokazí | Kde |
|---|---|---|
| 13 P1 | „Poslať manželke“: keď sa prvý pripojí jej telefón, tvoje fotky ostanú vo fronte, video sa pretrhne. | `jobs/FamilySyncJob.kt:112`, `FamilyServer.kt:204`, `FamilyClient.kt:172` |
| 14 P2 | Pri prvej veľkej synchronizácii vzniknú **zdvojené osoby** (operácie z viacerých vlákien bez zámku). | `FamilySync.kt:67`, `FamilyLocalState.kt:236` |
| 15 P2 | Zmena sa môže natrvalo stratiť (lamport ako kurzor, nie v jednej transakcii). | `FamilyRecorder.kt:93`, `MetaDao.kt:30` |
| 16 P2 | „Ponechať všetky“ pri rovnakých menách vytvorí dvojníkov. | `FamilyProposals.kt:80` |
| 17 P2 | Neodoslané položky zmiznú bez upozornenia; chýba stav prepojenia v UI. | `FamilyClient.kt:180`, `FamilySend.kt:117` |
| 18 P2 | Po zrušení a novom spárovaní nový telefón nedostane zmeny z medzičasu. | `FamilySync.kt:76` |

## Balík 4 – Spracovanie a výsledky (M)
| # | Čo sa pokazí | Kde |
|---|---|---|
| 19 ✔ P2 | OCR číta fotky bez otočenia podľa EXIF – doklad odfotený na výšku sa nedostane do Dokumentov ani hľadania, prázdny výsledok ostane natrvalo. | `faces/OcrIndexer.kt:142, 318` |
| 20 P2 | CLIP počíta z neotočených fotiek – horšie hľadanie podľa popisu. | `clip/ClipIndexer.kt:198` |
| 21 ✔ P2 | „Presné kópie“ nie sú presné (hash 8×8 ± 2 bity) a „Upratať presné kópie“ môže zmazať upravenú/lepšiu verziu. | `faces/PhashGrouper.kt:76–111`, `compare/CompareBulkKeep.kt:77` |
| 22 ✔ P2 | Hľadanie „pes a mačka“ vráti fotky všetkých osôb s „a“ v mene a vypne CLIP. | `helpers/SmartSearch.kt:42` |
| 23 P2 | Mriežka z mapy/Spomienok je po otočení telefónu prázdna. | `PhotoGridActivity.kt:32` |
| 24 P2 | Mapa ukazuje zmazané fotky. | GeoIndexer upratovanie |

## Balík 5 – Web a server pre PC (S)
| # | Čo sa pokazí | Kde |
|---|---|---|
| 25 P2 | Držaný kláves X označí rad fotiek (0,5 s = 16 fotiek). | `gallery.html:5020` |
| 26 P2 | Druhý ZIP počas sťahovania nahradí stránku textom `{"error":"busy"}`. | `gallery.html:1989` |
| 27 ✔ P2 | Mazanie viac ako 1000 označených z webu zlyhá; nad 500 sa stratí „Vrátiť“. | `gallery.html:4588`, `ServerLogic parseIds` |
| 28 P2 | Batéria: web pinguje každé 4 s a server sa sám nikdy nevypne. | `gallery.html:1775`, `HttpSyncService` |
| 29 ✔ P2 | Texty 0.67.3 sľubujú „Uložiť do priečinka“ a kopírovanie cez USB kábel – pri zdieľaní pripojenia to Chrome nedovolí. | `gallery.html`, changelog |
| 30 P3 | Pretiahnutá miniatúra príde do Claude ako `thumb.jpg`; Ctrl+C robí obrovské PNG. | `gallery.html` |

## Najlepšie nápady (z oboch revízií)
1. Jeden filter viditeľnosti + test súkromia (S–M).
2. Server pre PC: spárovanie PC namiesto PIN-u, len lokálne adresy, automatické vypnutie po nečinnosti (M).
3. Stav rodinného prepojenia v UI + sprievodca „zlúčiť osoby s rovnakým menom“ (S–M).
4. Úplná záloha jedným súborom (osoby, obľúbené, výrezy Picasa, nastavenia), aj do PC (M).
5. Hľadanie z PC podľa osôb, textu a popisu (`/api/search`) a „osoba + vec + čas“ v telefóne (M–L).
6. Nočné spracovanie pri nabíjaní bez otvorenia appky (M).
7. Miesta podľa názvu (offline GPS → obec) (M).

## Návrh poradia
Balík 1 → 2 → 5 (rýchle, jedna verzia 0.68.0), potom 3 a 4 (0.69), nápady podľa výberu.
