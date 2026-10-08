package com.lucianotoscano.pvppokego.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
data class BattleHistoryEvent(
    val elapsedMs: Long,
    val actor: String,
    val category: String,
    val moveName: String,
    val count: Int = 1,
    val pokemon: String? = null,
    val confidence: String? = null,
    val source: String? = null,
    val reason: String? = null,
    val details: Map<String, String> = emptyMap()
)

@Serializable
data class BattleHistoryEntry(
    val id: Long,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long,
    val leagueCp: Int,
    val playerName: String? = null,
    val playerCp: Int? = null,
    val opponentName: String? = null,
    val opponentCp: Int? = null,
    val appVersion: String = "desconhecida",
    val dataVersion: String? = null,
    val startedMidBattle: Boolean = false,
    val endReason: String? = null,
    val result: String? = null,
    val events: List<BattleHistoryEvent> = emptyList()
)

data class BattleStrategyProfile(
    val opponentName: String,
    val battles: Int,
    val chargedSamples: Int,
    val mostCommonChargedMove: String? = null,
    val mostCommonChargedRate: Float = 0f,
    val firstChargedMove: String? = null,
    val firstChargedRate: Float = 0f,
    val shieldEvents: Int = 0
)

class BattleHistoryRepository(context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun loadRawHistory(): List<BattleHistoryEntry> = runCatching {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        json.decodeFromString<List<BattleHistoryEntry>>(raw)
    }.getOrDefault(emptyList())

    fun loadHistory(): List<BattleHistoryEntry> {
        val raw = loadRawHistory()
        val repaired = BattleHistorySegmentMatcher.repair(raw)
        if (repaired != raw) {
            prefs.edit().putString(KEY_HISTORY, json.encodeToString(repaired)).apply()
        }
        return repaired
    }

    /**
     * Local-only pattern profile derived from the user's own prior battles.
     * It never uploads battle data and is intentionally advisory rather than authoritative.
     */
    fun strategyProfile(opponentName: String?): BattleStrategyProfile? {
        val target = opponentName?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val matchingBattles = loadHistory().filter { battle ->
            battle.opponentName.equals(target, ignoreCase = true) ||
                battle.events.any {
                    it.actor == "INIMIGO" &&
                        it.pokemon.equals(target, ignoreCase = true)
                }
        }
        if (matchingBattles.isEmpty()) return null

        val charged = matchingBattles.flatMap { battle ->
            battle.events.filter {
                it.actor == "INIMIGO" &&
                    it.category == "CARREGADO" &&
                    (it.pokemon == null || it.pokemon.equals(target, ignoreCase = true))
            }
        }
        val chargedCounts = charged.groupingBy { it.moveName }.eachCount()
        val topCharged = chargedCounts.maxByOrNull { it.value }

        val firstChargedPerBattle = matchingBattles.mapNotNull { battle ->
            battle.events
                .filter {
                    it.actor == "INIMIGO" &&
                        it.category == "CARREGADO" &&
                        (it.pokemon == null || it.pokemon.equals(target, ignoreCase = true))
                }
                .minByOrNull { it.elapsedMs }
                ?.moveName
        }
        val firstCounts = firstChargedPerBattle.groupingBy { it }.eachCount()
        val topFirst = firstCounts.maxByOrNull { it.value }
        val shields = matchingBattles.sumOf { battle ->
            battle.events.count {
                it.actor == "INIMIGO" &&
                    it.category == "ESCUDO" &&
                    (it.pokemon == null || it.pokemon.equals(target, ignoreCase = true))
            }
        }

        return BattleStrategyProfile(
            opponentName = target,
            battles = matchingBattles.size,
            chargedSamples = charged.size,
            mostCommonChargedMove = topCharged?.key,
            mostCommonChargedRate = if (charged.isNotEmpty()) {
                topCharged?.value?.toFloat()?.div(charged.size.toFloat()) ?: 0f
            } else 0f,
            firstChargedMove = topFirst?.key,
            firstChargedRate = if (firstChargedPerBattle.isNotEmpty()) {
                topFirst?.value?.toFloat()?.div(firstChargedPerBattle.size.toFloat()) ?: 0f
            } else 0f,
            shieldEvents = shields
        )
    }

    fun exportJson(): String = json.encodeToString(loadHistory())

    fun exportJson(entries: List<BattleHistoryEntry>): String = json.encodeToString(entries)

    fun battleById(id: Long): BattleHistoryEntry? = loadHistory().firstOrNull { it.id == id }

    /**
     * Human-readable export designed to be uploaded to ChatGPT or shared with a coach.
     * Technical fields are preserved, but the layout separates player, enemy, assistant
     * and diagnostic events so the file is useful without opening the app.
     */
    fun exportAnalysisText(entries: List<BattleHistoryEntry> = loadHistory()): String = buildString {
        appendLine("PvPPokeGo — histórico para análise")
        appendLine("Formato: 1")
        appendLine("Batalhas: ${entries.size}")
        appendLine()
        entries.forEachIndexed { index, battle ->
            val duration = ((battle.endedAtEpochMs - battle.startedAtEpochMs).coerceAtLeast(0L) / 1000.0)
            val date = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
                .format(Date(battle.startedAtEpochMs))
            val league = when (battle.leagueCp) {
                2500 -> "Liga Ultra"
                10000 -> "Liga Mestra"
                else -> "Liga Grande"
            }
            val ownFast = battle.events.sumOf { if (it.actor == "VOCÊ" && it.category == "RÁPIDO") it.count else 0 }
            val enemyFast = battle.events.sumOf { if (it.actor == "INIMIGO" && it.category == "RÁPIDO") it.count else 0 }
            val ownCharged = battle.events.count { it.actor == "VOCÊ" && it.category == "CARREGADO" }
            val enemyCharged = battle.events.count { it.actor == "INIMIGO" && it.category == "CARREGADO" }
            val decisions = battle.events.count { it.category == "DECISÃO" }

            appendLine("================================================================")
            appendLine("BATALHA ${index + 1}  |  ID ${battle.id}")
            appendLine("$date  |  $league  |  ${"%.1f".format(Locale.US, duration)}s")
            appendLine("VOCÊ: ${battle.playerName ?: "?"} PC ${battle.playerCp ?: "?"}")
            appendLine("INIMIGO: ${battle.opponentName ?: "?"} PC ${battle.opponentCp ?: "?"}")
            appendLine("APP: ${battle.appVersion} | BASE: ${battle.dataVersion ?: "desconhecida"}")
            appendLine("CAPTURA: ${if (battle.startedMidBattle) "iniciada no meio da luta" else "início normal/indeterminado"}")
            appendLine("ENCERRAMENTO: ${battle.endReason ?: "não informado"}")
            battle.result?.let { appendLine("RESULTADO: $it") }
            appendLine("RESUMO: rápidos você=$ownFast, inimigo=$enemyFast | carregados você=$ownCharged, inimigo=$enemyCharged | decisões=$decisions")
            appendLine()

            fun section(title: String, predicate: (BattleHistoryEvent) -> Boolean) {
                val selected = battle.events.filter(predicate)
                appendLine("## $title (${selected.size})")
                if (selected.isEmpty()) {
                    appendLine("- nenhum")
                } else {
                    selected.forEach { event ->
                        val sec = event.elapsedMs / 1000.0
                        val count = if (event.count > 1) " x${event.count}" else ""
                        append("[+${"%.1f".format(Locale.US, sec)}s] ")
                        append(event.category)
                        append(" • ")
                        append(event.moveName)
                        append(count)
                        event.pokemon?.let { append(" • Pokémon: ").append(it) }
                        event.confidence?.let { append(" • confiança: ").append(it) }
                        event.source?.let { append(" • fonte: ").append(it) }
                        appendLine()
                        if (event.details.isNotEmpty()) {
                            appendLine("  Estado: " + event.details.entries.joinToString(" • ") { "${it.key}=${it.value}" })
                        }
                        event.reason?.takeIf(String::isNotBlank)?.let { appendLine("  Motivo: $it") }
                    }
                }
                appendLine()
            }

            section("VOCÊ") { it.actor == "VOCÊ" }
            section("INIMIGO") { it.actor == "INIMIGO" }
            section("ASSISTENTE") { it.actor == "ASSISTENTE" || it.category == "DECISÃO" }
            section("PARTIDA / TROCAS / ESTADO") {
                it.actor == "PARTIDA" || it.category == "TROCA" || it.category == "ESTADO"
            }
            section("DIAGNÓSTICO TÉCNICO") { it.actor == "APP" || it.category == "DIAGNÓSTICO" }
        }
    }

    fun exportCsv(entries: List<BattleHistoryEntry> = loadHistory()): String {
        fun csv(value: Any?): String {
            val raw = value?.toString().orEmpty()
            return "\"" + raw.replace("\"", "\"\"") + "\""
        }
        return buildString {
            appendLine("battle_id,started_at_ms,league_cp,app_version,data_version,started_mid_battle,end_reason,result,player,player_cp,opponent,opponent_cp,elapsed_ms,actor,category,move,count,pokemon,confidence,source,reason,details")
            entries.forEach { battle ->
                battle.events.forEach { event ->
                    appendLine(
                        listOf(
                            battle.id, battle.startedAtEpochMs, battle.leagueCp,
                            battle.appVersion, battle.dataVersion, battle.startedMidBattle, battle.endReason, battle.result,
                            battle.playerName, battle.playerCp, battle.opponentName, battle.opponentCp,
                            event.elapsedMs, event.actor, event.category, event.moveName, event.count,
                            event.pokemon, event.confidence, event.source, event.reason,
                            event.details.entries.joinToString(";") { "${it.key}=${it.value}" }
                        ).joinToString(",") { csv(it) }
                    )
                }
            }
        }
    }

    fun append(entry: BattleHistoryEntry) {
        val existing = BattleHistorySegmentMatcher.repair(loadRawHistory())
        val previous = existing.firstOrNull()
        val next = if (previous != null && BattleHistorySegmentMatcher.shouldMerge(previous, entry)) {
            listOf(BattleHistorySegmentMatcher.merge(previous, entry)) + existing.drop(1)
        } else {
            listOf(entry) + existing
        }
        prefs.edit().putString(KEY_HISTORY, json.encodeToString(next.take(MAX_BATTLES))).apply()
    }

    fun saveActiveCheckpoint(entry: BattleHistoryEntry) {
        prefs.edit().putString(KEY_ACTIVE_CHECKPOINT, json.encodeToString(entry)).apply()
    }

    fun loadActiveCheckpoint(): BattleHistoryEntry? = runCatching {
        prefs.getString(KEY_ACTIVE_CHECKPOINT, null)
            ?.let { json.decodeFromString<BattleHistoryEntry>(it) }
    }.getOrNull()

    fun clearActiveCheckpoint() {
        prefs.edit().remove(KEY_ACTIVE_CHECKPOINT).apply()
    }

    fun clear() = prefs.edit()
        .remove(KEY_HISTORY)
        .remove(KEY_ACTIVE_CHECKPOINT)
        .apply()

    companion object {
        private const val FILE = "pvppokego_battle_history"
        private const val KEY_HISTORY = "history_json"
        private const val KEY_ACTIVE_CHECKPOINT = "active_checkpoint_json"
        private const val MAX_BATTLES = 50
    }
}

