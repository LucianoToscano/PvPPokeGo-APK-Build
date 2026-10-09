package com.lucianotoscano.pvppokego.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lucianotoscano.pvppokego.data.CatchDateDisplay
import com.lucianotoscano.pvppokego.data.GameMaster
import com.lucianotoscano.pvppokego.data.ManualTeamPokemon
import com.lucianotoscano.pvppokego.data.PokemonDef
import com.lucianotoscano.pvppokego.data.PokemonNameStyle
import com.lucianotoscano.pvppokego.data.TeamSetupRepository
import com.lucianotoscano.pvppokego.data.TrainerFaction
import com.lucianotoscano.pvppokego.data.TrainerPreferencesRepository
import com.lucianotoscano.pvppokego.data.TrainerTools
import com.lucianotoscano.pvppokego.engine.TypeChart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.text.Normalizer

private enum class TrainerSection(val title: String) {
    GENERAL("Gerais"),
    PVP_IV("PvP IV"),
    NAMES("Gerador de Nomes"),
    CAPTURE_DATE("Formato da Data Pego"),
    BATTLE_SIM("Simulador de Batalha")
}

private fun trainerSearchKey(value: String): String =
    Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").trim()

/**
 * Inspired by the supplied settings screenshot, not a replica of any other application's
 * backend. All available tools are local; full PvP IV rank and turn simulation are roadmap.
 */
