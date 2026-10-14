# Pokésearch Overlay v0.25 Alpha

- Sprites Pokémon por padrão 50×50 px, espaçamento visual mínimo (linhas 52 px), coluna transparente de 58 dp.
- Ajuste dos sprites 32/40/50/60 px sem reinstalação, nas preferências da tela inicial; aplica ao iniciar/reiniciar overlay.
- Ordem configurável por tempo de despawn, ranking PvP, IV ou Shiny; histórico local dos últimos 40 resultados, sem registrar coordenadas.
- Alertas opcionais sobre Pokémon novos (sujeitos à permissão de notificações), com limite de frequência.
- Tempos individuais opcionais, bordas finas por filtro, diagnóstico de sessão, erros da pesquisa e horário da última busca.
- Correção na leitura de cards após 'Get coords' substituir o botão por 'Open in Maps'.
- Busca avalia sessão e pesquisa resultados por até cerca de 14 s, reportando falta de confirmação em vez de substituir dados sem evidência.
- Seleção de imagens favorece sprites cujo nome/descrição correspondem ao Pokémon e aceita imagens HTTPS ou PNG/WebP/GIF/JPEG data URI.
- Atualização pelo botão ↻, por despawn e no período escolhido (5 min padrão); arrastar pela engrenagem, minimizar e restaurar preservados.

Limitações: Não foi possível validar login e DOM da busca em um aparelho autenticado nesta compilação. O site pode variar e as verificações devem ser conferidas no Samsung antes de confiar nos resultados. Recursos de avaliação PvP/PvE cruzada por base externa ou IA ainda não foram implementados.
