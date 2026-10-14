# Pokésearch Overlay 0.23 Alpha

- O botão − minimiza somente para a mesma engrenagem (ícone vetorial), não a bolha ◉; tocá-la restaura sprites e menu recolhido.
- ↻ inicia pesquisa manual genuína em fases separadas: selecionar Stats/PvP, aplicar filtros, clicar Search, aguardar resultados e ler novamente. Feedback visual de pesquisa em andamento.
- Ao vencer o despawn mais próximo entre os Pokémon da busca atual, faz nova busca automaticamente, sem esperar o intervalo periódico. Reagenda a partir dos dados frescos; não agenda repetidamente os casos já vencidos.
- A frequência automática padrão permanece em 5 minutos, com opções 1, 3, 5, 10, 15 e 20 min.
- Integração continua dependente dos controles do PoGoCoordinates; teste autenticado real ainda necessário para garantir sucesso da busca.