@Composable
fun TrainerSettingsPanel(
    trainerStore: TrainerPreferencesRepository,
    teamStore: TeamSetupRepository
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var section by remember { mutableStateOf<TrainerSection?>(null) }
    var level by remember { mutableStateOf(trainerStore.trainerLevel?.toString().orEmpty()) }
    var faction by remember { mutableStateOf(trainerStore.faction) }
    var levelMessage by remember { mutableStateOf("") }
    var nameStyle by remember { mutableStateOf(trainerStore.nameStyle) }
    var dateStyle by remember { mutableStateOf(trainerStore.dateDisplay) }
    var roster by remember { mutableStateOf(teamStore.load()) }
    var nameSlot by remember { mutableStateOf(0) }
    var nameMessage by remember { mutableStateOf("") }
    var opponentSearch by remember { mutableStateOf("") }
    var opponentId by remember { mutableStateOf("") }
    var activePreviewSlot by remember { mutableStateOf(0) }
    var catalog by remember { mutableStateOf<List<PokemonDef>?>(null) }
    var catalogError by remember { mutableStateOf(false) }

    LaunchedEffect(section) {
        if (section == TrainerSection.BATTLE_SIM && catalog == null && !catalogError) {
            val fetched = withContext(Dispatchers.IO) {
                runCatching {
                    context.assets.open("gamemaster.json").bufferedReader().use { input ->
                        Json { ignoreUnknownKeys = true; coerceInputValues = true }
                            .decodeFromString<GameMaster>(input.readText()).pokemon
                            .filter { it.speciesId.isNotBlank() }
                            .distinctBy { it.speciesId }
                    }
                }
            }
            fetched.onSuccess { catalog = it }
                .onFailure { catalogError = true }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("CONFIGURAÇÕES DO TREINADOR",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Nível: ${trainerStore.trainerLevel ?: "—"}  •  Equipe: ${faction.label}",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                expanded = !expanded
                if (expanded) roster = teamStore.load()
                if (!expanded) section = null
            }, modifier = Modifier.fillMaxWidth()) {
                Text(if (expanded) "Ocultar opções" else "Abrir configurações")
            }
            if (expanded) {
                TrainerSection.values().forEach { item ->
                    HorizontalDivider()
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            section = if (section == item) null else item
                            roster = teamStore.load()
                            nameMessage = ""
                        }
                    ) {
                        Text(item.title + if (section == item) "  ▲" else "  ›")
                    }
                    if (section == item) {
                        when (item) {
                            TrainerSection.GENERAL -> {
                                Text("Seu perfil informado manualmente",
                                    style = MaterialTheme.typography.bodySmall)
                                OutlinedTextField(
                                    value = level,
                                    onValueChange = { next ->
                                        if (next.length <= 2 && next.all(Char::isDigit)) {
                                            level = next
                                            levelMessage = ""
                                        }
                                    },
                                    label = { Text("Nível do Treinador (1–80)") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                Button(
                                    enabled = level.isBlank() || (level.toIntOrNull()?.let { it in 1..80 } == true),
                                    onClick = {
                                        trainerStore.trainerLevel = level.toIntOrNull()
                                        levelMessage = "Nível salvo localmente."
                                    }
                                ) { Text("Salvar nível") }
                                if (levelMessage.isNotBlank()) Text(levelMessage,
                                    style = MaterialTheme.typography.bodySmall)
                                Text("Equipe de treinador", style = MaterialTheme.typography.bodySmall)
                                listOf(
                                    listOf(TrainerFaction.UNSET, TrainerFaction.MYSTIC),
                                    listOf(TrainerFaction.VALOR, TrainerFaction.INSTINCT)
                                ).forEach { options ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth()) {
                                        options.forEach { choice ->
                                            OutlinedButton(
                                                modifier = Modifier.weight(1f),
                                                onClick = {
                                                    trainerStore.faction = choice
                                                    faction = choice
                                                }
                                            ) { Text(if (faction == choice) "✓ ${choice.label}"
                                                else choice.label,
                                                style = MaterialTheme.typography.bodySmall) }
                                        }
                                    }
                                }
                                Text("Estes dados não fazem login nem verificam sua conta Pokémon GO.",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            TrainerSection.PVP_IV -> {
                                Text("IVs dos Pokémon cadastrados em SUA EQUIPE",
                                    style = MaterialTheme.typography.bodySmall)
                                roster.slots.forEachIndexed { index, pokemon ->
                                    Text(
                                        "${index + 1}. ${pokemon.speciesName.ifBlank { "Vaga vazia" }}  " +
                                            "IVs ${TrainerTools.ivSummary(pokemon) ?: "não informados"}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Text(
                                    "IVs são valores cadastrados, não classificação PvP. " +
                                        "O cálculo de rank por espécie, nível e limite da liga " +
                                        "será integrado depois com validação do algoritmo.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            TrainerSection.NAMES -> {
                                Text("Crie um nome para copiar, sem alterar Pokémon GO.",
                                    style = MaterialTheme.typography.bodySmall)
                                roster.slots.forEachIndexed { idx, pokemon ->
                                    OutlinedButton(
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = { nameSlot = idx; nameMessage = "" }
                                    ) {
                                        Text((if (idx == nameSlot) "✓ " else "") +
                                            "${idx + 1}. ${pokemon.speciesName.ifBlank { "Vaga vazia" }}")
                                    }
                                }
                                PokemonNameStyle.values().forEach { choice ->
                                    OutlinedButton(
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = {
                                            trainerStore.nameStyle = choice
                                            nameStyle = choice
                                            nameMessage = ""
                                        }
                                    ) { Text((if (choice == nameStyle) "✓ " else "") + choice.label) }
                                }
                                val generated = TrainerTools.generatedName(roster.slots[nameSlot], nameStyle)
                                Text("Prévia: ${generated ?: "Selecione um Pokémon em SUA EQUIPE."}",
                                    style = MaterialTheme.typography.bodyMedium)
                                Button(
                                    enabled = generated != null,
                                    onClick = {
                                        if (generated != null) {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                                as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newPlainText(
                                                "Nome PvPPokeGo", generated))
                                            nameMessage = "Nome copiado."
                                        }
                                    }
                                ) { Text("Copiar nome") }
                                if (nameMessage.isNotBlank()) Text(nameMessage,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            TrainerSection.CAPTURE_DATE -> {
                                Text("Escolha como exibir uma data de captura.",
                                    style = MaterialTheme.typography.bodySmall)
                                CatchDateDisplay.values().forEach { option ->
                                    OutlinedButton(
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = {
                                            trainerStore.dateDisplay = option
                                            dateStyle = option
                                        }
                                    ) { Text((if (option == dateStyle) "✓ " else "") + option.label) }
                                }
                                Text("Exemplo (data de hoje): ${TrainerTools.formatDate(
                                    System.currentTimeMillis(), dateStyle
                                )}", style = MaterialTheme.typography.bodyMedium)
                                Text("Não lemos a data real de captura do Pokémon GO. " +
                                    "O formato será usado quando houver datas registradas.",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            TrainerSection.BATTLE_SIM -> {
                                Text("Prévia rápida de vantagens por tipo (offline).",
                                    style = MaterialTheme.typography.bodySmall)
                                roster.slots.forEachIndexed { idx, pokemon ->
                                    OutlinedButton(
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = { activePreviewSlot = idx }
                                    ) { Text((if (activePreviewSlot == idx) "✓ " else "") +
                                        "${idx + 1}. ${pokemon.speciesName.ifBlank { "Vaga vazia" }}") }
                                }
                                OutlinedTextField(
                                    modifier = Modifier.fillMaxWidth(),
                                    value = opponentSearch,
                                    onValueChange = { opponentSearch = it.take(90); opponentId = "" },
                                    label = { Text("Buscar Pokémon adversário") },
                                    singleLine = true
                                )
                                val matching = remember(catalog, opponentSearch) {
                                    val term = trainerSearchKey(opponentSearch)
                                    if (term.length < 2) emptyList() else catalog.orEmpty()
                                        .filter { trainerSearchKey(it.speciesName).contains(term) ||
                                            trainerSearchKey(it.speciesId).contains(term) }.take(5)
                                }
                                if (opponentId.isBlank()) matching.forEach { enemy ->
                                    OutlinedButton(
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = {
                                            opponentId = enemy.speciesId
                                            opponentSearch = enemy.speciesName
                                        }
                                    ) { Text(enemy.speciesName) }
                                }
                                if (catalogError) {
                                    Text("Banco local indisponível para a prévia.",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                val allyId = roster.slots[activePreviewSlot].speciesId
                                val ally = catalog?.firstOrNull { it.speciesId == allyId }
                                val enemy = catalog?.firstOrNull { it.speciesId == opponentId }
                                if (ally != null && enemy != null) {
                                    val score = TypeChart.matchupScore(ally.types, enemy.types)
                                    val label = when {
                                        score >= 1.20 -> "Vantagem potencial de tipos"
                                        score <= 0.83 -> "Desvantagem potencial de tipos"
                                        else -> "Tipos relativamente equilibrados"
                                    }
                                    Text("${ally.speciesName} × ${enemy.speciesName}",
                                        style = MaterialTheme.typography.titleSmall)
                                    Text("$label (indicador relativo: ${"%.2f".format(
                                        java.util.Locale.US, score
                                    )}).", style = MaterialTheme.typography.bodySmall)
                                } else {
                                    Text("Cadastre um aliado e selecione um adversário do banco local.",
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                Text("Esta é uma prévia de tipos, NÃO um vencedor previsto. " +
                                    "Simulação por golpes, turnos, PC, IV, HP, energia e escudos " +
                                    "ainda está pendente.",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
