# PvPPokeGo — IV4U como referência, ranking offline de IVs

**Data:** 2026-10-15  
**Fonte pública base:** `LucianoToscano/PvPPokeGo-APK-Build`, branch `preview/prediction-startup-0.5.41-20261015`, HEAD `e7de5d055a780b2cefe6e2daabccedc098309af3`.

## Fontes
- IV4U: https://iv4u.lima-city.de/
- Exemplo Clefable (Great): https://iv4u.lima-city.de/?l=en&m=3&p=Clefable
- Great: `?l=en&m=3&m2=1&p=Clefable`
- Ultra: `?l=en&m=3&m2=2&p=Clefable`
- Master: `?l=en&m=3&m2=3&p=Clefable`
- Mini: `?l=en&m=3&m2=4&p=Clefable`
- Banco local derivado de PvPoke Game Master + CP multipliers, sem conexão durante a batalha.

## Objetivo e implementação
1. Catálogo de referências estruturado em `app/src/main/assets/external_iv_reference_sources.json`.
2. `PvpIvReferences`: liga e espécie -> URL externa HTTPS, com URL encoding e fallback seguro.
3. `PvpIvDatabase`: catálogo calculado localmente, por espécie/liga, das 4096 distribuições possíveis de IV (0 a 15); exibição configurável Top 20 / 50 / 200 sem armazenar conteúdo do IV4U.
4. Consulta ao site disponível na edição manual da equipe, sem alterar os trackers de batalha.
5. Produto de atributos = Attack efetivo * Defense efetivo * HP efetivo, nível máximo permitido pelo teto de CP e CPM local.
6. Ataque efetivo separado do produto total para comparação de CMP **condicional a ataques carregados iniciados no mesmo turno**; rank IV sozinho não é previsão de vitória no espelho.

## Restrições e honestidade dos dados
- **IV4U não é uma API oficial confirmada.** Não coletar nem republicar tabelas massivas, sprites ou código do site sem autorização/licença adequada.
- Rankings são reproduções matemáticas locais, calculadas com **dados do PvPoke** que já são usados pelo app. Não são dados raspados do IV4U.
- Resultado pode variar de site para site devido a XL, Best Buddy, IV floor, desempates, inclusão de nível 51 e atualizações de CP multiplier/Game Master.
- Somente rank estatístico: breakpoints/bulkpoints, move sets, shields, lag, energia e simulação de matchup exigem análises independentes; não inventar ranking de combate.
- Empate exato de Attack no CMP permanece aleatório; quando Attack oculto é desconhecido, manter resultado incerto no motor existente.
- No modo Mini a referência externa existe, mas o editor de equipe atualmente não possui liga Mini; não habilitar automaticamente uma liga não suportada.
- A UI lista 10 registros por página para manter a disposição limpa e responsiva.

## Testes e verificação
- Testes JVM: URL encoding, 4 ligas, entradas inválidas, consistência do Top 20 com Top 200, ranking Master, compatibilidade de rank 1 com `TeamPvpTools`, regra matemática condicional do CMP.
- Build completo Android: depende de Gradle, Android SDK, dependências e runner; não confundir compilação de código Kotlin isolado com APK Android validado.

## Escopo não modificado
- Não alterar `EnemyEnergyTracker`, `BattleEngine`, reconhecimento, dados de imagem, ícones, fluxo de captura, HUD de batalha ou fontes de dados de combate.
- Implementação somente na branch de trabalho pública, sem envio automático para a branch privada.
