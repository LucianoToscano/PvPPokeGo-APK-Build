package com.lucianotoscano.pvppokego.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdviceStabilityGateTest {
    private fun key(title: String, priority: Int = 30) =
        AdviceStabilityGate.Key(title, "detail", priority)

    @Test fun firstAdviceAppearsImmediately() {
        val gate = AdviceStabilityGate(settleMs = 500)
        assertEquals("CONTINUE", gate.update(key("CONTINUE"), 0)?.title)
    }

    @Test fun lowUrgencyChangeMustSettle() {
        val gate = AdviceStabilityGate(settleMs = 500)
        gate.update(key("CONTINUE"), 0)
        assertEquals("CONTINUE", gate.update(key("TROQUE", 35), 100)?.title)
        assertEquals("TROQUE", gate.update(key("TROQUE", 35), 650)?.title)
    }

    @Test fun urgentPriorityChangeIsImmediate() {
        val gate = AdviceStabilityGate(settleMs = 500, urgentPriorityDelta = 10)
        gate.update(key("CONTINUE", 30), 0)
        assertEquals("ESCUDO", gate.update(key("ESCUDO", 110), 20)?.title)
    }

    @Test fun analyzingIsImmediate() {
        val gate = AdviceStabilityGate()
        gate.update(key("CONTINUE"), 0)
        assertEquals("ANALISANDO", gate.update(key("ANALISANDO", 125), 10)?.title)
    }

    @Test fun shortNullGapDoesNotEraseAdvice() {
        val gate = AdviceStabilityGate(clearAfterMs = 500)
        gate.update(key("CONTINUE"), 0)
        assertEquals("CONTINUE", gate.update(null, 100)?.title)
        assertNull(gate.update(null, 700))
    }
}
