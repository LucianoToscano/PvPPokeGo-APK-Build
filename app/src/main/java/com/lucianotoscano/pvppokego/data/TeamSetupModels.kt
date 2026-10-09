package com.lucianotoscano.pvppokego.data

import kotlinx.serialization.Serializable

/**
 * User-supplied hints for pre-battle preparation, not automatically verified OCR observations.
 * No false CP, IV, moveset or rank is generated when fields are not filled in.
 */
@Serializable
data class ManualTeamPokemon(
    val speciesId: String = "",
    val speciesName: String = "",
    val cp: Int? = null,
    val atkIv: Int? = null,
    val defIv: Int? = null,
    val hpIv: Int? = null,
    val maxHp: Int? = null,
    val gender: String = "UNKNOWN",
    val shadow: Boolean = false,
    val mega: Boolean = false,
    val fastMoveId: String = "",
    val chargedMove1Id: String = "",
    val chargedMove2Id: String = ""
)

@Serializable
data class ManualTeamRoster(
    val formatVersion: Int = 1,
    val slots: List<ManualTeamPokemon> = List(3) { ManualTeamPokemon() }
)

/** Validation is intentionally separate from battle recognition and does not alter game state. */
object ManualTeamPolicy {
    fun sanitize(input: ManualTeamRoster): ManualTeamRoster =
        ManualTeamRoster(slots = input.slots.take(3).map(::sanitizeSlot)
            .let { it + List((3 - it.size).coerceAtLeast(0)) { ManualTeamPokemon() } })

    private fun sanitizeSlot(slot: ManualTeamPokemon): ManualTeamPokemon {
        fun iv(value: Int?) = value?.takeIf { it in 0..15 }
        return slot.copy(
            speciesId = slot.speciesId.trim().take(90),
            speciesName = slot.speciesName.trim().take(90),
            cp = slot.cp?.takeIf { it in 10..10000 },
            atkIv = iv(slot.atkIv), defIv = iv(slot.defIv), hpIv = iv(slot.hpIv),
            maxHp = slot.maxHp?.takeIf { it in 1..2000 },
            gender = slot.gender.takeIf { it in setOf("MALE", "FEMALE", "UNKNOWN") } ?: "UNKNOWN",
            fastMoveId = slot.fastMoveId.trim().take(90),
            chargedMove1Id = slot.chargedMove1Id.trim().take(90),
            chargedMove2Id = slot.chargedMove2Id.trim().take(90)
        )
    }

    fun withinLeague(slot: ManualTeamPokemon, leagueCap: Int?): Boolean =
        slot.cp != null && slot.cp in 10..10000 && (leagueCap == null || slot.cp <= leagueCap)

    fun completeCount(roster: ManualTeamRoster, leagueCap: Int?): Int =
        sanitize(roster).slots.count { it.speciesId.isNotBlank() && withinLeague(it, leagueCap) }

    fun canPrepare(roster: ManualTeamRoster, leagueCap: Int?): Boolean =
        completeCount(roster, leagueCap) == 3

    fun validateSlot(slot: ManualTeamPokemon, leagueCap: Int?): String? = when {
        slot.speciesId.isBlank() -> "Selecione uma espécie do banco local."
        slot.cp == null -> "Informe PC válido (10 a 10000)."
        leagueCap != null && slot.cp > leagueCap -> "PC acima do limite da liga."
        else -> null
    }
}
