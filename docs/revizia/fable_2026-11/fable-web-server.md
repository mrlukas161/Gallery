# Nezávislá revízia: prepojenie telefón ↔ PC (Server pre PC + web „Galéria+ Sync“)

Vetva `revizia-2026-11` (0.67.3, commit 6d3c306). Len čítanie, v repe sa nič nemenilo.
Cesty sú relatívne k `app/src/main/kotlin/org/fossify/gallery/` (Kotlin) a `app/src/main/assets/gallery.html` (web).

**Ako som overoval**

- Kotlin (server): čítaním kódu, riadok po riadku pri prihlásení, mazaní, uploade, ZIP-e, indexe a zámkoch. Reálny server v telefóne som spustiť nemohol.
- Web: čítaním + Playwright (headless Chromium) proti kópii mock servera na vlastnom porte 18291 (`rev/fable-web/mock2.js`, test `rev/fable-web/t1.js`). Zdieľaný mock (18189/8797) som nespúšťal ani nezastavoval.
- Jednotkové testy webu (`gallery_core.test.js`, `gallery_help.test.js`): 64/64 prešlo.
- Pri každom náleze je uvedené, či je **overený testom**, **overený v kóde**, alebo **treba overiť na zariadení**.

**Počty:** P1 = 1, P2 = 5, P3 = 17 (spolu 23).

---

## 1. Chyby, ktoré naozaj nastanú

### P1

#### 1. PIN sa dá uhádnuť bez limitu cez `/api/zip` (obchádza zámok po 20 pokusoch)
- **Kde:** `sync/MediaServer.kt:445-451` (`handleZip`), zdokumentované ako zámer v `docs/api/server-pre-pc.md:222-223`.
- **Čo sa deje:** `/api/zip` je v `openRoutes` (overuje sa sám). PIN z tela formulára sa porovná cez `constantTimeEquals(bodyTok, pin)` – **nie** cez `checkPin`, takže zlý pokus sa nezaráta. `authorized()` na riadku 447 pri požiadavke bez cookie, bez hlavičky a bez `?token=` vráti `false` tiež bez započítania.
- **Scenár:** zariadenie na tej istej sieti (cudzí notebook na Wi-Fi, napadnutý IoT, hosť na hotspote) posiela `POST /api/zip` s telom `token=100000` … `token=999999` a bez `ids`. Zlý PIN → `403 forbidden`. Správny PIN → `400 bad ids` (kontrola `Origin` prejde, lebo skript ho neposiela; ZIP sa nevytvorí, slot sa neminie, nič sa nezapíše). 900 000 možností pri stovkách požiadaviek za sekundu = minúty až desiatky minút. Potom `POST /api/login` → celá knižnica vrátane originálov s GPS a mazanie.
- **Stav:** overené v kóde. Rýchlosť hádania je odhad, nemeral som ju.
- **Oprava (S):** v `handleZip` použiť `checkPin(bodyTok)` namiesto `constantTimeEquals`. Obava z komentára (stará karta zamkne server) neplatí – ochrana počíta len *rôzne* zlé tokeny (`badPins` je množina). Doplniť test do `ServerLogicTest`: 20 rôznych zlých tokenov v tele ZIP-u = zámok.

### P2

#### 2. Server počúva na všetkých rozhraniach vrátane mobilných dát a VPN
- **Kde:** `sync/MediaServer.kt:52` (`NanoHTTPD(null, port)` = wildcard), `services/HttpSyncService.kt:119-122`; `sync/NetUtil.kt:23-30` a `sync/LinkKinds.kt:13` filtrujú len to, čo sa *ukáže*, nie na čom sa počúva.
- **Scenár:** Lukáš nechá server bežať a odíde z domu. Telefón je na mobilných dátach s verejnou IPv6 (alebo na VPN, firemnej či hotelovej Wi-Fi). Port 8089 je dostupný každému, kto sa k adrese dostane. Spolu s nálezom 1 to znamená prístup ku knižnici; aj bez neho 20 pokusov a zámok.
- **Stav:** overené v kóde. Či operátor prichádzajúce spojenia filtruje, závisí od siete.
- **Oprava (S):** v `serve()` hneď na začiatku odmietnuť (zavrieť spojenie) klienta, ktorého `remote-addr` nie je loopback, site-local (10/8, 172.16/12, 192.168/16), link-local alebo IPv6 ULA/link-local. Lepšie (M): počúvať len na adresách z `NetUtil.addresses()` a pri zmene rozhraní socket presunúť.

