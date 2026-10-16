# PvPPokeGo 0.5.40 — QA com correções históricas

Base 0.5.39/0.5.38 ImageDB; 0.5.30 Hybrid apenas referência.
Correção de slot adversário inválido, bloqueio de contaminação HP/energia, hash persistido em streaming, ativação Hybrid em IO, testes recuperados, scripts do feed e workflow de QA restaurados. Build Android e QA físico pendentes. Documento: `docs/AUDITORIA_E_CORRECOES_0.5.40_2026-10-15.md`.

---

# Changelog PvPPokeGo

## 0.5.39 — código QA, NÃO COMPILADO em Gradle

- Baseada exclusivamente em 0.5.38 ImageDB (code 43); nova source version code 44.
- Restabelecida continuidade dos três slots do oponente com distinção de duplicatas por espécie+CP.
- Recuperado Hybrid Sync validado por SHA, URL confiável e ativação entre partidas com rollback.
- Settings Hybrid status, seleção do mini HUD de três slots, ZIP de diagnóstico com confiança e slots.
- Clamp seguro de controle HUD por insets; liberação de Bitmap no failure-path.
- Extrações auxiliares IdentityFusion, TeamAndReserveManager, ChargedMoveCoordinator, EnergyAndFastMoveCoordinator, AdviceAndForecastHelper (parciais, estado principal ainda em BattleEngine).
- Novos testes de regressão; Java/Kotlin smoke passou; Gradle Android bloqueado por ausência de rede.

## 0.5.38 — ImageDB QA

- Revisão anterior Android buildada e testada em CI; melhorias OCR CP estável, dual buffs e atlas decodificado fora da Main thread. Fonte preservada separadamente.

## 0.5.30 e versões anteriores

- Baselines históricos, não devem substituir código da raiz ImageDB atual.
