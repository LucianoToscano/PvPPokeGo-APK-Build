package com.lucianotoscano.pvppokego.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import com.lucianotoscano.pvppokego.BuildConfig
import com.lucianotoscano.pvppokego.data.BattleHistoryEntry
import com.lucianotoscano.pvppokego.data.BattleHistoryEvent
import com.lucianotoscano.pvppokego.data.BattleHistoryRepository
import com.lucianotoscano.pvppokego.data.BattleLeagueMode
import com.lucianotoscano.pvppokego.data.SettingsRepository
import com.lucianotoscano.pvppokego.data.TeamSetupRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    repository: SettingsRepository,
    historyRepository: BattleHistoryRepository,
    historyRevision: Int,
    overlayPermissionGranted: Boolean,
    onRequestOverlayPermission: () -> Unit,
    recorderCompatibilityAvailable: Boolean,
    recorderCompatibilityEnabled: Boolean,
    onOpenRecorderCompatibility: () -> Unit,
    onStartExternalRecorderMode: () -> Unit,
    onStartOverlay: () -> Unit,
    onStopOverlay: () -> Unit,
    onResetBattle: () -> Unit,
    onExportSettings: () -> Unit,
    onImportSettings: () -> Unit,
    onExportDiagnostics: () -> Unit,
    onExportHistory: () -> Unit,
    onExportBattle: (Long) -> Unit,
    onToggleRecording: () -> Unit
) {
    val context = LocalContext.current
    val teamStore = remember(context) { TeamSetupRepository(context) }
    var overlay by remember { mutableStateOf(repository.overlayEnabled) }
    var auto by remember { mutableStateOf(repository.autoRecognition) }
    var assist by remember { mutableStateOf(repository.battleAssistEnabled) }
    var league by remember { mutableStateOf(repository.leagueMode) }
    var strong by remember { mutableStateOf(repository.showStrongTypes) }
    var current by remember { mutableStateOf(repository.showCurrentIndicator) }
    var reserves by remember { mutableStateOf(repository.analyzeReserves) }
    var reserveTypes by remember { mutableStateOf(repository.showReserveTypes) }
    var moves by remember { mutableStateOf(repository.showEnemyMoves) }
    var counter by remember { mutableStateOf(repository.showChargedCounter) }
    var progress by remember { mutableStateOf(repository.showEnergyProgress) }
    var hpAssist by remember { mutableStateOf(repository.showHpAssist) }
    var timer by remember { mutableStateOf(repository.showSwitchTimer) }
    var debug by remember { mutableStateOf(repository.debugMode) }
    var edit by remember { mutableStateOf(repository.editMode) }
    var scale by remember { mutableFloatStateOf(repository.scale) }
    var opacity by remember { mutableFloatStateOf(repository.opacity) }
    var history by remember(historyRevision) { mutableStateOf(historyRepository.loadHistory()) }
    var expandedBattleId by remember { mutableStateOf<Long?>(null) }

    MaterialTheme {
        Scaffold { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 18.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "PvPPokeGo",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Assistente visual de batalhas PvP",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Analisa a tela e mostra informações em tempo real sem enviar comandos ao Pokémon GO.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                TeamBuilderPanel(
                    store = teamStore,
                    league = league,
                    autoLeagueCp = repository.lastDetectedLeagueCp,
                    onLeagueChange = { selected ->
                        league = selected
                        repository.leagueMode = selected
                    }
                )
                ArenaDashboard(history = history)

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
                    )
                ) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "Batalha",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (overlayPermissionGranted) {
                                "Pronto para iniciar o HUD."
                            } else {
                                "Permita a sobreposição para usar o HUD durante a batalha."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (!overlayPermissionGranted) {
                            Button(
                                onClick = onRequestOverlayPermission,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Permitir sobreposição")
                            }
                        }

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onStartOverlay,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Iniciar")
                            }
                            OutlinedButton(
                                onClick = onStopOverlay,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Parar")
                            }
                        }

                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onToggleRecording,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Gravar")
                            }
                            OutlinedButton(
                                onClick = onResetBattle,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Nova batalha")
                            }
                        }
                    }
                }

                if (recorderCompatibilityAvailable) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .92f)
                        )
                    ) {
                        Column(
                            Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "Gravador externo",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                if (recorderCompatibilityEnabled) {
                                    "Compatibilidade ativa para Samsung Screen Recorder e outros gravadores."
                                } else {
                                    "Ative somente se precisar usar um gravador externo junto com o PvPPokeGo."
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                            OutlinedButton(
                                onClick = onOpenRecorderCompatibility,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    if (recorderCompatibilityEnabled) "Compatibilidade ativa"
                                    else "Ativar compatibilidade"
                                )
                            }
                            Button(
                                onClick = onStartExternalRecorderMode,
                                enabled = recorderCompatibilityEnabled,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Iniciar modo gravador externo")
                            }
                        }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = .72f)
                ) {
                    Text(
                        "HUD: 🔓 mover • 🔒 travar • ◉ mostrar/ocultar • ⚙ menu rápido\n" +
                            "Gravação interna: Movies/PvPPokeGo • Versão " + BuildConfig.VERSION_NAME,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                SectionTitle("Assistência e HUD")
                Toggle("Overlay", overlay) {
                    overlay = it
                    repository.overlayEnabled = it
                }
                Toggle("Reconhecimento automático", auto) {
                    auto = it
                    repository.autoRecognition = it
                }
                Toggle("Modo Batalha Assistido", assist) {
                    assist = it
                    repository.battleAssistEnabled = it
                }
                if (assist) {
                    Text(
                        "Copiloto visual: recomenda CONTINUE FAST, TROQUE, ESCUDO, NÃO ESCUDE e alerta de Charged. " +
                            "Ele não toca nem envia comandos ao Pokémon GO."
                    )
                }

                SectionTitle("Liga e análise")
                Text("Liga / ranking PvP", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (league == BattleLeagueMode.AUTO) {
                        val detected = when (repository.lastDetectedLeagueCp) {
                            2500 -> "Liga Ultra"
                            10000 -> "Liga Mestra"
                            else -> "Liga Grande"
                        }
                        "Automático: ler o lobby. Última detectada: $detected"
                    } else {
                        league.label
                    }
                )

                LeagueSelectorRow(
                    left = BattleLeagueMode.AUTO,
                    right = BattleLeagueMode.GREAT,
                    selected = league
                ) { selected ->
                    league = selected
                    repository.leagueMode = selected
                }

                LeagueSelectorRow(
                    left = BattleLeagueMode.ULTRA,
                    right = BattleLeagueMode.MASTER,
                    selected = league
                ) { selected ->
                    league = selected
                    repository.leagueMode = selected
                }

                Toggle("Fraquezas do inimigo no topo", strong) {
                    strong = it
                    repository.showStrongTypes = it
                }
                Toggle("Indicador do Pokémon atual", current) {
                    current = it
                    repository.showCurrentIndicator = it
                }
                Toggle("Analisar reservas", reserves) {
                    reserves = it
                    repository.analyzeReserves = it
                }
                Toggle("Mostrar tipos das reservas", reserveTypes) {
                    reserveTypes = it
                    repository.showReserveTypes = it
                }
                Toggle("Ataques do inimigo", moves) {
                    moves = it
                    repository.showEnemyMoves = it
                }
                Toggle("Pontos de carregamento", counter) {
                    counter = it
                    repository.showChargedCounter = it
                }
                Toggle("Progresso de energia", progress) {
                    progress = it
                    repository.showEnergyProgress = it
                }
                Text(
                    "A previsão inimiga usa turns de 0,5 s, energia residual e hipóteses de Fast Move. " +
                        "Quando há incerteza, mostra faixa/READY? em vez de inventar um valor exato.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Toggle("HP e previsão de dano sem escudo", hpAssist) {
                    hpAssist = it
                    repository.showHpAssist = it
                }
                Text(
                    "Mostra HP estimado e uma faixa fantasma sobre a barra quando um carregado inimigo pode chegar. " +
                        "Dano observado tem prioridade; sem amostras, usa o modelo offline PvPoke como estimativa.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Toggle("Timer de troca", timer) {
                    timer = it
                    repository.showSwitchTimer = it
                }
                Toggle("Modo edição — segure e arraste", edit) {
                    edit = it
                    repository.editMode = it
                }
                if (edit) {
                    Text(
                        "Na batalha, segure um bloco do HUD por um instante e arraste. " +
                            "As posições são salvas automaticamente e o movimento é livre pela tela."
                    )
                    OutlinedButton(
                        onClick = { repository.clearHudPositions() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Restaurar posições padrão do HUD")
                    }
                }
                Toggle("DEBUG", debug) {
                    debug = it
                    repository.debugMode = it
                }

                Text("Escala: ${(scale * 100).toInt()}%")
                Slider(
                    value = scale,
                    onValueChange = {
                        scale = it
                        repository.scale = it
                    },
                    valueRange = .5f..1.2f,
                    steps = 6
                )

                Text("Opacidade: ${(opacity * 100).toInt()}%")
                Slider(
                    value = opacity,
                    onValueChange = {
                        opacity = it
                        repository.opacity = it
                    },
                    valueRange = .3f..1f,
                    steps = 6
                )

                SectionTitle("Backup e diagnóstico")
                Text("Backup de configurações e posições", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Salva opções, escala, opacidade e as posições personalizadas dos ícones, blocos e menus do HUD. " +
                        "Ao importar, o layout volta para o backup e fica travado por segurança.",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = onExportSettings, modifier = Modifier.weight(1f)) {
                        Text("Exportar")
                    }
                    OutlinedButton(onClick = onImportSettings, modifier = Modifier.weight(1f)) {
                        Text("Importar")
                    }
                }

                OutlinedButton(
                    onClick = onExportDiagnostics,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Exportar diagnóstico (.zip)")
                }
                Text(
                    "Inclui configurações, posições, histórico, confiança/origem dos eventos e dados do aparelho.",
                    style = MaterialTheme.typography.bodySmall
                )

                SectionTitle("Histórico de batalhas")
                Text(
                    "Cada batalha abre como uma conversa cronológica: você, inimigo e assistente em balões separados.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { history = historyRepository.loadHistory() },
                        modifier = Modifier.weight(1f)
                    ) { Text("Atualizar") }
                    Button(
                        onClick = onExportHistory,
                        modifier = Modifier.weight(1f),
                        enabled = history.isNotEmpty()
                    ) { Text("Exportar ZIP") }
                }
                OutlinedButton(
                    onClick = {
                        historyRepository.clear()
                        history = emptyList()
                        expandedBattleId = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = history.isNotEmpty()
                ) { Text("Limpar histórico") }

                Text(
                    "O ZIP de exportação contém: histórico legível (.txt), dados completos (.json) e tabela (.csv). " +
                        "Pode ser enviado diretamente para análise.",
                    style = MaterialTheme.typography.bodySmall
                )

                if (history.isEmpty()) {
                    Text("Nenhuma batalha registrada ainda.", style = MaterialTheme.typography.bodySmall)
                } else {
                    history.take(10).forEach { battle ->
                        BattleHistoryCard(
                            battle = battle,
                            expanded = expandedBattleId == battle.id,
                            onToggle = {
                                expandedBattleId = if (expandedBattleId == battle.id) null else battle.id
                            },
                            onExport = { onExportBattle(battle.id) }
                        )
                    }
                }

                SectionTitle("Sobre")
                Text("Desenvolvimento PvPPokGo by: LucianoToscano\n(84) 99131-9021")
                Text("Versão " + BuildConfig.VERSION_NAME)
                Text(
                    "Base visual V03 preservada. O HUD protege a interface nativa do Pokémon GO, " +
                        "permite reposicionar blocos no modo edição e mantém o botão de olho no topo."
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun BattleHistoryCard(
    battle: BattleHistoryEntry,
    expanded: Boolean,
    onToggle: () -> Unit,
    onExport: () -> Unit
) {
    val date = remember(battle.startedAtEpochMs) {
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(battle.startedAtEpochMs))
    }
    val league = when (battle.leagueCp) {
        2500 -> "Ultra"
        10000 -> "Mestra"
        else -> "Grande"
    }
    val player = battle.playerName ?: "Seu Pokémon"
    val enemy = battle.opponentName ?: "Inimigo"
    val playerCp = battle.playerCp?.let { " PC $it" }.orEmpty()
    val enemyCp = battle.opponentCp?.let { " PC $it" }.orEmpty()
    val duration = ((battle.endedAtEpochMs - battle.startedAtEpochMs).coerceAtLeast(0L) / 1000L)
    val ownFast = battle.events.sumOf { if (it.actor == "VOCÊ" && it.category == "RÁPIDO") it.count else 0 }
    val enemyFast = battle.events.sumOf { if (it.actor == "INIMIGO" && it.category == "RÁPIDO") it.count else 0 }
    val ownCharged = battle.events.count { it.actor == "VOCÊ" && it.category == "CARREGADO" }
    val enemyCharged = battle.events.count { it.actor == "INIMIGO" && it.category == "CARREGADO" }
    val decisions = battle.events.count { it.actor == "ASSISTENTE" || it.category == "DECISÃO" }
    val diagnostics = battle.events.count { it.actor == "APP" || it.category == "DIAGNÓSTICO" }
    val ownPokemon = remember(battle.id, battle.events.size) {
        linkedSetOf<String>().apply {
            battle.playerName?.takeIf { it.isNotBlank() }?.let(::add)
            battle.events.filter { it.actor == "VOCÊ" }.forEach { event ->
                event.pokemon?.takeIf { it.isNotBlank() }?.let(::add)
                event.details["de"]?.takeIf { it.isNotBlank() }?.let(::add)
                event.details["para"]?.takeIf { it.isNotBlank() }?.let(::add)
            }
        }.take(3)
    }
    val enemyPokemon = remember(battle.id, battle.events.size) {
        linkedSetOf<String>().apply {
            battle.opponentName?.takeIf { it.isNotBlank() }?.let(::add)
            battle.events.filter { it.actor == "INIMIGO" }.forEach { event ->
                event.pokemon?.takeIf { it.isNotBlank() }?.let(::add)
                event.details["de"]?.takeIf { it.isNotBlank() }?.let(::add)
                event.details["para"]?.takeIf { it.isNotBlank() }?.let(::add)
            }
        }.take(3)
    }
    val ownShieldsUsed = battle.events.count { it.actor == "VOCÊ" && it.category == "ESCUDO" }
    val enemyShieldsUsed = battle.events.count { it.actor == "INIMIGO" && it.category == "ESCUDO" }
    val switches = battle.events.count { it.category == "TROCA" }
    val largestDamage = battle.events
        .filter { it.category == "DANO" }
        .mapNotNull { event -> event.details["damagePercent"]?.toFloatOrNull()?.let { it to event } }
        .maxByOrNull { it.first }
    val uncertainEvents = battle.events.count {
        it.confidence == "ESTIMADO" || it.confidence == "DESCONHECIDO" || it.confidence == "FAIXA"
    }
    var showDiagnostics by remember(battle.id) { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
        )
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(
                "$date • Liga $league • ${duration}s",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                "$player$playerCp  ×  $enemy$enemyCp",
                style = MaterialTheme.typography.titleMedium
            )
            battle.result?.let {
                Text("Resultado: $it", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
            if (battle.startedMidBattle) {
                Text(
                    "Captura iniciada no meio da luta",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            battle.endReason?.takeIf { it.isNotBlank() }?.let {
                Text("Fim: $it", style = MaterialTheme.typography.bodySmall)
            }

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text("RÁPIDOS     Você $ownFast  •  Inimigo $enemyFast", style = MaterialTheme.typography.bodySmall)
                    Text("CARREGADOS  Você $ownCharged  •  Inimigo $enemyCharged", style = MaterialTheme.typography.bodySmall)
                    Text("ASSISTENTE  $decisions decisões  •  $diagnostics diagnósticos", style = MaterialTheme.typography.bodySmall)
                }
            }

            if (ownPokemon.isNotEmpty() || enemyPokemon.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = .72f)
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        if (ownPokemon.isNotEmpty()) {
                            Text("Seu time: " + ownPokemon.joinToString(" • "), style = MaterialTheme.typography.bodySmall)
                        }
                        if (enemyPokemon.isNotEmpty()) {
                            Text("Inimigos vistos: " + enemyPokemon.joinToString(" • "), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onToggle, modifier = Modifier.weight(1f)) {
                    Text(if (expanded) "Ocultar" else "Ver batalha")
                }
                OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f)) {
                    Text("Exportar")
                }
            }

            if (expanded) {
                HorizontalDivider()
                Text(
                    "Conversa da batalha",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Os eventos aparecem na ordem em que aconteceram. Você fica à direita, o inimigo à esquerda e o assistente em destaque.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = .78f)
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Momentos-chave", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        Text("Escudos: você usou $ownShieldsUsed • inimigo usou $enemyShieldsUsed", style = MaterialTheme.typography.bodySmall)
                        Text("Trocas detectadas: $switches • decisões do assistente: $decisions", style = MaterialTheme.typography.bodySmall)
                        largestDamage?.let { (damage, event) ->
                            Text(
                                "Maior dano observado: ${"%.1f".format(damage)}% • ${event.moveName}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (uncertainEvents > 0) {
                            Text(
                                "$uncertainEvents eventos ficaram estimados/indeterminados; veja o diagnóstico para revisar.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                val conversationEvents = battle.events
                    .filterNot { it.actor == "APP" || it.category == "DIAGNÓSTICO" }
                    .sortedBy { it.elapsedMs }

                if (conversationEvents.isEmpty()) {
                    Text("Nenhum evento identificado.", style = MaterialTheme.typography.bodySmall)
                } else {
                    conversationEvents.forEach { event ->
                        BattleHistoryEventCard(event)
                    }
                }

                if (diagnostics > 0) {
                    OutlinedButton(
                        onClick = { showDiagnostics = !showDiagnostics },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (showDiagnostics) "Ocultar diagnóstico técnico"
                            else "Diagnóstico técnico ($diagnostics)"
                        )
                    }
                    if (showDiagnostics) {
                        Text(
                            "Leituras, confiança e fontes usadas pelo motor.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        battle.events
                            .filter { it.actor == "APP" || it.category == "DIAGNÓSTICO" }
                            .sortedBy { it.elapsedMs }
                            .forEach { event -> BattleHistoryEventCard(event, technical = true) }
                    }
                }
            }
        }
    }
}

@Composable
private fun BattleHistorySection(
    title: String,
    events: List<BattleHistoryEvent>
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)
    ) {
        Column(
            Modifier.padding(9.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            if (events.isEmpty()) {
                Text("Nenhum evento.", style = MaterialTheme.typography.bodySmall)
            } else {
                events.sortedBy { it.elapsedMs }.forEach { event ->
                    BattleHistoryEventCard(event)
                }
            }
        }
    }
}

@Composable
private fun BattleHistoryEventCard(
    event: BattleHistoryEvent,
    technical: Boolean = false
) {
    val seconds = event.elapsedMs / 1000.0
    val actorLabel = when (event.actor) {
        "VOCÊ" -> "Você"
        "INIMIGO" -> "Inimigo"
        "ASSISTENTE" -> "Assistente"
        "PARTIDA" -> "Partida"
        else -> "Sistema"
    }
    val isOwn = event.actor == "VOCÊ"
    val isEnemy = event.actor == "INIMIGO"
    val isAssistant = event.actor == "ASSISTENTE" || event.category == "DECISÃO"
    val isSystem = !isOwn && !isEnemy && !isAssistant

    val container = when {
        technical -> MaterialTheme.colorScheme.surfaceVariant
        isOwn -> MaterialTheme.colorScheme.primaryContainer
        isEnemy -> MaterialTheme.colorScheme.surfaceVariant
        isAssistant -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    val content = when {
        technical -> MaterialTheme.colorScheme.onSurfaceVariant
        isOwn -> MaterialTheme.colorScheme.onPrimaryContainer
        isAssistant -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val bubbleFraction = when {
        technical -> .94f
        isAssistant -> .92f
        isSystem -> .78f
        else -> .84f
    }
    val arrangement = when {
        isOwn -> Arrangement.End
        isEnemy -> Arrangement.Start
        else -> Arrangement.Center
    }

    val count = if (event.count > 1) " ×" + event.count else ""
    val source = friendlyHistorySource(event.source)
    val confidence = friendlyConfidence(event.confidence)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = arrangement
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(bubbleFraction),
            shape = when {
                isOwn -> RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp)
                isEnemy -> RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp)
                else -> RoundedCornerShape(14.dp)
            },
            color = container,
            contentColor = content,
            tonalElevation = if (technical) 0.dp else 1.dp
        ) {
            Column(
                Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        actorLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "+" + "%.1f".format(Locale.US, seconds) + "s",
                        style = MaterialTheme.typography.labelSmall,
                        color = content.copy(alpha = .68f)
                    )
                }

                Text(
                    event.moveName + count,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )

                if (event.category.isNotBlank()) {
                    Text(
                        event.category,
                        style = MaterialTheme.typography.labelSmall,
                        color = content.copy(alpha = .72f)
                    )
                }

                val detail = buildList {
                    event.pokemon?.takeIf { it.isNotBlank() }?.let { add(it) }
                    confidence?.let { add(it) }
                    source?.let { add(it) }
                }.joinToString(" • ")
                if (detail.isNotBlank()) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = content.copy(alpha = .80f)
                    )
                }

                if (event.details.isNotEmpty()) {
                    Text(
                        event.details.entries.joinToString(" • ") { (key, value) ->
                            when (key) {
                                "hpPercent" -> "HP $value%"
                                "energyMin" -> "Energia mín. $value"
                                "energyMax" -> "Energia máx. $value"
                                "shields" -> "Escudos $value"
                                "damagePercent" -> "Dano $value%"
                                "damagePerHitPercent" -> "Dano/golpe $value%"
                                "damagePoints" -> "Dano $value/1000"
                                "samples" -> "Amostras $value"
                                "target" -> "Alvo: $value"
                                "de" -> "De: $value"
                                "para" -> "Para: $value"
                                "pc" -> "PC $value"
                                else -> "$key: $value"
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = content.copy(alpha = .82f)
                    )
                }

                event.reason?.takeIf { it.isNotBlank() }?.let { reason ->
                    Text(
                        reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = content.copy(alpha = .78f)
                    )
                }
            }
        }
    }
}