#### 3. Držaný kláves X / Delete označí na zmazanie rad fotiek za sebou, bez hlásenia
- **Kde:** `gallery.html:5020-5026` (`listKeys`, vetva `x/X/Delete` – chýba `e.repeat`), `2140-2147` (`markSelOrCur` – hlásenie len pri výbere alebo viac ako 1 fotke), `2119-2137` (`setMarks` → `applyFilter(false, true)` posunie pod kurzor ďalšiu fotku).
- **Scenár:** predvolene je zapnuté „Skryť označené“. Po X fotka zmizne a na jej miesto príde ďalšia. Keď kláves ostane stlačený čo i len pol sekundy (auto-repeat), označí sa každá ďalšia fotka, ktorú Lukáš ani nevidel, a všetky hneď zmiznú z mriežky.
- **Stav:** overené testom – 15 opakovaní po 33 ms (~0,5 s) označilo **16 fotiek** (id 400 → 385), hlásenie prázdne, v lište len „384 / 400 · 🗑 skrytých 16“. Medzerník túto poistku má (`spaceSel`, riadok 4824) – pri tom istom teste ostala vybraná 1 fotka.
- **Dopad:** nie je to okamžitá strata (treba ešte potvrdiť „Zmazať do Koša“ a je tam Kôš), ale v potvrdení je len počet, takže nechcené fotky sa ľahko zvezú s ostatnými.
- **Oprava (S):** `if (e.repeat) return;` pre `x/X/Delete` vo `viewerKeys`, `cmpKeys`, `fcmpKeys`, `listKeys`. Pri značke jednej fotky v režime skrývania ukázať krátke hlásenie „🗑 IMG_… · Ctrl+Z vráti“.

#### 4. Ďalší ZIP počas sťahovania nahradí celú stránku textom `{"error":"busy"}`
- **Kde:** `gallery.html:1989-2024` (`zipDownload`, `submitZip` – formulár do hlavného okna), `1309-1313` (`zipFree`: ZIP starší ako 20 s sa považuje za hotový); server `sync/ServerExtras.kt:269-271` (503 pri obsadených 2 slotoch).
- **Scenár:** výber 1 200 fotiek = 3 ZIP-y. Prvé dva (každý ~2,5 GB) tečú minúty. Lukáš po pol minúte klikne „Stiahnuť ďalší ZIP“ (alebo stlačí D na inom výbere). Web si myslí, že sloty sú voľné, server vráti 503 a Chrome prejde na `/api/zip` s JSON-om. Stránka je preč – výber, Ctrl+Z história, otvorený porovnávač.
- **Stav:** overené testom (mock vracajúci 503): URL po odoslaní `…/api/zip`, telo stránky `{"error":"busy"}`.
- **Oprava (S):** server nech pri plných slotoch počká na voľný (napr. do 60 s) namiesto okamžitého 503, alebo nech `/api/ping` nesie `zipBusy` a web sa riadi ním, nie hodinami. Robustnejšie (M): `POST /api/zip/prepare` cez `fetch` → jednorazový lístok → `GET /api/zip?ticket=` cez `<a download>`; chyba sa potom nikdy nedostane do hlavného okna.

#### 5. Prerušený upload z PC sa uloží ako hotová (useknutá) fotka
- **Kde:** `sync/ServerUpload.kt:139-148` (`copyExactly`: pri `n < 0` len `break`), volané z `70-72`; `sync/MediaStoreSaver.kt:44-55` potom nastaví `IS_PENDING = 0`.
- **Scenár:** PC program posiela `PUT /api/upload` s `Content-Length`, spojenie spadne v polovici (Wi-Fi, uspanie PC, zavretý program). Čítanie vráti koniec prúdu, výnimka nevznikne a v `Pictures/Galéria` ostane poškodený súbor, ktorý sa tvári ako riadna fotka.
- **Stav:** overené v kóde.
- **Oprava (S):** po cykle `if (remaining > 0) throw IOException("upload incomplete")` – `saveMediaScoped` položku pri výnimke zmaže.

