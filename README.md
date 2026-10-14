# PokeSearch

Aplicativo Android independente para exibir um overlay compacto e transparente de Pokémon encontrados no PoGoCoordinates.

## Interface
- **Tela inicial renovada:** marca PokeSearch, botão principal **Ativar overlay**, acesso aos **Pokémon procurados**, coração para contribuição Pix voluntária e engrenagem de configurações.
- **Pokémon procurados:** busca visual de 1.025 espécies com nome e número da Pokédex, sprites quando há acesso à internet; catálogo incluído no APK para busca de nomes mesmo sem a página carregada.
- **Overlay:** engrenagem arrastável para abrir/retrair o menu; filtros IV100 (100% verde), Grande Liga #1 e Ultra Liga #1, Shiny, intervalo periódico, atualização, tamanho P/M/G, cronômetro, sino, configurações, coração (Pix), minimizar e fechar. Os dois últimos controles ficam na extremidade exterior em ambos os lados.
- Tamanhos: P 35, M 45 (padrão), G 55. Sprites sem borda e mínimo espaço.
- **Alertas seletivos:** notificações sonoras apenas para novas ocorrências da lista de procurados cujos IV100 / GL#1 / UL#1 sejam confirmados no filtro atual. O sino no menu liga ou pausa; mantenha-o pressionado para abrir a lista.
- **Pix:** contribuição opcional de qualquer valor via QR/copia-e-cola no diálogo. Confirme o destinatário no aplicativo bancário.

## Build
Workflow do GitHub Actions em `main`: **Build PokeSearch Android APK**, gera APK debug instalável no artifact `PokeSearch-0.30-alpha-APK`.

O applicationId `com.lucianotoscano.pokesearchoverlay` foi preservado para manter o armazenamento local de versões anteriores.

## Limitações
As buscas usam a página do PoGoCoordinates e a sessão do navegador integrado; não há captura garantida de autenticação do Chrome. O DOM do site pode mudar, afetando pesquisa ou ranking. Não é possível garantir detecção de resultados, alarmes ou gestos até testes em aparelho Android autenticado. A lista de favoritos observa somente os resultados do modo de busca selecionado; não consulta simultaneamente os três modos.
