package com.lucianotoscano.pvppokego.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class ChargedMoveSlotResolverTest {
    @Test
    fun firstObservedMoveUsesFirstOpenSlot() {
        assertEquals(0, ChargedMoveSlotResolver.choose(
            "ROCK_SLIDE", "BODY_SLAM", "EARTH_POWER",
            false, false, false, false
        ))
    }

    @Test
    fun secondDistinctObservedMoveUsesSecondSlot() {
        assertEquals(1, ChargedMoveSlotResolver.choose(
            "EARTH_POWER", "ROCK_SLIDE", "BODY_SLAM",
            false, false, true, false
        ))
    }

    @Test
    fun repeatedObservedMoveKeepsSameSlot() {
        assertEquals(0, ChargedMoveSlotResolver.choose(
            "ROCK_SLIDE", "ROCK_SLIDE", "EARTH_POWER",
            false, false, true, true
        ))
    }

    @Test
    fun manualFirstSlotIsNeverOverwritten() {
        assertEquals(1, ChargedMoveSlotResolver.choose(
            "EARTH_POWER", "ROCK_SLIDE", "BODY_SLAM",
            true, false, true, false
        ))
    }

    @Test
    fun twoManualSlotsRejectAutomaticReplacement() {
        assertEquals(-1, ChargedMoveSlotResolver.choose(
            "STONE_EDGE", "ROCK_SLIDE", "EARTH_POWER",
            true, true, true, true
        ))
    }
}
