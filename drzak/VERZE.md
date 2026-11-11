# Verze držáku

Každá vytištěná verze má git tag `drzak-vN`. Model z ní: `git checkout drzak-vN -- drzak/`.

| Verze | Datum | Co se změnilo | Tisk a výsledek |
|---|---|---|---|
| v1 | 2026-11-08 | Tělo a víčko spojené 4 šroubky, konce o 3,5 mm kratší než úplně první tisk. Podstavec se sklonem 15°. | Bílé PLA. Telefon sedí, za konektorem zůstala mezera asi 0,5 mm. |
| v2 | 2026-11-10 | Nové víčko: kabel ven zezadu, štěrbiny na zvuk. Lišta se sklopnou nožkou (sklon 30°), 6 šroubků. Tělo z v1. | Bílé PLA. Funguje, nožka se ale přidělává, není součástí rámečku. |
| v3 | 2026-11-10 | Nové čelo: místo teček tři svislé štěrbiny se sraženou hranou na každém konci (víc otevřené plochy než tečky, náhled `nahled_celo.png`), konce uvnitř o 4 mm kratší než úplně první tisk, na obou stejně. Obrys stejný, víčko z v2 pasuje. | Černé PLA, vytištěno 2026-11-10: **nepovedlo se, vypadá hrozně** (jeho slova). Štěrbiny jsou na čele 3 mm široké, protože sražení začíná na plné šířce, a přes ně je vidět konektor. Mezi nimi zbylo na čele jen 0,4 mm, to je míň než jedna stopa trysky, a vytisklo se to ošklivě. Chyba návrhu Clauda, kontrola tisku tenká místa nehlídá. Tečky z v1 byly lepší. |
| v4 | 2026-11-11 | Nové čelo: zpátky kulaté tečky Ø2 z v1 bez sražení (žebra 2 mm), celá přední stěna 1,2 mm místo 2 (Live Display má 1,0), konce uvnitř o další 1 mm kratší, celkem 5 mm proti úplně prvnímu tisku, na obou stejně. Zesílená stěna je jen u konektoru a níž (y pod 5,5 mm); nad ním zůstala jako ve v3, jinak by do ní narazila hlava šroubu ve zdi. Okénko má kvůli tenčímu čelu užší sražení (1,0 místo 1,6). Tělo je o 0,8 mm tenčí, víčko, lišta a nožka z v2 beze změny pasují; do starého podstavce z v1 by sedělo volně. Slicer: 1 h 7 min, 30 g. | Černé PLA. |