class BattleHistoryRecorder(private val repository: BattleHistoryRepository) {
    private data class Active(
        val id: Long,
        val startedAt: Long,
        var leagueCp: Int,
        var playerName: String? = null,
        var playerCp: Int? = null,
        var opponentName: String? = null,
        var opponentCp: Int? = null,
        var appVersion: String = "desconhecida",
        var dataVersion: String? = null,
        var startedMidBattle: Boolean = false,
        var result: String? = null,
        var lastEvidenceAtMs: Long = startedAt,
        val events: MutableList<BattleHistoryEvent> = mutableListOf()
    )

    private var active: Active? = null
    private var appVersion: String = "desconhecida"
    private var dataVersion: String? = null
    private var lastCheckpointAtMs: Long = 0L

    init {
        val now = System.currentTimeMillis()
        val checkpoint = repository.loadActiveCheckpoint()
        if (
            checkpoint != null &&
            now - checkpoint.endedAtEpochMs in 0..RECOVERY_MAX_AGE_MS
        ) {
            active = Active(
                id = checkpoint.id,
                startedAt = checkpoint.startedAtEpochMs,
                leagueCp = checkpoint.leagueCp,
                playerName = checkpoint.playerName,
                playerCp = checkpoint.playerCp,
                opponentName = checkpoint.opponentName,
                opponentCp = checkpoint.opponentCp,
                appVersion = checkpoint.appVersion,
                dataVersion = checkpoint.dataVersion,
                startedMidBattle = true,
                result = checkpoint.result,
                lastEvidenceAtMs = checkpoint.endedAtEpochMs,
                events = checkpoint.events.toMutableList()
            )
            appVersion = checkpoint.appVersion
            dataVersion = checkpoint.dataVersion
            lastCheckpointAtMs = now
        } else {
            repository.clearActiveCheckpoint()
        }
    }

