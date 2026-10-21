# Spoľahlivosť tvárí — analýza a návrh úprav (21.10.2026)

**Stav 21.10.2026:** balíky A, B, C (C1–C11) a D sú implementované a zlúčené v `rodina-0.66`, CI zelené. Balík E (zmeny schémy) zatiaľ nie. Čaká sa na lokálny test (emulátor + Xiaomi). Riziko na overenie: k-NN skóre má inú škálu, treba skontrolovať prah automatiky podľa odporúčania v Nastaveniach.

Analýzu robili 4 nezávislí analytici nad vetvou `rodina-0.66`: detekcia a odtlačky, zaraďovanie do osôb, integrita dát a prenos medzi telefónmi. Zistenia sú podložené odkazmi do kódu, plné znenia sú v histórii session. Tu je zhrnutie a návrh poradia.

## Najvážnejšie zistenia
1. **Poškodená people.db sa potichu zmaže.** Predvolený `DefaultDatabaseErrorHandler` SQLite poškodený súbor vymaže a vytvorí prázdny. Všetky mená by sa stratili bez možnosti obnovy.
2. **Chýba automatická záloha.** `allowBackup` nemá pravidlá a limit 25 MB pravdepodobne prekročí faces.db spolu s clip.db, takže sa nezálohuje nič. Pri odinštalovaní zmizne všetko, lebo chýba `hasFragileUserData`.
3. **Odtlačky sa zarovnávajú podľa nesprávnych bodov očí.** Kód berie vonkajšie kútiky očí (landmarky 33/263), ale šablóna ArcFace čaká stredy očí. Všetky tváre zarovnané metódou M5 sú preto systematicky zmenšené a posunuté, čo znižuje presnosť rozpoznávania.
4. **Na skupinových fotkách môže tvár dostať odtlačok suseda.** Landmarker má `numFaces(1)` a berie prvú tvár vo výreze s 30 % okrajom.
5. **Ignorované tváre sa aj tak automaticky zaraďujú** k osobám, lebo `withAutoMatches` nečíta extras.db.
6. **Akcia „Nie je táto osoba“ nad fotkou** odmietne všetky tváre osoby na fotke, aj tú správnu. Cannot-linky sa tým otrávia.
7. **Chýba rozostup od druhej najbližšej osoby** a pravidlo „jedna osoba najviac raz na fotke“. Súrodenci a rodič s dieťaťom sa preto zamieňajú.
8. **Presun, premenovanie alebo obnova z Koša pod iným menom odpojí tváre** a vzniknú nepomenované duplikáty. Otočenie fotky zas zneplatní rámčeky. Nič sa nemaže, ale menovky sa „stratia z dohľadu“.
9. **Rodinné prepojenie:**
   - chybné ručné priradenie sa prenesie potichu, ale jeho oprava príde len ako návrh;
   - prijaté anchory sa nikdy nenahradia a majú plnú váhu, takže chyba sa šíri;
   - fotka z WhatsAppu, ktorú druhý telefón už spracoval, nedostane mená;
   - séria fotiek bez EXIF môže dostať mená na susednú snímku;
   - pri obnove staršej `family.db` sa nové zmeny nikdy neodošlú (lamport).
10. **Drobnosti:**
    - EXIF 5/7 je prehodené oproti Glide (tváre hore nohami, zriedkavé);
    - `PhotoQuality` žiada blendshapes, ktoré model nemá (komparátor asi nevidí tváre, overiť na zariadení);
    - FaceIndexer nepoužíva `FaceFilter`;
    - Picasa ukladá aj nezarovnané vzory;
    - schémy DB sa neexportujú, takže migrácie sa nedajú testovať;
    - zápisy cez viac DB nie sú atomické (duplicitná osoba pri páde).

## Návrh úprav po balíkoch

