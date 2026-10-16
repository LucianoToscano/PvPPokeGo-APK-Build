# Checklist QA físico — PvPPokeGo 0.5.39 (código, sem APK validado)

- [ ] Instalar APK assinado após CI concluir build e testes
- [ ] Verificar HUD inicial, cadeado/olho delicados e posições aprovadas com layout travado
- [ ] Alternar HUD de 3 slots e o HUD clássico sem sobrepor botões do jogo
- [ ] Arrastar somente quando destravado: todos os extremos/insets/rotação
- [ ] Medir `dumpsys meminfo`, GC/OOM, FPS, bateria, uso de Bitmap durante captura contínua
- [ ] Reconhecer espécies e formas Mega/Shadow/Alolan/Galarian/Hisuian/Purified; CP contraditório não altera slot conhecido
- [ ] Dois Pokémon inimigos da mesma espécie com CPs diferentes conservam HP/energia/movimentos separados
- [ ] Retorno de Pokémon inimigo após troca preserva energia, HP e golpes; nova batalha zera tudo
- [ ] Variação de framerate não duplica fast moves; turns de 500 ms não derivam durante Charged animation
- [ ] Shields 2→1→0, empates CMP, buffs/debuffs e faixas de dano concordam com baseline
- [ ] Hybrid toggle auto/wifi/status sem download ao atualizar status
- [ ] Feed confiável SHA válido → pending; não ativa durante batalha; reabrir ativa e rollback de hash errado
- [ ] ZIP diagnóstico contém `hybrid_sync.txt` e `opponent-slots-and-confidence.json`
- [ ] Flavor standard não inclui AccessibilityService e não realiza gestos/cliques no jogo
- [ ] Tests Gradle `:app:testStandardDebugUnitTest` e `:app:assembleStandardDebug`
