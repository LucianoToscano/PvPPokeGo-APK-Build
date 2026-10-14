# Pokésearch Overlay 0.21 Alpha

Correção de retração: engrenagem fecha somente o menu horizontal; remove imediatamente o deslocamento lateral `menu-left`, sincroniza com a janela nativa e mantém sempre engrenagem + sprites visíveis. Minimizar também limpa o estado lateral.

Intervalo inicial alterado de 10 para 5 minutos, inclusive migração das configurações herdadas da v0.20 que não registravam escolha explícita. Após escolha pelo usuário (1, 3, 5, 10, 15, 20 min), o intervalo é preservado nas próximas aberturas.

Contador de despawn usa o instante em que os dados foram recebidos e não volta ao início por mudanças de status na ficha. Mantidos layout lateral em linha, coluna transparente, filtros e cópia de coordenadas. Alterações ainda precisam de teste em aparelho.
