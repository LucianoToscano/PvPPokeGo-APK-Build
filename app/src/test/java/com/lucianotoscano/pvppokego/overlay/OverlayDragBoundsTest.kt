package com.lucianotoscano.pvppokego.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayDragBoundsTest {
    @Test fun `HUD control remains inside display and system insets`() {
        assertEquals(OverlayDragBounds.Position(0, 100),
            OverlayDragBounds.clamp(-500, -300, 1080, 2400, 60, 60, insetTop = 100))
        assertEquals(OverlayDragBounds.Position(1010, 2260),
            OverlayDragBounds.clamp(1400, 4000, 1080, 2400, 60, 60,
                insetTop = 100, insetRight = 10, insetBottom = 80))
    }
    @Test fun `too small display never produces invalid bounds`() {
        assertEquals(OverlayDragBounds.Position(20, 30),
            OverlayDragBounds.clamp(999, 999, 80, 100, 120, 120,
                insetLeft = 20, insetTop = 30))
    }
}
