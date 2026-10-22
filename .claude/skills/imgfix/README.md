# imgfix – úprava obrázkov a tvorba grafiky bez Photoshopu

Nástroj, ktorým Claude upravuje obrázky presne na pixel príkazmi namiesto
klikania vo Photoshope cez snímky obrazovky. Rýchlejšie, presnejšie a každá
úprava sa dá zopakovať (recept).

Vie: odstrániť vodoznak / logo / objekt / AI artefakt (neurónová sieť LaMa),
klonovať a liečiť (clone/heal), opraviť pokazený AI text a napísať správny,
expozícia, kontrast, krivky, úrovne, vyváženie bielej, prefarbenie,
doostrenie, odšumenie, orez, zmena veľkosti, vyrovnanie, perspektíva,
rozšírenie plátna (dokreslenie), odstránenie pozadia, zmazanie AI metadát.
Funguje na akékoľvek obrázky – fotky, skeny, snímky obrazovky, AI obrázky.

**Tvorba grafiky od nuly** (bez AI modelu, zadarmo, lokálne): plagáty,
bannery, pozvánky, príspevky na sociálne siete, titulky, pozadia, koláže.
Prechody, moderné „mesh“ pozadia, hviezdy, bokeh, vzory, tvary so zaoblením
a tieňom, text, ktorý sa sám prispôsobí rámčeku, fotky v kruhu/s rámikom,
hotové rozmery (Instagram, Story, FB cover, YouTube, A4…). Fotorealistické
obrázky (ľudí, krajinu) bez AI modelu vytvoriť nevie – použije sa fotka.

## Inštalácia na tvoj počítač (Windows, raz)

1. Python 3.10+ z python.org (zaškrtni „Add Python to PATH“).
2. Skopíruj priečinok `.claude/skills/imgfix` do
   `%USERPROFILE%\.claude\skills\imgfix` – potom ho Claude vidí vo všetkých
   projektoch, nielen v tomto repozitári.
3. V tom priečinku spusti:
   ```
   py -m pip install -r requirements.txt
   py imgfix.py setup
   ```
   (`setup` stiahne model LaMa ~92 MB do `%USERPROFILE%\.cache\imgfix`.)

Potom stačí Claudovi napísať napr. „odstráň vodoznak z C:\...\obrazok.png“,
„oprav text na tabuli“ alebo „sprav plagát na letné kino“ – skill `imgfix`
sa načíta sám.

## Ručné použitie

```
py imgfix.py info obrazok.png
py imgfix.py grid obrazok.png -o mriezka.png
py imgfix.py apply obrazok.png vysledok.png -e "inpaint rect=1000,730,180,50" --preview porovnanie.png
py imgfix.py apply new:instagram plagat.png -e "texture type=mesh colors=sunset" -e "text 'Ahoj' box=100,400,880,300"
py imgfix.py collage kolaz.jpg a.jpg b.jpg c.jpg --cols 3 --radius 16
py imgfix.py ops        # zoznam všetkých operácií
py imgfix.py presets    # rozmery a farebné palety
```

Test: `py test_imgfix.py`
