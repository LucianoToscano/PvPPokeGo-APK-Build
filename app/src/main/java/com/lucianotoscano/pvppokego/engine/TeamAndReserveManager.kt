package com.lucianotoscano.pvppokego.engine

/** Mutable battle-team slot remains stable across reserve-card OCR refreshes. */
internal data class OwnTeamSlot(
    val slot: Int,
    var name: String,
    var cp: Int?,
    var speciesId: String? = null,
    var dex: Int? = null,
    var identityConfidence: Float = 0f
)

internal data class TeamIdentityCandidate(
    val name: String,
    val cp: Int?,
    val confidence: Float,
    val speciesId: String?,
    val dex: Int?
)

/** Stateless reserve-scan policy. The battle facade still owns state mutation/order. */
internal object TeamAndReserveManager {
    fun needsOwnTeamScan(
        manualSelected: Boolean,
        slots: List<OwnTeamSlot>,
        minimumConfidence: Float
    ): Boolean = !manualSelected && (slots.size < 3 || slots.any {
        it.cp == null || it.speciesId.isNullOrBlank() ||
            it.identityConfidence < minimumConfidence
    })
}