#### 6. Batéria: otvorená karta budí telefón každé 4 s a server sa sám nikdy nevypne
- **Kde:** `gallery.html:1775` (`setInterval(… tick, 4000)`, stráži len `document.hidden`), `services/HttpSyncService.kt:176` (`START_STICKY`, žiadny časový limit), `sync/ServerLocks.kt` (zámky sa správne nedržia pri pingu – ale paket telefón zobudí aj tak).
- **Scenár:** PC ostane zapnuté s viditeľnou kartou (druhý monitor, nezamknutá obrazovka). Telefón so zhasnutou obrazovkou dostane požiadavku každé 4 s, takže nikdy neprejde do hlbokého spánku. Druhá časť: zabudnutý server beží dni, s tým istým PIN-om, na každej sieti, kam sa telefón pripojí.
- **Stav:** overené v kóde. Spotrebu som nemeral.
- **Oprava:** (S) web – po 2 min bez vstupu používateľa ping každých 30 s, po 10 min každých 60 s, pri pohybe myši hneď späť. (S) služba – automatické zastavenie po N hodinách bez požiadavky (nastaviteľné, predvolene napr. 4 h) s oznámením. (M) namiesto pingu jedno dlhé spojenie (SSE `/api/events`).

### P3

#### 7. Mazanie po dávkach: chyba v druhej dávke zahodí výsledok prvej
- **Kde:** `gallery.html:4588-4603` (`deleteInBatches`), `4564-4586`.
- **Scenár:** viac ako 500 označených, prvá dávka prejde, druhá zlyhá (sieť). Hlásenie povie „Mazanie zlyhalo“, hoci 500 fotiek je v Koši, a tlačidlo „Vrátiť“ sa neukáže (`batchIds` sa stratia). Počas minút kopírovania do Koša navyše nie je žiadny priebeh.
- **Stav:** overené v kóde.
- **Oprava (S):** pri chybe vrátiť čiastočný `out` s príznakom, vždy ukázať „Presunuté X, zlyhalo Y · Vrátiť“; priebeh „dávka 2/5“.

#### 8. Ktokoľvek na sieti vie server vypnúť 20 rôznymi zlými PIN-mi
- **Kde:** `sync/MediaServer.kt:418-440`, `HttpSyncService.kt:134-138`.
- **Scenár:** 20 požiadaviek `GET /api/ping?token=x1…x20` → server sa zamkne a zastaví, Lukáš musí v telefóne spustiť nový (nový PIN, nové prihlásenie).
- **Oprava (M):** počítať po IP adresách s narastajúcou pauzou, zámok dočasný (napr. 5 min) a jasné oznámenie v telefóne. Trvalé riešenie je párovanie PC (nápad A).

#### 9. PIN je čitateľný na zamknutej obrazovke a ostáva v histórii Chrome
- **Kde:** `HttpSyncService.kt:239-259` (odkaz `…/?token=PIN` v texte notifikácie, bez `setVisibility`), `gallery.html:1756-1757` (`replaceState` adresu vyčistí, ale prvá návšteva s tokenom ostáva v histórii a v našepkávači).
- **Oprava (S):** `setVisibility(VISIBILITY_PRIVATE)` + verejná verzia bez PIN-u. Do schránky dávať adresu bez tokenu a PIN zadať ručne (alebo jednorazový párovací kód, nápad A).

