package com.lucianotoscano.pvppokego.ui

import android.content.Context
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lucianotoscano.pvppokego.BuildConfig
import com.lucianotoscano.pvppokego.R
import com.lucianotoscano.pvppokego.arena.ArenaProgress
import com.lucianotoscano.pvppokego.data.*
import com.lucianotoscano.pvppokego.overlay.PokemonIconAtlas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dark = Color(0xFF061939)
private val blue = Color(0xFF075EA8)
private val electric = Color(0xFF4BDEFF)
private val white = Color(0xFFF1FAFF)
private val soft = Color(0xFFC1DDF2)
private val golden = Color(0xFFFFD65A)

/** Native Android portrait Home; reuses old screens and live local repositories. */
@Composable
fun AppShell(
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
    var screen by rememberSaveable { mutableStateOf("HOME") }
    val history = remember(screen, historyRevision) { historyRepository.loadHistory() }

    MaterialTheme {
        Scaffold(
            containerColor = dark,
            bottomBar = {
                Row(
                    modifier = Modifier.fillMaxWidth().background(dark)
                        .border(1.dp, electric.copy(alpha = .45f),
                            RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                        .padding(vertical = 5.dp, horizontal = 3.dp)
                ) {
                    listOf(
                        Triple("HOME", "⌂", "Início"),
                        Triple("TEAM", "◉", "Equipe"),
                        Triple("ARENA", "★", "Arena"),
                        Triple("HISTORY", "≡", "Histórico"),
                        Triple("SETTINGS", "⚙", "Ajustes")
                    ).forEach { (target, symbol, label) ->
                        Column(
                            modifier = Modifier.weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (target == screen) blue else Color.Transparent)
                                .clickable { screen = target }
                                .padding(vertical = 6.dp, horizontal = 1.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(symbol, color = electric, fontSize = 22.sp)
                            Text(label, color = white, fontSize = 10.sp,
                                maxLines = 1, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (screen) {
                    "HOME" -> DashboardHome(
                        repository, teamStore, history, historyRevision,
                        overlayPermissionGranted, onRequestOverlayPermission,
                        onStartOverlay, onStopOverlay, onResetBattle
                    ) { screen = it }
                    "TEAM" -> Column(
                        Modifier.fillMaxSize().background(dark)
                            .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)
                    ) {
                        PageTitle("SUA EQUIPE", "Edite, escaneie e prepare seus 3 Pokémon.")
                        TeamBuilderPanel(
                            store = teamStore,
                            league = repository.leagueMode,
                            autoLeagueCp = repository.lastDetectedLeagueCp,
                            automaticRecognitionEnabled = repository.autoRecognition,
                            onLeagueChange = { repository.leagueMode = it }
                        )
                        Spacer(Modifier.height(22.dp))
                    }
                    "ARENA" -> Column(
                        Modifier.fillMaxSize().background(dark)
                            .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)
                    ) {
                        PageTitle("ARENA PvP", "Progresso local, sem ranking online.")
                        ArenaDashboard(history = history)
                    }
                    "HISTORY" -> DashboardHistory(
                        historyRepository, historyRevision, onExportHistory, onExportBattle
                    ) { screen = "SETTINGS" }
                    "ABOUT" -> Column(
                        Modifier.fillMaxSize().background(dark).padding(18.dp)
                    ) {
                        PageTitle("Sobre", "PvPPokeGo " + BuildConfig.VERSION_NAME)
                        Glass {
                            Text("Assistente visual para batalhas PvP.", color = white)
                            Text("Não envia comandos ao Pokémon GO.", color = soft)
                            Text("Desenvolvimento: LucianoToscano", color = soft)
                            Text("Ranking competitivo e prêmios reais ainda não implementados.",
                                color = soft)
                        }
                        TextButton(onClick = { screen = "HOME" }) {
                            Text("Voltar ao início")
                        }
                    }
                    else -> SettingsScreen(
                        repository = repository,
                        historyRepository = historyRepository,
                        historyRevision = historyRevision,
                        overlayPermissionGranted = overlayPermissionGranted,
                        onRequestOverlayPermission = onRequestOverlayPermission,
                        recorderCompatibilityAvailable = recorderCompatibilityAvailable,
                        recorderCompatibilityEnabled = recorderCompatibilityEnabled,
                        onOpenRecorderCompatibility = onOpenRecorderCompatibility,
                        onStartExternalRecorderMode = onStartExternalRecorderMode,
                        onStartOverlay = onStartOverlay,
                        onStopOverlay = onStopOverlay,
                        onResetBattle = onResetBattle,
                        onExportSettings = onExportSettings,
                        onImportSettings = onImportSettings,
                        onExportDiagnostics = onExportDiagnostics,
                        onExportHistory = onExportHistory,
                        onExportBattle = onExportBattle,
                        onToggleRecording = onToggleRecording
                    )
                }
            }
        }
    }
}

@Composable
private fun DashboardHome(
    settings: SettingsRepository,
    teamStore: TeamSetupRepository,
    history: List<BattleHistoryEntry>,
    historyRevision: Int,
    permissionGranted: Boolean,
    requestPermission: () -> Unit,
    startOverlay: () -> Unit,
    stopOverlay: () -> Unit,
    resetBattle: () -> Unit,
    navigate: (String) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences("pvppokego_prefs", Context.MODE_PRIVATE)
    }
    var nickname by remember { mutableStateOf(prefs.getString("home_trainer_name", "").orEmpty()) }
    var editing by remember { mutableStateOf(false) }
    var editedNickname by remember { mutableStateOf("") }
    var league by remember { mutableStateOf(settings.leagueMode) }
    val roster = teamStore.load()
    val arena = ArenaProgress.fromHistory(history)
    val cap = if (league == BattleLeagueMode.AUTO) {
        settings.lastDetectedLeagueCp.takeIf { it in setOf(1500, 2500, 10000) }
    } else league.cpCap
    val present = roster.slots.count { it.speciesId.isNotBlank() }
    val valid = ManualTeamPolicy.completeCount(roster, cap)
    val pcs = roster.slots.mapNotNull { it.cp }
    val avg = pcs.takeIf { it.isNotEmpty() }?.average()?.toInt()
    val running = settings.overlayServiceRunning
    var atlas by remember { mutableStateOf<PokemonIconAtlas?>(null) }
    LaunchedEffect(Unit) {
        atlas = withContext(Dispatchers.IO) {
            runCatching { PokemonIconAtlas(context) }.getOrNull()
        }
    }

    // Recomposition after resume checks the real service flag rather than a demo toggle.
    @Suppress("UNUSED_VARIABLE") val revisionToken = historyRevision

    Box(Modifier.fillMaxSize().background(
        Brush.verticalGradient(listOf(
            Color(0xFF0575AD), Color(0xFF0B4A76), Color(0xFF063E55),
            Color(0xFF042E46), dark
        ))
    )) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.pvppokego_launcher),
                    contentDescription = "Logo PvPPokeGo", modifier = Modifier.size(60.dp))
                Column(Modifier.weight(1f)) {
                    Text("PvPPokeGo", color = white, fontWeight = FontWeight.Black,
                        fontSize = 25.sp, maxLines = 1)
                    Text("Assistente visual de PvP", color = soft, fontSize = 11.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Surface(
                        color = if (running) Color(0xFF087B56) else dark,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.clickable {
                            if (running) stopOverlay()
                            else if (permissionGranted) startOverlay()
                            else requestPermission()
                        }
                    ) {
                        Text(if (running) "● Ativo" else "○ Parado",
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                            color = white, fontSize = 11.sp)
                    }
                    TextButton(onClick = { navigate("SETTINGS") }) {
                        Text("⚙ Ajustes", color = white, fontSize = 10.sp)
                    }
                }
            }
            Glass {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("PERFIL DO TREINADOR", color = electric, fontSize = 10.sp,
                            fontWeight = FontWeight.Bold)
                        Text(nickname.ifBlank { "Seu treinador" }, color = white,
                            fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                    TextButton(onClick = {
                        editedNickname = nickname
                        editing = true
                    }) { Text("✎ Editar", color = electric, fontSize = 11.sp) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Counter("Vitórias", arena.victories.toString(), Modifier.weight(1f))
                    Counter("Batalhas", arena.recorded.toString(), Modifier.weight(1f))
                    Counter("Sequência", arena.bestStreak.toString(), Modifier.weight(1f))
                }
                Text("Apenas histórico local disponível (até 50 partidas).",
                    color = soft, fontSize = 10.sp)
            }
            Glass {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("SUA EQUIPE PvP", color = white, fontSize = 18.sp,
                            fontWeight = FontWeight.Bold)
                        Text(present.toString() + "/3 cadastrados • " + valid + " válidos",
                            color = soft, fontSize = 10.sp)
                    }
                    Text("Média PC " + (avg?.toString() ?: "—"),
                        color = electric, fontSize = 12.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    roster.slots.take(3).forEachIndexed { i, pokemon ->
                        PokemonPreview(i, pokemon, atlas, Modifier.weight(1f)) {
                            navigate("TEAM")
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { navigate("TEAM") }, modifier = Modifier.weight(1f)) {
                        Text(if (present < 3) "+ Adicionar" else "Editar equipe", fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = { navigate("TEAM") },
                        modifier = Modifier.weight(1f)) {
                        Text("Gerenciar 3 vagas", fontSize = 10.sp, maxLines = 1)
                    }
                }
            }
            Glass {
                Text("LIGA / RANKING PvP", color = white, fontSize = 15.sp,
                    fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    listOf(
                        BattleLeagueMode.AUTO to "Auto",
                        BattleLeagueMode.GREAT to "Grande",
                        BattleLeagueMode.ULTRA to "Ultra",
                        BattleLeagueMode.MASTER to "Mestra"
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = value == league,
                            onClick = { league = value; settings.leagueMode = value },
                            modifier = Modifier.weight(1f),
                            label = { Text(label, fontSize = 10.sp, maxLines = 1) }
                        )
                    }
                }
                Text(
                    if (league == BattleLeagueMode.AUTO) "Liga automática: leitura do lobby."
                    else if (league == BattleLeagueMode.MASTER) "Liga Mestra: sem limite de PC."
                    else "Limite de PC: " + league.cpCap,
                    color = soft, fontSize = 11.sp
                )
                Text("Ranking online e recompensas: não disponíveis nesta etapa.",
                    color = soft, fontSize = 10.sp)
            }
            Button(
                modifier = Modifier.fillMaxWidth().height(54.dp),
                onClick = {
                    if (running) stopOverlay()
                    else if (permissionGranted) startOverlay()
                    else requestPermission()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (running) Color(0xFFAD424D) else Color(0xFF058F67))
            ) {
                Text(if (running) "■ Parar Overlay" else "▶ Iniciar Overlay",
                    fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = resetBattle, modifier = Modifier.weight(1f)) {
                    Text("↻ Nova batalha", fontSize = 11.sp)
                }
                OutlinedButton(onClick = { navigate("HISTORY") }, modifier = Modifier.weight(1f)) {
                    Text("≡ Histórico", fontSize = 11.sp)
                }
            }
            Glass {
                Text("FERRAMENTAS", color = white, fontWeight = FontWeight.Bold)
                listOf(
                    listOf(Triple("▣", "Overlay", "SETTINGS"),
                        Triple("◎", "Reconhecer", "SETTINGS"),
                        Triple("♟", "Reservas", "TEAM"),
                        Triple("◆", "Tipos", "SETTINGS")),
                    listOf(Triple("✦", "Ataques", "SETTINGS"),
                        Triple("⬡", "Escudo", "SETTINGS"),
                        Triple("⚙", "Ajustes", "SETTINGS"),
                        Triple("⇩", "Backup", "SETTINGS"))
                ).forEach { group ->
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        group.forEach { (symbol, label, target) ->
                            Column(
                                modifier = Modifier.weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(blue.copy(alpha = .5f))
                                    .clickable { navigate(target) }
                                    .padding(horizontal = 2.dp, vertical = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(symbol, color = electric, fontSize = 20.sp)
                                Text(label, color = white, fontSize = 10.sp,
                                    maxLines = 1, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
            Text("PvPPokeGo " + BuildConfig.VERSION_NAME + " • Dados locais",
                color = soft, fontSize = 10.sp, modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center)
        }
    }
    if (editing) AlertDialog(
        onDismissRequest = { editing = false },
        title = { Text("Nome do treinador") },
        text = {
            OutlinedTextField(value = editedNickname,
                onValueChange = { editedNickname = it.take(32) },
                label = { Text("Apelido") }, singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = {
                nickname = editedNickname.trim()
                prefs.edit().putString("home_trainer_name", nickname).apply()
                editing = false
            }) { Text("Salvar") }
        },
        dismissButton = {
            TextButton(onClick = { editing = false }) { Text("Cancelar") }
        }
    )
}

@Composable
private fun PokemonPreview(
    index: Int, pokemon: ManualTeamPokemon, atlas: PokemonIconAtlas?,
    modifier: Modifier, onClick: () -> Unit
) {
    val brush = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true } }
    Column(
        modifier.border(1.dp, electric.copy(alpha = .6f), RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF438DBE), Color(0xFFE5F8FF))))
            .clickable(onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text((index + 1).toString(), color = dark, fontWeight = FontWeight.Black,
            fontSize = 13.sp)
        if (pokemon.speciesId.isNotBlank() && atlas?.isAvailable == true) {
            Canvas(Modifier.fillMaxWidth().height(70.dp)) {
                drawIntoCanvas { canvas ->
                    atlas.draw(canvas.nativeCanvas, pokemon.speciesId, null,
                        RectF(0f, 0f, size.width, size.height), brush)
                }
            }
        } else {
            Box(Modifier.fillMaxWidth().height(70.dp),
                contentAlignment = Alignment.Center) {
                Text(if (pokemon.speciesId.isBlank()) "+" else "◉",
                    color = blue, fontSize = 39.sp)
            }
        }
        Text(pokemon.speciesName.ifBlank { "Adicionar" }, color = dark,
            fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        Text("PC " + (pokemon.cp?.toString() ?: "—"), color = dark, fontSize = 10.sp)
        Text("HP " + (pokemon.maxHp?.toString() ?: "—"), color = dark, fontSize = 10.sp)
        Text("IV " + listOf(pokemon.atkIv, pokemon.defIv, pokemon.hpIv)
            .joinToString("/") { it?.toString() ?: "?" },
            color = blue, fontSize = 9.sp, maxLines = 1)
        Text(if (pokemon.gender == "MALE") "♂"
             else if (pokemon.gender == "FEMALE") "♀" else "—",
            color = blue, fontSize = 13.sp)
        Text(if (pokemon.fastMoveId.isBlank()) "Toque para editar"
             else pokemon.fastMoveId.replace('_', ' ').take(15),
            color = dark, fontSize = 9.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Glass(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().border(1.dp, electric.copy(alpha = .7f),
            RoundedCornerShape(17.dp))
            .clip(RoundedCornerShape(17.dp))
            .background(Brush.linearGradient(
                listOf(Color(0xE70A4275), Color(0xF0071C3E), Color(0xDF08668B))))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}

@Composable
private fun Counter(label: String, number: String, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(9.dp)).background(blue.copy(alpha = .42f))
            .padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(number, color = golden, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Text(label, color = white, fontSize = 10.sp)
    }
}

@Composable
private fun PageTitle(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 13.dp)) {
        Text(title, color = white, fontWeight = FontWeight.Bold, fontSize = 23.sp)
        Text(subtitle, color = soft, fontSize = 12.sp)
    }
}

@Composable
private fun DashboardHistory(
    historyRepository: BattleHistoryRepository, revision: Int,
    onExport: () -> Unit, onExportBattle: (Long) -> Unit,
    onSettings: () -> Unit
) {
    var refresh by remember { mutableStateOf(0) }
    val entries = remember(revision, refresh) { historyRepository.loadHistory() }
    Column(
        Modifier.fillMaxSize().background(dark).verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        PageTitle("HISTÓRICO", "Partidas salvas neste dispositivo.")
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            OutlinedButton(onClick = { refresh++ }, modifier = Modifier.weight(1f)) {
                Text("Atualizar")
            }
            Button(onClick = onExport, enabled = entries.isNotEmpty(),
                modifier = Modifier.weight(1f)) { Text("Exportar ZIP") }
        }
        if (entries.isEmpty()) Glass {
            Text("Nenhuma batalha registrada.", color = white)
        }
        entries.take(50).forEach { battle ->
            var expanded by remember(battle.id) { mutableStateOf(false) }
            Glass {
                Text(SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR"))
                    .format(Date(battle.startedAtEpochMs)), color = electric)
                Text((battle.playerName ?: "Aliado não identificado") + " × " +
                    (battle.opponentName ?: "Oponente não identificado"), color = white)
                Text("Resultado: " + (battle.result ?: "não confirmado"),
                    color = soft, fontSize = 11.sp)
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Recolher" else "Detalhes", color = electric)
                }
                if (expanded) {
                    battle.events.take(80).forEach { event ->
                        Text((event.elapsedMs / 1000).toString() + "s • " +
                            event.actor + ": " + event.moveName,
                            color = soft, fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = { onExportBattle(battle.id) }) {
                        Text("Exportar esta batalha")
                    }
                }
            }
        }
        OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) {
            Text("Gerenciar histórico e diagnósticos")
        }
        Spacer(Modifier.height(20.dp))
    }
}
