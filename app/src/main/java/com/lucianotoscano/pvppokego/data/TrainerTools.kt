package com.lucianotoscano.pvppokego.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Player-provided metadata. Not an official Pokémon GO account connection. */
enum class TrainerFaction(val label: String) {
    UNSET("Não informada"),
    MYSTIC("Mystic · Azul"),
    VALOR("Valor · Vermelha"),
    INSTINCT("Instinct · Amarela")
}

enum class CatchDateDisplay(val label: String, val pattern: String) {
    BRAZIL("Dia/mês/ano", "dd/MM/yyyy"),
    ISO("Ano-mês-dia", "yyyy-MM-dd"),
    SHORT("Dia/mês/ano curto", "dd/MM/yy")
}

enum class PokemonNameStyle(val label: String) {
    ORIGINAL("Nome original"),
    PVP("Nome + IVs + PC"),
    COMPACT("Nome · PC")
}

/**
 * Never fabricates IVs or PvP ranks: unknown values stay unknown.
 * The name preview is for the player's clipboard, not an action on Pokémon GO.
 */
object TrainerTools {
    fun validLevel(value: Int?): Boolean = value == null || value in 1..80

    fun formatDate(timeMs: Long, style: CatchDateDisplay): String =
        SimpleDateFormat(style.pattern, Locale("pt", "BR")).format(Date(timeMs))

    fun ivSummary(pokemon: ManualTeamPokemon): String? {
        val a = pokemon.atkIv ?: return null
        val d = pokemon.defIv ?: return null
        val h = pokemon.hpIv ?: return null
        if (a !in 0..15 || d !in 0..15 || h !in 0..15) return null
        return "$a/$d/$h"
    }

    fun generatedName(pokemon: ManualTeamPokemon, style: PokemonNameStyle): String? {
        val name = pokemon.speciesName.trim().takeIf { it.isNotBlank() } ?: return null
        return when (style) {
            PokemonNameStyle.ORIGINAL -> name
            PokemonNameStyle.COMPACT -> name +
                (pokemon.cp?.takeIf { it in 10..10000 }?.let { " · PC $it" } ?: "")
            PokemonNameStyle.PVP -> buildString {
                append(name)
                ivSummary(pokemon)?.let { append(" · ").append(it) }
                pokemon.cp?.takeIf { it in 10..10000 }?.let { append(" · PC ").append(it) }
            }
        }
    }
}