#### 10. `OutOfMemoryError` v obsluhe požiadavky zhodí celú appku
- **Kde:** `sync/MediaServer.kt:348-352` (`route` chytá len `Exception`), `sync/ServerTransport.kt:99-106` (vlákno bez `catch`), `sync/ImageDecode.kt:144-149` (`jpeg()` – `ByteArrayOutputStream` + `toByteArray()` mimo `try`).
- **Scenár:** „Plná kvalita“ pri HEIC 8192 px (JPEG ~20–30 MB a jeho kópie) súbežne s veľkým `/api/list` na telefóne s menšou haldou. `Error` vyletí z vlákna `galeria-http-N` a Android ukončí proces.
- **Stav:** cesta chyby overená v kóde; či pamäť naozaj dôjde, závisí od telefónu.
- **Oprava (S):** v `route` pridať `catch (e: OutOfMemoryError)` → 503 + `Retry-After` a vyprázdniť pamäťové cache.

#### 11. Potvrdenie zmazania v mobile ukáže a zmaže aj fotky z medzičasom zamknutého priečinka
- **Kde:** `sync/PcMarkedPrompt.kt:46-53` (`load` nefiltruje cez `IndexScope`), `103-120` (náhľady), `135-160`.
- **Scenár:** fotka označená z PC, priečinok potom zamknutý PIN-om. Pri otvorení appky dialóg ukáže jej náhľad bez odomknutia a po potvrdení ju zmaže. Značka je navyše viazaná len na cestu – ak na tej istej ceste medzitým vznikol iný súbor, zmaže sa ten.
- **Oprava (S):** v `load` vynechať cesty mimo `IndexScope`. K značke ukladať `mtime` a veľkosť a pri nezhode ju zahodiť.

#### 12. `/api/delete` neoveruje, že klient maže to, čo videl
- **Kde:** `sync/ServerSync.kt:155-161`; web `gallery.html:4564-4566` a `3966` (tlačidlo v hlásení „Zmazať do Koša teraz…“ maže všetko v `S.marked`).
- **Scenár:** zmažú sa aj staré značky z iného dňa, z iného PC alebo z nálezu 3; potvrdenie ukáže len počet a veľkosť. Server berie holé id, bez kontroly, že fotka je stále označená alebo nezmenená.
- **Oprava (S/M):** web – v potvrdení uviesť „z toho N označených skôr ako dnes“ a upozorniť na obľúbené (★) medzi označenými. Server – voliteľné `onlyMarked: true` a `expect: {id: mtime}`; nezhoda ide do `failed`.

#### 13. Web nepovie, že spojenie s mobilom vypadlo
- **Kde:** `gallery.html:1818-1833` (`tick` chybu zahodí, `fetch` bez časového limitu; pri visiacom spojení ostane `ticking = true`).
- **Scenár:** server zastavený alebo telefón mimo Wi-Fi. Miniatúry blednú, akcie hlásia „Failed to fetch“, živé zmeny ticho stoja.
- **Oprava (S):** po 2 neúspešných pingoch pruh „Mobil neodpovedá – skontroluj Server pre PC“ s opakovaním; `AbortController` s limitom 8 s.

#### 14. Starší `/api/state` prepíše čerstvú značku; skok `stateGen` preskočí „✓ v PC“
- **Kde:** `gallery.html:1855-1868` (`refreshState` nahrádza celé `S.marked`), `2123-2125` (`S.stateGen = r.stateGen` po značke).
- **Scenár:** X stlačené počas bežiaceho ticku – značka na obrazovke na pár sekúnd zmizne a vráti sa (ďalší tick to opraví). Naopak dávka „✓ v PC“ tesne pred značkou sa neukáže až do ďalšej zmeny stavu.
- **Oprava (S):** odpoveď `/api/state` so `stateGen` nižším ako aktuálny zahodiť; po značke `S.stateGen` neposúvať, ak nie je presne o 1 vyššie – nechať to na tick. Použiť hotové `?since=`.

#### 15. Upload: odmietnutie bez dočítania tela; multipart zaberie viac miesta, ako sa kontroluje
- **Kde:** `sync/ServerUpload.kt:38-55, 58-68, 102-110`.
- **Scenár:** odmietnutie (413, chýbajúci `X-Filename`, nepodporovaný MIME → výnimka z `resolver.insert`) nechá telo na spojení; ďalšia požiadavka na keep-alive dostane 400. Multipart sa na disk dostane 3× (telo, časť, MediaStore), kontroluje sa `copies = 2`; nad 2 GB zlyhá (`map()` v NanoHTTPD).
- **Oprava (S):** pri odmietnutí `closeConnection(true)` (ako `Http.readJson`); `copies = 3`; multipart nad ~1 GB odmietnuť s odkazom na PUT.

