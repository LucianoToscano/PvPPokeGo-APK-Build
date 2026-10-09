package com.lucianotoscano.pvppokego.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lucianotoscano.pvppokego.data.BattleLeagueMode
import com.lucianotoscano.pvppokego.data.GameMaster
import com.lucianotoscano.pvppokego.data.ManualTeamPokemon
import com.lucianotoscano.pvppokego.data.ManualTeamPolicy
import com.lucianotoscano.pvppokego.data.MoveDef
import com.lucianotoscano.pvppokego.data.PokemonDef
import com.lucianotoscano.pvppokego.data.TeamScanHistoryRepository
import com.lucianotoscano.pvppokego.data.TeamPvpTools
import com.lucianotoscano.pvppokego.data.IvLeagueResult
import com.lucianotoscano.pvppokego.detect.TeamScanKind
import com.lucianotoscano.pvppokego.detect.TeamScreenshotResult
import com.lucianotoscano.pvppokego.detect.TeamScreenshotScanner
import com.lucianotoscano.pvppokego.data.TeamSetupRepository
import com.lucianotoscano.pvppokego.data.TeamRecognitionMode
import com.lucianotoscano.pvppokego.overlay.PokemonIconAtlas
import android.graphics.Paint
import android.graphics.RectF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import java.text.Normalizer

private data class TeamCatalog(
    val species: List<PokemonDef>,
    val moves: Map<String, MoveDef>,
    val namesPtBr: Map<String, String>,
    val cpms: List<Double>
) {
    fun displayMove(id: String): String = namesPtBr[id] ?: moves[id]?.name ?: id
    fun moveOptions(ids: List<String>): List<String> =
        ids.distinct().filter { it in moves }.sortedBy { displayMove(it) }

    companion object {
        fun load(context: android.content.Context): TeamCatalog {
            val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
            val master = context.assets.open("gamemaster.json").bufferedReader().use {
                json.decodeFromString<GameMaster>(it.readText())
            }
            val portuguese = runCatching {
                context.assets.open("move_names_ptbr.json").bufferedReader().use {
                    json.decodeFromString<Map<String, String>>(it.readText())
                }
            }.getOrDefault(emptyMap())
            return TeamCatalog(
                species = master.pokemon.filter { it.speciesId.isNotBlank() && it.dex > 0 }
                    .distinctBy { it.speciesId }.sortedBy { it.speciesName },
                moves = master.moves.associateBy { it.moveId },
                namesPtBr = portuguese,
                cpms = runCatching {
                    context.assets.open("pvpoke_cpms.json").bufferedReader().use {
                        json.parseToJsonElement(it.readText()).jsonObject["cpms"]?.jsonArray
                            ?.mapNotNull { value -> value.jsonPrimitive.content.toDoubleOrNull() }
                    }.orEmpty()
                }.getOrDefault(emptyList())
            )
        }
    }
}

private fun searchKey(raw: String): String =
    Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").trim()

