# Rodinné prepojenie – nezávislá revízia (Galéria+ 0.67.3, vetva revizia-2026-11)

Dátum: 14. 11. 2026. Rozsah: `family/*`, `jobs/FamilySyncJob.kt`, `jobs/PeopleBackupJob.kt`, `dialogs/FamilyProposalsDialog.kt`, testy `app/src/test/.../family/*`, návrh a plán.

**Ako som overoval:** len čítaním kódu (statická revízia). Nič som nespúšťal – ani build, ani unit testy, ani dva telefóny. Každý nález nižšie som dohľadal v kóde po konkrétny riadok a prešiel celú cestu volaní; scenáre sú odvodené z kódu, nie odpozorované na zariadení. Kde som si nie istý dôsledkom, píšem to pri náleze. Cesty sú relatívne k `app/src/main/kotlin/org/fossify/gallery/`.

**Počty:** P1 = 2, P2 = 10, P3 = 16.

**Čo je v poriadku (stručne):** kryptografické jadro je čisté – ECDH P-256 overené tokenom, HKDF, AES-GCM s náhodným nonce, AAD viaže odosielateľa, poradie, smer aj endpoint, kľúč sedenia je pre každé sedenie nový, token je jednorazový, kľúč páru nie je v QR ani v DB. Schéma `family.db` v1 má vo všetkých vydaných tagoch (0.66.0 – 0.67.1) aj v HEAD rovnaký identityHash, bez destruktívneho fallbacku – železné pravidlo o migráciách je dodržané. Do people/faces/extras sa zapisuje len cez existujúce DAO. Server nebeží trvalo (len acquire/release). Fotky a výsledky zo zamknutých/vylúčených priečinkov sa cez balíky, AssignFace ani odtlačky neposielajú (výnimka: nález 2).

---

## 1. Chyby, ktoré naozaj nastanú

### P1

**1. Fronta „Poslať manželke“ často neodíde a dlhý prenos sa pretrhne – server sa vypne hneď po vlastnej úspešnej synchronizácii**
- Kde: `jobs/FamilySyncJob.kt:112-128` (`syncUntil` vráti `true` po prvom úspechu a vo `finally` uvoľní server), `family/FamilySchedule.kt:105-109` (po úspechu ďalšie okno až o 6 h), `family/FamilyAutoSync.kt:75-98` (pri otvorení appky server žije len počas vlastného pokusu), `family/FamilyServer.kt:204-211` (`release` → `stop()` zavrie aj rozbehnuté spojenia), `family/FamilyClient.kt:172-189` (frontu posiela len ten, kto je klient; server svoju frontu v tom istom sedení neodovzdá).
- Scenár A (nič neodíde): Lukáš cez deň pošle 5 fotiek, idú do fronty. V noci sa na nabíjačke skôr rozbehne jeho job, jej telefón ešte nie – jeho pokus zlyhá. O chvíľu sa rozbehne jej job: jej klient sa spojí s jeho serverom, vymenia si operácie, jej synchronizácia je „hotová“ → jej server sa vypne a jej okno končí na 6 h. Jeho ďalší pokus (o ≤ 2 min) ju už nenájde a hodinu skúša naprázdno. Fotky ostanú vo fronte, hoci sa telefóny „stretli“.
- Scenár B (prenos sa pretrhne): Lukáš posiela video, jeho klient nahráva kúsky na jej server. Medzitým prebehne jej 2-minútový pokus proti jeho serveru (trvá sekundy), vráti úspech a jej server sa zastaví uprostred jeho nahrávania. Pokračuje sa až pri ďalšom okne, s tou istou lotériou.
- Cez deň je to rovnaké: jej server beží len pár sekúnd po otvorení appky.
- Oprava: (S) server neuvoľňovať po vlastnom úspechu, ale držať do konca segmentu/okna alebo kým je aktívne sedenie (počítadlo sedení + krátky dobeh); pred `stop()` počkať na rozbehnuté požiadavky. (S–M) po `/f/ack` v roli servera: ak mám pre toho peera položky vo fronte, hneď spustiť vlastný klientsky sync (adresu poznám zo spojenia). (M) lepšie: obojsmerná fronta v jednom sedení – klient si po svojej fronte vypýta aj frontu servera.

