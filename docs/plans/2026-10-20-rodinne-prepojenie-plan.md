# Rodinné prepojenie — implementačný plán (20.10.2026)

Zdroj: `docs/specs/2026-10-19-rodinne-prepojenie-navrh.md` (schválený Lukášom 20.10.2026).
Vetva: `rodina-0.66`, vychádza z `revizia-0.65` po zlúčení q2. Každý krok má vlastnú vetvu `rodina-F<n>`. Zlučuje sa do `rodina-0.66` a kompiluje sa cez draft PR → main (CI `PR`), **nikdy sa nepushuje do main**.

## Zásady
- **Žiadne nové ťažké knižnice.** Použije sa Room, ZXing (QR kódovanie aj čítanie), NanoHTTPD, `javax.crypto` (AES-GCM, HKDF ručne cez HMAC-SHA256), Android NSD, JobScheduler (ako `jobs/NewPhotoFetcher`). Pribudne len `testImplementation` JUnit 4.
- Nový balík `org.fossify.gallery.family`. Do people.db, faces.db a extras.db sa **zapisuje len cez existujúce DAO metódy**, ich entity ani verzie sa nemenia.
- `family.db` je tiež nenahraditeľná: rodinné UUID ↔ lokálne id. Platí rovnako: bez destruktívneho fallbacku, zmeny len cez Room Migration, `exportSchema` zapnutý.
- Texty do `values/strings_family.xml`, tykanie, pojmy podľa slovníka. UI podľa zvyklostí appky.
- Každá čistá logika (bez Androidu) je v samostatnom súbore a má unit test, ktorý beží v CI (`testFossDebugUnitTest`).

## F1 — Základ: family.db, obsahový kľúč, súradnice (sekvenčne, prvé)
1. `build.gradle.kts`: `testImplementation("junit:junit:4.13.2")`, adresár `app/src/test/kotlin`.
2. `family/FamilyDatabase.kt` (version 1, bez fallbacku). Entity:
   - `PeerEntity(deviceId PK, name, publicTag, keyAlias, pairedAt, lastSeenAt, lastLamportSeen)`;
   - `ContentKeyEntity(path PK, size, mtime, contentKey, computedAt)` + index na `contentKey`;
   - `FamilyPersonEntity(familyUuid PK, localPersonId UNIQUE, nameVersionLamport, nameDeviceId)`;
   - `OpEntity(opId PK, deviceId, lamport, type, payloadJson, appliedAt)` + index `(deviceId, lamport)`;
   - `OutboxEntity(id PK auto, peerDeviceId, kind, refKey, createdAt, attempts, state)`;
   - `ProposalEntity(id PK auto, fromDeviceId, type, payloadJson, createdAt, state)`.
3. `family/ContentKey.kt`: `compute(file)` = SHA-256(size ‖ prvých 64 kB ‖ posledných 64 kB) → hex. `ContentKeyStore` počíta lenivo podľa (size, mtime). **Test:** stabilita, malý súbor < 128 kB, zmena posledného bajtu zmení kľúč.
4. `family/RelBox.kt`: prevod absolútnych a relatívnych súradníc voči upright rozmerom z `UprightDecoder`. Test zahŕňa EXIF rotácie 90, 180 a 270 a zaokrúhlenie.
5. `family/FuzzyMatch.kt`: pHash ≤ 6 a dátum ± 2 s, bez EXIF pHash ≤ 3. Test hraníc.

## F2 — Párovanie a prenos (po F1, paralelne s F3)
1. `family/FamilyCrypto.kt`:
   - QR nesie **jednorazový** párovací token (256 bit, platí 5 min).
   - Pri párovaní prebehne ECDH P-256 (dostupné na všetkých API), autentifikované HMAC tokenom. Z neho vznikne **trvalý kľúč páru** cez HKDF-SHA256, ktorý nikdy nebol v QR. Uniknutý QR (história skenera) je teda po spárovaní bezcenný.
   - Rámec je `[nonce12][ciphertext][tag16]`, AAD = deviceId + poradové číslo.
   - Trvalý kľúč sa ukladá zabalený cez Android Keystore.
   - **Test:** šifrovanie a dešifrovanie, zmenený bajt → zamietnutie, opakované poradové číslo → zamietnutie, vypršaný alebo použitý token → zamietnutie.
2. `family/PairingActivity`: Nastavenia → „Prepojiť telefón“ zobrazí QR s odkazom `galeriaplus://family/pair?v=1&id=…&name=…&t=<token>&ip=…&port=…`. Na druhom telefóne ho otvorí systémová kamera alebo skener HyperOS (intent-filter v manifeste), bez novej knižnice. Záloha bez skenera: tlačidlo „Naskenovať“ → `ACTION_IMAGE_CAPTURE` → dekódovanie cez existujúci `ZxingDecoder`. Zrušenie prepojenia zmaže peer aj kľúč na oboch stranách (pri najbližšom stretnutí).
3. `family/FamilyServer.kt` (NanoHTTPD, vlastný port 8090, iný ako Server pre PC). Endpointy: `POST /f/hello`, `/f/ops?since=`, `/f/bundle`, `/f/ack`. Telo každej požiadavky aj odpovede je zašifrovaný rámec. Bez platného rámca odpovie 404 bez detailov.
4. `family/FamilyDiscovery.kt`: NSD `_galeriaf._tcp` s TXT `id=<hash deviceId>`. Debug build má prepínač „ručná IP:port“ (pre emulátor + `adb forward`).
5. `family/FamilyClient.kt`: hello → výmena opov (rozdiel podľa lamport) → odoslanie fronty → ack. Prerušenie sa obnoví od posledného ack.

