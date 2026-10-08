# Assets gerados

Arquivos já versionados:
- `gamemaster.json`
- `rankings-1500.json`
- `rankings-2500.json`
- `rankings-10000.json`
- `pvpoke_snapshot.json`
- `pokemon_visual_fingerprints.json`
- `move_names_ptbr.json`

`tools/package_visual_fingerprints.py` também produz `pokemon_icon_atlas.png` e `pokemon_icon_atlas.json` quando os renders upstream estão acessíveis. O HUD mantém fallback para retrato capturado quando o atlas não está disponível, sem diminuir os gates de identidade.