/** Manual setup does not write the OCR or battle engine state. */
@Composable
fun TeamBuilderPanel(
    store: TeamSetupRepository,
    league: BattleLeagueMode,
    autoLeagueCp: Int,
    automaticRecognitionEnabled: Boolean,
    onLeagueChange: (BattleLeagueMode) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scanHistoryStore = remember(context) { TeamScanHistoryRepository(context) }
    var scanHistory by remember { mutableStateOf(scanHistoryStore.load()) }
    var scanKind by remember { mutableStateOf(TeamScanKind.DETAIL) }
    var scanTargetSlot by remember { mutableStateOf(0) }
    var scanResult by remember { mutableStateOf<TeamScreenshotResult?>(null) }
    var scanBusy by remember { mutableStateOf(false) }
    var scanApplied by remember { mutableStateOf(false) }
    var ivRanking by remember { mutableStateOf<IvLeagueResult?>(null) }
    var saved by remember { mutableStateOf(store.load()) }
    var recognitionMode by remember { mutableStateOf(store.recognitionMode) }
    var opened by remember { mutableStateOf(false) }
    var editingSlot by remember { mutableStateOf(0) }
    var draft by remember { mutableStateOf(saved.slots[0]) }
    var search by remember { mutableStateOf(saved.slots[0].speciesName) }
    var catalog by remember { mutableStateOf<TeamCatalog?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf("") }
    var leagueMenu by remember { mutableStateOf(false) }
    var atlas by remember { mutableStateOf<PokemonIconAtlas?>(null) }
    val spritePaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true } }
    val hasSelectedPokemon = saved.slots.any { it.speciesId.isNotBlank() }
    val scanPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null && !scanBusy) {
            val requestedSlot = scanTargetSlot
            val requestedKind = scanKind
            val currentCatalog = catalog
            if (currentCatalog == null) {
                feedback = "Aguarde o carregamento do banco offline antes de escanear."
            } else {
                scanBusy = true
                scanResult = null
                scope.launch {
                    val result = runCatching {
                        TeamScreenshotScanner.scan(context, uri, requestedKind,
                            currentCatalog.species, currentCatalog.moves.values.toList(),
                            currentCatalog.namesPtBr)
                    }
                    scanBusy = false
                    if (requestedSlot != editingSlot) {
                        feedback = "A vaga mudou durante o scan. Selecione a vaga desejada e repita."
                    } else {
                        result.onSuccess {
                            scanResult = it
                            feedback = if (requestedKind == TeamScanKind.DETAIL)
                                "Leitura concluída. Revise os campos antes de aplicar."
                            else "Avaliação lida. Confirme os IVs ou informe-os manualmente."
                        }.onFailure {
                            feedback = "Não foi possível ler esta imagem: ${it.message ?: "formato não reconhecido"}"
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(draft.speciesId, draft.atkIv, draft.defIv, draft.hpIv,
        league, autoLeagueCp, catalog) {
        ivRanking = null
        val found = catalog?.species?.firstOrNull { it.speciesId == draft.speciesId }
        val cap = if (league == BattleLeagueMode.AUTO) autoLeagueCp else league.cpCap
        if (found != null && cap != null && draft.atkIv != null &&
            draft.defIv != null && draft.hpIv != null) {
            ivRanking = withContext(Dispatchers.Default) {
                TeamPvpTools.rank(found.baseStats, catalog?.cpms.orEmpty(), cap,
                    draft.atkIv, draft.defIv, draft.hpIv)
            }
        }
    }

    LaunchedEffect(hasSelectedPokemon) {
        if (hasSelectedPokemon && atlas == null) {
            atlas = withContext(Dispatchers.IO) { runCatching { PokemonIconAtlas(context) }.getOrNull() }
        }
    }

    LaunchedEffect(opened) {
        if (opened && catalog == null && loadError == null) {
            val result = withContext(Dispatchers.IO) { runCatching { TeamCatalog.load(context) } }
            result.onSuccess { catalog = it }
                .onFailure { loadError = "Não foi possível carregar o banco local. " + it.message }
        }
    }

    val cpCap = when (league) {
        BattleLeagueMode.AUTO -> autoLeagueCp.takeIf { it in setOf(1500, 2500, 10000) }
        else -> league.cpCap
    }
    val valid = ManualTeamPolicy.completeCount(saved, cpCap)
    val average = saved.slots.mapNotNull { it.cp }.takeIf { it.isNotEmpty() }?.average()?.toInt()
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        )
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("SUA EQUIPE", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold)
            Text("Selecione, compare e prepare seus três Pokémon PvP.",
                style = MaterialTheme.typography.bodySmall)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = recognitionMode == TeamRecognitionMode.AUTOMATIC,
                    onClick = {
                        store.recognitionMode = TeamRecognitionMode.AUTOMATIC
                        recognitionMode = TeamRecognitionMode.AUTOMATIC
                    },
                    label = { Text("Automático") },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = recognitionMode == TeamRecognitionMode.MANUAL,
                    onClick = {
                        store.recognitionMode = TeamRecognitionMode.MANUAL
                        recognitionMode = TeamRecognitionMode.MANUAL
                        opened = true
                    },
                    label = { Text("Manual") },
                    modifier = Modifier.weight(1f)
                )
            }
            val manualReady = ManualTeamPolicy.canPrepare(saved, cpCap)
            Text(
                when (recognitionMode) {
                    TeamRecognitionMode.AUTOMATIC ->
                        if (automaticRecognitionEnabled) {
                            "Modo automático: o sistema busca seu time pela imagem e pelos PCs."
                        } else {
                            "Reconhecimento automático geral está desligado nas configurações."
                        }
                    TeamRecognitionMode.MANUAL ->
                        if (manualReady) {
                            "✓ Equipe ATUAL selecionada por você (sem confirmação visual). " +
                                (if (automaticRecognitionEnabled) {
                                    "A câmera ainda identifica o Pokémon ativo e as reservas."
                                } else {
                                    "Ative Reconhecimento automático geral para identificar o ativo pela imagem."
                                })
                        } else {
                            "Manual: preencha e salve três vagas válidas para a liga. " +
                                (if (automaticRecognitionEnabled) {
                                    "Enquanto incompleta, o app pode continuar detectando a equipe."
                                } else {
                                    "O reconhecimento visual geral está desligado."
                                })
                        }
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (recognitionMode == TeamRecognitionMode.MANUAL && manualReady) {
                    MaterialTheme.colorScheme.primary
                } else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                saved.slots.forEachIndexed { i, pokemon ->
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (opened) {
                                editingSlot = i
                                draft = saved.slots[i]
                                search = draft.speciesName
                                feedback = ""
                                scanResult = null
                                scanApplied = false
                            } else {
                                opened = true
                                editingSlot = i
                                draft = saved.slots[i]
                                search = draft.speciesName
                                scanResult = null
                                scanApplied = false
                            }
                        }
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${i + 1}", style = MaterialTheme.typography.bodySmall)
                            if (pokemon.speciesId.isNotBlank() && atlas?.isAvailable == true) {
                                Canvas(Modifier.size(64.dp)) {
                                    drawIntoCanvas { canvas ->
                                        atlas?.draw(
                                            canvas.nativeCanvas,
                                            pokemon.speciesId, null,
                                            RectF(0f, 0f, size.width, size.height),
                                            spritePaint
                                        )
                                    }
                                }
                            }
                            Text(if (pokemon.speciesName.isBlank()) "Adicionar" else
                                pokemon.speciesName.take(13),
                                style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            Text(if (pokemon.cp == null) "PC —" else "PC ${pokemon.cp}",
                                style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("$valid/3 prontos • Média PC: ${average ?: "—"}",
                    style = MaterialTheme.typography.bodySmall)
                Text(if (valid == 3) "✓ Cadastro completo" else "Preencher equipe",
                    style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(modifier = Modifier.weight(1f), onClick = { leagueMenu = true }) {
                    Text(if (league == BattleLeagueMode.AUTO) "Liga: Auto"
                        else league.label, style = MaterialTheme.typography.bodySmall)
                }
                DropdownMenu(expanded = leagueMenu, onDismissRequest = { leagueMenu = false }) {
                    BattleLeagueMode.values().forEach { option ->
                        DropdownMenuItem(text = { Text(option.label) }, onClick = {
                            onLeagueChange(option)
                            leagueMenu = false
                        })
                    }
                }
                OutlinedButton(modifier = Modifier.weight(1f),
                    onClick = { opened = !opened }) {
                    Text(if (opened) "Recolher" else "Editar equipe")
                }
            }
            if (league == BattleLeagueMode.AUTO) {
                Text("Modo automático: último limite detectado ${cpCap ?: "desconhecido"}. " +
                    "A confirmação da liga depende da leitura do lobby.",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (opened) {
                HorizontalDivider()
                Text("Pokémon ${editingSlot + 1} — dados manuais ou scanner",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                if (loadError != null) Text(loadError!!, color = MaterialTheme.colorScheme.error)
                Text("Scanner: abra a ficha no Pokémon GO, faça uma captura e selecione-a aqui. " +
                    "Use uma segunda imagem caso os golpes estejam fora da tela. " +
                    "O app não salva a imagem nem altera a equipe sem sua confirmação.",
                    style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(modifier = Modifier.weight(1f),
                        enabled = !scanBusy && catalog != null,
                        onClick = {
                            scanKind = TeamScanKind.DETAIL
                            scanTargetSlot = editingSlot
                            scanPicker.launch("image/*")
                        }) { Text("Escanear ficha") }
                    OutlinedButton(modifier = Modifier.weight(1f),
                        enabled = !scanBusy && catalog != null,
                        onClick = {
                            scanKind = TeamScanKind.APPRAISAL
                            scanTargetSlot = editingSlot
                            scanPicker.launch("image/*")
                        }) { Text("Ler Avaliar") }
                }
                if (scanBusy) Text("Analisando captura...", style = MaterialTheme.typography.bodySmall)
                scanResult?.let { scanned ->
                    Card(colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("Prévia do scan • ${if (scanned.kind == TeamScanKind.DETAIL) "Ficha" else "Avaliação"}",
                                fontWeight = FontWeight.Bold)
                            val e = scanned.evidence
                            Text("Espécie: ${e.speciesName ?: "não identificada"} • " +
                                "PC: ${e.cp ?: "?"} • PS: ${e.maxHp ?: "?"}",
                                style = MaterialTheme.typography.bodySmall)
                            Text("Golpes: ${e.matchedMoveNames.joinToString().ifBlank { "não reconhecidos" }}",
                                style = MaterialTheme.typography.bodySmall)
                            scanned.suggestedIvs?.let { (a, d, h) ->
                                Text("IV sugerido: $a / $d / $h • precisa de confirmação",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            if (scanned.appraisalWarning.isNotBlank()) Text(scanned.appraisalWarning,
                                style = MaterialTheme.typography.bodySmall)
                            e.notes.take(3).forEach {
                                Text("• $it", style = MaterialTheme.typography.bodySmall)
                            }
                            val conflict = e.speciesId != null && draft.speciesId.isNotBlank() &&
                                e.speciesId != draft.speciesId
                            if (conflict) Text("ESPÉCIE DIFERENTE da vaga atual. " +
                                "Limpe a vaga antes de importar outro Pokémon.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(enabled = !conflict && (e.hasData || scanned.suggestedIvs != null),
                                    onClick = {
                                        draft = draft.copy(
                                            speciesId = e.speciesId ?: draft.speciesId,
                                            speciesName = e.speciesName ?: draft.speciesName,
                                            cp = e.cp ?: draft.cp,
                                            maxHp = e.maxHp ?: draft.maxHp,
                                            gender = e.gender ?: draft.gender,
                                            fastMoveId = e.fastMoveId ?: draft.fastMoveId,
                                            chargedMove1Id = e.chargedMove1Id ?: draft.chargedMove1Id,
                                            chargedMove2Id = e.chargedMove2Id ?: draft.chargedMove2Id,
                                            atkIv = scanned.suggestedIvs?.first ?: draft.atkIv,
                                            defIv = scanned.suggestedIvs?.second ?: draft.defIv,
                                            hpIv = scanned.suggestedIvs?.third ?: draft.hpIv
                                        )
                                        if (e.speciesName != null) search = e.speciesName
                                        scanApplied = true
                                        scanResult = null
                                        feedback = "Dados aplicados à edição da vaga. " +
                                            "Revise e toque Salvar Pokémon para confirmar."
                                    }) { Text("Aplicar na vaga") }
                                OutlinedButton(onClick = { scanResult = null }) { Text("Descartar") }
                            }
                        }
                    }
                }
                if (scanHistory.isNotEmpty()) {
                    var historyOpen by remember { mutableStateOf(false) }
                    OutlinedButton(onClick = { historyOpen = !historyOpen }) {
                        Text(if (historyOpen) "Ocultar histórico de scans" else
                            "Histórico de scans (${scanHistory.size})")
                    }
                    if (historyOpen) {
                        scanHistory.take(8).forEach { item ->
                            OutlinedButton(modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    draft = item.pokemon
                                    search = draft.speciesName
                                    scanApplied = false
                                    scanResult = null
                                    feedback = "Histórico carregado na edição; salve para confirmar."
                                }) {
                                Text("${item.pokemon.speciesName} • PC ${item.pokemon.cp ?: "?"}")
                            }
                        }
                    }
                }
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = search,
                    onValueChange = {
                        search = it.take(90)
                        if (search != draft.speciesName) draft = draft.copy(
                            speciesId = "", speciesName = "", fastMoveId = "",
                            chargedMove1Id = "", chargedMove2Id = ""
                        )
                    },
                    label = { Text("Buscar Pokémon / forma") },
                    singleLine = true
                )
                val options = remember(search, catalog) {
                    val term = searchKey(search)
                    if (term.length < 2) emptyList() else catalog?.species.orEmpty()
                        .filter { searchKey(it.speciesName).contains(term) ||
                            searchKey(it.speciesId).contains(term) }.take(6)
                }
                if (options.isNotEmpty() && draft.speciesId.isBlank()) {
                    Text("Escolha uma opção do banco offline:",
                        style = MaterialTheme.typography.bodySmall)
                    options.forEach { found ->
                        OutlinedButton(modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                draft = draft.copy(speciesId = found.speciesId,
                                    speciesName = found.speciesName,
                                    fastMoveId = "", chargedMove1Id = "", chargedMove2Id = "")
                                search = found.speciesName
                            }) {
                            Text("${found.speciesName} · #${found.dex}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (draft.speciesId.isNotBlank()) Text("Selecionado: ${draft.speciesName} " +
                    "(${draft.speciesId})", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("PC", draft.cp?.toString().orEmpty(), Modifier.weight(1f), 5) {
                        draft = draft.copy(cp = it.toIntOrNull())
                    }
                    NumberField("HP máximo", draft.maxHp?.toString().orEmpty(),
                        Modifier.weight(1f), 4) { draft = draft.copy(maxHp = it.toIntOrNull()) }
                }
                Text("IVs (Ataque / Defesa / PS) — opcionais",
                    style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Ataque", draft.atkIv?.toString().orEmpty(),
                        Modifier.weight(1f), 2) { draft = draft.copy(atkIv = it.toIntOrNull()) }
                    NumberField("Defesa", draft.defIv?.toString().orEmpty(),
                        Modifier.weight(1f), 2) { draft = draft.copy(defIv = it.toIntOrNull()) }
                    NumberField("PS", draft.hpIv?.toString().orEmpty(),
                        Modifier.weight(1f), 2) { draft = draft.copy(hpIv = it.toIntOrNull()) }
                }
                ivRanking?.let { rank ->
                    Text("Rank IV desta liga: #${rank.rank}/${rank.total} • " +
                        "${"%.2f".format(rank.percentOfBest)}% do produto de atributos ideal • " +
                        "Nível ${rank.level} • PC potencial ${rank.cp}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                    Text("PC estimado no nível 50: ${rank.maximumCpAtLevel50}. " +
                        "Custo exato de poeira/doces requer dados de fortalecimento adicionais.",
                        style = MaterialTheme.typography.bodySmall)
                }
                Text("Sexo e forma (marcação informativa)", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    FilterChip(selected = draft.gender == "MALE",
                        onClick = { draft = draft.copy(gender =
                            if (draft.gender == "MALE") "UNKNOWN" else "MALE") },
                        label = { Text("♂") })
                    FilterChip(selected = draft.gender == "FEMALE",
                        onClick = { draft = draft.copy(gender =
                            if (draft.gender == "FEMALE") "UNKNOWN" else "FEMALE") },
                        label = { Text("♀") })
                    FilterChip(selected = draft.mega,
                        onClick = { draft = draft.copy(mega = !draft.mega) },
                        label = { Text("Mega") })
                    FilterChip(selected = draft.shadow,
                        onClick = { draft = draft.copy(shadow = !draft.shadow) },
                        label = { Text("Sombroso") })
                }
                val def = catalog?.species?.firstOrNull { it.speciesId == draft.speciesId }
                if (def != null) {
                    val fast = catalog?.moveOptions(def.fastMoves).orEmpty()
                    val charged = catalog?.moveOptions(def.chargedMoves).orEmpty()
                    MoveChooser("Golpe ágil", draft.fastMoveId, fast, catalog,
                        Modifier.fillMaxWidth()) { draft = draft.copy(fastMoveId = it) }
                    MoveChooser("Carregado 1", draft.chargedMove1Id, charged, catalog,
                        Modifier.fillMaxWidth()) { selected ->
                        draft = draft.copy(chargedMove1Id = selected,
                            chargedMove2Id = if (draft.chargedMove2Id == selected) "" else draft.chargedMove2Id)
                    }
                    MoveChooser("Carregado 2", draft.chargedMove2Id,
                        charged.filter { it != draft.chargedMove1Id }, catalog,
                        Modifier.fillMaxWidth()) { draft = draft.copy(chargedMove2Id = it) }
                    val fastDef = catalog?.moves?.get(draft.fastMoveId)
                    listOf(draft.chargedMove1Id, draft.chargedMove2Id)
                        .distinct().filter { it.isNotBlank() }.forEach { id ->
                            val chargedDef = catalog?.moves?.get(id)
                            if (fastDef != null && chargedDef != null) {
                                TeamPvpTools.moveSummary(fastDef, chargedDef)?.let { summary ->
                                    Text("${catalog?.displayMove(id)}: ${summary.chargedCost} energia • " +
                                        "${summary.fastMovesNeeded} golpes ágeis para carregar • " +
                                        "${summary.secondsNeeded}s • DPE ${"%.2f".format(summary.damagePerEnergy)}",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                } else {
                    Text("Selecione uma espécie para listar os golpes compatíveis.",
                        style = MaterialTheme.typography.bodySmall)
                }
                val issue = ManualTeamPolicy.validateSlot(draft, cpCap)
                if (issue != null) Text(issue, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = issue == null,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val updated = ManualTeamPolicy.sanitize(saved.copy(
                                slots = saved.slots.toMutableList().apply { set(editingSlot, draft) }
                            ))
                            store.save(updated)
                            saved = updated
                            draft = updated.slots[editingSlot]
                            feedback = "Pokémon ${editingSlot + 1} salvo."
                            if (scanApplied) {
                                scanHistoryStore.remember(editingSlot, draft)
                                scanHistory = scanHistoryStore.load()
                                scanApplied = false
                            }
                        }) { Text("Salvar Pokémon") }
                    OutlinedButton(modifier = Modifier.weight(1f), onClick = {
                        store.saveSlot(editingSlot, ManualTeamPokemon())
                        saved = store.load()
                        draft = ManualTeamPokemon()
                        search = ""
                        feedback = "Vaga ${editingSlot + 1} apagada."
                        scanResult = null
                        scanApplied = false
                    }) { Text("Limpar vaga") }
                }
                if (feedback.isNotBlank()) Text(feedback,
                    style = MaterialTheme.typography.bodySmall)
                Text("As mudanças nesta vaga só ficam salvas ao tocar Salvar Pokémon. " +
                    "Não são enviadas ao jogo nem substituem automaticamente a detecção.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Rank IV calculado com o banco offline, não é ranking do meta. " +
                    "Mega/Sombroso e HP em batalha continuam sujeitos à validação. " +
                    "Leitura automática dos IVs da avaliação exige calibração visual.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    modifier: Modifier,
    maxDigits: Int,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        modifier = modifier,
        value = value,
        onValueChange = { raw ->
            if (raw.length <= maxDigits && raw.all(Char::isDigit)) onChange(raw)
        },
        label = { Text(label) },
        singleLine = true
    )
}

@Composable
private fun MoveChooser(
    title: String,
    selected: String,
    candidates: List<String>,
    catalog: TeamCatalog?,
    modifier: Modifier,
    onSelect: (String) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { open = true },
            enabled = candidates.isNotEmpty()) {
            Text(if (selected.isBlank()) "Selecionar golpe" else
                (catalog?.displayMove(selected) ?: selected))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Não informado") }, onClick = {
                onSelect(""); open = false
            })
            candidates.forEach { id ->
                DropdownMenuItem(
                    text = { Text(catalog?.displayMove(id) ?: id) },
                    onClick = { onSelect(id); open = false }
                )
            }
        }
    }
}
