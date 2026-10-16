# PvPPokeGo — Android (ImageDB + Hybrid QA 0.5.40, fonte editável)

Esta pasta é a base **0.5.38 ImageDB** (versionCode 43), com trabalho incremental de reconciliação na revisão **0.5.40, versionCode 44**, ainda **sem build Android homologado**. O APK 0.5.38 já existente é a única compilação confirmada anteriormente e **não inclui as alterações 0.5.40**. Não confundir fonte editável com APK pronto.

Pacote `com.lucianotoscano.pvppokego`; uso externo, somente leitura/overlay. Flavor `standard` não tem AccessibilityService. Nunca modificar, automatizar toques ou injetar no Pokémon GO.

**A 0.5.30 Hybrid é histórico para análise, NÃO a raiz compilável.** Não copiar código de diretórios `ARQUIVO_HISTORICO_*` ou `ARQUIVO_0.5.10_*` para esta raiz.

Relatório e pendências em `docs/AUDITORIA_COMPARATIVA_0.5.40_2026-10-16.md`; checklist em `docs/QA_CHECKLIST_0.5.40.md`. Todos os assets/posições de HUD/identidade de imagem da base 0.5.38 preservados.

## Compilar

Exige Android SDK 36, JDK 17 e acesso à distribuição Gradle 8.11.1. Comandos:

```bash
./gradlew :app:testStandardDebugUnitTest
./gradlew :app:assembleStandardDebug
```

**Status atual:** tentados, mas o ambiente não conseguiu acessar services.gradle.org (UnknownHostException); o funcionamento integrado e a APK 0.5.40 não estão certificados. Execute e inspecione testes em seu CI/Android Studio antes de instalar.


## Correções históricas 0.5.40 QA
Leia `docs/AUDITORIA_E_CORRECOES_0.5.40_2026-10-15.md`. Não incluir pastas ARQUIVO_*. Fonte 0.5.38/39 ImageDB; 0.5.30 usada apenas como referência.
