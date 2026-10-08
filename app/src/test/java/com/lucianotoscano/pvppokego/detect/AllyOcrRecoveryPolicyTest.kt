package com.lucianotoscano.pvppokego.detect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AllyOcrRecoveryPolicyTest {
    @Test fun acceptsValidNameAndCpWithoutAnySpriteMatch() {
        assertTrue(AllyOcrRecoveryPolicy.allowNameAndCp("Cramorant", 1485, 0.98f))
        assertTrue(AllyOcrRecoveryPolicy.allowNameAndCp("Cradily", 1494, 0.90f))
    }

    @Test fun rejectsMissingIdentityOrInvalidCp() {
        assertFalse(AllyOcrRecoveryPolicy.allowNameAndCp(null, 1485, 0.98f))
        assertFalse(AllyOcrRecoveryPolicy.allowNameAndCp("", 1485, 0.98f))
        assertFalse(AllyOcrRecoveryPolicy.allowNameAndCp("Cramorant", null, 0.98f))
        assertFalse(AllyOcrRecoveryPolicy.allowNameAndCp("Cramorant", 0, 0.98f))
        assertFalse(AllyOcrRecoveryPolicy.allowNameAndCp("Cramorant", 1485, 0.70f))
    }
}
