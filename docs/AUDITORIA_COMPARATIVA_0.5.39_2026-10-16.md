# PvPPokeGo — Auditoria de regressões ImageDB 0.5.38 → revisão 0.5.39

Data: 2026-10-15/16. **BASE ATUAL: 0.5.38 ImageDB, versionCode 43.**
Esta entrega incremental modifica a **cópia da fonte atual** para versionName 0.5.39 / versionCode 44; não substitui o APK 0.5.38 previamente compilado. Histórico 0.5.30 Hybrid (versionCode 35) é apenas comparação, não base de build. Pastas ARQUIVO_HISTORICO_* ou ARQUIVO_0.5.10_* NÃO devem ser mescladas na raiz.

## Sumário executivo de achados (linha da versão corrigida; localizar a correção)

| Severidade | Arquivo / classe / localização | Regressão / risco | Situação |
| --- | --- | --- | --- |
| CRÍTICO | `app/src/main/java/com/lucianotoscano/pvppokego/engine/BattleEngine.kt:404` (`BattleEngine.onDetection`) | Remoção do `OpponentTeamTracker` em 0.5.38; histórico deixa de mapear permanentemente até três cartões e iguais da mesma espécie por CP | Código de correção + cinco testes novos; build Android pendente |
| ALTO | `app/src/main/java/com/lucianotoscano/pvppokego/engine/BattleEngine.kt:409` (`BattleEngine.onEnemyChanged`) | Dois oponentes da mesma espécie poderiam compartilhar estado/energia | Restabelecida chave `speciesId#slot` e restauro por slot |
| ALTO | `app/src/main/java/com/lucianotoscano/pvppokego/data/GameDataRepository.kt:71` (`GameDataRepository.load`) | 0.5.38 não tinha consumo de HybridSnapshotStore; perdia failover de dados validados | Sincronizador de volta em integração controlada; caso inválido desativa snapshot |
| ALTO | `app/src/main/java/com/lucianotoscano/pvppokego/capture/ScreenCapture.kt:86` (`ScreenCapture.openCaptureSurfacesOnly`) | Bitmap padded poderia sobreviver se copy/crop lançasse erro | Liberação em `finally` |
| MÉDIO | `app/src/main/java/com/lucianotoscano/pvppokego/overlay/BattleOverlayService.kt:539` (`BattleOverlayService.installHudControlDrag`) | HUD arrastável permitia `y` negativo e ignorava system bars | Clamping de x/y/insets no controle transparente, sem alterar posições salvas por padrão |
| MÉDIO | `app/src/main/java/com/lucianotoscano/pvppokego/data/SettingsRepository.kt:30` (`SettingsRepository.hybridDataStatus`) | UI não exibia pending/current, última sincronização, último erro | Indicadores multilinha e recarga local sem download; próxima execução depende de WorkManager |
| MÉDIO | `app/src/main/java/com/lucianotoscano/pvppokego/data/BattleDiagnosticState.kt:8` (`BattleDiagnosticState`) | ZIP não exportava três slots + scores de OCR/visual | Snapshot em memória e exportação JSON sob ação do usuário |
| BAIXO | `README.md` | Cabeçalho apontava para 0.5.29 | Reescrito com estado, limitações e linhagem atual |

## Análise 0.5.30 histórico vs 0.5.38 atual

**Ganhos preservados na atual:** seleção manual de time, reconhecimento de formas/CP com checagens adicionais de plausibilidade, image catalog/import/sync via WorkManager, bitmap atlas carregado em `Dispatchers.IO`, eventos de diagnóstico e verificação da 0.5.38.

**Regressões constatadas:** 0.5.38 retirou `OpponentTeamTracker`, os identificadores estáveis dos três adversários e `HybridSnapshotStore`/scheduler/ligação ao GameDataRepository e Settings. Os símbolos de OCR, detector visual e fingerprints continuam presentes em ambas; não se deve afirmar que todo fallback visual foi removido. ImageDB/atlas é em grande parte biblioteca de exibição, não um segundo motor OCR independente.

**Estratégia:** reimplantar comportamento rastreável sob os tipos novos, preservar código-base 0.5.38 e nunca migrar diretórios ARQUIVO_*. O fallback entre **bases de dados** foi reintegrado; fallback dinâmico entre scores da biblioteca de imagens e um segundo motor visual não foi demonstrado em QA físico e segue pendente.

## Extrações feitas (parciais, mas reais)

