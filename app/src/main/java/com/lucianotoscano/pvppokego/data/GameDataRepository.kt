package com.lucianotoscano.pvppokego.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.floor
import kotlin.math.pow
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

internal fun sanitizePokemonTypes(types: List<String>): List<String> =
    types.map { it.lowercase() }
        .filter { it.isNotBlank() && it != "none" }
        .distinct()

class GameDataRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Volatile private var gameMaster: GameMaster? = null
    @Volatile private var movesById: Map<String, MoveDef> = emptyMap()
    @Volatile private var pokemonByName: Map<String, PokemonDef> = emptyMap()
    @Volatile private var pokemonByDex: Map<Int, List<PokemonDef>> = emptyMap()
    @Volatile private var uniquePokemon: List<PokemonDef> = emptyList()
    @Volatile private var ranking: List<RankingEntry> = emptyList()
    @Volatile private var moveNamesPtBr: Map<String, String> = emptyMap()
    @Volatile private var cpMultipliers: List<Double> = emptyList()
    private val legalFastCache = ConcurrentHashMap<String, List<MoveDef>>()
    private val legalChargedCache = ConcurrentHashMap<String, List<MoveDef>>()
    private val weightedFastCache = ConcurrentHashMap<String, List<WeightedMove>>()
    private val weightedChargedCache = ConcurrentHashMap<String, List<WeightedMove>>()
    private val battleStatsCache = ConcurrentHashMap<String, EstimatedBattleStats?>()
    private val attackRangeCache = ConcurrentHashMap<String, AttackRangeEstimate>()
    @Volatile var currentLeagueCp: Int = 1500
        private set

    suspend fun load(leagueCp: Int = 1500) = withContext(Dispatchers.IO) {
        currentLeagueCp = leagueCp
        legalFastCache.clear()
        legalChargedCache.clear()
        weightedFastCache.clear()
        weightedChargedCache.clear()
        battleStatsCache.clear()
        attackRangeCache.clear()
        val gmText = runCatching {
            context.assets.open("gamemaster.json").bufferedReader().use { it.readText() }
        }.getOrElse {
            runCatching {
                fetchWithCache(
                    url = GAMEMASTER_URL,
                    cacheName = "gamemaster.json",
                    maxAgeMs = 24L * 60 * 60 * 1000
                )
            }.getOrElse { "{\"pokemon\":[],\"moves\":[]}" }
        }
        gameMaster = runCatching { json.decodeFromString<GameMaster>(gmText) }.getOrNull()
        movesById = gameMaster?.moves?.associateBy { normalizeId(it.moveId) }.orEmpty()
        uniquePokemon = gameMaster?.pokemon
            ?.map { p ->
                p.copy(
                    types = sanitizePokemonTypes(p.types)
                )
            }
            ?.distinctBy { normalizeName(it.speciesId) }
            .orEmpty()
        pokemonByName = uniquePokemon
            .flatMap { p -> listOf(p.speciesName to p, p.speciesId to p) }
            .associate { normalizeName(it.first) to it.second }
        pokemonByDex = uniquePokemon
            .filter { it.dex > 0 }
            .groupBy { it.dex }

        val rankingText = runCatching {
            context.assets.open("rankings-$leagueCp.json").bufferedReader().use { it.readText() }
        }.getOrElse {
            fetchWithCache(
                url = "$RANKINGS_PREFIX$leagueCp.json",
                cacheName = "rankings-$leagueCp.json",
                maxAgeMs = 12L * 60 * 60 * 1000
            )
        }
        ranking = runCatching { json.decodeFromString<List<RankingEntry>>(rankingText) }.getOrDefault(emptyList())
        moveNamesPtBr = runCatching {
            context.assets.open("move_names_ptbr.json").bufferedReader().use {
                json.decodeFromString<Map<String, String>>(it.readText())
            }
        }.getOrDefault(emptyMap())

        cpMultipliers = runCatching {
            context.assets.open("pvpoke_cpms.json").bufferedReader().use { reader ->
                json.parseToJsonElement(reader.readText())
                    .jsonObject["cpms"]
                    ?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull?.toDoubleOrNull() }
                    .orEmpty()
            }
        }.getOrDefault(emptyList())
    }

    fun move(id: String?): MoveDef? = id?.let { raw ->
        val key = normalizeId(raw)
        movesById[key]?.let { m -> m.copy(name = moveNamesPtBr[key] ?: m.name) }
    }

    fun pokemon(nameOrId: String?): PokemonDef? = nameOrId?.let { pokemonByName[normalizeName(it)] }

    fun pokemonForDex(dex: Int): List<PokemonDef> = pokemonByDex[dex].orEmpty()

    /**
     * Visual recognition is only allowed to choose a species directly when that Pokédex
     * number maps to one unambiguous PvPoke entry. Regional/forms that share a dex number
     * are left to OCR/CP instead of guessing a potentially wrong type.
     */
    fun uniquePokemonForVisualDex(dex: Int): PokemonDef? {
        val candidates = pokemonByDex[dex].orEmpty()
        return candidates.singleOrNull()
    }

    fun pokemonForVisualIdentity(speciesId: String?, dex: Int): PokemonDef? {
        speciesId?.takeIf { it.isNotBlank() }?.let { exact ->
            pokemon(exact)?.takeIf { it.dex <= 0 || dex <= 0 || it.dex == dex }?.let { return it }
        }
        return uniquePokemonForVisualDex(dex)
    }

    private fun rankingEntry(nameOrSpeciesId: String): RankingEntry? {
        val key = normalizeName(nameOrSpeciesId)
        return ranking.firstOrNull { normalizeName(it.speciesId) == key }
            ?: ranking.firstOrNull { normalizeName(it.speciesName) == key }
    }

    fun possibleFastMoves(nameOrSpeciesId: String): List<MoveDef> {
        val entry = rankingEntry(nameOrSpeciesId)
        return entry?.moves?.fastMoves.orEmpty()
            .sortedByDescending { it.uses }
            .mapNotNull { move(it.moveId) }
    }

    fun possibleChargedMoves(nameOrSpeciesId: String): List<MoveDef> {
        val entry = rankingEntry(nameOrSpeciesId)
        return entry?.moves?.chargedMoves.orEmpty()
            .sortedByDescending { it.uses }
            .mapNotNull { move(it.moveId) }
    }

    /** Full Game Master movepool used by the predictive engine, not just the top-ranked set. */
    fun legalFastMoves(nameOrSpeciesId: String): List<MoveDef> {
        val key = normalizeName(nameOrSpeciesId)
        return legalFastCache.getOrPut(key) {
            val def = pokemon(nameOrSpeciesId)
            val ids = linkedSetOf<String>().apply {
                def?.fastMoves.orEmpty().forEach(::add)
                rankingEntry(nameOrSpeciesId)?.moves?.fastMoves.orEmpty().forEach { add(it.moveId) }
            }
            ids.mapNotNull(::move)
                .filter { it.energyGain > 0 }
                .distinctBy { it.moveId }
        }
    }

    fun legalChargedMoves(nameOrSpeciesId: String): List<MoveDef> {
        val key = normalizeName(nameOrSpeciesId)
        return legalChargedCache.getOrPut(key) {
            val def = pokemon(nameOrSpeciesId)
            val ids = linkedSetOf<String>().apply {
                def?.chargedMoves.orEmpty().forEach(::add)
                rankingEntry(nameOrSpeciesId)?.moves?.chargedMoves.orEmpty().forEach { add(it.moveId) }
            }
            ids.mapNotNull(::move)
                .filter { it.chargedCost > 0 }
                .distinctBy { it.moveId }
        }
    }

    fun weightedFastMoves(nameOrSpeciesId: String): List<WeightedMove> {
        val key = normalizeName(nameOrSpeciesId)
        return weightedFastCache.getOrPut(key) {
            weightedMoves(
                legal = legalFastMoves(nameOrSpeciesId),
                ranked = rankingEntry(nameOrSpeciesId)?.moves?.fastMoves.orEmpty()
            )
        }
    }

    fun weightedChargedMoves(nameOrSpeciesId: String): List<WeightedMove> {
        val key = normalizeName(nameOrSpeciesId)
        return weightedChargedCache.getOrPut(key) {
            weightedMoves(
                legal = legalChargedMoves(nameOrSpeciesId),
                ranked = rankingEntry(nameOrSpeciesId)?.moves?.chargedMoves.orEmpty()
            )
        }
    }

    private fun weightedMoves(legal: List<MoveDef>, ranked: List<RankingMove>): List<WeightedMove> {
        if (legal.isEmpty()) return emptyList()
        val rankedUses = ranked.associate { normalizeId(it.moveId) to it.uses.coerceAtLeast(0.0) }
        val raw = legal.map { candidate ->
            val use = rankedUses[normalizeId(candidate.moveId)]
            // Legal but unranked moves remain mathematically possible with a small prior.
            candidate to when {
                use != null && use > 0.0 -> use.toFloat()
                rankedUses.isNotEmpty() -> 2.5f
                else -> 1f
            }
        }
        val total = raw.sumOf { it.second.toDouble() }.toFloat().takeIf { it > 0f }
            ?: raw.size.toFloat()
        return raw.map { (move, weight) -> WeightedMove(move, (weight / total).coerceIn(0f, 1f)) }
            .sortedByDescending { it.weight }
    }

    fun likelyMoveset(nameOrSpeciesId: String): Triple<MoveDef?, MoveDef?, MoveDef?> {
        val entry = rankingEntry(nameOrSpeciesId)
        val ids = entry?.moveset.orEmpty()
        return Triple(move(ids.getOrNull(0)), move(ids.getOrNull(1)), move(ids.getOrNull(2)))
    }

    fun canonicalPokemonName(ocrName: String): String? {
        val key = normalizeName(ocrName)
        if (key.length < 3) return null
        pokemonByName[key]?.let { return it.speciesName }
        val best = pokemonByName.entries
            .asSequence()
            .filter { kotlin.math.abs(it.key.length - key.length) <= 2 }
            .map { it to levenshtein(key, it.key) }
            .minByOrNull { it.second }
        return best?.takeIf { it.second <= if (key.length >= 8) 2 else 1 }?.first?.value?.speciesName
    }

    /**
     * Finds Pokémon names in a larger OCR block (party/team screen). This is used only to
     * remember the user's own three Pokémon; it never reads game memory or sends input.
     */
    fun findPokemonMentions(rawText: String, limit: Int = 3): List<String> {
        if (rawText.isBlank() || uniquePokemon.isEmpty()) return emptyList()
        val result = LinkedHashSet<String>()
        val lines = rawText.lines().map { it.trim() }.filter { it.length >= 3 }

        // First: line-level OCR matching (safer than substring matching).
        for (line in lines) {
            val cleaned = line
                .replace(Regex("(?i)(?:PC|CP)\\s*[: ]?\\s*\\d{2,5}"), " ")
                .replace(Regex("\\d{2,5}"), " ")
                .replace(Regex("[^\\p{L}♀♂' .-]+"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (cleaned.length < 3) continue
            canonicalPokemonName(cleaned)?.let { result += it }
            if (result.size >= limit) return result.toList()
        }

        // Second: bounded exact mentions, longest species first so Mewtwo beats Mew.
        val normalizedText = " " + normalizeWords(rawText) + " "
        uniquePokemon
            .sortedByDescending { it.speciesName.length }
            .forEach { p ->
                val needle = normalizeWords(p.speciesName)
                if (needle.length >= 4 && Regex("(^|\\s)${Regex.escape(needle)}(\\s|$)").containsMatchIn(normalizedText)) {
                    result += p.speciesName
                }
                if (result.size >= limit) return@forEach
            }
        return result.take(limit)
    }

    fun offlineSnapshotMetadata(): String? = runCatching {
        context.assets.open("pvpoke_snapshot.json").bufferedReader().use { it.readText() }
    }.getOrNull()

    fun offlineSnapshotVersion(): String? = offlineSnapshotMetadata()?.let { raw ->
        runCatching {
            val obj = json.parseToJsonElement(raw).jsonObject
            val generated = obj["generatedAtUtc"]?.jsonPrimitive?.contentOrNull
            val ref = obj["pvpokeRef"]?.jsonPrimitive?.contentOrNull
            listOfNotNull(
                ref?.takeIf { it.isNotBlank() }?.let { "PvPoke:$it" },
                generated?.takeIf { it.isNotBlank() }?.let { "snapshot:$it" }
            ).joinToString(" • ").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun estimatedBattleStats(nameOrSpeciesId: String?, observedCp: Int? = null): EstimatedBattleStats? {
        val raw = nameOrSpeciesId ?: return null
        val cacheKey = normalizeName(raw) + "|" + (observedCp ?: 0) + "|" + currentLeagueCp
        if (battleStatsCache.containsKey(cacheKey)) return battleStatsCache[cacheKey]
        val def = pokemon(raw) ?: return null
        val base = def.baseStats
        if (base.atk <= 0 || base.def <= 0 || base.hp <= 0 || cpMultipliers.isEmpty()) return null

        val combo = if (currentLeagueCp >= 10_000) {
            listOf(50.0, 15.0, 15.0, 15.0)
        } else {
            def.defaultIVs["cp$currentLeagueCp"]
                ?: listOf(50.0, 15.0, 15.0, 15.0)
        }
        val preferredLevel = combo.getOrNull(0)?.coerceIn(1.0, 51.0) ?: 50.0
        val atkIv = combo.getOrNull(1)?.toInt()?.coerceIn(0, 15) ?: 15
        val defIv = combo.getOrNull(2)?.toInt()?.coerceIn(0, 15) ?: 15
        val hpIv = combo.getOrNull(3)?.toInt()?.coerceIn(0, 15) ?: 15

        fun levelForIndex(index: Int): Double = 1.0 + index * 0.5
        fun cpAt(cpm: Double): Int = floor(
            ((base.atk + atkIv) *
                (base.def + defIv).toDouble().pow(0.5) *
                (base.hp + hpIv).toDouble().pow(0.5) *
                cpm.pow(2.0)) / 10.0
        ).toInt().coerceAtLeast(10)

        val preferredIndex = ((preferredLevel - 1.0) * 2.0).toInt()
            .coerceIn(0, cpMultipliers.lastIndex)
        val chosenIndex = observedCp
            ?.takeIf { it >= 10 }
            ?.let { target ->
                val maxLevel = if (currentLeagueCp >= 10_000) 50.0 else preferredLevel.coerceAtLeast(1.0)
                val maxIndex = ((maxLevel - 1.0) * 2.0).toInt().coerceIn(0, cpMultipliers.lastIndex)
                (0..maxIndex).minByOrNull { index ->
                    kotlin.math.abs(cpAt(cpMultipliers[index]) - target)
                }
            }
            ?: preferredIndex

        val cpm = cpMultipliers[chosenIndex]
        val attack = cpm * (base.atk + atkIv)
        val defense = cpm * (base.def + defIv)
        val hp = floor(cpm * (base.hp + hpIv)).toInt().coerceAtLeast(10)
        return EstimatedBattleStats(
            attack = attack,
            defense = defense,
            hp = hp,
            level = levelForIndex(chosenIndex),
            atkIv = atkIv,
            defIv = defIv,
            hpIv = hpIv,
            cp = cpAt(cpm)
        ).also { battleStatsCache[cacheKey] = it }
    }

    /**
     * Hidden-IV Attack interval for CMP. The visible CP is treated as an exact constraint
     * when possible; if no exact legal combination exists, only the closest CP shell is used.
     * Results are cached because the exhaustive scan is intentionally done once per species/CP.
     */
    fun attackRangeEstimate(nameOrSpeciesId: String?, observedCp: Int?): AttackRangeEstimate? {
        val raw = nameOrSpeciesId ?: return null
        val target = observedCp?.takeIf { it >= 10 } ?: return null
        val key = normalizeName(raw) + "|" + target + "|" + currentLeagueCp
        attackRangeCache[key]?.let { return it }

        val def = pokemon(raw) ?: return null
        val likelyAttack = estimatedBattleStats(raw, target)?.attack
        return AttackRangeEstimator.estimate(
            base = def.baseStats,
            cpMultipliers = cpMultipliers,
            targetCp = target,
            leagueCp = currentLeagueCp,
            likelyAttack = likelyAttack
        )?.also { attackRangeCache[key] = it }
    }

    fun localizedMoveName(move: MoveDef): String = moveNamesPtBr[normalizeId(move.moveId)] ?: move.name

    fun normalizeForOcr(v: String): String = normalizeWords(v).replace(" ", "")

    private fun fetchWithCache(url: String, cacheName: String, maxAgeMs: Long): String {
        val file = File(context.cacheDir, cacheName)
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < maxAgeMs) {
            return file.readText()
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("User-Agent", "PVPPokeGo/0.3")
        return try {
            conn.inputStream.bufferedReader().use { it.readText() }.also { text ->
                runCatching { file.writeText(text) }
            }
        } catch (e: Exception) {
            if (file.exists()) file.readText() else throw e
        } finally {
            conn.disconnect()
        }
    }

    private fun normalizeWords(v: String): String = Normalizer.normalize(v.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace("♀", " f ").replace("♂", " m ")
        .replace(Regex("[^a-z0-9]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun normalizeName(v: String): String = normalizeWords(v).replace(" ", "")
    private fun normalizeId(v: String): String = v.uppercase().trim()

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in a.indices) {
            cur[0] = i + 1
            for (j in b.indices) {
                val c = if (a[i] == b[j]) 0 else 1
                cur[j + 1] = minOf(cur[j] + 1, prev[j + 1] + 1, prev[j] + c)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    companion object {
        const val GAMEMASTER_URL = "https://pvpoke.com/data/gamemaster.json"
        const val RANKINGS_PREFIX = "https://pvpoke.com/data/rankings/all/overall/rankings-"
    }
}
