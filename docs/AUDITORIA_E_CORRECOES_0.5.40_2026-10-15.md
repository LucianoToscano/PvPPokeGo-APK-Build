# PvPPokeGo 0.5.40 — Correções de regressões históricas (QA em validação)

Base: **0.5.39 ImageDB+Hybrid QA** sobre 0.5.38 ImageDB. A 0.5.30 só foi usada como referência comportamental. Não reincorporar `ARQUIVO_*` à fonte.

## Implementado

- `OpponentTeamTracker`: rejeitar quarta identidade e CP ambíguo de espécies repetidas invalida o ponteiro ativo, sem alterar HP/energia/histórico dos três slots. Um próximo frame com espécie+CP confiáveis pode reativar o slot existente. Testes reproduzem e impedem regressão R02.
- `BattleEngine`: leituras sem slot reconhecido não instalam estado de combate `#unknown`, nem gravam HP no oponente anterior; descarte de previsão de identidade incerta e de resultado de charged no alvo incerto. Reassociação ao mesmo slot quando volta uma observação confiável. O episódio de OCR não confirmado não deve acionar o cooldown de troca.
- `HybridSnapshotStore`: verificação de SHA-256 de arquivos persistentes em fluxo de 16 KiB, em vez de `readBytes()`, reduz uso temporário de RAM e preserva bloqueio em batalha/rollback.
- `PvPPokeGoApp`: ativação de snapshot pendente agora roda em `Dispatchers.IO`, não em `Application.onCreate()` na main. `GameDataRepository.load()` espera a conclusão desta ativação já em IO antes de consultar dados. Falhas não apagam pending e são exibidas no status.
- Restabelecidos `tools/generate_hybrid_feed.py` e `tools/test_generate_hybrid_feed.py` como recursos de desenvolvimento. O workflow de publicação 0.5.30 foi preservado SOMENTE em `docs/archive/` por conter tag e commit antigos. Adicionado o workflow seguro de build/QA `qa-regressions-0.5.40.yml` sem publicação de release.
- Restaurados testes individuais de rollback, corrupção/restaging, path traversal, conjuntos incompletos e asserções de slots/CP/HP. Mantidos testes de 0.5.39.

## Execuções

- `kotlinc` + runner JVM dos componentes puros: **14 testes, 0 falhas** (OpponentTeamTracker, HybridSnapshotStore, OverlayDragBounds). Runner local usa mini-stub JUnit, não é substituto oficial de Gradle.
- `PYTHONPATH=tools python3 -m pytest -q tools/test_generate_hybrid_feed.py`: **2 testes passaram**.
- Sem confirmação de compilação completa do Android neste ambiente devido a resolução de rede `services.gradle.org`. As rotinas CI reais só são aprovadas após execução e logs. Não declarar APK compilado ou paridade integral de `uiState`/energia/escudos sem build + replay.

## Pendências não resolvidas automaticamente

1. Validar `:app:testStandardDebugUnitTest` e `:app:assembleStandardDebug` no runner GitHub/Android Studio.
2. Replays de frames de batalhas para paridade 0.5.38↔0.5.40 (energia, HP, shield, turns, formas).
3. QA física do HUD/arrasto/One UI, 30 minutos de captura contínua, memórias nativas e GC.
4. Modularização integral de BattleEngine e eventual treinamento offline são funcionalidades de escopo maior, não podem ser declaradas concluídas sem testes.

## Segurança

App independente. Nenhuma injeção, hook ou automação de Pokémon GO. Flavor `standard` sem AccessibilityService. Snapshots apenas feed fixo HTTPS e SHA-256. Ícones, posições aprovadas e ImageDB são preservados.
