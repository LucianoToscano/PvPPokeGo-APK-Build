# Pokésearch Overlay 0.2 Alpha
Aplicativo Android independente do PvPPokeGo (pacote com.lucianotoscano.pokesearchoverlay).

## Teste
1. Desinstale a 0.1 antes de instalar a 0.2: builds debug do GitHub podem usar assinaturas diferentes.
2. Entre na sua conta em https://pogocoordinates.com/search dentro do próprio APK.
3. Toque em INICIAR OVERLAY e conceda permissão de exibição sobre outros apps.
4. Mova a janela com a alça ⠿ à esquerda, não arrastando os botões.
5. Troque IV100, GL#1 e ✨, atualize, minimize e maximize.
6. Toque no ícone do Pokémon para abrir informações disponíveis, incluindo IV real capturado do site.

## Mudanças
- Painel de largura 270 dp, fundo quase transparente com blur se suportado.
- Janela compacta com até 10 ícones (duas linhas) ou lista com rolagem.
- Arrasto nativo com coordenadas absolutas: sem eventos touchmove enviados pela WebView.
- Extração robusta de IV 60% (0/13/14) e 100% (15/15/15), sem selos de IV falsos.
- Resultado IV 100 diferente é ocultado em vez de anunciado como 100%.
- Captura tentativa dos demais detalhes da página sem criar dados sintéticos.
- Atualização a cada 5 minutos.

## Limitações da alpha
A automação dos controles React e Get coords depende da estrutura real do site e ainda precisa de testes autenticados no aparelho.
O app não contorna restrições do PoGoCoordinates. Se a consulta não puder ser confirmada, informa o problema em vez de inventar resultados.
