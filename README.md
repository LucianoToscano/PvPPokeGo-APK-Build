# Pokésearch Overlay 0.1 Alpha
Projeto Android independente de PvPPokeGo. Consulte https://pogocoordinates.com/search.
Abra o app para autenticar no PoGoCoordinates e pressione INICIAR OVERLAY.
O painel semitransparente e arrastável mostra no máximo 10 Pokémon, em grade compacta de ícones ou lista.
Modos IV100 e GL#1, opção Shiny ✨, atualizar, minimizar e fechar. Atualização a cada 5 minutos.
Ao tocar no Pokémon, aparecem os dados disponíveis do site e Get coords.
A consulta tenta reproduzir a pesquisa na sessão WebView autenticada. Os seletores do site não foram validados e podem falhar; nunca são fabricados resultados.
As coordenadas só ficam disponíveis se o botão do site conseguir fornecê-las.
Compilar com Java 17, Android SDK 36 e Gradle 8.11.1: gradle :app:assembleDebug.
