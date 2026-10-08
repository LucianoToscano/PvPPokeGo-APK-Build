package com.lucianotoscano.pvppokego.engine

import android.graphics.Color
import com.lucianotoscano.pvppokego.data.BattleUiState
import com.lucianotoscano.pvppokego.data.CaptureHealth
import com.lucianotoscano.pvppokego.data.CmpOutcome
import com.lucianotoscano.pvppokego.data.EnergyConfidence
import com.lucianotoscano.pvppokego.data.GameDataRepository
import com.lucianotoscano.pvppokego.data.MatchupState
import com.lucianotoscano.pvppokego.data.MoveKnowledgeConfidence
import com.lucianotoscano.pvppokego.data.ShieldAction

/** Read-only PvP copilot. It recommends the next action but never sends input to Pokemon GO. */
object BattleAssistAdvisor {
    data class Advice(
        val title: String,
        val detail: String,
        val color: Int,
        val priority: Int
    )

    fun advise(state: BattleUiState, repo: GameDataRepository): Advice? {
        val playerTypes = state.playerTypes.ifEmpty { repo.pokemon(state.playerName)?.types.orEmpty() }
        val enemyTypes = state.opponentTypes
        val enemyCharged = listOfNotNull(state.charged1, state.charged2)
        val ownCharged = listOfNotNull(state.ownCharged1, state.ownCharged2)

        val identityReady =
            !state.playerName.isNullOrBlank() &&
                !state.opponentName.isNullOrBlank() &&
                playerTypes.isNotEmpty() &&
                enemyTypes.isNotEmpty()

        if (state.captureHealth == CaptureHealth.STALE || state.captureHealth == CaptureHealth.PAUSED) {
            return Advice(
                "ANALISANDO",
                state.captureStatusText ?: "Aguardando uma imagem atual da batalha",
                Color.rgb(128, 136, 146),
                140
            )
        }

        if (!identityReady || state.battleConfidence < .45f) {
            return Advice(
                "ANALISANDO",
                "Confirmando Pokémon, tipos e estado da batalha",
                Color.rgb(128, 136, 146),
                125
            )
        }

        state.sacSwap?.takeIf { !state.chargedIncoming && it.confidence >= .68f }?.let { sac ->
            return Advice(
                "SAC SWAP → " + shortName(sac.targetName),
                sac.reason + " • " + "%.0f".format(sac.confidence * 100f) + "%",
                Color.rgb(232, 61, 72),
                121
            )
        }

        if (state.chargedIncoming) {
            state.shieldDecision?.takeIf { it.confidence >= .70f }?.let { shield ->
                val bait = shield.baitProbability.takeIf { it >= .20f }
                    ?.let { " • bait ~" + "%.0f".format(it * 100f) + "%" }
                    .orEmpty()
                val detail = listOfNotNull(
                    shield.moveName?.let(::shortName),
                    shield.reason.takeIf { it.isNotBlank() }
                ).joinToString(" • ") + bait
                when (shield.action) {
                    ShieldAction.SHIELD -> return Advice(
                        "ESCUDO", detail, Color.rgb(232, 61, 72), 119
                    )
                    ShieldAction.HOLD -> return Advice(
                        if (state.ownShieldsKnown && state.ownShieldsRemaining <= 0) "SEM ESCUDOS" else "NÃO ESCUDE",
                        detail, Color.rgb(46, 188, 105), 117
                    )
                    ShieldAction.OPTIONAL -> if (shield.confidence >= .76f) return Advice(
                        "ESCUDO OPCIONAL", detail, Color.rgb(245, 175, 52), 113
                    )
                    ShieldAction.UNKNOWN -> Unit
                }
            }
        }

        // 1) Incoming Charged: compare cheap bait and high-damage nuke possibilities.
        if (state.chargedIncoming && playerTypes.isNotEmpty() && enemyCharged.isNotEmpty()) {
            val plausible = enemyCharged.filter { it.ready || it.maxEnergy >= it.energyCost }.ifEmpty { enemyCharged }
            val threat = plausible.maxByOrNull { it.move.power.toDouble() * TypeChart.multiplier(it.move.type, playerTypes) }
            val bait = plausible.minByOrNull { it.energyCost }
            if (threat != null) {
                val threatKnowledge = enemyMoveKnowledge(state, threat.move.moveId)
                val observedOrManual = threatKnowledge == MoveKnowledgeConfidence.CONFIRMED ||
                    threatKnowledge == MoveKnowledgeConfidence.MANUAL

                if (!observedOrManual) {
                    val possibleNames = plausible
                        .distinctBy { it.move.moveId }
                        .take(2)
                        .joinToString(" ou ") { shortName(it.move.name) }
                    val forecast = state.incomingDamagePreview?.let { f ->
                        " • sem escudo ~${"%.0f".format(f.minPercent)}-${"%.0f".format(f.maxPercent)}% HP"
                    }.orEmpty()
                    return Advice(
                        if (state.ownShieldsKnown && state.ownShieldsRemaining <= 0) "SEM ESCUDOS" else "ANALISANDO ATAQUE",
                        if (possibleNames.isNotBlank()) {
                            "Possível: $possibleNames$forecast • ${energyLabel(threat)}"
                        } else {
                            "Ataque carregado detectado$forecast"
                        },
                        Color.rgb(245, 175, 52),
                        112
                    )
                }

                val eff = TypeChart.multiplier(threat.move.type, playerTypes)
                val score = threat.move.power.toDouble() * eff
                val observedDamage = incomingDamageForMove(state, threat.move.moveId)
                val currentHpPercent = state.playerHpRatio?.times(100f)
                val learnedEnough = (observedDamage?.samples ?: 0) >= 2
                val observedLikelyLethal = learnedEnough &&
                    currentHpPercent != null &&
                    observedDamage != null &&
                    observedDamage.averagePercent >= currentHpPercent - 1.5f
                val observedLowDamage = learnedEnough &&
                    observedDamage != null &&
                    observedDamage.averagePercent <= 16f
                val damageDetail = observedDamage?.let(::damageLabel)
                val baitPossible = bait != null && bait.move.moveId != threat.move.moveId &&
                    bait.energyCost + 5 <= threat.energyCost && threat.maxEnergy >= threat.energyCost
                return when {
                    state.ownShieldsKnown && state.ownShieldsRemaining <= 0 -> Advice(
                        "SEM ESCUDOS",
                        listOfNotNull(
                            shortName(threat.move.name),
                            damageDetail ?: state.incomingDamagePreview?.let {
                                "~${"%.0f".format(it.minPercent)}-${"%.0f".format(it.maxPercent)}% HP"
                            },
                            "prepare troca/farm conforme o HP"
                        ).joinToString(" • "),
                        Color.rgb(232, 61, 72),
                        118
                    )
                    observedLikelyLethal -> Advice(
                        "ESCUDO",
                        listOfNotNull(shortName(threat.move.name), damageDetail, "pode nocautear").joinToString(" • "),
                        Color.rgb(232, 61, 72),
                        114
                    )
                    observedLowDamage && eff <= 1.01 -> Advice(
                        "NÃO ESCUDE",
                        listOfNotNull(shortName(threat.move.name), damageDetail, "dano observado baixo").joinToString(" • "),
                        Color.rgb(46, 188, 105),
                        110
                    )
                    baitPossible && score >= 95.0 -> Advice(
                        "PODE SER BAIT",
                        "${shortName(bait!!.move.name)} ou ${shortName(threat.move.name)} • ${energyLabel(threat)}",
                        Color.rgb(245, 175, 52),
                        110
                    )
                    eff >= 1.5 || score >= 100.0 -> Advice(
                        "ESCUDO",
                        "${shortName(threat.move.name)} • ${if (eff > 1.01) "SUPEREFICAZ • " else ""}${energyLabel(threat)}",
                        Color.rgb(232, 61, 72),
                        108
                    )
                    eff <= .65 && score < 70.0 -> Advice(
                        "NÃO ESCUDE",
                        "${shortName(threat.move.name)} • pouco eficaz",
                        Color.rgb(46, 188, 105),
                        108
                    )
                    else -> Advice(
                        "ESCUDO OPCIONAL",
                        "${shortName(threat.move.name)} • dano moderado",
                        Color.rgb(245, 175, 52),
                        103
                    )
                }
            }
        }

        // Predictive warning before the Charged prompt appears.
        state.enemyEnergyForecast?.let { forecast ->
            val threat = forecast.candidates.firstOrNull()
            if (threat != null && threat.confidence >= .30f) {
                val damage = threat.damageForecast
                val damageText = damage?.let {
                    "sem escudo ~" + "%.0f".format(it.minPercent) + "-" + "%.0f".format(it.maxPercent) + "% HP"
                }
                val currentHp = state.playerHpRatio?.times(100f)
                val lethal = threat.likelyKo || (
                    damage != null && currentHp != null && damage.maxPercent >= currentHp - 1f
                )
                val energyRange = if (forecast.energyMin == forecast.energyMax) {
                    forecast.energyLikely.toString() + "E"
                } else {
                    forecast.energyMin.toString() + "-" + forecast.energyMax + "E"
                }
                if (threat.readyPossible) {
                    val readyLabel = if (threat.readyCertain) "READY" else "READY POSSÍVEL"
                    return Advice(
                        when {
                            lethal && state.ownShieldsKnown && state.ownShieldsRemaining > 0 -> "PERIGO • ESCUDO"
                            lethal && !state.ownShieldsKnown -> "PERIGO • ESCUDO SE DISPONÍVEL"
                            else -> "PERIGO • $readyLabel"
                        },
                        listOfNotNull(
                            shortName(threat.move.name),
                            energyRange,
                            damageText,
                            if (threat.baitProbability >= .20f) {
                                "bait ~" + "%.0f".format(threat.baitProbability * 100f) + "%"
                            } else if (threat.baitPotential) "bait possível" else null,
                            threat.fastCountSequenceLikely.takeIf { it.isNotEmpty() }?.let {
                                "seq " + it.joinToString("-")
                            },
                            if (threat.doubleReadyPossible) "pode armazenar 2" else null,
                            if (state.ownShieldsKnown && state.ownShieldsRemaining <= 0) "sem escudos"
                            else if (!state.ownShieldsKnown) "escudos restantes não confirmados"
                            else null
                        ).joinToString(" • "),
                        if (lethal) Color.rgb(232, 61, 72) else Color.rgb(245, 175, 52),
                        104
                    )
                }
                val insideReactionWindow = threat.turnsRemainingMin <= forecast.reactionTurns
                val nearWindow = threat.fastMovesRemainingMin <= 2 ||
                    threat.turnsRemainingMin <= forecast.reactionTurns + 2
                if (insideReactionWindow || (nearWindow && ownCharged.none { it.ready })) {
                    val fastText = if (threat.fastMovesRemainingMin == threat.fastMovesRemainingMax) {
                        threat.fastMovesRemainingMin.toString() + " Fast"
                    } else {
                        threat.fastMovesRemainingMin.toString() + "-" + threat.fastMovesRemainingMax + " Fast"
                    }
                    val seconds = "%.1f".format(threat.likelyTimeMs / 1000f)
                    return Advice(
                        "PREPARE-SE",
                        listOfNotNull(
                            shortName(threat.move.name),
                            fastText,
                            "~" + seconds + "s",
                            damageText,
                            if (threat.mayKoBeforeCharged) "Fast pode nocautear antes" else null,
                            if (lethal) "risco de KO" else null
                        ).joinToString(" • "),
                        if (insideReactionWindow) Color.rgb(245, 175, 52) else Color.rgb(110, 170, 235),
                        if (insideReactionWindow) 101 else 98
                    )
                }
            }
        }

        state.optimalThrow?.takeIf { it.confidence >= .64f && it.waitFastMoves > 0 }?.let { timing ->
            return Advice(
                "TIMING +" + timing.waitFastMoves + " FAST",
                timing.reason + " • " + "%.0f".format(timing.confidence * 100f) + "%",
                Color.rgb(110, 170, 235),
                97
            )
        }

        // 2) If one of our Charged Moves is ready, prefer actual matchup value rather than slot #1.
        val ownReady = ownCharged.filter { it.ready }
        if (ownReady.isNotEmpty() && enemyTypes.isNotEmpty()) {
            val best = ownReady.maxByOrNull { p ->
                val eff = TypeChart.multiplier(p.move.type, enemyTypes)
                (p.move.power.toDouble() * eff) / p.energyCost.coerceAtLeast(1)
            }
            if (best != null) {
                val eff = TypeChart.multiplier(best.move.type, enemyTypes)
                val other = ownCharged.firstOrNull { it.move.moveId != best.move.moveId }
                val holdForNuke = other != null && !other.ready && other.fastMovesRemaining <= 1 &&
                    other.move.power * TypeChart.multiplier(other.move.type, enemyTypes) >
                    best.move.power * eff * 1.35
                if (holdForNuke && state.playerMatchup != MatchupState.UNFAVORABLE) {
                    return Advice(
                        "SEGURE ENERGIA",
                        "+1 FAST para ${shortName(other!!.move.name)}",
                        Color.rgb(110, 170, 235),
                        92
                    )
                }
                return Advice(
                    "USE ${shortName(best.move.name).uppercase()}",
                    (if (eff > 1.01) "SUPEREFICAZ • melhor valor agora" else "Charged pronto • melhor valor agora") +
                        when (state.cmpForecast?.outcome) {
                            CmpOutcome.WIN -> " • CMP favorável"
                            CmpOutcome.LOSE -> " • CMP desfavorável"
                            CmpOutcome.UNCERTAIN -> " • CMP incerto"
                            else -> ""
                        },
                    if (eff > 1.01) Color.rgb(245, 175, 52) else Color.rgb(110, 170, 235),
                    96
                )
            }
        }

        state.farmDown?.takeIf { it.safe && it.confidence >= .70f }?.let { farm ->
            return Advice(
                "FARME ATÉ KO",
                farm.hitsToKo.toString() + " Fast • " + farm.turnsToKo + "T" +
                    (farm.enemyThreatTurns?.let { turns -> " • ameaça em " + turns + "T" } ?: ""),
                Color.rgb(46, 188, 105),
                90
            )
        }

        // 3) One fast away from our next useful Charged.
        val nextOwn = ownCharged.filter { !it.ready }.minByOrNull { it.fastMovesRemaining }
        if (nextOwn != null && nextOwn.fastMovesRemaining == 1) {
            return Advice(
                "+1 FAST",
                "Depois ${shortName(nextOwn.move.name)}",
                Color.rgb(110, 170, 235),
                88
            )
        }

        // 4) Smarter swap recommendation using the engine's move-aware reserve score.
        val visibleReserves = state.reserves.filter {
            it.visible &&
                it.identityConfirmed &&
                it.status != com.lucianotoscano.pvppokego.data.TeamPokemonStatus.FAINTED
        }
        val bestReserve = visibleReserves.maxByOrNull { it.score }
        if (state.playerMatchup == MatchupState.UNFAVORABLE && bestReserve != null && bestReserve.score > -.10) {
            return Advice(
                "TROQUE → ${shortName(bestReserve.name ?: "RESERVA")}",
                "${bestReserve.recommendation ?: "melhora o matchup"} • score ${"%.1f".format(bestReserve.score)}",
                Color.rgb(232, 61, 72),
                86
            )
        }

        // 5) Enemy energy warning. RANGE explicitly tells the user when OCR could not identify cost.
        val enemyReady = enemyCharged.filter { it.ready }
        if (enemyReady.isNotEmpty()) {
            val cheapest = enemyReady.minByOrNull { it.energyCost }
            val pattern = state.strategyPatternHint
                ?.takeIf { state.strategyPatternConfidence >= .58f }
                ?.let { " • $it" }
                .orEmpty()
            return Advice(
                "ATENÇÃO",
                (cheapest?.let { "Pode usar ${shortName(it.move.name)} • ${energyLabel(it)}" }
                    ?: "Charged inimigo disponível") + pattern,
                Color.rgb(245, 175, 52),
                75
            )
        }

        if (state.strategyPatternConfidence >= .68f && !state.strategyPatternHint.isNullOrBlank()) {
            return Advice(
                "PADRÃO LOCAL",
                state.strategyPatternHint,
                Color.rgb(126, 156, 220),
                52
            )
        }

        return when (state.playerMatchup) {
            MatchupState.FAVORABLE -> Advice(
                "CONTINUE FAST",
                ownEnergyDetail(ownCharged, "Matchup favorável • mantenha pressão"),
                Color.rgb(46, 188, 105),
                35
            )
            MatchupState.NEUTRAL -> Advice(
                "CONTINUE FAST",
                ownEnergyDetail(ownCharged, "Matchup neutro • acompanhe energia"),
                Color.rgb(110, 170, 235),
                30
            )
            MatchupState.UNFAVORABLE -> Advice(
                "CUIDADO",
                "Matchup desfavorável • preserve energia/troca",
                Color.rgb(245, 175, 52),
                40
            )
            MatchupState.UNKNOWN -> null
        }
    }