**2. Mená osôb a skupín odchádzajú aj vtedy, keď osoba existuje len v zamknutom alebo vylúčenom priečinku**
- Kde: `family/FamilyRecorder.kt:105-112` (`uuidFor` zapíše `PersonCreate` s menom bez ohľadu na priečinok), `:141-143` (`facesAssigned` volá `uuidFor` ešte pred filtrom `refFor`), `:115-119` (premenovanie), `:160-163` (`GroupSet` s názvom skupiny a všetkými členmi), `family/FamilySync.kt:86-91` (snapshot po spárovaní: `uuidFor` pre všetky osoby a všetky skupiny).
- Scenár: Lukáš v zamknutom priečinku pomenuje osobu „X“. Tvár (AssignFace) sa správne neodošle, ale `PersonCreate("X")` áno – na manželkinom telefóne vznikne v Ľuďoch nová pomenovaná osoba „X“ bez tvárí. To isté pri prvom spárovaní pre všetky osoby a skupiny naraz.
- Dôsledok: priamo porušuje pravidlo „zamknuté a vylúčené priečinky sa nikdy neposielajú, ani výsledky z nich“ (spec 2.5). Neodchádza fotka ani odtlačok, „len“ meno a existencia osoby, ale je to viditeľné na druhom telefóne.
- Oprava: (S–M) osobu zverejniť až vtedy, keď má aspoň jednu tvár (ručné priradenie) v povolenom priečinku alebo vlastný vzor; do `GroupSet` dávať len už zverejnených členov; v snapshote rovnaký filter. Doplniť unit test (spec ho v časti 4 žiada: „filter zamknutých priečinkov“).

### P2

**3. Operácie sa aplikujú z viacerých vlákien naraz bez spoločného zámku → duplicitné osoby**
- Kde: `family/FamilySync.kt:67-72` (`retryPending` bez zámku), volajú ho: vlákno servera `family/OpExchange.kt:49` (z `FamilyServer.kt:119`), vlákno servera pri prijatej fotke `family/BundleReceiver.kt:112`, klient (ten istý `storeFromPeer`), rozhodnutie o návrhu `family/FamilyProposals.kt:118-123`. Zámok `syncLock` (`family/FamilyAutoSync.kt:28`) drží len klientska strana a obnova zálohy. Kritické miesto: `family/FamilyLocalState.kt:236-249` (`createPerson`: kontrola „už existuje“ ide cez cache osôb jednej inštancie, potom `insertPerson` a `upsertPerson` s REPLACE).
- Scenár: prvá veľká synchronizácia v noci, oba telefóny sú naraz klient aj server. Na manželkinom telefóne dostane tie isté Lukášove operácie vlákno servera (jeho push) aj jej klient (pull), každé po inej dávke, a obe spustia `retryPending` nad všetkými čakajúcimi operáciami. Druhé vlákno má cache osôb načítanú skôr, než prvé osobu vytvorilo → vloží ju druhýkrát a mapu UUID prepíše na novú. Výsledok: dve osoby „Mama“, jedna bez UUID (a s priradeniami, ktoré stihlo prvé vlákno).
- Dvojité návrhy sú ošetrené (kľúč `proposal_op:`), dvojité priradenie je neškodné; problém je vytvorenie osoby a nahradenie odtlačkov.
- Oprava: (S) jeden globálny zámok (alebo jednovláknový executor) okolo celého `OpApplier.process` + `FamilyLocal`, použitý zo všetkých štyroch miest.

