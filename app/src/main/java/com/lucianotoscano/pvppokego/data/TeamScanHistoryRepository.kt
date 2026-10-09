package com.lucianotoscano.pvppokego.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Only confirmed user-entered fields are stored; no screenshot, pixels, OCR text or URI. */
@Serializable
data class TeamScanHistoryEntry(
    val pokemon: ManualTeamPokemon,
    val savedAtMs: Long,
    val slot: Int
)

class TeamScanHistoryRepository(context: Context) {
    private val prefs = context.getSharedPreferences("pvppokego_prefs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): List<TeamScanHistoryEntry> = runCatching {
        json.decodeFromString<List<TeamScanHistoryEntry>>(prefs.getString(KEY, "[]") ?: "[]")
    }.getOrDefault(emptyList()).take(30)

    fun remember(slot: Int, pokemon: ManualTeamPokemon, nowMs: Long = System.currentTimeMillis()) {
        if (slot !in 0..2 || pokemon.speciesId.isBlank() || pokemon.cp == null) return
        val key = identity(pokemon)
        val fresh = (listOf(TeamScanHistoryEntry(pokemon, nowMs, slot)) +
            load().filterNot { identity(it.pokemon) == key }).take(30)
        prefs.edit().putString(KEY, json.encodeToString(fresh)).apply()
    }

    private fun identity(p: ManualTeamPokemon): String =
        listOf(p.speciesId, p.cp, p.atkIv, p.defIv, p.hpIv,
            p.fastMoveId, p.chargedMove1Id, p.chargedMove2Id).joinToString("|")

    companion object { const val KEY = "team_scan_history_v1" }
}