## F3 — Osoby a op-log (po F1, paralelne s F2)
1. `family/OpLog.kt`: typy `PersonCreate`, `PersonRename`, `PersonMerge`, `AssignFace(contentKey, relBox, familyUuid, manual)`, `UnassignFace`, `GroupSet`, `AnchorsSet(familyUuid, faceModelId, vectors≤20)`. Lamport hodiny v `family.db`.
2. Zachytávanie zmien: na miestach, kde UI volá PeopleDao (premenovať, zlúčiť, priradiť, vytvoriť, skupiny), pribudne volanie `FamilyRecorder.record(...)`. **Recorder nesmie zablokovať ani zhodiť pôvodnú akciu** (beží v try/catch na pozadí). Keď nie je spárovaný žiadny telefón, nerobí nič.
3. `family/OpApplier.kt`, čistá rozhodovacia logika podľa spec 2.4:
   - potichu sa prevezme nová osoba, nové priradenie bez konfliktu, skupina a anchory;
   - návrh vznikne pri premenovaní na oboch stranách, zlúčení, odobratí alebo preradení a mazaní;
   - ručné priradenie má prednosť pred automatickým;
   - každá operácia sa aplikuje len raz (podľa `opId`).
   **Test:** tabuľka scenárov, poradie, duplicitné doručenie.
4. Anchory: výber ≤ 20 reprezentatívnych z existujúceho mechanizmu `AnchorEmbeddingEntity`, prijaté sa ukladajú so `source = "family:<deviceId>"`. Pri inom `faceModelId` sa zahodia.
5. UI v Ľuďoch: lišta „Máš N návrhov z telefónu <názov>“ → zoznam návrhov s voľbami prijať / ponechať / zlúčiť. Dialógy cez getAlertDialogBuilder.

## F4 — Poslanie fotky s výsledkami (po F2 a F3)
1. `family/BundleBuilder.kt` zostaví balík ku `contentKey`:
   - zo súboru: tváre (rel. box, skóre, odtlačok, `faceModelId`) a priradenia (familyUuid);
   - z extras/ocr/geo/phash/clip/qr: OCR, poloha, pHash, CLIP (+ `clipModelId`), kvalita a QR;
   - neprenášajú sa rámčeky s nejasným priestorom (raw/upright) a nič mimo `IndexScope`.
2. Akcia „Poslať do prepojeného telefónu“ v prehliadači a vo výberovom režime mriežok. Súbor ide cez FamilyServer upload (uloží sa ako cez Server pre PC do `Galéria/`). Ak druhý telefón nie je dostupný, ide do fronty a príde notifikácia o odoslaní.
3. `family/BundleReceiver.kt` hľadá zhodu najprv podľa `contentKey`, potom cez FuzzyMatch. Prijaté výsledky zapíše cez DAO a označí fotku ako spracovanú, aby ju indexery preskočili. Pri zhode len cez zálohu sa OCR nepreberá.
4. Fotky prijaté inou cestou (Quick Share, WhatsApp): balík príde aj samostatne, bez súboru, a čaká vo `family.db`. Keď sa fotka s rovnakým kľúčom objaví v MediaStore, výsledky sa aplikujú pri najbližšom behu spracovania knižnice. Je to lacná kontrola pred detekciou.

## F5 — Profily a plánovanie (paralelne s F4)
1. Nastavenia → „Profil spracovania“: Plný / Ľahký. Ľahký = tváre + poloha, len pri nabíjaní, ostatné indexery vypnuté.
2. Voľba „Nové fotky hneď, zvyšok pri nabíjaní“ (predvolene vypnutá = správanie ako doteraz). Pri batérii pod 20 % sa spracovanie zastaví.
3. Spúšťače synchronizácie:
   - (a) `onResume` hlavnej obrazovky: krátky pokus s timeoutom 5 s;
   - (b) JobScheduler s podmienkami nabíjanie + nemeraná sieť: okno 1 h, opakované pokusy;
   - (c) fronta po pridaní položky.
   Nič nebeží trvalo, server beží len počas okna alebo kým je appka otvorená.

## F6 — Export a záloha osôb (po F3)
- Nastavenia → „Zálohovať osoby“ / „Obnoviť osoby“: súbor `.galeria-family` (rovnaký formát ako op-log snapshot + anchory, ZIP + JSON). Rieši otvorený bod revízie 4.

## F7 — Testovanie a dokončenie
- CI: unit testy z F1 až F3 + lint/detekt zelené.
- Ručný test (lokálne, Lukáš): emulátor `galeria` (x86_64, `-PtestAbi=x86_64`) + Xiaomi, scenáre zo spec Časť 4.
- Changelog, verzia 0.66. Vydanie robí Lukáš lokálne.

## Poradie a paralelizácia
```
F1 ──┬── F2 ──┐
     ├── F3 ──┼── F4
     │        └── F6
     └── F5 (nezávisle)
```
F2, F3 a F5 bežia paralelne ako 3 agenti v cloude, F4 a F6 až po nich. Po každej vlne sa vetvy zlúčia do `rodina-0.66` a overia cez CI.