**4. Lamport hodiny slúžia aj ako kurzor prenosu → operácia sa môže navždy stratiť**
- Kde: `family/FamilyRecorder.kt:93-100` (`nextLamport()` a `insertOp()` sú dve samostatné transakcie; zapisuje vlákno recordera, vlákno servera cez `beforeOwnOps`, klient aj rozhodnutia o návrhoch), `family/MetaDao.kt:30-47`, `family/OpExchange.kt:20-24` (odosiela sa „lamport > have“).
- Scenár A (súbeh): recorder si vezme lamport 100 pre premenovanie, ešte ho nezapíše; synchronizácia medzitým zapíše odtlačky s lamport 101 a hneď ich odošle; druhá strana potvrdí have = 101. Premenovanie s lamport 100 sa zapíše o chvíľu neskôr a už nikdy neodíde. Okno je krátke (mikrosekundy), ale strata je tichá a trvalá.
- Scenár B (obnova staršej `family.db`): oprava D1 zdvihne hodiny až pri hello (`FamilyClient.kt:84`, `FamilyServer.kt:94`). Zmeny urobené po obnove, ale pred prvým hello, dostanú lamport ≤ have druhej strany a neodídu.
- Oprava: (S) `nextLamport` + `insertOp` v jednej transakcii `family.db`. (M) kurzor prenosu oddeliť od lamportu: vlastné monotónne poradové číslo operácie (napr. rowid) na „čo som komu poslal“, lamport nechať len na poradie.

**5. Po zrušení prepojenia sa zmeny prestanú zapisovať a snapshot sa už nikdy nezopakuje**
- Kde: `family/FamilyRecorder.kt:59,86` (bez peera recorder nič nezapíše), `family/FamilySync.kt:76-79` (snapshot len raz, kľúč `f3_snapshot_done` sa nikde nemaže), `family/FamilyStore.kt:63-84` (zrušenie prepojenia nič z toho neupratuje; `onUnpaired` neimplementuje žiadny handler).
- Scenár: manželka dostane nový telefón (alebo preinštaluje appku bez zálohy → nové deviceId). Lukáš staré prepojenie zruší, týždeň upravuje Ľudí (nové osoby, mená, priradenia), potom spáruje nový telefón. Ten dostane celý starý op-log, ale týždeň zmien v ňom nie je a snapshot už neprebehne. Chýbajúce veci sa nedoplnia, kým sa ich Lukáš znova nedotkne.
- Oprava: (S) pri zrušení posledného prepojenia zmazať `f3_snapshot_done` (snapshot je idempotentný – druhá strana duplicitné operácie preskočí), alebo (M) snapshot robiť ako rozdiel oproti vlastnému op-logu pri každom novom spárovaní.

**6. Zlyhané položky fronty zmiznú potichu**
- Kde: `family/FamilyClient.kt:180-185` (po 5 pokusoch alebo keď sa balík nedá zostaviť → `STATE_FAILED`), tento stav už nikto nečíta (overené hľadaním – okrem tohto riadku žiadne použitie), `family/FamilySendUi.kt:49-55` (používateľ vidí len „odíde neskôr“), `family/FamilySend.kt:117-126` (odoslané kúsky sa po každej dokončenej synchronizácii zmažú, aj keď záverečný balík fotky ešte neprešiel).
- Scenáre: fotka sa po zaradení do fronty upravila/presunula (iná veľkosť alebo mtime) → `buildBundle` vráti null → hneď FAILED; prijímateľ nemá miesto alebo uloženie do MediaStore zlyhá → 5 synchronizácií a FAILED; prijímateľ medzičasom prišiel o rozpracovaný súbor (týždňové upratovanie, preinštalovanie, nezhoda kľúča) → kúsky sú u odosielateľa už zmazané z fronty, balík sa nikdy nedokončí. Vo všetkých prípadoch Lukáš nič nevidí, fotka u manželky nie je a riadok s cestou ostane v `family.db` navždy.
- Oprava: (S) notifikácia „N fotiek sa nepodarilo poslať – skúsiť znova“; stav fronty na obrazovke prepojenia; kúsky mazať až po úspechu záverečného balíka; pri FAILED zmazať aj `f4.path.*`.

