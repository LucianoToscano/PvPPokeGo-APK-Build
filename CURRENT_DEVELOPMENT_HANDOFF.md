# PvPPokeGo 0.5.40 — QA com correções históricas

Base 0.5.39/0.5.38 ImageDB; 0.5.30 Hybrid apenas referência.
Correção de slot adversário inválido, bloqueio de contaminação HP/energia, hash persistido em streaming, ativação Hybrid em IO, testes recuperados, scripts do feed e workflow de QA restaurados. Build Android e QA físico pendentes. Documento: `docs/AUDITORIA_E_CORRECOES_0.5.40_2026-10-15.md`.

---

# Handoff técnico de desenvolvimento

Versão anterior estável em CI: 0.5.38 ImageDB / code 43. Código desta pasta: revisão 0.5.39 / code 44, pendente de Android CI. Histórico Hybrid 0.5.30 apenas benchmark, não base. Sem merge de pastas históricas.

Alterados OpponentTeamTracker, BattleEngine, HybridSnapshotStore/HybridSyncScheduler, GameDataRepository, SettingsRepository/SettingsScreen, PvPPokeGoApp, ScreenCapture, BattleOverlayService, MainActivity, módulos auxiliares e testes. Preserve branch ImageDB 0.5.38 como backup imutável. Detalhes no relatório de auditoria.
