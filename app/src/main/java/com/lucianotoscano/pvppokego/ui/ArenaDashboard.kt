package com.lucianotoscano.pvppokego.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lucianotoscano.pvppokego.arena.ArenaProgress
import com.lucianotoscano.pvppokego.data.BattleHistoryEntry

/**
 * Initial Arena surface on the existing home screen.
 * Local progress is real; global leaderboards, validated brackets and prize redemptions
 * are intentionally not represented as live services until a backend exists.
 */
@Composable
fun ArenaDashboard(history: List<BattleHistoryEntry>) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("pvppokego_arena_profile", 0) }
    var nickname by remember { mutableStateOf(prefs.getString("nickname", "Treinador") ?: "Treinador") }
    var draft by remember { mutableStateOf(nickname) }
    var editing by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val snapshot = remember(history) { ArenaProgress.fromHistory(history) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Text("🏆 PvPPokeGo Arena", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold)
            Text(
                "Progresso, medalhas, ranking e campeonatos — fase inicial local.",
                style = MaterialTheme.typography.bodySmall
            )
            Text("Seu progresso: " + snapshot.tier + " • " + snapshot.activityXp + " XP",
                style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (expanded) "Recolher Arena" else "Abrir minha Arena") }

            if (expanded) {
                HorizontalDivider()
                Text("Perfil local: " + nickname, style = MaterialTheme.typography.titleMedium)
                if (editing) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it.take(24) },
                        label = { Text("Apelido local (3 a 24 caracteres)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(onClick = {
                        val clean = draft.trim()
                        if (clean.length in 3..24) {
                            prefs.edit().putString("nickname", clean).apply()
                            nickname = clean
                            editing = false
                        }
                    }, enabled = draft.trim().length in 3..24) { Text("Salvar apelido") }
                } else {
                    OutlinedButton(onClick = { draft = nickname; editing = true }) {
                        Text("Editar apelido")
                    }
                }
                Text("Faixa de atividade: " + snapshot.tier +
                    " • " + snapshot.activityXp + " XP",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("XP local não é pontuação do ranking competitivo nem dá direito a prêmios.",
                    style = MaterialTheme.typography.bodySmall)
                Row(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Vitórias: " + snapshot.victories)
                    Text("Derrotas: " + snapshot.defeats)
                    Text("Empates: " + snapshot.draws)
                }
                Text("Maior sequência: " + snapshot.bestStreak +
                    " • Sem resultado: " + snapshot.unresolved,
                    style = MaterialTheme.typography.bodySmall)
                Text("Somente " + snapshot.recorded +
                    " partidas do histórico disponível (limite atual: 50).",
                    style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                Text("🎖 Medalhas", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                snapshot.medals.forEach { medal ->
                    Row(modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text((if (medal.earned) "🏅 " else "◯ ") + medal.name,
                            modifier = Modifier.weight(1f))
                        Text(medal.current.toString() + "/" + medal.target,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (!medal.earned) {
                        LinearProgressIndicator(
                            progress = { (medal.current.toFloat() / medal.target)
                                .coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                HorizontalDivider()
                Text("📊 Ranking PvPPokeGo", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Text(
                    "Em preparação: ranking por Grande, Ultra e Mestra, temporada, país e " +
                        "classificação global. Apenas resultados confirmados pelos dois " +
                        "jogadores poderão ser candidatos a pontuar. " +
                        "Ainda não há sincronização online nem posições reais.",
                    style = MaterialTheme.typography.bodySmall
                )
                HorizontalDivider()
                Text("⚔ Campeonatos", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Text(
                    "Chaves eliminatórias, inscrições, temporadas e disputas estão " +
                        "planejadas. O motor inicial já prevê confrontos por sementes " +
                        "e concordância de resultados; partidas e inscrições online " +
                        "ainda não estão disponíveis.",
                    style = MaterialTheme.typography.bodySmall
                )
                HorizontalDivider()
                Text("🎁 Recompensas", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Text(
                    "Catálogo planejado: molduras, títulos, troféus e temas exclusivos " +
                        "para jogadores elegíveis. Sem resgate ou premiação ativa. " +
                        "PokéCoins, passes e produtos reais dependerão de parceria " +
                        "autorizada, regras públicas e revisão jurídica.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "A Arena não interfere nos controles do Pokémon GO nem publica " +
                        "seu histórico automaticamente.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