### Balík A — Ochrana dát (bez zmeny schémy, prvá priorita)
- A1 Vlastný error handler pre people/extras/family/faces: poškodený súbor sa **premenuje** (`*.corrupt-<čas>`), nezmaže sa. Appka to oznámi a ponúkne obnovu zo zálohy.
- A2 `hasFragileUserData="true"` a pravidlá zálohy: zálohovať people.db, extras.db a family.db, vylúčiť faces/clip/ocr a ostatné indexy, ktoré sa dajú prestavať.
- A3 **Automatická týždenná záloha pri nabíjaní** do `Documents/Galeria+/zalohy/` (prežije odinštalovanie). Obsahuje F6 súbor a `VACUUM INTO` kópiu people/extras/family. Rotácia 4 kópií, zápis cez dočasný súbor a premenovanie. Formát F6 doplniť o automatické priradenia, cannot-links a ignorované tváre a v súhrne hlásiť, čo bolo vynechané.
- A4 Export schém (`room.schemaLocation`), commit JSON schém a migračné testy v CI pred akoukoľvek budúcou zmenou schémy. Pri DB novšej než appka zobraziť hlášku namiesto pádu.
- A5 **Znovu nájsť presunuté fotky podľa contentKey:** pred detekciou sa chýbajúca cesta s rovnakým obsahom preklopí cez `UPDATE` (id tvárí aj menovky ostanú).
- A6 **Prepočet rámčekov pri otočení** fotky v appke (odtlačky ostanú platné) a `ContentKeyStore.forget`.
- A7 Atomicita:
  - `createPerson` vo family najprv zapíše mapu a potom osobu;
  - návrhy sa nezdvojujú (kľúčom je opId);
  - Picasa import beží v transakcii a manifest zapíše až na konci;
  - `isRunning` je atomický;
  - `doAssign` je obalený v try/catch.
- A8 Osoba, ktorej tváre sú všetky skryté, ostane v Ľuďoch sivo s textom „fotky sa nenašli“ a nezmizne.

### Balík B — Presnejšie odtlačky („odtlačky v2“, bez zmeny schémy, jednorazový prepočet pri nabíjaní)
- B1 Body očí pre M5 brať zo stredov očí (dúhovky 468/473).
- B2 Landmarker s `numFaces(3)` a výberom tvári podľa prekryvu s detekciou, M2 rovnako.
- B3 Výrez tváre z plného rozlíšenia (`BitmapRegionDecoder`) a zmenšenie s antialiasingom.
- B4 Odtlačok + zrkadlový odtlačok, ich súčet a L2 normalizácia (selfie, póza).
- B5 FaceIndexer použije `FaceFilter`, Picasa neukladá nezarovnané vzory, `PhotoQuality` bez blendshapes.
- Prepočet ide **na mieste** (`updateEmbedding`), id tvárí a menovky ostanú. `FaceEmbedder.MODEL_ID` sa zvýši na v2.
- **Pozor:** vzory z Picasy sa nedajú prepočítať (výrezy sa neuchovali) a staré vektory v1 sa s v2 nesmú miešať. Rozhodnutie pre Lukáša:
  - (a) starý import z Picasy zopakovať zo ZIP súboru;
  - (b) staré vzory vypnúť a nechať len odtlačky potvrdených tvárí;
  - (c) nechať v1 vzory a porovnávať ich len navzájom.

### Balík C — Spoľahlivejšie zaraďovanie do osôb (bez zmeny schémy)
- C1 Ignorované tváre vylúčiť z automatického zaraďovania.
- C2 „Nie je táto osoba“ nad fotkou odmietne len tú tvár, ktorá osobu nesie. Ak je ich viac, appka sa spýta, ktorú.
- C3 **Rozostup:** automaticky zaradiť tvár len vtedy, keď je najlepšia osoba aspoň o ~0,06–0,08 lepšia než druhá.
- C4 **Jedna osoba najviac raz na fotke**, ručne potvrdená tvár blokuje ďalšie.
- C5 Centroid a vzory počítať len z ručne potvrdených tvárí. Potvrdené tváre nefiltrovať podľa skóre. Rodinné priradenia majú nižšiu váhu.
- C6 **Porovnanie s najbližšími potvrdenými tvárami (k-NN)** namiesto jedného priemeru, rovnako v automatike aj návrhoch. Rieši deti, okuliare aj veľké verzus malé osoby. Vzory z Picasy najviac 50 na osobu.
- C7 **Tri stavy:** automaticky / „neisté, potvrď“ / nič. Na karte osoby fronta „Potvrď“.
- C8 Učiť sa z odmietnutí: odmietnutá tvár penalizuje podobných kandidátov. Presun z A na B automaticky zapíše „nie je A“.
- C9 Jeden zdroj pravdy pre Ľudí, hľadanie, mapu aj prehliadač (dnes ukazujú rôzne fotky tej istej osoby).
- C10 **Odhad presnosti** (leave-one-out na potvrdených tvárach) v Nastaveniach a upozornenie „Ján ↔ Peter sa pletú“.
- C11 **„Pomôž mi rozhodnúť“:** otázky na tváre s najmenším rozostupom medzi dvoma osobami, kde pár klikov výrazne zlepší výsledok.