    private fun ownEnergyDetail(charged: List<com.lucianotoscano.pvppokego.data.ChargedPrediction>, fallback: String): String {
        val first = charged.minByOrNull { it.fastMovesRemaining } ?: return fallback
        return if (first.fastMovesRemaining > 0) "$fallback • ${first.fastMovesRemaining}F p/ ${shortName(first.move.name)}" else fallback
    }

    private fun energyLabel(p: com.lucianotoscano.pvppokego.data.ChargedPrediction): String {
        if (p.maxEnergy <= 0 && p.currentEnergy <= 0) return "energia em sincronização"
        return when (p.confidence) {
            EnergyConfidence.CONFIRMED -> "${p.currentEnergy}E confirmado"
            EnergyConfidence.ESTIMATED -> "~${p.currentEnergy}E estimado"
            EnergyConfidence.RANGE -> "${p.minEnergy}-${p.maxEnergy}E possíveis"
        }
    }

    private fun incomingDamageForMove(
        state: BattleUiState,
        moveId: String
    ) = when (moveId) {
        state.charged1?.move?.moveId -> state.incomingCharged1Damage
        state.charged2?.move?.moveId -> state.incomingCharged2Damage
        else -> null
    }

    private fun damageLabel(value: com.lucianotoscano.pvppokego.data.ObservedDamageEstimate): String =
        "~" + "%.0f".format(value.averagePercent) + "% HP obs. " + value.samples + "x"

    private fun enemyMoveKnowledge(state: BattleUiState, moveId: String): MoveKnowledgeConfidence = when (moveId) {
        state.charged1?.move?.moveId -> state.charged1Knowledge
        state.charged2?.move?.moveId -> state.charged2Knowledge
        else -> MoveKnowledgeConfidence.UNKNOWN
    }

    private fun shortName(name: String): String = if (name.length <= 18) name else name.take(17) + "…"
}
