package com.lucianotoscano.pvppokego.data

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Uses the existing SettingsRepository preferences file so the existing JSON
 * settings export/import includes manual_team_v1. Never writes battle state.
 */
enum class TeamRecognitionMode { AUTOMATIC, MANUAL }

class TeamSetupRepository(context: Context) {
    private val prefs = context.getSharedPreferences("pvppokego_prefs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    var recognitionMode: TeamRecognitionMode
        get() = runCatching {
            TeamRecognitionMode.valueOf(
                prefs.getString(MODE_KEY, TeamRecognitionMode.AUTOMATIC.name).orEmpty()
            )
        }.getOrDefault(TeamRecognitionMode.AUTOMATIC)
        set(value) { prefs.edit().putString(MODE_KEY, value.name).apply() }

    /** Incomplete manual rosters never masquerade as verified active teams. */
    fun currentlySelectedTeam(leagueCp: Int?): ManualTeamRoster? =
        load().takeIf {
            recognitionMode == TeamRecognitionMode.MANUAL &&
                ManualTeamPolicy.canPrepare(it, leagueCp)
        }

    fun load(): ManualTeamRoster = runCatching {
        prefs.getString(KEY, null)?.let { json.decodeFromString<ManualTeamRoster>(it) }
            ?: ManualTeamRoster()
    }.map(ManualTeamPolicy::sanitize).getOrElse { ManualTeamRoster() }

    fun save(roster: ManualTeamRoster) {
        prefs.edit().putString(KEY, json.encodeToString(ManualTeamPolicy.sanitize(roster))).apply()
    }

    fun saveSlot(index: Int, slot: ManualTeamPokemon) {
        require(index in 0..2) { "Equipe possui três vagas" }
        val roster = load()
        save(roster.copy(slots = roster.slots.toMutableList().apply { set(index, slot) }))
    }

    companion object {
        const val KEY = "manual_team_v1"
        const val MODE_KEY = "team_recognition_mode_v1"
    }
}