**7. Párovací odkaz môže telefón spárovať s ľubovoľným serverom na internete**
- Kde: `AndroidManifest.xml:233-248` (exportovaná aktivita, BROWSABLE, `galeriaplus://family/pair`), `family/PairingActivity.kt:100-114` (dialóg ukáže len názov z odkazu), `family/PairUri.kt:34,48` (hostiteľ môže byť ľubovoľné DNS meno alebo verejná IP), `family/FamilyClient.kt:48-53` (adresa sa zapamätá a klient sa na ňu potom sám pripája).
- Scenár: odkaz na webe alebo v správe „galeriaplus://family/pair?…&name=POCO F8&ip=útočník&port=443“. Po jednom ťuknutí na „Prepojiť“ sa telefón spáruje s cudzím serverom, ktorý pozná token (sám ho vymyslel). Ten potom pri každej synchronizácii dostane mená osôb, skupiny, odtlačky tvárí a obsahové kľúče fotiek, môže posielať operácie a súbory do `Galéria/`.
- Treba ťuknutie používateľa, preto P2, nie P1. Kryptografia je v poriadku, slabé je, komu sa verí.
- Oprava: (S) z odkazu prijať len IP literál z lokálnych rozsahov (10/8, 172.16/12, 192.168/16, 169.254/16, fe80::/10, fc00::/7) a adresu ukázať v dialógu; (S–M) krátky overovací kód (4 číslice odvodené z kľúča páru) zobrazený na oboch telefónoch.

**8. „Ponechať všetky“ vytvorí duplicitné osoby**
- Kde: `family/FamilyProposals.kt:80-94` (pri návrhu rovnakého mena `keepAll` vytvorí novú osobu), `dialogs/FamilyProposalsDialog.kt:58` (tlačidlo), komentár na `:27` tvrdí, že hromadná voľba „nič nemení v Ľuďoch“ – pre návrhy rovnakého mena to neplatí.
- Scenár: hneď po spárovaní majú oba telefóny tie isté mená (Lukáš, manželka, deti, starí rodičia) → na každom desiatky návrhov „rovnaké meno“. Jedno ťuknutie na „Ponechať všetky“ vytvorí ku každej osobe dvojníka s rovnakým menom a všetky odložené priradenia a odtlačky sa zapíšu k dvojníkom. Späť sa dá len ručným zlučovaním po jednej (a každé zlúčenie pošle návrh druhej strane).
- Oprava: (S) z hromadnej voľby vynechať návrhy rovnakého mena (ostanú otvorené), alebo pridať „Zlúčiť všetky s rovnakým menom“ ako predvolenú hromadnú voľbu.

**9. Prvá synchronizácia: snapshot celých Ľudí beží vnútri obsluhy požiadavky a bez ochrany proti súbehu**
- Kde: `family/FamilyServer.kt:123` → `family/OpExchange.kt:20-24` → `family/FamilySync.kt:38-46,76-95`; časový limit klienta 30 s `family/FamilyClient.kt:215`; `snapshotIfNeeded` nemá zámok a `beforeOwnOps` sa volá v každom kole výmeny na oboch stranách.
- Scenár: po spárovaní si manželkin telefón vypýta operácie; Lukášov server v tej požiadavke počíta obsahové kľúče a geometriu pre tisíce ručne priradených tvárí (pri starších otočených fotkách aj načíta modely tvárí). Trvá to dlhšie než 30 s → klient hlási nedostupnosť. Ďalší pokus (o 2 min, alebo súbežný vlastný klient) spustí snapshot druhýkrát, lebo značka „hotovo“ sa zapisuje až na konci → priradenia a skupiny sú v op-logu dvakrát. Ak systém proces počas toho ukončí, snapshot začne zakaždým odznova.
- Dôsledok: prvé spojenie vyzerá ako nefunkčné, op-log zbytočne napuchne (duplicitné operácie sú inak na prijímateľovi neškodné). Čas snapshotu som nemeral, je to odhad podľa toho, čo kód na jednu tvár robí.
- Oprava: (S) zámok okolo snapshotu a priebežná značka; (M) snapshot spustiť hneď po spárovaní ako samostatnú úlohu s priebehom na obrazovke párovania, nie v obsluhe požiadavky.