    fun setMetadata(appVersion: String, dataVersion: String?) {
        this.appVersion = appVersion
        this.dataVersion = dataVersion
        active?.let {
            it.appVersion = appVersion
            it.dataVersion = dataVersion
        }
    }

    fun hasActiveSession(): Boolean = active != null

    fun activeElapsedMs(nowMs: Long = System.currentTimeMillis()): Long? =
        active?.let { (nowMs - it.startedAt).coerceAtLeast(0L) }

    fun markStartedMidBattle() {
        active?.startedMidBattle = true
    }

    fun setResult(result: String?) {
        if (!result.isNullOrBlank()) active?.result = result
    }

    fun setBattleActive(isActive: Boolean, leagueCp: Int, nowMs: Long = System.currentTimeMillis()) {
        if (isActive) {
            if (active == null) {
                active = Active(
                    id = nowMs,
                    startedAt = nowMs,
                    leagueCp = leagueCp,
                    appVersion = appVersion,
                    dataVersion = dataVersion,
                    lastEvidenceAtMs = nowMs
                )
                addEvent(
                    actor = "PARTIDA",
                    category = "INÍCIO",
                    moveName = "Partida detectada",
                    count = 1,
                    nowMs = nowMs,
                    pokemon = null,
                    confidence = null,
                    source = "session",
                    reason = null
                )
            } else {
                active?.leagueCp = leagueCp
                active?.lastEvidenceAtMs = nowMs
            }
        } else {
            val a = active ?: return
            if (nowMs - a.lastEvidenceAtMs >= SESSION_INACTIVITY_GRACE_MS) {
                finish(nowMs, reason = "Sem evidência de batalha por 28s")
            }
        }
    }

