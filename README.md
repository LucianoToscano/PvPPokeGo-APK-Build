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

## Interface v0.31
- A própria engrenagem vira **X** quando as opções estão abertas; toque no X para fechar apenas o menu, sem encerrar o serviço.
- O botão **⌃**, na ponta oposta à engrenagem, recolhe toda a lista, mantendo somente a engrenagem; toque nela para restaurar.
- **AUTO 5m** deixa claro o intervalo automático. **↻** continua sendo a atualização manual.
- O sino abre a lista visual dos procurados; nela há uma caixa explícita para ativar ou pausar o som.
- **Encerrar overlay** está nas configurações, mediante confirmação.

## Shiny opt-in v0.32
- Shiny only desativado inicialmente. Migração de alpha anteriores restaura o estado desativado uma única vez; toques subsequentes são preservados.
- Somente ao tocar em ✨ o aplicativo abre o PoGoCoordinates visível e seleciona o controle Shiny only na página. O próprio site poderá solicitar login por Discord ou Google; nenhuma autenticação automática foi adicionada.
- Sem Shiny only a pesquisa IV100/GL#1/UL#1 não precisa ser bloqueada apenas pela existência de um botão de login na página.
- Sessões do navegador externo (Chrome) não são copiadas. O login via Google pode ser recusado em WebView dependendo do provedor.

## v0.33 · Controles mais claros
- Engrenagem abre e fecha apenas o menu; não vira mais um X. **X vermelho** na última posição encerra o serviço e o overlay imediatamente.
- A seta lateral, **‹** ou **›**, aponta na direção da engrenagem conforme o lado em que o menu está aberto; recolhe Pokémon, deixando apenas a engrenagem para restaurar.
- **Sino** liga/desliga apenas as notificações gerais de novos Pokémon válidos no filtro selecionado. **Estrela** abre favoritos com busca por sprite/número/nome; alertas exclusivos de favoritos ficam configuráveis dentro dessa lista. A lista antiga é mantida.
- Retirados histórico, diagnóstico, confirmação de encerramento e edição manual dos nomes. Preferências incluem apenas tamanho P/M/G, ordenação e relógio individual.
- Coração continua representando Pix voluntário, AUTO 5m e ↻ preservados.

## v0.34 · Ações acima da engrenagem e favoritos separados
- **X** (vermelho) e **minimizar todas as janelas** aparecem verticalmente **acima da engrenagem** apenas quando o menu está aberto, separados da barra horizontal. O ícone de minimizar mostra uma janela e uma seta para baixo; deixa apenas a engrenagem para restaurar. O X encerra o serviço completamente.
- A janela reserva 54 dp de espaço acima da engrenagem ao abrir as opções. Reposicionamento nativo preserva a posição dos sprites e evita interceptar o toque na engrenagem; menu à esquerda também foi corrigido para ficar realmente à esquerda.
- Editor de favoritos com **duas seções**: "★ Meus favoritos (n/50)" em cima, sempre visível, com sprite, Pokédex, nome e estrela; "Adicionar Pokémon" embaixo, com pesquisa de 1.025 espécies. Selecionar move imediatamente para a lista de cima; tocar na área superior remove. A lista de procurados e os alertas existentes são preservados.
- A lista superior ocupa até 3 linhas com rolagem, e a pesquisa inferior mostra até cinco sugestões por vez, separadas por divisória e títulos.