**10. Zbytočná práca na „ľahkom“ telefóne pri každom príjme**
- Kde: `family/FamilySync.kt:69,126-131` + `family/FamilyDaos.kt:80-84` (kým čaká čo i len jedna operácia s tvárou, každý `retryPending` prejde všetky fotky s tvárami: `isFile` + dotaz do DB na každú), `family/FamilyLocalState.kt:112-113,124-126` (pri každej operácii s odtlačkami sa 2–4× načíta celá tabuľka odtlačkov), `family/OpApplier.kt:213-216` + `family/FamilyLocalState.kt:158` (každá odložená operácia = zápis do DB pri každom opakovaní), `family/BundleReceiver.kt:112` (`retryPending` po každej prijatej fotke).
- Scenár: Lukáš má tisíce ručných priradení na fotkách, ktoré manželka nemá. Všetky sa jej pošlú a 90 dní čakajú. Počas toho každá dávka 200 operácií, každá prijatá fotka a každé rozhodnutie o návrhu spustí prechod celej knižnice. Pri poslaní 100 fotiek je to 100 prechodov na vlákne servera.
- Dôsledok: pomalá synchronizácia a zbytočná záťaž práve na telefóne, ktorý má byť ľahký. Nie je to strata dát.
- Oprava: (S) `prepareFaceKeys` najviac raz za synchronizáciu a len pre nové/zmenené cesty; odtlačky načítať raz na beh; odloženie zapisovať len pri zmene kľúča čakania; celý beh v jednej transakcii. (M) pozri nápad 6.

**11. Odobratý člen skupiny sa vráti späť**
- Kde: `family/FamilyRecorder.kt:160-163` (`GroupSet` nesie celý zoznam členov), `family/OpApplier.kt:406-418` (prijímateľ len pridáva).
- Scenár: Lukáš odoberie Jana zo skupiny „Rodina“. Manželke Jano v skupine ostane (zámer – nič sa nemaže). Keď ona neskôr do skupiny niekoho pridá, jej `GroupSet` obsahuje aj Jana a Lukášovi sa Jano potichu vráti.
- Oprava: (S–M) posielať zmenu („pridaný X“ / „odobratý X“) namiesto celého zoznamu; odobratie na druhej strane ako návrh.

**12. Pokus pri otvorení appky nepozerá na sieť a pri čakajúcej fronte sa opakuje každú minútu**
- Kde: `family/FamilyAutoSync.kt:41-54`, `family/FamilySchedule.kt:72-84` (žiadna podmienka Wi-Fi; pri čakajúcej fronte interval 60 s), `family/FamilyServer.kt:29` (server počúva na všetkých rozhraniach).
- Scenár: Lukáš má vo fronte fotky (pre nález 1 tam môžu ležať dni). Mimo domu na mobilných dátach každý návrat na hlavnú obrazovku po ≥ 60 s spustí server, ohlásenie mDNS, pokus o spojenie na starú domácu adresu (3 s) a hľadanie cez mDNS (5 s). V cudzej Wi-Fi je port 8090 na chvíľu otvorený (bez kľúča síce vráti len 404).
- Do fronty sa rátajú aj položky pre už zrušené prepojenie (nález 5), tie ostanú „čakajúce“ navždy.
- Oprava: (S) pokus len na Wi-Fi/hotspote; pri neúspechu predlžovať interval (1 → 5 → 15 min); frontu zrušeného peera zmazať.

### P3

