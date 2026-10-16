package com.lucianotoscano.pvppokego.overlay

/** Coordinates of a touchable overlay window in screen pixels. */
internal object OverlayDragBounds {
    data class Position(val x: Int, val y: Int)
    fun clamp(
        proposedX: Int, proposedY: Int,
        screenWidth: Int, screenHeight: Int,
        windowWidth: Int, windowHeight: Int,
        insetLeft: Int = 0, insetTop: Int = 0,
        insetRight: Int = 0, insetBottom: Int = 0
    ): Position {
        val left = insetLeft.coerceAtLeast(0)
        val top = insetTop.coerceAtLeast(0)
        val right = (screenWidth - insetRight.coerceAtLeast(0) - windowWidth).coerceAtLeast(left)
        val bottom = (screenHeight - insetBottom.coerceAtLeast(0) - windowHeight).coerceAtLeast(top)
        return Position(proposedX.coerceIn(left, right), proposedY.coerceIn(top, bottom))
    }
}