#### 16. WifiLock pravdepodobne neúčinkuje pri zhasnutej obrazovke
- **Kde:** `sync/ServerLocks.kt:164-168` (`WIFI_MODE_FULL_LOW_LATENCY` od Androidu 10).
- **Prečo:** podľa dokumentácie Androidu je tento režim aktívny len pri zapnutej obrazovke a appke v popredí; cieľ z komentára („pri zhasnutej obrazovke sa uspáva Wi-Fi“) plní `WIFI_MODE_FULL_HIGH_PERF`.
- **Stav:** **treba overiť na zariadení** – `/api/stats` → `locks` a odozva `/api/ping` so zhasnutou obrazovkou.
- **Oprava (S):** použiť HIGH_PERF, alebo oba zámky.

#### 17. „✓ v PC“: každé 3 s sa prepisuje celý JSON všetkých ciest a nikdy sa nečistí
- **Kde:** `sync/SyncStore.kt:97-107` (`flushSynced` → `SyncedJson.encode(live(p))`), spoločné prefs `galeria_pcsync`.
- **Scenár:** sťahovanie stoviek súborov po jednom pri desaťtisícoch už stiahnutých – megabajtový súbor nastavení sa prepisuje každé 3 s. Záznamy zmazaných fotiek ostávajú navždy.
- **Oprava (M):** malá SQLite tabuľka alebo prírastkový súbor; čistenie ciest, ktoré už neexistujú.

#### 18. Zbytočné prestavby indexu
- **Kde:** `HttpSyncService.kt:54-57` (každá zmena MediaStore → `requestRebuild`), `sync/ServerExtras.kt:149-158` (zápis `IS_FAVORITE` z webu spustí observer → plná prestavba), `MediaServer.kt:660-677`.
- **Scenár:** každé ★ z PC = dotaz na celú knižnicu o 2,5 s. Fotenie pri bežiacom serveri bez pripojeného PC = prestavby naprázdno.
- **Oprava (S/M):** keď 5 min neprišla požiadavka, len si poznačiť „zastarané“ a prestavať pri najbližšej požiadavke; vlastný zápis obľúbených nepočítať.

#### 19. Kôš: nová fotka s rovnakou cestou prepíše staršiu kópiu v Koši
- **Kde:** `sync/PcDeleter.kt:49, 60, 65` (`copyRecursively(target, true)`; pri zlyhaní `target.delete()` zmaže aj pôvodnú kópiu).
- **Scenár:** zmazaná `IMG_0001.jpg`, neskôr na tej istej ceste vznikne iný súbor a tiež sa zmaže – prvá fotka z Koša zmizne. Zriedkavé; rovnako sa správa aj appka.
- **Oprava (S):** ak cieľ v Koši existuje a líši sa veľkosťou alebo časom, uložiť pod `AltName.free`.

#### 20. Pustenie súboru z Prieskumníka na stránku ju nahradí tým súborom
- **Kde:** `gallery.html` – nie je žiadny `dragover` / `drop` (hľadané v celom súbore), len `dragstart` / `dragend` (4429-4446).
- **Stav:** z kódu + bežné správanie Chrome; v prehliadači **netestované** (Playwright nevie pustiť súbor z OS).
- **Oprava (S):** `dragover` / `drop` s `preventDefault()` na dokumente; ideálne rovno nahrať do mobilu (nápad D).

#### 21. Miniatúra pretiahnutá do Claude je malá a volá sa `thumb.jpg`
- **Kde:** `sync/ServerMedia.kt:180-195` (`thumb()` bez `Content-Disposition`), `gallery.html:4396-4399`.
- **Scenár:** z mriežky sa ťahá náhľad 300–500 px a Chrome ho pomenuje podľa adresy. V chate je viac príloh s rovnakým menom a v nízkom rozlíšení.
- **Oprava (S):** v `thumb()` pridať `Content-Disposition: inline; filename="<názov>.jpg"` (Chrome ho pri ťahaní použije – overiť v Claude). V mriežke ponúknuť „ťahaj z náhľadu / 💬 Do chatu“ pre plnšie rozlíšenie.

