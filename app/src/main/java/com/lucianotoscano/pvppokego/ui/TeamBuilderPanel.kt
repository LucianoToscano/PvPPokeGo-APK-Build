package com.lucianotoscano.pvppokego.ui

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
import com.lucianotoscano.pvppokego.data.TeamSetupRepository
import com.lucianotoscano.pvppokego.data.TeamRecognitionMode
import com.lucianotoscano.pvppokego.overlay.PokemonIconAtlas
import android.graphics.Paint
import android.graphics.RectF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.text.Normalizer

private data class TeamCatalog(
    val species: List<PokemonDef>,
    val moves: Map<String, MoveDef>,
    val namesPtBr: Map<String, String>
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
                namesPtBr = portuguese
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
                            "✓ Este time está selecionado como ATUAL. " +
                                "A imagem identifica quem está em campo e a posição das reservas."
                        } else {
                            "Manual selecionado: preencha e salve as três vagas dentro da liga. " +
                                "Até lá, a leitura automática continua disponível."
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
                            } else {
                                opened = true
                                editingSlot = i
                                draft = saved.slots[i]
                                search = draft.speciesName
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
                Text("Pokémon ${editingSlot + 1} — dados manuais",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                if (loadError != null) Text(loadError!!, color = MaterialTheme.colorScheme.error)
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
                        }) { Text("Salvar Pokémon") }
                    OutlinedButton(modifier = Modifier.weight(1f), onClick = {
                        store.saveSlot(editingSlot, ManualTeamPokemon())
                        saved = store.load()
                        draft = ManualTeamPokemon()
                        search = ""
                        feedback = "Vaga ${editingSlot + 1} apagada."
                    }) { Text("Limpar vaga") }
                }
                if (feedback.isNotBlank()) Text(feedback,
                    style = MaterialTheme.typography.bodySmall)
                Text("As mudanças nesta vaga só ficam salvas ao tocar Salvar Pokémon. " +
                    "Não são enviadas ao jogo nem substituem automaticamente a detecção.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Rank competitivo, Mega/Sombroso permitidos e HP real ainda " +
                    "dependem de validação. O HP máximo informado é opcional.",
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
