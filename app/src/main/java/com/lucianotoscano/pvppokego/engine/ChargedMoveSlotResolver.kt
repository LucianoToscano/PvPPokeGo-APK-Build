package com.lucianotoscano.pvppokego.engine

/**
 * Chooses which enemy Charged Move slot should learn an observed move.
 * Manual choices are never overwritten and a second distinct observed move is kept
 * separate from the first confirmed move.
 */
internal object ChargedMoveSlotResolver {
    fun choose(
        observedMoveId: String,
        slot1Id: String?,
        slot2Id: String?,
        slot1Manual: Boolean,
        slot2Manual: Boolean,
        slot1Confirmed: Boolean,
        slot2Confirmed: Boolean
    ): Int {
        if (observedMoveId == slot1Id) return 0
        if (observedMoveId == slot2Id) return 1

        if (!slot1Manual && !slot1Confirmed) return 0
        if (!slot2Manual && !slot2Confirmed) return 1

        if (!slot1Manual && slot1Id == null) return 0
        if (!slot2Manual && slot2Id == null) return 1

        // Both slots already represent confirmed/manual information. Do not destroy it.
        return -1
    }
}