#### 22. Bez cookie ide PIN do adries obrázkov, videí a sťahovaní
- **Kde:** `gallery.html:1588-1593` (`q()`), `1948-1953`, `2019`.
- **Scenár:** Chrome so zakázanými cookies – PIN je v každej adrese `<img>` / `<video>` a v zozname stiahnutí (Ctrl+J).
- **Oprava (S):** po prihlásení bez cookie vydať krátkodobý náhodný token (nie PIN) a posielať ten.

#### 23. Náhľady ostávajú 7 dní v cache Chrome a web nemá odhlásenie
- **Kde:** `sync/ServerMedia.kt:103` (`private, max-age=604800, immutable`); `/api/logout` existuje (`MediaServer.kt:483-489`), tlačidlo vo webe nie.
- **Scenár:** na cudzom alebo spoločnom PC ostanú náhľady (aj 2560 px) na disku aj po zastavení servera či zamknutí priečinka.
- **Oprava (S):** tlačidlo „Odhlásiť“ (logout + `Clear-Site-Data: "cache"`), voliteľný „režim cudzie PC“ s `no-store`.

### Čo som preveril a je v poriadku

- **Zamknuté a vylúčené priečinky:** každá cesta k dátam ide cez index postavený s `IndexScope` – `itemOf`, `/api/meta`, ZIP, obľúbené, skupiny, kvalita, Kôš (`PcBin.list`), živé fotky. Zmena zámku alebo vylúčenia spustí okamžitú prestavbu (`prefsListener`, `MediaServer.kt:250-256`). Fotka bez cesty sa nesprístupní. Zamknutie platí len pre presný priečinok, nie podpriečinky – rovnako ako v appke.
- **CSRF:** cookie `SameSite=Strict` + `HttpOnly`, kontrola `Origin` a `Sec-Fetch-Site`, CSP s nonce, `frame-ancestors 'none'`, CORP. Starý GET na zmeny len s PIN-om v požiadavke. Web skladá DOM cez `textContent`; jediné `innerHTML` je pevný text.
- **Path traversal:** klient posiela len id, názvy v ZIP-e a `Content-Disposition` sú čistené.
- **Mazanie:** originál sa zmaže až po overenej kópii v Koši (veľkosť), pri zlyhaní sa Kôš vráti späť; jedno mazanie naraz (`deleteLock`); kontrola voľného miesta.
- **Limity:** veľkosť tiel požiadaviek, počet spojení (20), fronty dekódovania, WakeLock s časovou poistkou a len počas práce.

---

## 2. Slabé miesta návrhu