    fun observeStableState(
        playerName: String?,
        playerCp: Int?,
        opponentName: String?,
        opponentCp: Int?,
        leagueCp: Int,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val a = active ?: return
        a.leagueCp = leagueCp
        playerName?.let { a.playerName = it }
        playerCp?.let { a.playerCp = it }
        opponentName?.let { a.opponentName = it }
        opponentCp?.let { a.opponentCp = it }
        a.lastEvidenceAtMs = nowMs
    }

    fun observeDetection(detection: BattleDetection, leagueCp: Int, nowMs: Long = System.currentTimeMillis()) {
        val a = active ?: return
        a.leagueCp = leagueCp
        detection.player?.let {
            a.playerName = it.name
            if (it.cp != null) a.playerCp = it.cp
        }
        detection.opponent?.let {
            a.opponentName = it.name
            if (it.cp != null) a.opponentCp = it.cp
        }
    }

    fun recordFast(
        actor: String,
        moveName: String,
        count: Int,
        nowMs: Long = System.currentTimeMillis(),
        pokemon: String? = null,
        confidence: String? = null,
        source: String? = null,
        damageFraction: Float? = null
    ) {
        if (count <= 0) return
        val details = buildMap {
            damageFraction?.takeIf { it > 0f }?.let { fraction ->
                val totalPercent = fraction.coerceIn(0f, 1f) * 100f
                val perHitPercent = totalPercent / count.coerceAtLeast(1)
                put("damagePercent", "%.1f".format(Locale.US, totalPercent))
                put("damagePerHitPercent", "%.1f".format(Locale.US, perHitPercent))
                put("damagePoints", (fraction.coerceIn(0f, 1f) * 1000f).toInt().toString())
            }
        }
        addEvent(actor, "RÁPIDO", moveName, count, nowMs, pokemon, confidence, source, null, details)
    }

