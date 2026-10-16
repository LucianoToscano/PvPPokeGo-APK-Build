# PvPPokeGo 0.5.41 QA — previsão e tolerância a dados persistidos

Base: fonte verificável da 0.5.40, sem fusão de diretórios históricos.

## Escopo de código
- Correção de falhas recuperáveis na leitura de preferências, histórico e snapshots Hybrid; startup não confia em valores tipados ou arquivos antigos.
- Confirmação antecipada de Fast Move apenas após múltiplas evidências coerentes; múltiplas hipóteses permanecem durante ambiguidade.
- Recuperação do intervalo sem captura com informações da fase e sem promover hipóteses a certezas.
- Histórico de Charged por adversário e heurística de bait conservadora, nunca probabilidade calibrada ou alerta absoluto.
- Debug indica janelas imprecisas e recuperação sem perturbar posições aprovadas do HUD.
- Proteção adicional ao registrar canal de notificação do Application.
- `standard` mantém serviço de acessibilidade desativado; a aplicação não interage com o processo do jogo nem emite cliques.

## Testes locais
- 34 testes Kotlin de mecanismos independentes concluídos sem falhas.
- 2 testes Python para o feed Hybrid concluídos sem falhas.
- Build Android: verificar no CI; Kotlin/JVM isolado não é equivalente a integração Android.

## Itens não homologados sem telefone
- Abertura com SharedPreferences corrompido de forma física (dados de teste)
- Replays de batalha com câmera/captura real, teste de rotação, memória, passos de arrasto
- Assinatura release permanente (APK de QA usa assinatura de debug)

## Previsibilidade
- Mínimo 3 eventos de Fast, posterior >=0.75, margem >=0.35, consistência >=0.60.
- Gap decays 0.58 geral / 0.72 quando fase >=0.60 é confiável.
- Nunca exibir '100% bait' ou 'não escude' com base em hipótese energética.
