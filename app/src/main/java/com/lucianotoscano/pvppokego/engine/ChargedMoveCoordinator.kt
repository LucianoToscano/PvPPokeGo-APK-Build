package com.lucianotoscano.pvppokego.engine

import com.lucianotoscano.pvppokego.data.MoveDef

/** Side-effect-free OCR charged-move matcher used before ChargedAnimationCycleGate. */
internal object ChargedMoveCoordinator {
    fun findMoveMention(
        text: String,
        candidates: List<MoveDef>,
        normalizedNames: (MoveDef) -> Set<String>
    ): MoveDef? = candidates.distinctBy { it.moveId }
        .map { it to normalizedNames(it) }
        .firstOrNull { (_, names) -> names.any { n -> n.length >= 4 && text.contains(n) } }
        ?.first
}