- `app/src/main/java/com/lucianotoscano/pvppokego/engine/IdentityFusion.kt:33`: `resolveTeamIdentityCandidate` e `samePokemonIdentity` (decisão sem estado; fachada mantém identidade estável).
- `app/src/main/java/com/lucianotoscano/pvppokego/engine/TeamAndReserveManager.kt:23`: structs de slots/candidatos e política pura de `needsOwnTeamScan`.
- `app/src/main/java/com/lucianotoscano/pvppokego/engine/ChargedMoveCoordinator.kt:7`: parser puro de menção de Charged Move (continua integrado a `ChargedAnimationCycleGate` na fachada).
- `app/src/main/java/com/lucianotoscano/pvppokego/engine/EnergyAndFastMoveCoordinator.kt:10`: cálculo determinístico de durações de Fast Move (500 ms / turno).
- `app/src/main/java/com/lucianotoscano/pvppokego/engine/AdviceAndForecastHelper.kt:9`: agregação determinística das faixas de previsão de dano.

**Limite deliberado:** os manipuladores com estado de time/reserva, `processChargedOutcome`, máquina de energia e `uiState` permanecem no `BattleEngine` porque deslocá-los sem CI e replay exigiria evidência de equivalência que não temos. `BattleEngine` ainda tem ~2.4 mil linhas. A extração integral de A NÃO está concluída. Os métodos públicos existentes permanecem como fachada.

## Sincronização Hybrid (controlada)

1. Feed HTTPS é **fixo** e restrito a `raw.githubusercontent.com/LucianoToscano/PvPPokeGo-APK-Build/hybrid-feed/`, sem redirecionamentos.
2. Cada asset recebe SHA-256 do manifesto antes de `stage` e a versão ativa é novamente validada por hash antes do carregamento.
3. A troca de snapshot ocorre em `PvPPokeGoApp.onCreate`, fora de batalha. Nunca durante captura.
4. Incompatibilidade ou corrupção leva a desligar base ativa e voltar à base offline do APK; arquivo staged permanece para auditoria.
5. Settings mostra current/pending/lastSuccess/lastError e botão `Atualizar status` sem rede. O horário exato da próxima execução não é previsível só com SharedPreferences, então a UI diz corretamente que o Android agenda a execução.

## Integridade do acervo de entrada

- Manifestos `00_MANIFESTO_SHA256.json` dos dois ZIPs do acervo: 4 itens em cada, **8/8 SHA-256 conferiram**.
- 0.5.38 assets JSON de base: parse válido em `gamemaster.json` (1751 Pokémon/352 movimentos), rankings (1153/850/409 em 1500/2500/10000), `pokemon_icon_atlas.json`, `pokemon_visual_fingerprints.json`, `pvpoke_cpms.json`, `pvpoke_snapshot.json`.
- Manifesto **novo** `00_MANIFESTO_SHA256.json` na raiz do ZIP acompanha cada arquivo com SHA-256 individual. É excluído de seu próprio hash, para evitar referência circular.

## Testes e limitações

- `kotlinc` local: compilados componentes puros `OpponentTeamTracker`, `OverlayDragBounds`, `HybridSnapshotStore`, os cinco módulos auxiliares com stubs de tipos. **PASSOU**.
- Harness executável: slots duplicados, troca, HP, reset, clamping, stage, bloqueio de ativação em batalha, adulteração SHA: **PASSOU**.
- Manifestos/ZIP originais: integridade de entrada **PASSOU**.
- `./gradlew :app:assembleStandardDebug :app:testStandardDebugUnitTest`: **NÃO EXECUTADOS COM SUCESSO**; ambiente não possui Gradle nem rede para obter `gradle-8.11.1`, `UnknownHostException: services.gradle.org`. Não afirmar compilação, assinatura nem apk 0.5.39 funcional.
- Sem teste em hardware físico, Android 15/16, rotação real, load/stress OCR de 30 minutos, battery drain, P95 FPS, leaks, CMP em aparelho; não há garantia observacional completa ainda.

## Próximas pendências prioritárias

1. Compilar com Gradle no CI/Android Studio e corrigir quaisquer erros de integração antes de entregar APK 0.5.39.
2. Adicionar testes instrumentados com replay de frames reais (trocas iguais, alolan/galarian/hisuian/shadow/mega), snapshots e UI state before/after.
3. Extrair integralmente manipuladores de estado de A em commits menores, com teste de equivalência após cada extração.
4. Confirmar drag/clamping em diferentes insets, rotação e One UI Samsung; `View.OnTouchListener` substitui APIs Compose para controles `WindowManager` nesta implementação.
5. Medir `ImageReader`/bitmap durante sessões longas, considerar pooling/LRU só com dados de perfil.
6. Testar CMP, energia, shields, reset, gravação, Hybrid update/rollback e preservar exportação de configs/HUD.

## Política de segurança

Somente leitura via MediaProjection/MLKit e overlay de assistência externa; nenhum clique, toque, hook ou injeção no Pokémon GO. `standard` não incorpora AccessibilityService. Posições de HUD originais, ícones delicados e options de imagem 0.5.38 permanecem intactos; o novo ajuste de clamp atua apenas durante arrastos.
