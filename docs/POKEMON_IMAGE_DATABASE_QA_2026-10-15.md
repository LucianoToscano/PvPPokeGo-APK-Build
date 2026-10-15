# PvPPokeGo 0.5.37 ImageDB — QA técnico final (15/10/2026)

Status: **BUILD QA CONFIRMADO; QA EM APARELHO PENDENTE**. Branch isolada e PR draft, não mesclados à versão estável ou reconstrução 1.0.0.

- CI: https://github.com/LucianoToscano/PvPPokeGo-APK-Build/actions/runs/38809088247 — SUCCESS, Android Standard JUnit + assemble, GameMaster/atlas/fingerprints e assinatura v2 verificados.
- APK: 80.990.041 bytes, `com.lucianotoscano.pvppokego`, `versionName=0.5.37`, `versionCode=42`.
- SHA256: `7828f7ad092e0f807f3a95c0f3536f3ed8164c927bf2951306ee47f316f9b9b6`.
- 1 signatário `Android Debug`, APK Signature Scheme v2 `true`. NÃO é assinatura release permanente; backup antes de substituir instalação já existente.
- Drive do APK QA com checksum/metadados: https://drive.google.com/file/d/1-vmbqRPp73Qp-MqpPsqxBOXxE9FBXuGm/view?usp=drivesdk
- Fonte externa opcional: 1.071 retratos Pokémon HOME WebP RGBA 256×256 (1.025 bases + 46 Mega), hash único, ZIP íntegro, prontos para importar localmente: https://drive.google.com/file/d/1hScejMYPZrWSR4wuDiiQCVpuzaakPnZG/view?usp=drivesdk
- GO atlas local original: 1.167 entradas; funcional offline sem as imagens HOME. Conserva formas especiais (Armored Mewtwo, Pikachu Libre, Giratina, Deoxys, formas regionais) e prioriza espécie exata em relação a Dex inferido. Testes novos passaram no CI.
- Importar banco no app: `Configurações → Banco de imagens Pokémon → Importar banco completo (ZIP)`. Atualizações automáticas diárias somente por Wi-Fi inicialmente ativadas.
- Pendências: QA em dispositivo, teste do importador ZIP em Android real, autorização/avaliação de direitos de redistribuição das artes © The Pokémon Company e aceite antes de incluir na reconstrução 1.0.0.

---
## Registro prévio preservado
# Banco de imagens — QA (15/10/2026)

Este branch é a prévia de teste da Home 0.5.37 com cache offline 3D e importação do ZIP. Não substitui a versão liberada nem altera o plano de reconstrução 1.0.0.

Fonte de imagens real no novo Drive oficial: https://drive.google.com/file/d/1hScejMYPZrWSR4wuDiiQCVpuzaakPnZG/view
1.071 WebP 256px, 46 Mega; hashes conferidos e ZIP íntegro.
Arte Pokémon HOME © The Pokémon Company — requer validação de direitos para redistribuição.

Build sem pacote do atlas: run 38802502348, JUnit/Gradle PASS (não entregar APK sem verificar assets offline).
Este branch executa novo CI com scripts existentes de GameMaster, atlas e fingerprints antes da compilação.

Código privado e Guia Mestre: https://github.com/LucianoToscano/PvPPokeGo/tree/feature/pokemon-image-db-1.0.0-20261015