13. **„Toto nie je táto osoba“ sa stratí, keď tvár na druhom telefóne ešte nie je priradená** – `family/OpApplier.kt:348` (Skip a označí ako hotové). Keď ju potom jej telefón automaticky priradí tej istej osobe (aj vďaka Lukášovým odtlačkom), odmietnutie sa už neuplatní. Oprava: aj pri `current == null` zapísať negatívne pravidlo.
14. **Zlúčenie alebo zmazanie osoby, ktorú prijímateľ ešte nepozná, sa zahodí navždy** – `family/OpApplier.kt:307,318` (`?: Skip(PERSON_GONE)` aj pre UUID, ktoré len čaká na rozhodnutie o rovnakom mene). Po neskoršom „zlúčiť“ návrh na zlúčenie už nepríde. Oprava: použiť `missingPerson()` (odložiť, kým je UUID neznáme).
15. **„Ponechať moje meno“ pošle meno z času vzniku návrhu, nie aktuálne** – `family/FamilyProposals.kt:89-91`, `family/RenameResolution.kt:13-14`. Ak Lukáš osobu medzitým premenoval ešte raz, druhý telefón potichu prevezme staré meno a telefóny sa rozídu. Oprava: čítať aktuálne meno z people.db.
16. **Jedno preradenie tváre = dva návrhy** – `helpers/FaceDecisions.kt:39-40` posiela priradenie aj odobratie; na druhej strane vzniknú návrhy „preradiť“ aj „odobrať“ (`family/OpApplier.kt:338,352`). Oprava: pri odobratí, ku ktorému existuje otvorený návrh preradenia tej istej tváre, návrh nevytvárať.
17. **Zopakovanie zachyteného hello po reštarte servera** – pamäť nonce je len v inštancii servera (`family/FamilyServer.kt:33`, `family/ReplayGuard.kt:21`), ktorá vzniká pri každom okne. Útočník na sieti sedenie nezíska (nepozná kľúč), ale vyhodí rozbehnuté sedenie pravého telefónu (`FamilyServer.kt:92`) a prepíše „naposledy videný“. Oprava: čas v hello + tolerancia, alebo výzva servera (dva kroky).
18. **Párovanie bez potvrdenia kľúča** – telefón s QR uloží prepojenie skôr, než odpoveď dorazí (`family/FamilyServer.kt:70-76`), a pri opakovanom párovaní prepíše starý funkčný kľúč (`family/FamilyStore.kt:41-43`, `family/FamilyKeys.kt:22-34`). Stratená odpoveď = jeden telefón hlási „prepojené“, druhý nie; pomôže len nové párovanie. Oprava: tretí krok (potvrdenie novým kľúčom), až potom uložiť.
19. **Zrušenie prepojenia sa druhý telefón nemusí dozvedieť** – `family/FamilyClient.kt:106-110,137`: akákoľvek odpoveď iná než platná (aj od cudzieho zariadenia na starej adrese) sa berie ako „druhá strana nás už nepozná“ a kľúč sa zmaže. Druhý telefón potom donekonečna dostáva 404. Oprava: za hotové považovať len overenú odpoveď.
20. **Odolnosť servera pred overením** – telo do 8 MB sa číta do pamäte pred overením rámca (`family/FamilyServer.kt:44,140-155`); 20 nezmyselných požiadaviek zruší platný QR (`family/PairingTokens.kt:56`). Len na lokálnej sieti, len obmedzenie služby.
21. **Dávka operácií je obmedzená počtom, nie veľkosťou** – `family/OpExchange.kt:58` (200) proti limitu tela 8 MB (`family/FamilyServer.kt:166`). Dnes to vychádza (MobileFaceNet, ~20 kB na sadu odtlačkov → ~4 MB), pri modeli s 512 rozmermi by push 200 sád limit prekročil a synchronizácia tým smerom by sa zasekla. `getOpsSince` navyše načíta všetky operácie naraz v každom kole.
22. **Záloha Androidu prenesie `family.db` aj s deviceId** – `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml`, `family/MetaDao.kt:24`. Po prenose na nový telefón majú starý aj nový rovnaké deviceId; návrhy a `remote_assignment` držia id tvárí zo starej faces.db, ktorá sa nezálohuje. Oprava: po obnove (chýbajú kľúče v Keystore) vygenerovať nové deviceId a vyčistiť tabuľky viazané na lokálne id tvárí.
23. **Prijímateľ potrebuje dvojnásobok miesta a neúspešný súbor ostane týždeň** – `family/InboundParts.kt:15`, `family/BundleReceiver.kt:46-51` (pri zlyhaní uloženia sa rozpracovaný súbor nezahodí).
24. **Priečinky skryté cez `.nomedia`** – filter pozná len názvy s bodkou (`family/BundleRules.kt:124-128`) a zamknuté/vylúčené (`faces/IndexScope.kt:46-53`). Či sa z `.nomedia` priečinkov niečo indexuje a teda môže odísť cez op-log, som neoveroval.
25. **Verzia protokolu sa posiela, ale nikto ju nekontroluje** – `family/FamilyServer.kt:79-83`, `family/FamilyClient.kt:68-71`. Pri budúcej zmene formátu sa staršia a novšia appka nedohodnú čisto.
26. **Záloha `.galeria-family` nie je šifrovaná** – `family/PeopleBackupFormat.kt:150-157`: mená a odtlačky tvárí v čitateľnom ZIP-e na SD/PC.
27. **Bez dopredného utajenia a so stálou značkou v mDNS** – kľúč sedenia je odvodený len z kľúča páru (`family/SecureChannel.kt:17-25`); `publicTag` je stály identifikátor telefónu viditeľný v každej sieti (`family/FamilyCrypto.kt:85-86`).
28. **Kurzor sa môže vrátiť späť** – `family/FamilyServer.kt:92,135` zapisuje `lastLamportSeen` zo staršieho čítania; pri súbehu s príjmom ho zníži. Dôsledok je len opakovaný prenos a predčasné ukončenie výmeny (`family/FamilyClient.kt:163`).

