package com.lucianotoscano.pvppokego.data

import android.content.Context

/** Shares the existing settings preference file for portable backup; no external account sync. */
class TrainerPreferencesRepository(context: Context) {
    private val prefs = context.getSharedPreferences("pvppokego_prefs", Context.MODE_PRIVATE)

    var trainerLevel: Int?
        get() = prefs.getInt(KEY_LEVEL, 0).takeIf { it in 1..80 }
        set(value) {
            require(TrainerTools.validLevel(value)) { "O nível deve estar entre 1 e 80." }
            if (value == null) prefs.edit().remove(KEY_LEVEL).apply()
            else prefs.edit().putInt(KEY_LEVEL, value).apply()
        }

    var faction: TrainerFaction
        get() = runCatching {
            TrainerFaction.valueOf(prefs.getString(KEY_FACTION, TrainerFaction.UNSET.name).orEmpty())
        }.getOrDefault(TrainerFaction.UNSET)
        set(value) { prefs.edit().putString(KEY_FACTION, value.name).apply() }

    var dateDisplay: CatchDateDisplay
        get() = runCatching {
            CatchDateDisplay.valueOf(prefs.getString(KEY_DATE, CatchDateDisplay.BRAZIL.name).orEmpty())
        }.getOrDefault(CatchDateDisplay.BRAZIL)
        set(value) { prefs.edit().putString(KEY_DATE, value.name).apply() }

    var nameStyle: PokemonNameStyle
        get() = runCatching {
            PokemonNameStyle.valueOf(prefs.getString(KEY_NAME, PokemonNameStyle.PVP.name).orEmpty())
        }.getOrDefault(PokemonNameStyle.PVP)
        set(value) { prefs.edit().putString(KEY_NAME, value.name).apply() }

    companion object {
        const val KEY_LEVEL = "trainer_profile_level_v1"
        const val KEY_FACTION = "trainer_profile_faction_v1"
        const val KEY_DATE = "trainer_catch_date_display_v1"
        const val KEY_NAME = "trainer_pokemon_name_style_v1"
    }
}