### Balík D — Rodinné prepojenie (mení len nevydanú family.db)
- D1 Lamport: pri hello zdvihnúť vlastné hodiny na `have` druhej strany.
- D2 `refFor` použije rovnaké pravidlo pre otočené fotky ako balíky (jedna hypotéza, keď je priestor istý). Rámček presahujúci okraj fotky je nejasný. Nejasná tvár sa vynechá sama, nie celá fotka.
- D3 **Overenie priestoru rámčeka odtlačkom** (prepočet z výrezu pre obe hypotézy a porovnanie s uloženým) namiesto heuristiky podľa dátumu 31.8.
- D4 Mapa vzdialený contentKey → lokálna cesta pre WhatsApp kópie. Keď prijímateľ fotku už spracoval, preniesť aspoň mená cez párovanie tvárí.
- D5 Séria bez EXIF: pred zápisom overiť, že balíku nezodpovedá lepšie iná fotka. Inak mená zapísať len ako automatické.
- D6 Pôvod priradení (`remote_assignment`):
  - prijaté priradenia sa neposielajú späť ako vlastné anchory;
  - oprava od toho istého telefónu sa aplikuje potichu.
- D7 Prijaté anchory majú nižšiu váhu a netvoria vlastné ťažisko. `AnchorPicker` vyradí odľahlé vektory. `AnchorsSet` sadu nahradí, nie pridá.
- D8 Mená podľa pravidla „posledná zmena vyhráva“ cez `nameVersionLamport` (návrh len pri súbežnej zmene). Prijatý návrh premenovania sa zapíše ako operácia.
- D9 Op-log: odložené operácie sa opakujú až po objavení fotky, po 90 dňoch sa odložia. Pri inom modeli `Defer` namiesto `Skip`.
- D10 Záloha F6: vzory zo zálohy sa berú ako vlastné. Po obnove sa osoby znova pošlú.

### Balík E — Neskôr, so zmenou schémy (len cez Room Migration, až po A4)
- E1 faces.db v2→3:
  - skóre kvality, yaw/pitch, ostrosť a metóda zarovnania (brána kvality pre centroidy);
  - `box_space` s opravou EXIF 5/7;
  - `embed_version`;
  - `taken_at` pre deti (vekové prototypy).
- E2 people.db v2→3: `source` a `confidence` priradení (audit, späť), záznam zlúčení na vrátenie.
- E3 Detekcia vo viacerých mierkach a dlaždiciach pre malé tváre v skupinách (s bránou kvality z E1).

## Odporúčané poradie
**A** (ochrana dát) → **C1–C5** (rýchle opravy zaraďovania) → **B** (odtlačky v2, po rozhodnutí o Picase) → **D** (pred testom 0.66 na dvoch telefónoch) → **C6–C11** → **E**.

Pred zmenami v `PersonGrouper` doplniť unit testy scenárov (súrodenci, dve tváre na fotke, malá osoba vedľa veľkej, 3 vlastné tváre + 20 rodinných). Testovacie scenáre pre prenos sú v správe analytika prenosu: WhatsApp kópia spracovaná pred synchronizáciou, séria 5 záberov, chybné priradenie a jeho oprava, súbežné premenovanie, obnova F6 po preinštalovaní.
