package com.lucianotoscano.pvppokego.data

import org.junit.Assert.*
import org.junit.Test

class ManualTeamMatchPolicyTest {
    private val roster = listOf("ALTARIA" to 1497, "HOUNDOOM" to 1499, "RILLABOOM" to 1500)

    @Test fun uniqueCpIdentifiesCurrentMemberWhenOcrNameIsUnknown() {
        assertEquals(1, ManualTeamMatchPolicy.memberIndex(roster, null, 1499, "ALTARIA"))
    }

    @Test fun confirmedVisualCanIdentifyMemberWithoutCp() {
        assertEquals(2, ManualTeamMatchPolicy.memberIndex(roster, "RILLABOOM", null, null))
    }

    @Test fun contradictoryCpAndStrongVisualDoesNotImpersonateAnotherSlot() {
        assertNull(ManualTeamMatchPolicy.memberIndex(roster, "HOUNDOOM", 1497, null))
    }

    @Test fun unlistedSpeciesCannotReplaceUserSelectedTeam() {
        assertNull(ManualTeamMatchPolicy.memberIndex(roster, "MEWTWO", null, null))
        assertNull(ManualTeamMatchPolicy.memberIndex(roster, null, null, "MEWTWO"))
    }

    @Test fun ambiguousSameSpeciesWithoutCpRequiresMoreEvidence() {
        val duplicate = listOf("ALTARIA" to 1471, "ALTARIA" to 1497, "HOUNDOOM" to 1499)
        assertNull(ManualTeamMatchPolicy.memberIndex(duplicate, "ALTARIA", null, null))
        assertEquals(1, ManualTeamMatchPolicy.memberIndex(duplicate, "ALTARIA", 1497, null))
    }

    @Test fun switchingToOtherRosterCanNeverReuseAnOldCp() {
        val different = listOf("UMBREON" to 1497, "AZUMARILL" to 1499, "SKARMORY" to 1500)
        assertNull(ManualTeamMatchPolicy.memberIndex(different, "HOUNDOOM", 1499, null))
        assertEquals(0, ManualTeamMatchPolicy.memberIndex(different, "UMBREON", 1497, null))
    }
}
