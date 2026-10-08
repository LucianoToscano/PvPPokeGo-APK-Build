package com.lucianotoscano.pvppokego.engine

import com.lucianotoscano.pvppokego.data.MoveDef
import com.lucianotoscano.pvppokego.data.StatStageRange

/**
 * PvP stat-stage math. Stages are bounded to [-4,+4] and reset when a Pokémon leaves play.
 * Probabilistic effects widen a range instead of pretending the proc was observed.
 */
internal object StatStageTracker {
    data class Stages(
        val attack: StatStageRange = StatStageRange(),
        val defense: StatStageRange = StatStageRange()
    )

    data class Result(
        val self: Stages,
        val opponent: Stages,
        val deterministic: Boolean,
        val chance: Float
    )

    fun applyMove(move: MoveDef, self: Stages, opponent: Stages): Result {
        val buffs = move.buffs
        val chance = move.buffChance
        if (buffs.size < 2 || chance <= 0f) {
            return Result(self, opponent, deterministic = true, chance = 0f)
        }

        val targetSelf = move.buffTarget.equals("self", ignoreCase = true)
        val targetOpponent = move.buffTarget.equals("opponent", ignoreCase = true)
        val targetBoth = move.buffTarget.equals("both", ignoreCase = true)

        if (targetBoth) {
            val selfBuffs = move.buffsSelf.ifEmpty { move.buffs }
            val opponentBuffs = move.buffsOpponent.ifEmpty { move.buffs }
            return Result(
                self = Stages(
                    attack = applyDelta(self.attack, selfBuffs.getOrElse(0) { 0 }, chance),
                    defense = applyDelta(self.defense, selfBuffs.getOrElse(1) { 0 }, chance)
                ),
                opponent = Stages(
                    attack = applyDelta(opponent.attack, opponentBuffs.getOrElse(0) { 0 }, chance),
                    defense = applyDelta(opponent.defense, opponentBuffs.getOrElse(1) { 0 }, chance)
                ),
                deterministic = chance >= .999f,
                chance = chance
            )
        }

        if (!targetSelf && !targetOpponent) {
            return Result(self, opponent, deterministic = false, chance = chance)
        }

        val target = if (targetSelf) self else opponent
        val changed = Stages(
            attack = applyDelta(target.attack, buffs.getOrElse(0) { 0 }, chance),
            defense = applyDelta(target.defense, buffs.getOrElse(1) { 0 }, chance)
        )
        return Result(
            self = if (targetSelf) changed else self,
            opponent = if (targetOpponent) changed else opponent,
            deterministic = chance >= .999f,
            chance = chance
        )
    }

    fun reset(): Stages = Stages()

    internal fun applyDelta(range: StatStageRange, delta: Int, chance: Float): StatStageRange {
        if (delta == 0 || chance <= 0f) return range.normalized()
        val current = range.normalized()
        val changedMin = (current.min + delta).coerceIn(-4, 4)
        val changedMax = (current.max + delta).coerceIn(-4, 4)
        return if (chance >= .999f) {
            StatStageRange(changedMin, changedMax).normalized()
        } else {
            // Proc unknown: preserve both "did not trigger" and "triggered" worlds.
            StatStageRange(
                minOf(current.min, changedMin),
                maxOf(current.max, changedMax)
            ).normalized()
        }
    }

    /**
     * PvPoke resolves CMP from raw battle Attack rather than buffed effective Attack,
     * so stages intentionally do not enter this comparison.
     */
    fun cmp(attackerAttack: Double, defenderAttack: Double): Int = when {
        attackerAttack > defenderAttack -> 1
        attackerAttack < defenderAttack -> -1
        else -> 0
    }
}