---

## 2. Čo zo špecifikácie chýba alebo je inak

Priznané odchýlky (v hlavičke spec): kvalita sa neprenáša; „zlúčiť?“ len pri rovnakom mene; skupiny podľa názvu; staré tváre s nejasným rámčekom sa neposielajú.

Ďalšie, v spec nepriznané:
- **„Odíde pri najbližšom stretnutí“ (časť 1, bod 3)** neplatí, keď je pri stretnutí klientom druhý telefón (nález 1).
- **„Neposielajú sa ani výsledky zo zamknutých priečinkov“ (2.5)** neplatí pre mená osôb a skupiny (nález 2).
- **„Krátky pokus s timeoutom 5 s“ (plán F5 3a)**: 5 s ohraničuje len hľadanie cez mDNS. Samotná synchronizácia spustená otvorením appky nie je ohraničená (snapshot, až 500 kôl, celá fronta vrátane videí) a nepozerá na typ siete.
- **„Zrušenie zmaže peer aj kľúč na oboch stranách“ (plán F2.2)**: kľúč áno, ale operácie, fronta, návrhy, čakajúce balíky a odtlačky `family:<deviceId>` ostávajú; `onUnpaired` nikto neimplementuje (nálezy 5, 12, 19).
- **„Kto ju poslal, vidno v histórii návrhu“ (2.4)**: pri otvorenom návrhu je názov telefónu, ale história rozhodnutých návrhov sa nikde nezobrazuje (stav sa len ukladá).
- **Unit test „filter zamknutých priečinkov“ (časť 4)**: testuje sa len kôš a názvy s bodkou (`BundleRulesTest.kt:162-166`), zamknuté a vylúčené priečinky nie.
- **Odobratie „toto nie je on“ (2.4)**: pri nepriradenej tvári sa informácia zahodí (nález 13) – spec hovorí o návrhu/prevzatí.
- **Hotspot mimo domu (časť 1)**: okno pri nabíjaní vyžaduje nemeranú sieť (`FamilySyncJob.kt:163-164`); klient hotspotu ju často vidí ako meranú, takže ostáva len pokus pri otvorení appky.
- **Záloha osôb** obsahuje navyše automatické priradenia, „nie je to on“ a ignorované tváre (nad rámec spec, užitočné).
- **Notifikácia** príde len odosielateľovi po úspechu; o zlyhaní ani o prijatých fotkách na druhej strane nič.

