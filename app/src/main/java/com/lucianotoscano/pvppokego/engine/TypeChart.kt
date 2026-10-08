package com.lucianotoscano.pvppokego.engine

import kotlin.math.abs

object TypeChart {
    /** Standard Pokémon type chart. Pokémon GO maps immunity to strong resistance. */
    private val superEffective = mapOf(
        "normal" to emptySet(),
        "fire" to setOf("grass", "ice", "bug", "steel"),
        "water" to setOf("fire", "ground", "rock"),
        "electric" to setOf("water", "flying"),
        "grass" to setOf("water", "ground", "rock"),
        "ice" to setOf("grass", "ground", "flying", "dragon"),
        "fighting" to setOf("normal", "ice", "rock", "dark", "steel"),
        "poison" to setOf("grass", "fairy"),
        "ground" to setOf("fire", "electric", "poison", "rock", "steel"),
        "flying" to setOf("grass", "fighting", "bug"),
        "psychic" to setOf("fighting", "poison"),
        "bug" to setOf("grass", "psychic", "dark"),
        "rock" to setOf("fire", "ice", "flying", "bug"),
        "ghost" to setOf("psychic", "ghost"),
        "dragon" to setOf("dragon"),
        "dark" to setOf("psychic", "ghost"),
        "steel" to setOf("ice", "rock", "fairy"),
        "fairy" to setOf("fighting", "dragon", "dark")
    )

    private val resisted = mapOf(
        "normal" to setOf("rock", "steel"),
        "fire" to setOf("fire", "water", "rock", "dragon"),
        "water" to setOf("water", "grass", "dragon"),
        "electric" to setOf("electric", "grass", "dragon"),
        "grass" to setOf("fire", "grass", "poison", "flying", "bug", "dragon", "steel"),
        "ice" to setOf("fire", "water", "ice", "steel"),
        "fighting" to setOf("poison", "flying", "psychic", "bug", "fairy"),
        "poison" to setOf("poison", "ground", "rock", "ghost"),
        "ground" to setOf("grass", "bug"),
        "flying" to setOf("electric", "rock", "steel"),
        "psychic" to setOf("psychic", "steel"),
        "bug" to setOf("fire", "fighting", "poison", "flying", "ghost", "steel", "fairy"),
        "rock" to setOf("fighting", "ground", "steel"),
        "ghost" to setOf("dark"),
        "dragon" to setOf("steel"),
        "dark" to setOf("fighting", "dark", "fairy"),
        "steel" to setOf("fire", "water", "electric", "steel"),
        "fairy" to setOf("fire", "poison", "steel")
    )

    private val immunityLike = mapOf(
        "normal" to setOf("ghost"),
        "electric" to setOf("ground"),
        "fighting" to setOf("ghost"),
        "poison" to setOf("steel"),
        "ground" to setOf("flying"),
        "psychic" to setOf("dark"),
        "ghost" to setOf("normal"),
        "dragon" to setOf("fairy")
    )

    val allTypes = listOf(
        "normal", "fire", "water", "electric", "grass", "ice", "fighting", "poison",
        "ground", "flying", "psychic", "bug", "rock", "ghost", "dragon", "dark", "steel", "fairy"
    )

    fun multiplier(attackType: String, defenderTypes: List<String>): Double {
        var out = 1.0
        for (d in defenderTypes.map { it.lowercase() }) {
            out *= when {
                immunityLike[attackType.lowercase()]?.contains(d) == true -> 0.390625
                superEffective[attackType.lowercase()]?.contains(d) == true -> 1.6
                resisted[attackType.lowercase()]?.contains(d) == true -> 0.625
                else -> 1.0
            }
        }
        return out
    }

    fun strongTypesAgainst(defenderTypes: List<String>): List<String> =
        allTypes.filter { multiplier(it, defenderTypes) > 1.01 }
            .sortedByDescending { multiplier(it, defenderTypes) }

    fun compareAttackingPotential(myTypes: List<String>, enemyTypes: List<String>): Double =
        myTypes.maxOfOrNull { multiplier(it, enemyTypes) } ?: 1.0

    fun matchupScore(myTypes: List<String>, enemyTypes: List<String>): Double {
        val offense = compareAttackingPotential(myTypes, enemyTypes)
        val enemyOffense = compareAttackingPotential(enemyTypes, myTypes)
        return offense / enemyOffense.coerceAtLeast(0.01)
    }

    fun classify(myTypes: List<String>, enemyTypes: List<String>): Int {
        val score = matchupScore(myTypes, enemyTypes)
        return when {
            score >= 1.20 -> 1
            score <= 0.83 -> -1
            abs(score - 1.0) < 0.20 -> 0
            score > 1.0 -> 1
            else -> -1
        }
    }
}