    fun recordShield(
        actor: String,
        againstMove: String,
        remaining: Int?,
        nowMs: Long = System.currentTimeMillis(),
        pokemon: String? = null,
        confidence: String = "INFERIDO_ALTO",
        source: String = "barra de HP estável pós-Charged"
    ) {
        addEvent(
            actor = actor,
            category = "ESCUDO",
            moveName = "Escudo contra $againstMove",
            count = 1,
            nowMs = nowMs,
            pokemon = pokemon,
            confidence = confidence,
            source = source,
            reason = null,
            details = buildMap {
                remaining?.let { put("shields", it.coerceIn(0, 2).toString()) }
                if (remaining == null) put("shields", "desconhecido")
            }
        )
    }

    fun recordDamageObservation(
        actor: String,
        moveName: String,
        damagePercent: Float,
        damagePoints: Int,
        learnedSamples: Int,
        nowMs: Long = System.currentTimeMillis(),
        attackerPokemon: String? = null,
        targetPokemon: String? = null,
        source: String = "HP"
    ) {
        addEvent(
            actor = actor,
            category = "DANO",
            moveName = moveName,
            count = 1,
            nowMs = nowMs,
            pokemon = attackerPokemon,
            confidence = if (learnedSamples >= 5) "CONFIRMADO" else "ESTIMADO",
            source = source,
            reason = null,
            details = buildMap {
                put("damagePercent", "%.1f".format(Locale.US, damagePercent.coerceAtLeast(0f)))
                put("damagePoints", damagePoints.coerceAtLeast(0).toString())
                put("samples", learnedSamples.coerceAtLeast(1).toString())
                targetPokemon?.let { put("target", it) }
            }
        )
    }

    fun recordCharged(
        actor: String,
        moveName: String,
        nowMs: Long = System.currentTimeMillis(),
        pokemon: String? = null,
        confidence: String? = null,
        source: String? = null
    ) {
        addEvent(actor, "CARREGADO", moveName, 1, nowMs, pokemon, confidence, source, null)
    }

    fun recordDecision(title: String, detail: String, nowMs: Long = System.currentTimeMillis()) {
        addEvent("ASSISTENTE", "DECISÃO", title, 1, nowMs, null, null, "BattleAssist", detail)
    }

    fun recordDiagnostic(label: String, detail: String, nowMs: Long = System.currentTimeMillis()) {
        addEvent("APP", "DIAGNÓSTICO", label, 1, nowMs, null, null, "engine", detail)
    }

    fun recordSwitch(
        actor: String,
        fromPokemon: String?,
        toPokemon: String?,
        cp: Int?,
        nowMs: Long = System.currentTimeMillis(),
        confidence: String? = null
    ) {
        val label = when {
            fromPokemon != null && toPokemon != null -> "$fromPokemon → $toPokemon"
            toPokemon != null -> "Entrou $toPokemon"
            else -> "Troca detectada"
        }
        addEvent(
            actor, "TROCA", label, 1, nowMs, toPokemon, confidence, "OCR", null,
            buildMap {
                fromPokemon?.let { put("de", it) }
                toPokemon?.let { put("para", it) }
                cp?.let { put("pc", it.toString()) }
            }
        )
    }