---

## 3. Slabé miesta návrhu a nápady

**Slabé miesta**
- Roly klient/server sú nesymetrické (frontu posiela len klient), ale spúšťače sú symetrické a po prvom úspechu sa zatvárajú. Z toho vychádza nález 1.
- Jeden údaj (lamport) má dve úlohy: poradie zmien a „čo už druhá strana má“. Z toho nálezy 4 a 28 a potreba záplaty D1.
- Aplikovanie operácií nemá jedného vlastníka (žiadna fronta ani zámok) – nález 3.
- Op-log posiela všetky ručné priradenia bez ohľadu na to, či druhá strana fotku má – nález 10.
- „Nič sa nemaže“ je dobrá zásada, ale chýba k nej spôsob, ako odobratie vôbec preniesť (členovia skupín, negatívne pravidlá) – nálezy 11, 13.
- Používateľ nevidí stav: čo je vo fronte, kedy bola posledná synchronizácia, čo zlyhalo. Chyby sú tiché (nález 6).
- Prvé spárovanie dvoch už naplnených knižníc je najťažší okamih (desiatky návrhov, snapshot, súbehy) a nemá vlastný sprievodca.

**Nápady**

| # | Nápad | Veľkosť |
|---|-------|---------|
| 1 | Server držať do konca okna / kým beží sedenie; po `/f/ack` hneď vlastný sync, ak mám frontu | S |
| 2 | Obojsmerná fronta v jednom sedení (klient si vypýta aj frontu servera) | M |
| 3 | Jeden zámok alebo jednovláknový executor na aplikovanie operácií; zápis vlastnej operácie v jednej transakcii | S |
| 4 | Vlastné poradové číslo ako kurzor prenosu, lamport len na poradie | M |
| 5 | Stav prepojenia na obrazovke párovania: posledná synchronizácia, vo fronte N, zlyhalo M, tlačidlo „Synchronizovať teraz“ a „Skúsiť znova“ | S |
| 6 | Operácie s tvárou posielať len k fotkám, ktoré druhá strana má (zoznam kľúčov pri hello alebo spolu s balíkom), nie celú históriu | M |
| 7 | Sprievodca po prvom spárovaní: „Zlúčiť všetky osoby s rovnakým menom“ jedným ťuknutím, pri zhode mena aj podobných odtlačkov prepojiť potichu | S–M |
| 8 | Osoba sa zdieľa až s prvou tvárou z povoleného priečinka; prepínač „túto osobu nezdieľať“ | S |
| 9 | Overovací kód pri párovaní (4 číslice na oboch telefónoch) + odkaz len na lokálnu adresu | S |
| 10 | Pokus pri otvorení appky len na Wi-Fi/hotspote, s predlžovaním intervalu | S |
| 11 | Snapshot ako samostatná úloha hneď po spárovaní, s priebehom | M |
| 12 | Zmeny skupín a „nie je to on“ ako samostatné operácie pridaj/odober, odobratie ako návrh | M |
| 13 | Denník posledných ~50 udalostí synchronizácie (na ladenie bez adb) | S |
| 14 | Voliteľné heslo na zálohu `.galeria-family` | S |
| 15 | Nové deviceId po obnove zo zálohy Androidu | S |
| 16 | Záložná cesta bez routera (Wi-Fi Direct / Nearby), keď domáca sieť izoluje klientov | L |
| 17 | „Rodinný album“: automaticky ponúknuť na odoslanie nové fotky s vybranými osobami (dnes mimo rozsahu spec) | L |

**Poradie, ktoré by som zvolil:** 1 → 3 → nález 2 (súkromie) → 5 → nálezy 5, 6, 8 (malé opravy) → 9 → 4 a 2 (väčšie zmeny protokolu spolu, s kontrolou verzie z nálezu 25).