private fun friendlyConfidence(raw: String?): String? = when (raw?.uppercase()) {
    "CONFIRMADO" -> "Confirmado"
    "ESTIMADO" -> "Estimado"
    "MANUAL" -> "Manual"
    "DESCONHECIDO" -> "Desconhecido"
    null, "" -> null
    else -> raw
}

private fun friendlyHistorySource(raw: String?): String? = when (raw) {
    "OCR" -> "leitura da tela"
    "BattleAssist" -> "assistente"
    "engine" -> "motor interno"
    "opponent-hp-drop" -> "queda de HP do inimigo"
    "opponent-hp-microdrop" -> "microqueda de HP do inimigo"
    "opponent-hp-cadence" -> "queda de HP + cadência"
    "opponent-hp-microdrop-cadence" -> "microqueda de HP + cadência"
    "player-hp-cadence" -> "queda de HP + cadência do inimigo"
    "player-hp-microdrop-cadence" -> "microqueda de HP + cadência do inimigo"
    "energia/faixa" -> "faixa de energia"
    "session" -> "sessão de batalha"
    "hp-drop" -> "queda de HP"
    "microdrop" -> "microqueda de HP"
    "motion-cadence" -> "movimento + cadência"
    null, "" -> null
    else -> raw
}

@Composable
private fun SectionTitle(title: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 14.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        HorizontalDivider()
    }
}

@Composable
private fun LeagueSelectorRow(
    left: BattleLeagueMode,
    right: BattleLeagueMode,
    selected: BattleLeagueMode,
    onSelect: (BattleLeagueMode) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LeagueButton(left, selected == left, Modifier.weight(1f), onSelect)
        LeagueButton(right, selected == right, Modifier.weight(1f), onSelect)
    }
}

@Composable
private fun LeagueButton(
    mode: BattleLeagueMode,
    selected: Boolean,
    modifier: Modifier,
    onSelect: (BattleLeagueMode) -> Unit
) {
    if (selected) {
        Button(
            onClick = { onSelect(mode) },
            modifier = modifier
        ) {
            Text(mode.label)
        }
    } else {
        OutlinedButton(
            onClick = { onSelect(mode) },
            modifier = modifier
        ) {
            Text(mode.label)
        }
    }
}

@Composable
private fun Toggle(
    label: String,
    value: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}
