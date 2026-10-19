# Rodinné prepojenie dvoch telefónov — návrh (ROZPRACOVANÝ, 19.10.2026)

Lukáš (Xiaomi 15) ↔ manželka (pravdepodobne POCO F8, HyperOS). Cieľ: lokálne zdieľanie fotiek, prenos tvárí a mien, „nech nemusia procesory robiť dvakrát", appka ľahká a spoľahlivá na oboch.

## Rozhodnutia Lukáša (brainstorming 19.10.2026)
- Režim **zmiešaný**: osoby, mená a odtlačky tvárí sa synchronizujú samé; fotky sa posielajú ručne a idú s nimi hotové výsledky.
- **Obaja** môžu meniť mená/osoby, synchronizuje sa oboma smermi; pri rozpore sa nič neprepisuje (appka ponúkne zlúčenie/voľbu); nič sa nemaže automaticky.
- Spojenie: **rovnaká Wi-Fi**, mimo domu hotspot jedného z telefónov. Žiadny internet ani cloud.
- Jej telefón: **ľahký režim** — svoje fotky spracuje len tváre + polohu, len pri nabíjaní; text/CLIP/duplikáty vypnuté, kým ich nezapne.
- Prístup **A**: vlastné prepojenie cez Wi-Fi (stavia na existujúcom serveri), párovanie QR kódom, výsledky sa párujú podľa OBSAHU fotky (fotka poslaná hocijako — Quick Share, WhatsApp — si výsledky nájde).
- Implementácia **cez cloud** (Lukášove cloud kredity), nie na lokálnom limite.

## Časť 1 — Prepojenie a spojenie (SCHVÁLENÉ)
- Samostatný modul „Rodinné prepojenie" s vlastnou malou DB (`family.db`); do people.db/faces.db/extras.db sa nezasahuje (žiadna zmena ich schémy).
- Párovanie raz: Nastavenia → „Prepojiť telefón" → QR (identita zariadenia + 256-bit tajný kľúč) → druhá appka naskenuje. Dá sa zrušiť.
- Objavenie na Wi-Fi bez IP (Android NSD/mDNS), šifrovanie kľúčom z QR (AES-GCM), nikto iný na sieti nič neprečíta ani nepošle.
- Kedy: (1) pri otvorení appky krátky pokus; (2) doma na Wi-Fi a pri nabíjaní až 1 h pripravený, sám skúša synchronizovať (v noci sa stretnú); (3) „Poslať manželke" — ak nedostupná, fronta, odíde pri najbližšom stretnutí, notifikácia. Nič nebeží trvalo na pozadí.

## Časť 2 — odpovede Lukáša (19.10.2026)
- Identita fotky: **rýchly odtlačok + záloha** (veľkosť + SHA-256 prvých/posledných 64 kB; pre prekomprimované kópie pHash + dátum nasnímania).
- Osoby: **rodinné UUID + meno + do ~20 reprezentatívnych odtlačkov** (nie všetky, nie len mená).

## Časti na predloženie Lukášovi (NESCHVÁLENÉ — pracovný návrh)
- **Časť 2 — Čo sa prenáša:** identita fotky = rýchly odtlačok obsahu (veľkosť + SHA-256 prvých a posledných 64 kB; plný hash 10k fotiek by bol príliš ťažký), pre prekomprimované kópie (WhatsApp) doplnková zhoda pHash + dátum nasnímania. Osoba = trvalé rodinné UUID (mapa v family.db), meno, zlúčenia/premenovania + do ~20 reprezentatívnych odtlačkov (mechanizmus `AnchorEmbeddingEntity` už existuje) → jej telefón rozpozná osoby na vlastných fotkách bez ručného pomenovania. Výsledky k fotke: tváre v RELATÍVNYCH súradniciach (sedia aj na zmenšenej kópii), odtlačky, priradenia, text, poloha, pHash, CLIP vektor (ak má model), kvalita → prijímateľ zapíše a označí fotku ako spracovanú, indexery ju preskočia. Rozpory: rovnaká osoba premenovaná na oboch → otázka „prevziať/ponechať"; dve osoby s rovnakým menom alebo veľmi podobnou tvárou → návrh zlúčiť; mazanie sa neprenáša.
- **Časť 3 — Ľahkosť a spoľahlivosť:** profil Ľahký/Plný; meranie APK, RAM, batérie; spracovanie šetrné (nabíjanie), modely na požiadanie.
- **Časť 4 — Chyby a testovanie:** dva emulátory (AVD) s presmerovaním portov; NSD v emulátore nejde → ladiaci prepínač na ručnú IP; testy fronty, rozporov, prerušeného prenosu.
- Súvisí s otvorenými bodmi revízie 3 a 4 (`docs/revizia/vysledky_2026-10.md`: identita fotky podľa obsahu, export/záloha osôb).
