# Pokésearch Overlay v0.28 Alpha

**Apoie via Pix:** botão voluntário na tela principal abre QR Pix estático gerado do código enviado pelo criador. Código validado por CRC-16/CCITT e aceito como Pix copia-e-cola, sem valor fixo. O usuário confere o destinatário antes de confirmar; nenhuma doação é obrigatória e o app não processa pagamentos.

**Alertas monitorados estritos:** notificação sonora só quando for NOVO Pokémon da lista de procurados **e** tiver IV 15/15/15 (100%), ou rank explicitamente 1 no modo Grande Liga/Ultra Liga. Os resultados PvP também são filtrados para rank1. No modo PvP, exige confirmação dos controles Rank 1 e da liga selecionada no site antes de enviar qualquer alerta. Se IV/rank/liga não puder ser confirmado, não avisa — pode deixar resultados legítimos sem alerta em caso de mudança de DOM do PoGoCoordinates. A lista de procurados monitora resultados do modo atualmente selecionado; não busca todos os três modos ao mesmo tempo.

**Auditoria:** QR validado e recompilação em GitHub Actions. Permanecem tamanho padrão 45x45, menu [+][-][◷][🔔], alerta e horários, refresh manual, por despawn e 5 min. Limitações: funcionamento do site, login e notificação sonora requerem teste real em Android com sessão autenticada.