    fun recordState(
        actor: String,
        pokemon: String?,
        hpRatio: Float?,
        energyMin: Int?,
        energyMax: Int?,
        shields: Int? = null,
        confidence: String? = null,
        nowMs: Long = System.currentTimeMillis(),
        source: String = "engine",
        extraDetails: Map<String, String> = emptyMap()
    ) {
        val details = buildMap {
            hpRatio?.let { put("hpPercent", (it.coerceIn(0f, 1f) * 100f).toInt().toString()) }
            energyMin?.let { put("energyMin", it.toString()) }
            energyMax?.let { put("energyMax", it.toString()) }
            shields?.let { put("shields", it.toString()) }
            putAll(extraDetails)
        }
        if (details.isEmpty()) return
        addEvent(actor, "ESTADO", "Estado da batalha", 1, nowMs, pokemon, confidence, source, null, details)
    }

    private fun addEvent(
        actor: String,
        category: String,
        moveName: String,
        count: Int,
        nowMs: Long,
        pokemon: String?,
        confidence: String?,
        source: String?,
        reason: String?,
        details: Map<String, String> = emptyMap()
    ) {
        val a = active ?: return
        val elapsed = (nowMs - a.startedAt).coerceAtLeast(0L)
        a.events += BattleHistoryEvent(
            elapsed, actor, category, moveName, count, pokemon, confidence, source, reason, details
        )
        maybeCheckpoint(nowMs)
    }

    private fun maybeCheckpoint(nowMs: Long, force: Boolean = false) {
        val a = active ?: return
        if (!force && nowMs - lastCheckpointAtMs < CHECKPOINT_INTERVAL_MS) return
        lastCheckpointAtMs = nowMs
        repository.saveActiveCheckpoint(
            BattleHistoryEntry(
                id = a.id,
                startedAtEpochMs = a.startedAt,
                endedAtEpochMs = nowMs.coerceAtLeast(a.startedAt),
                leagueCp = a.leagueCp,
                playerName = a.playerName,
                playerCp = a.playerCp,
                opponentName = a.opponentName,
                opponentCp = a.opponentCp,
                appVersion = a.appVersion,
                dataVersion = a.dataVersion,
                startedMidBattle = a.startedMidBattle,
                endReason = "checkpoint ativo",
                result = a.result,
                events = a.events.toList()
            )
        )
    }

    fun finish(
        nowMs: Long = System.currentTimeMillis(),
        reason: String = "Encerramento solicitado",
        result: String? = null
    ) {
        val a = active ?: return
        result?.takeIf(String::isNotBlank)?.let { a.result = it }
        addEvent(
            actor = "PARTIDA",
            category = "FIM",
            moveName = a.result?.let { "Partida encerrada: $it" } ?: "Partida encerrada",
            count = 1,
            nowMs = nowMs,
            pokemon = null,
            confidence = null,
            source = "session",
            reason = reason
        )
        active = null
        repository.clearActiveCheckpoint()
        val useful = a.playerName != null || a.opponentName != null || a.events.isNotEmpty()
        if (!useful) return
        repository.append(
            BattleHistoryEntry(
                id = a.id,
                startedAtEpochMs = a.startedAt,
                endedAtEpochMs = nowMs.coerceAtLeast(a.startedAt),
                leagueCp = a.leagueCp,
                playerName = a.playerName,
                playerCp = a.playerCp,
                opponentName = a.opponentName,
                opponentCp = a.opponentCp,
                appVersion = a.appVersion,
                dataVersion = a.dataVersion,
                startedMidBattle = a.startedMidBattle,
                endReason = reason,
                result = a.result,
                events = a.events.toList()
            )
        )
    }

    companion object {
        private const val SESSION_INACTIVITY_GRACE_MS = 28_000L
        private const val CHECKPOINT_INTERVAL_MS = 5_000L
        private const val RECOVERY_MAX_AGE_MS = 180_000L
    }
}
