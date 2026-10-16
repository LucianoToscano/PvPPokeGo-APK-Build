# PvPPokeGo 0.5.40 — QA com correções históricas

Base 0.5.39/0.5.38 ImageDB; 0.5.30 Hybrid apenas referência.
Correção de slot adversário inválido, bloqueio de contaminação HP/energia, hash persistido em streaming, ativação Hybrid em IO, testes recuperados, scripts do feed e workflow de QA restaurados. Build Android e QA físico pendentes. Documento: `docs/AUDITORIA_E_CORRECOES_0.5.40_2026-10-15.md`.

---

# START HERE — PvPPokeGo QA 0.5.39

Base canônica do código nesta entrega = 0.5.38 ImageDB; revisão em andamento 0.5.39/44. Origem conhecida: branch `feature/definitive-imagedb-audit-0.5.38-20261015` no GitHub privado. Não usar 0.5.30 como base atual. NÃO misturar ARQUIVO_HISTORICO_* ou ARQUIVO_0.5.10_*.

1. Ler `README.md`, relatório `docs/AUDITORIA_COMPARATIVA_0.5.39_2026-10-16.md` e manifesto SHA256.
2. Executar Gradle unit tests e assemble standard debug (não puderam ser concluídos neste ambiente).
3. Preservar posição HUD, eye/lock, ImageDB, formas, seleção manual, CorrespondenceTracker e trackers de carregados.
4. Auditar os três slots e Hybrid data antes de qualquer refatoração adicional. A modularização integral A ainda está pendente.
5. Não afirmar que existe APK 0.5.39 testado.