1. **PIN robí tri veci naraz** – párovací kód, trvalé heslo PC programu (v každej požiadavke) aj súčasť odkazu. 6 číslic je dosť len dovtedy, kým každý overovací bod počíta pokusy (nález 1 ukazuje, ako ľahko jeden ujde). Chýba pojem „dôveryhodné PC“.
2. **Nešifrované HTTP.** Na cudzej Wi-Fi sa PIN (v adrese alebo hlavičke), session aj fotky dajú odpočúvať. Je to vedomý kompromis, ale appka neupozorní, že je na inej sieti než doma, a server tam beží ďalej.
3. **Životný cyklus servera je čisto ručný** – žiadne automatické vypnutie, žiadna väzba na sieť, počúvanie všade (nálezy 2 a 6).
4. **Adresa = identita webu.** Nastavenia (veľkosť miniatúr, filter, hlasitosť) a zapamätaný priečinok pre chat sú v `localStorage` / IndexedDB viazané na `http://IP:8089`. Zmena IP (DHCP, kábel vs. Wi-Fi) = všetko odznova a nové prihlásenie.
5. **Bezpečný kontext len cez `localhost`.** Kopírovanie obrázka (Ctrl+C) a zápis do priečinka pre chat fungujú len cez adb alebo `chrome://flags`. Bežná cesta (http://IP) má pre hlavný scenár „fotky do Claude“ najhoršiu ergonómiu: Stiahnuté → Prieskumník → označiť → pretiahnuť.
6. **Dopytovanie každé 4 s** namiesto oznamovania zmien: batéria, oneskorenie, súbehy stavu (nálezy 6 a 14).
7. **ZIP cez formulár v hlavnom okne** a odhad obsadenosti podľa hodín (nález 4) – chyba servera vie zničiť stav stránky.
8. **Stav v SharedPreferences ako veľké JSON-y** (značky, stiahnuté, stavy skupín, dávky Koša): každá zmena prepíše celý súbor, žiadne transakcie ani čistenie.
9. **Značky viazané na cestu, klient pracuje s id**, mazanie bez predpokladu (nálezy 11 a 12). „Skryť označené“ je predvolene zapnuté, takže návrhy na zmazanie sú mimo dohľadu a mažú sa hromadne.
10. **Kôš kopírovaním** medzi úložiskom a dátami appky: zmazanie 4 GB videa potrebuje 4 GB voľného miesta a minúty; uvoľniť miesto na plnom telefóne sa cez Kôš nedá.
11. **„✓ v PC“ = server dočítal súbor**, nie „súbor je uložený v PC“. Zahodené „nezabezpečené stiahnutie“ v Chrome alebo zrušený ZIP po dočítaní dajú falošné ✓.
12. **Plná prestavba indexu** pri každej zmene MediaStore a celý zoznam ako jeden JSON (pri 30 000 fotkách ~9 MB pred gzipom) pri každom novom behu.
13. **`gallery.html` má 289 kB v jednom súbore** (5 093 riadkov, stav v globálnych objektoch `S`, `CM`, `FC`, `PV`, `DLQ`). Čistá logika (`GCore`) je testovaná dobre, ale súhra okien (`refreshOpenViews`) a klávesnica len cez e2e. Každá nová funkcia zvyšuje riziko vedľajších účinkov – nález 3 je presne taký prípad (medzerník poistku má, X nie).
14. **USB kábel = zdieľanie pripojenia.** Windows môže začať ťahať internet cez mobilné dáta telefónu (aktualizácie). V pomocníkovi ani dokumentácii o tom nie je zmienka.

---

## 3. Čo chýba, čo doladiť a nové nápady

Veľkosť: S = hodiny, M = dni, L = týždeň a viac.

### Chýba alebo treba doladiť
- **Nahrávanie z webu neexistuje** – v `gallery.html` nie je žiadny kód pre upload; API `/api/upload` je hotové (M, nápad D).
- **Odhlásenie a prehľad prihlásených PC** v telefóne s tlačidlom „Odhlásiť všetky“ (S).
- **Hlásenie o výpadku spojenia** (S, nález 13).
- **Priebeh a čiastočný výsledok pri mazaní** (S, nález 7).
- **Poistka proti držanému klávesu** a hlásenie pri značke (S, nález 3).
- **Upozornenie na obľúbené medzi označenými** pred zmazaním; hromadné „Ponechať najlepšiu“ nech ★ nikdy nenavrhne na zmazanie bez výslovného súhlasu (S).
- **Časová os** (skok na mesiac / rok) – pri tisíckach fotiek je len posuvník a hľadanie podľa názvu (M).
- **Hľadanie** je len podľa názvu a priečinka (`filterItems`, riadok 671) – nápad C.
- **Varovanie pri USB zdieľaní pripojenia** („nastav sieť vo Windows ako meranú“) (S).
- **Dokumentácia:** v `docs/api/server-pre-pc.md:222-223` po oprave nálezu 1 zmeniť vetu, že sa zlý PIN v ZIP-e neráta.

### Nové nápady s najväčším prínosom

**A. Párovanie PC namiesto PIN-u pri každom štarte (M)** – najväčší prínos pre bezpečnosť aj pohodlie.
Prvé pripojenie: telefón ukáže „Povoliť PC ‚Chrome / Windows‘?“ a 6-miestny kód platí len 2 minúty. Po potvrdení dostane prehliadač dlhodobý náhodný token; PC program má vlastný token namiesto PIN-u. Ďalšie štarty servera sú bez PIN-u. Hádanie stráca zmysel a z odkazov aj notifikácie zmizne tajomstvo. Rieši nálezy 1, 8, 9, 22. K tomu „len lokálne adresy“ a automatické vypnutie (S + S).

**B. Udalosti namiesto pingu (M).**
`GET /api/events` (SSE): server pošle `gen` / `stateGen` / `favGen` len pri zmene a raz za ~45 s prázdny riadok. Telefón sa nebudí 900× za hodinu, zmeny sú okamžité, výpadok spojenia je vidno hneď (nálezy 6, 13, 14). Úsporná medzicesta: predlžovať ping pri nečinnosti (S).

**C. Hľadanie podľa obsahu z indexov telefónu (L, po častiach M).**
Appka už má CLIP, OCR, tváre a polohu (`faces/IndexScope.kt` ich menuje). `GET /api/search?q=pes na pláži`, `?person=`, `?text=`, `?near=` vráti id z indexu servera – rozsah `IndexScope` už platí. Na webe hľadanie, filter Ľudia / Miesta, „podobné tejto“. Pre Claude: nájsť správne fotky bez listovania.

**D. Fotky do Claude na jeden ťah + cesta späť (M).**
- *Kontaktný hárok:* z výberu jeden obrázok s očíslovanými náhľadmi (canvas na PC) – Claude dostane 20 fotiek v jednej prílohe a vie povedať „č. 7 a 12“; web potom podľa čísel fotky vyberie alebo označí.
- *Popis do schránky:* zoznam „č. · názov · dátum · miesto“ ako text k hárku.
- *Upload pustením alebo Ctrl+V:* obrázok pustený na stránku alebo vložený zo schránky sa cez hotové `PUT /api/upload` uloží do mobilu – napr. obrázok, ktorý Claude upravil. Zároveň rieši nález 20.
- *Skutočný názov pri ťahaní* (S, nález 21).

**E. Stála adresa a bezpečný kontext (S–M).**
- mDNS meno (`galeria.local:8089`, Android NSD) – nastavenia a prihlásenie prežijú zmenu IP (S–M).
- Návod alebo `.bat` do pomocníka: `netsh interface portproxy add v4tov4 listenaddress=127.0.0.1 listenport=8089 connectaddress=<IP mobilu> connectport=8089` → `http://localhost:8089` je bezpečný kontext aj cez Wi-Fi, bez adb. Hneď funguje Ctrl+C obrázka a zapamätaný priečinok pre chat (S; treba overiť, že `Origin` / `Host` sedí – server porovnáva `http://<Host>`, takže by malo).

**Ďalšie menšie nápady**
- **Reťazenie ZIP-ov** – nad 500 fotiek web sám pošle ďalší ZIP, keď server hlási voľný slot (S, s opravou nálezu 4).
- **Presun do albumu, premenovanie, otočenie z webu** (M).
- **„Stiahnuť nové od poslednej synchronizácie“** jedným tlačidlom s cieľovým priečinkom podľa mesiaca (S–M; filter „nové“ už je).
- **Kôš bez kopírovania:** priečinok `.GaleriaKos` na tom istom úložisku + `rename` (appka má Prístup ku všetkým súborom) – okamžité a bez nároku na miesto (M; zasahuje aj Kôš appky).
- **Mapa a cestovné série** z GPS (M).
- **Stránka „Stav spojenia“** z hotového `/api/stats`: rýchlosť, Wi-Fi vs. kábel, odporúčanie „prepni na kábel“ (S).

---

## Odporúčané poradie opráv

1. Nález 1 (S) – jeden riadok + test.
2. Nález 2 (S) – odmietnuť nelokálne adresy.
3. Nález 3 (S) – `e.repeat` + hlásenie.
4. Nález 4 (S) – čakanie na slot alebo `zipBusy` v pingu.
5. Nález 5 (S) – výnimka pri neúplnom uploade.
6. Nález 6 (S) – predlžovanie pingu + automatické vypnutie.
7. Potom P3 podľa chuti; z nápadov A (párovanie) a B (udalosti) odstránia celé triedy chýb naraz.
