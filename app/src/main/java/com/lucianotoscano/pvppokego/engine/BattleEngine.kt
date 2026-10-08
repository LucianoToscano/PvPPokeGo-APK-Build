package com.lucianotoscano.pvppokego.engine

import com.lucianotoscano.pvppokego.data.BattleDetection
import com.lucianotoscano.pvppokego.data.BattlePokemonState
import com.lucianotoscano.pvppokego.data.BattleStrategyProfile
import com.lucianotoscano.pvppokego.data.BattleUiState
import com.lucianotoscano.pvppokego.data.CaptureHealth
import com.lucianotoscano.pvppokego.data.DamageForecast
import com.lucianotoscano.pvppokego.data.DamageForecastConfidence
import com.lucianotoscano.pvppokego.data.DetectedPokemon
import com.lucianotoscano.pvppokego.data.EnergyConfidence
import com.lucianotoscano.pvppokego.data.GameDataRepository
import com.lucianotoscano.pvppokego.data.MatchupState
import com.lucianotoscano.pvppokego.data.MoveDef
import com.lucianotoscano.pvppokego.data.MoveKnowledgeConfidence
import com.lucianotoscano.pvppokego.data.ReserveCardEvidence
import com.lucianotoscano.pvppokego.data.ReserveState
import com.lucianotoscano.pvppokego.data.TeamPokemonStatus
import com.lucianotoscano.pvppokego.data.StatStageRange
import java.text.Normalizer

/**
 * Battle-domain state. Read-only relative to Pokemon GO: this class never sends input.
 * Enemy and own energy are preserved per Pokemon through switches.
 */
class BattleEngine(private val repo: GameDataRepository) {
    data class DetectionChanges(val playerChanged: Boolean, val enemyChanged: Boolean)
    data class DamageObservation(
        /** Actor that launched the Charged Move: VOCÊ or INIMIGO. */
        val actor: String,
        val moveId: String,
        val moveName: String,
        val damagePercent: Float,
        val damagePoints: Int,
        val learnedSamples: Int,
        val shielded: Boolean = false,
        val shieldsRemaining: Int? = null
    )
    data class BattleTextResult(
        val newChargedPrompt: Boolean = false,
        val chargedMoveConfirmed: Boolean = false,
        val ownChargedMoveConfirmed: Boolean = false,
        val enemyChargedMove: MoveDef? = null,
        val ownChargedMove: MoveDef? = null
    )

    private val enemyStates = BattleStateStore()
    private val ownStates = BattleStateStore()

    private var playerName: String? = null
    private var playerSpeciesId: String? = null
    private var playerCp: Int? = null
    private var enemyName: String? = null
    private var enemySpeciesId: String? = null
    private var enemyCp: Int? = null
    private val playerCpTracker = StableCpTracker()
    private val enemyCpTracker = StableCpTracker()

    private var tracker = EnergyTracker()
    private val predictiveEnergy = EnemyEnergyTracker()
    private val enemyFastPhase = FastMovePhaseTracker()
    private val reactionWindow = ReactionWindowEstimator()
    private var lastEnemyFastEventAtMs = 0L
    /** Last frame where the player's HP bar was readable; used only for non-mutating time projection. */
    private var lastPlayerHpVisibleAtMs = 0L
    private var activeState: BattlePokemonState? = null
    private var fast: MoveDef? = null
    private var charge1: MoveDef? = null
    private var charge2: MoveDef? = null

    private var ownTracker = EnergyTracker()
    private var ownActiveState: BattlePokemonState? = null
    private var ownFast: MoveDef? = null
    private var ownCharge1: MoveDef? = null
    private var ownCharge2: MoveDef? = null

    private data class OwnTeamSlot(
        val slot: Int,
        var name: String,
        var cp: Int?,
        var speciesId: String? = null,
        var dex: Int? = null,
        var identityConfidence: Float = 0f
    )

    private data class TeamIdentityCandidate(
        val name: String,
        val cp: Int?,
        val confidence: Float,
        val speciesId: String?,
        val dex: Int?
    )

    private val ownTeam = linkedMapOf<String, Int?>()
    private val ownTeamSlots = mutableListOf<OwnTeamSlot>()
    private val reserveSlotVisible = arrayOfNulls<Boolean>(2)
    private val reserveCardHpRatios = arrayOfNulls<Float>(2)
    private val reserveMissingStreak = IntArray(2)
    private val reserveCardCps = arrayOfNulls<Int>(2)
    private val reserveCpCandidate = arrayOfNulls<Int>(2)
    private val reserveCpStreak = IntArray(2)
    private val reserveCardDexes = arrayOfNulls<Int>(2)
    private val reserveDexCandidate = arrayOfNulls<Int>(2)
    private val reserveDexStreak = IntArray(2)
    private val reserveCardSpeciesIds = arrayOfNulls<String>(2)
    private val reserveSpeciesCandidate = arrayOfNulls<String>(2)
    private val reserveSpeciesStreak = IntArray(2)
    private var ownSwitchReadyAtMs: Long = 0L
    private var opponentSwitchReadyAtMs: Long = 0L
    private var chargedPendingSinceMs: Long? = null
    private var lastChargedConfirmedAtMs: Long = 0L
    private val enemyChargedCycle = ChargedAnimationCycleGate()
    private val ownChargedCycle = ChargedAnimationCycleGate()
    private var ignoreFastUntilMs: Long = 0L
    private var lastAutoSource: String = "-"
    private var lastOwnAutoSource: String = "-"
    private var lastDetectorConfidence = 0f
    private var lastBattleConfidence = 0f
    private var lastHpRatio: Float? = null
    private var lastOpponentHpRatio: Float? = null
    private var lastPlayerHpBarYFraction: Float? = null
    private var lastMotion = 0f
    private var ownShieldsRemaining = 2
    private var opponentShieldsRemaining = 2
    private var ownShieldsKnown = true
    private var opponentShieldsKnown = true
    private var captureHealth = CaptureHealth.FRESH
    private var captureStatusText: String? = null
    private var captureGapStartedAtMs: Long? = null
    private var localStrategyProfile: BattleStrategyProfile? = null

    private val incomingDamageTracker = ObservedDamageTracker()
    private var pendingIncomingChargedHpBefore: Float? = null
    private var pendingOutgoingChargedHpBefore: Float? = null
    private data class PendingChargedDamage(
        val actor: String,
        val moveId: String,
        val moveName: String,
        val attackerKey: String,
        val defenderKey: String,
        val hpBefore: Float,
        val armedAtMs: Long,
        var minimumHpAfter: Float = hpBefore,
        var firstDropAtMs: Long? = null,
        var observations: Int = 0
    )
    private var pendingIncomingChargedDamage: PendingChargedDamage? = null
    private var pendingOutgoingChargedDamage: PendingChargedDamage? = null

    fun resetBattle() {
        playerName = null
        playerSpeciesId = null
        playerCp = null
        enemyName = null
        enemySpeciesId = null
        enemyCp = null
        playerCpTracker.reset()
        enemyCpTracker.reset()
        tracker.reset()
        predictiveEnergy.clear()
        enemyFastPhase.reset()
        lastEnemyFastEventAtMs = 0L
        lastPlayerHpVisibleAtMs = 0L
        ownTracker.reset()
        activeState = null
        ownActiveState = null
        fast = null
        charge1 = null
        charge2 = null
        ownFast = null
        ownCharge1 = null
        ownCharge2 = null
        ownTeam.clear()
        ownTeamSlots.clear()
        reserveSlotVisible.fill(null)
        reserveCardHpRatios.fill(null)
        reserveMissingStreak.fill(0)
        reserveCardCps.fill(null)
        reserveCpCandidate.fill(null)
        reserveCpStreak.fill(0)
        reserveCardDexes.fill(null)
        reserveDexCandidate.fill(null)
        reserveDexStreak.fill(0)
        reserveCardSpeciesIds.fill(null)
        reserveSpeciesCandidate.fill(null)
        reserveSpeciesStreak.fill(0)
        ownSwitchReadyAtMs = 0L
        opponentSwitchReadyAtMs = 0L
        chargedPendingSinceMs = null
        lastChargedConfirmedAtMs = 0L
        enemyChargedCycle.reset()
        ownChargedCycle.reset()
        ignoreFastUntilMs = 0L
        lastAutoSource = "-"
        lastOwnAutoSource = "-"
        lastDetectorConfidence = 0f
        lastBattleConfidence = 0f
        lastHpRatio = null
        lastOpponentHpRatio = null
        lastPlayerHpBarYFraction = null
        lastMotion = 0f
        ownShieldsRemaining = 2
        opponentShieldsRemaining = 2
        ownShieldsKnown = true
        opponentShieldsKnown = true
        captureHealth = CaptureHealth.FRESH
        captureStatusText = null
        captureGapStartedAtMs = null
        localStrategyProfile = null
        incomingDamageTracker.resetBattle()
        pendingIncomingChargedHpBefore = null
        pendingOutgoingChargedHpBefore = null
        pendingIncomingChargedDamage = null
        pendingOutgoingChargedDamage = null
        enemyStates.clearBattle()
        ownStates.clearBattle()
    }

    fun onBattleConfidence(value: Float) {
        lastBattleConfidence = value.coerceIn(0f, 1f)
    }

    /**
     * Capture may start after one or more shields were already spent. Preserve the
     * unknown state instead of pretending the battle still has 2/2 shields.
     */
    fun onStartedMidBattle() {
        ownShieldsKnown = false
        opponentShieldsKnown = false
    }

    fun onCaptureHealth(health: CaptureHealth, statusText: String? = null) {
        val now = System.currentTimeMillis()
        val wasGap = captureHealth == CaptureHealth.STALE || captureHealth == CaptureHealth.PAUSED
        val isGap = health == CaptureHealth.STALE || health == CaptureHealth.PAUSED
        if (!wasGap && isGap) {
            captureGapStartedAtMs = now
        } else if (wasGap && !isGap) {
            val gapMs = captureGapStartedAtMs?.let { (now - it).coerceAtLeast(0L) } ?: 0L
            ensurePredictiveEnergyInitialized()
            if (predictiveEnergy.isInitialized && gapMs > 0L) {
                val fastestTurns = predictiveEnergy.fastTurnsRange().first.coerceAtLeast(1)
                val fastestMs = fastestTurns * EnemyEnergyTracker.TURN_MS.toLong()
                val possibleMissed = kotlin.math.ceil(gapMs.toDouble() / fastestMs.toDouble())
                    .toInt()
                    .coerceIn(0, 5)
                predictiveEnergy.widenForPossibleMissedFast(possibleMissed)
                syncLegacyEnemyEnergyFromPredictive()
                saveActiveState()
            }
            captureGapStartedAtMs = null
        }
        captureHealth = health
        captureStatusText = statusText
    }

    fun onPipelineLatencySample(latencyMs: Long) {
        reactionWindow.recordPipelineLatency(latencyMs)
    }
    fun onLocalStrategyProfile(profile: BattleStrategyProfile?) {
        localStrategyProfile = profile?.takeIf {
            enemyName == null || it.opponentName.equals(enemyName, ignoreCase = true)
        }
    }

    fun onPlayerHpBarYFraction(value: Float?) {
        value?.takeIf { it in 0f..1f }?.let { lastPlayerHpBarYFraction = it }
    }


    fun onDetection(d: BattleDetection): DetectionChanges {
        val oldPlayer = playerName
        val oldPlayerSpeciesId = playerSpeciesId
        val oldEnemy = enemyName
        val oldEnemySpeciesId = enemySpeciesId

        d.player?.let { detected ->
            val ocrCanonical = repo.canonicalPokemonName(detected.name) ?: detected.name
            val cpPinnedSlot = detected.cp?.let { rawCp ->
                ownTeamSlots.filter { slot -> slot.cp == rawCp }.singleOrNull()
            }
            val visualDef = detected.visualSpeciesId
                ?.takeIf(String::isNotBlank)
                ?.takeIf { (detected.visualConfidence ?: 0f) >= IdentityThresholds.ACTIVE_VISUAL_MIN_CONFIDENCE }
                ?.let(repo::pokemon)
            val ocrDef = repo.pokemon(ocrCanonical)
            val resolvedDef = cpPinnedSlot?.speciesId?.let(repo::pokemon)
                ?: visualDef
                ?: ocrDef
            val canonical = cpPinnedSlot?.name ?: resolvedDef?.speciesName ?: ocrCanonical
            val speciesId = cpPinnedSlot?.speciesId
                ?: resolvedDef?.speciesId
            val dex = cpPinnedSlot?.dex
                ?: resolvedDef?.dex?.takeIf { it > 0 }
            val stableCp = playerCpTracker.observe(
                normalize(speciesId ?: canonical),
                detected.cp
            )

            rememberOwnPokemon(
                canonical,
                stableCp,
                speciesId,
                dex,
                maxOf(detected.confidence, detected.visualConfidence ?: 0f)
            )
            if (!samePokemonIdentity(playerName, playerSpeciesId, canonical, speciesId)) {
                onPlayerChanged(canonical, stableCp, speciesId)
            } else {
                playerName = canonical
                if (!speciesId.isNullOrBlank()) playerSpeciesId = speciesId
                if (stableCp != null) playerCp = stableCp
                rebindOwnActiveStateToStableSlot()
            }
        }

        d.opponent?.let { detected ->
            val ocrCanonical = repo.canonicalPokemonName(detected.name) ?: detected.name
            val visualDef = detected.visualSpeciesId
                ?.takeIf(String::isNotBlank)
                ?.takeIf { (detected.visualConfidence ?: 0f) >= IdentityThresholds.ACTIVE_VISUAL_MIN_CONFIDENCE }
                ?.let(repo::pokemon)
            val resolvedDef = visualDef ?: repo.pokemon(ocrCanonical)
            val canonical = resolvedDef?.speciesName ?: ocrCanonical
            val speciesId = resolvedDef?.speciesId
            val stableCp = enemyCpTracker.observe(
                normalize(speciesId ?: canonical),
                detected.cp
            )
            if (!samePokemonIdentity(enemyName, enemySpeciesId, canonical, speciesId)) {
                onEnemyChanged(canonical, speciesId)
                enemyCp = stableCp
            } else {
                enemyName = canonical
                if (!speciesId.isNullOrBlank()) enemySpeciesId = speciesId
                if (stableCp != null) enemyCp = stableCp
            }
        }

        val playerChanged = oldPlayer != null &&
            !samePokemonIdentity(oldPlayer, oldPlayerSpeciesId, playerName, playerSpeciesId)
        val enemyChanged = oldEnemy != null &&
            !samePokemonIdentity(oldEnemy, oldEnemySpeciesId, enemyName, enemySpeciesId)
        val now = System.currentTimeMillis()
        if (playerChanged) {
            reserveSlotVisible.fill(null)
            reserveMissingStreak.fill(0)
            clearReserveCardIdentity()
            ownSwitchReadyAtMs = now + SWITCH_COOLDOWN_MS
        }
        if (enemyChanged) {
            opponentSwitchReadyAtMs = now + SWITCH_COOLDOWN_MS
            enemyFastPhase.reset(fast)
        }
        return DetectionChanges(playerChanged, enemyChanged)
    }

    fun onOwnTeamDetected(team: List<DetectedPokemon>) {
        val candidates = team
            .mapNotNull(::resolveTeamIdentityCandidate)
            // Same species can exist on both sides. Only discard an exact opponent card,
            // never a same-name reserve with a different CP or form.
            .filterNot { candidate ->
                val sameIdentity = samePokemonIdentity(
                    candidate.name,
                    candidate.speciesId,
                    enemyName,
                    enemySpeciesId
                )
                val sameExactCard = enemyCp != null &&
                    candidate.cp != null &&
                    enemyCp == candidate.cp
                sameIdentity && sameExactCard
            }
            .distinctBy { candidate ->
                "${normalize(candidate.speciesId ?: candidate.name)}:${candidate.cp ?: 0}"
            }
            .take(3)

        // Only a complete, high-confidence scan may replace all slot identities.
        val completeTeam = candidates.size >= 3 && candidates.take(3).all { candidate ->
            candidate.cp != null &&
                candidate.confidence >= IdentityThresholds.MIN_STABLE_TEAM_CONFIDENCE &&
                !candidate.speciesId.isNullOrBlank()
        }

        if (completeTeam) {
            ownTeamSlots.clear()
            candidates.forEachIndexed { index, candidate ->
                ownTeamSlots += OwnTeamSlot(
                    slot = index,
                    name = candidate.name,
                    cp = candidate.cp,
                    speciesId = candidate.speciesId,
                    dex = candidate.dex,
                    identityConfidence = candidate.confidence
                )
                rememberOwnPokemon(
                    candidate.name,
                    candidate.cp,
                    candidate.speciesId,
                    candidate.dex
                )
            }
        } else {
            candidates.forEach { candidate ->
                rememberOwnPokemon(
                    candidate.name,
                    candidate.cp,
                    candidate.speciesId,
                    candidate.dex
                )
                val existing = findOwnSlot(
                    name = candidate.name,
                    cp = candidate.cp,
                    speciesId = candidate.speciesId
                )
                if (existing != null) {
                    if (candidate.cp != null) existing.cp = candidate.cp
                    if (!candidate.speciesId.isNullOrBlank()) existing.speciesId = candidate.speciesId
                    if (candidate.dex != null && candidate.dex > 0) existing.dex = candidate.dex
                    existing.identityConfidence = maxOf(existing.identityConfidence, candidate.confidence)
                    existing.name = candidate.name
                } else if (ownTeamSlots.size < 3) {
                    ownTeamSlots += OwnTeamSlot(
                        slot = ownTeamSlots.size,
                        name = candidate.name,
                        cp = candidate.cp,
                        speciesId = candidate.speciesId,
                        dex = candidate.dex,
                        identityConfidence = candidate.confidence
                    )
                }
            }
        }
        rebindOwnActiveStateToStableSlot()
    }

    private fun resolveTeamIdentityCandidate(pokemon: DetectedPokemon): TeamIdentityCandidate? {
        val ocrCanonical = repo.canonicalPokemonName(pokemon.name) ?: pokemon.name
        val ocrDef = repo.pokemon(ocrCanonical)
        val visualDef = if (
            (pokemon.visualConfidence ?: 0f) >= IdentityThresholds.TEAM_VISUAL_MIN_CONFIDENCE
        ) {
            repo.pokemonForVisualIdentity(
                pokemon.visualSpeciesId,
                pokemon.visualDex ?: 0
            )
        } else {
            null
        }

        // The OCR detector has already rejected strong cross-dex conflicts. Here the
        // visual identity is used to preserve the exact regional/form speciesId.
        val resolved = when {
            visualDef == null -> ocrDef
            ocrDef == null -> visualDef
            visualDef.dex <= 0 || ocrDef.dex <= 0 || visualDef.dex == ocrDef.dex -> visualDef
            else -> ocrDef
        }
        val name = resolved?.speciesName ?: ocrCanonical
        if (name.isBlank()) return null
        return TeamIdentityCandidate(
            name = name,
            cp = pokemon.cp,
            confidence = pokemon.confidence,
            speciesId = resolved?.speciesId ?: pokemon.visualSpeciesId,
            dex = resolved?.dex?.takeIf { it > 0 } ?: pokemon.visualDex
        )
    }

    fun onReserveHpRatios(first: Float?, second: Float?) {
        listOf(first, second).forEachIndexed { index, value ->
            value?.takeIf { it in 0f..1f }?.let { reserveCardHpRatios[index] = it }
        }

        // Persist HP only after the native card is confidently mapped to a stable team slot.
        // An unconfirmed upper/lower card must never contaminate another Pokémon's HP.
        resolvedOwnReserves()
            .filter { it.confirmed }
            .forEach { resolved ->
                val hp = reserveCardHpRatios.getOrNull(resolved.cardIndex) ?: return@forEach
                val slot = resolved.slot
                val def = slotDefinition(slot)
                val name = def?.speciesName ?: slot.name
                val speciesId = slot.speciesId ?: def?.speciesId
                val key = ownIdentityKey(name, slot.cp, speciesId)
                val state = ownStates.getOrCreate(key, name, speciesId)
                state.lastHpRatio = hp.coerceIn(0f, 1f)
                if (hp <= .01f) state.fainted = true
                state.lastSeenAtMs = System.currentTimeMillis()
            }
    }

    fun onReserveSlotsDetected(firstVisible: Boolean?, secondVisible: Boolean?) {
        fun update(index: Int, value: Boolean?) {
            when (value) {
                true -> { reserveMissingStreak[index] = 0; reserveSlotVisible[index] = true }
                false -> {
                    reserveMissingStreak[index]++
                    if (reserveMissingStreak[index] >= RESERVE_MISSING_CONFIRM_FRAMES) reserveSlotVisible[index] = false
                }
                null -> Unit
            }
        }
        update(0, firstVisible)
        update(1, secondVisible)
    }

    /**
     * Fuses OCR CP and visual National Dex evidence from Pokemon GO's two reserve cards.
     * CP always has priority. Image evidence is accepted only when it is strong and maps
     * to exactly one known team member.
     */
    fun onReserveCardEvidence(top: ReserveCardEvidence, bottom: ReserveCardEvidence) {
        fun updateCp(index: Int, cp: Int?) {
            if (cp == null) return

            val uniqueKnownCp = ownTeamSlots.count { it.cp == cp } == 1
            if (uniqueKnownCp) {
                reserveCardCps[index] = cp
                reserveCpCandidate[index] = cp
                reserveCpStreak[index] = 2
                return
            }

            if (reserveCpCandidate[index] == cp) {
                reserveCpStreak[index]++
            } else {
                reserveCpCandidate[index] = cp
                reserveCpStreak[index] = 1
            }
            if (reserveCpStreak[index] >= 2) reserveCardCps[index] = cp
        }

        fun updateSpecies(index: Int, evidence: ReserveCardEvidence) {
            val speciesId = evidence.visualSpeciesId?.takeIf { it.isNotBlank() } ?: return
            val confidence = evidence.visualConfidence ?: return
            if (confidence < RESERVE_VISUAL_MIN_CONFIDENCE) return

            if (reserveSpeciesCandidate[index].equals(speciesId, ignoreCase = true)) {
                reserveSpeciesStreak[index]++
            } else {
                reserveSpeciesCandidate[index] = speciesId
                reserveSpeciesStreak[index] = 1
            }

            if (
                confidence >= RESERVE_VISUAL_IMMEDIATE_CONFIDENCE ||
                reserveSpeciesStreak[index] >= RESERVE_VISUAL_CONFIRM_FRAMES
            ) {
                reserveCardSpeciesIds[index] = speciesId
            }
        }

        fun updateDex(index: Int, evidence: ReserveCardEvidence) {
            val dex = evidence.visualDex ?: return
            val confidence = evidence.visualConfidence ?: return
            if (confidence < RESERVE_VISUAL_MIN_CONFIDENCE) return

            if (reserveDexCandidate[index] == dex) {
                reserveDexStreak[index]++
            } else {
                reserveDexCandidate[index] = dex
                reserveDexStreak[index] = 1
            }

            if (
                confidence >= RESERVE_VISUAL_IMMEDIATE_CONFIDENCE ||
                reserveDexStreak[index] >= RESERVE_VISUAL_CONFIRM_FRAMES
            ) {
                reserveCardDexes[index] = dex
            }
        }

        fun learnMissingMember(index: Int, evidence: ReserveCardEvidence) {
            val stableSpeciesId = reserveCardSpeciesIds[index]
            val stableDex = reserveCardDexes[index] ?: evidence.visualDex
            val def = repo.pokemonForVisualIdentity(stableSpeciesId, stableDex ?: 0) ?: return
            val confidence = evidence.visualConfidence ?: return
            if (confidence < RESERVE_VISUAL_MIN_CONFIDENCE) return

            // The native reserve cards belong to our team. Once the visual identity has
            // passed the same confidence gate used by the matcher, it may fill a missing
            // team slot even when the pre-battle team screen was never captured.
            if (samePokemonIdentity(
                    playerName,
                    playerSpeciesId,
                    def.speciesName,
                    def.speciesId
                )
            ) {
                return
            }

            val stableCp = reserveCardCps[index]
            val existing = findOwnSlot(def.speciesName, stableCp, def.speciesId)
            if (existing != null) {
                if (stableCp != null) existing.cp = stableCp
                existing.speciesId = def.speciesId
                if (def.dex > 0) existing.dex = def.dex
                existing.identityConfidence = maxOf(existing.identityConfidence, confidence)
                rememberOwnPokemon(
                    def.speciesName,
                    stableCp,
                    def.speciesId,
                    def.dex.takeIf { it > 0 },
                    confidence
                )
                return
            }

            if (ownTeamSlots.size >= 3) return
            rememberOwnPokemon(
                def.speciesName,
                stableCp,
                def.speciesId,
                def.dex.takeIf { it > 0 },
                confidence
            )
        }

        listOf(top, bottom).forEachIndexed { index, evidence ->
            updateCp(index, evidence.cp)
            updateSpecies(index, evidence)
            updateDex(index, evidence)
            learnMissingMember(index, evidence)
        }
        rebindOwnActiveStateToStableSlot()
    }

    fun onReserveCardCps(topCp: Int?, bottomCp: Int?) =
        onReserveCardEvidence(ReserveCardEvidence(topCp), ReserveCardEvidence(bottomCp))

    fun needsReserveCardScan(): Boolean {
        val confirmedCards = resolvedOwnReserves().filter { it.confirmed }.map { it.cardIndex }.toSet()
        return ReserveScanPolicy.shouldScan(
            hasActivePlayer = !playerName.isNullOrBlank(),
            knownTeamSize = ownTeamSlots.size,
            cardCps = reserveCardCps.toList(),
            cardDexes = reserveCardDexes.toList(),
            cardSpeciesIds = reserveCardSpeciesIds.toList(),
            cardMappingConfirmed = (0..1).map { it in confirmedCards }
        )
    }

    private fun clearReserveCardIdentity() {
        reserveCardHpRatios.fill(null)
        reserveCardCps.fill(null)
        reserveCpCandidate.fill(null)
        reserveCpStreak.fill(0)
        reserveCardDexes.fill(null)
        reserveDexCandidate.fill(null)
        reserveDexStreak.fill(0)
        reserveCardSpeciesIds.fill(null)
        reserveSpeciesCandidate.fill(null)
        reserveSpeciesStreak.fill(0)
    }

    fun needsOwnTeamScan(): Boolean =
        ownTeamSlots.size < 3 || ownTeamSlots.any { it.cp == null }
    internal fun battleCorrespondenceContext(
        inactiveForMs: Long,
        sessionAgeMs: Long
    ): BattleCorrespondenceTracker.Context {
        fun identity(
            name: String?,
            cp: Int?,
            speciesId: String? = null
        ): BattleCorrespondenceTracker.Identity? {
            if (name.isNullOrBlank() && speciesId.isNullOrBlank()) return null
            val def = speciesId?.let(repo::pokemon) ?: repo.pokemon(name)
            return BattleCorrespondenceTracker.Identity(
                name = def?.speciesName ?: name,
                speciesId = speciesId ?: def?.speciesId,
                cp = cp,
                confidence = 1f
            )
        }

        val team = if (ownTeamSlots.isNotEmpty()) {
            ownTeamSlots.map { slot ->
                BattleCorrespondenceTracker.Identity(
                    name = slot.name,
                    speciesId = slot.speciesId ?: slotDefinition(slot)?.speciesId,
                    cp = slot.cp,
                    confidence = slot.identityConfidence
                        .coerceAtLeast(IdentityThresholds.MIN_STABLE_TEAM_CONFIDENCE)
                )
            }
        } else {
            ownTeam.mapNotNull { (name, cp) -> identity(name, cp) }
        }
        val opponents = enemyStates.all()
            .mapNotNull { state -> identity(state.speciesName, null, state.speciesId) }
            .distinctBy { it.stableKey() }

        return BattleCorrespondenceTracker.Context(
            activePlayer = identity(playerName, playerCp, playerSpeciesId),
            activeOpponent = identity(enemyName, enemyCp, enemySpeciesId),
            ownTeam = team,
            knownOpponents = opponents,
            inactiveForMs = inactiveForMs.coerceAtLeast(0L),
            sessionAgeMs = sessionAgeMs.coerceAtLeast(0L)
        )
    }

    internal fun battleCorrespondenceObservation(
        detection: BattleDetection,
        pairedCards: Boolean,
        battleTextEvidence: Boolean,
        switchPrompt: Boolean
    ): BattleCorrespondenceTracker.Observation {
        fun identity(
            pokemon: DetectedPokemon?,
            ownPlayer: Boolean
        ): BattleCorrespondenceTracker.Identity? {
            pokemon ?: return null

            // For our side, a CP uniquely pinned to one already-known team slot is
            // stronger than a generic top-card OCR name and preserves regional/forms.
            val pinnedOwn = if (ownPlayer && pokemon.cp != null) {
                ownTeamSlots.filter { it.cp == pokemon.cp }.singleOrNull()
            } else {
                null
            }
            val def = pinnedOwn?.speciesId?.let(repo::pokemon)
                ?: pinnedOwn?.let(::slotDefinition)
                ?: pokemon.visualSpeciesId?.let(repo::pokemon)
                ?: repo.pokemon(pokemon.name)
            val confidence = maxOf(
                pokemon.confidence,
                pokemon.visualConfidence ?: 0f
            ).coerceIn(0f, 1f)
            return BattleCorrespondenceTracker.Identity(
                name = def?.speciesName ?: pinnedOwn?.name ?: pokemon.name,
                speciesId = pinnedOwn?.speciesId ?: def?.speciesId ?: pokemon.visualSpeciesId,
                cp = pokemon.cp,
                confidence = confidence
            )
        }
        return BattleCorrespondenceTracker.Observation(
            player = identity(detection.player, ownPlayer = true),
            opponent = identity(detection.opponent, ownPlayer = false),
            pairedCards = pairedCards,
            battleTextEvidence = battleTextEvidence,
            switchPrompt = switchPrompt
        )
    }

    fun activePlayerName(): String? = playerName
    fun activeEnemyName(): String? = enemyName
    fun latestPlayerHpRatio(): Float? = lastHpRatio
    fun latestOpponentHpRatio(): Float? = lastOpponentHpRatio
    fun currentFastDurationMs(): Long {
        ensurePredictiveEnergyInitialized()
        val manualOrOnlyHypothesis =
            activeState?.fastMoveManual == true || predictiveEnergy.hypothesisCount() <= 1
        return if (manualOrOnlyHypothesis) {
            effectiveEnemyFastMove()?.cooldown?.toLong()?.coerceIn(500L, 3_500L) ?: 1_000L
        } else {
            (predictiveEnergy.likelyFastTurns() * EnemyEnergyTracker.TURN_MS)
                .toLong()
                .coerceIn(500L, 3_500L)
        }
    }
    fun currentOwnFastDurationMs(): Long = ownFast?.cooldown?.toLong()?.coerceIn(500L, 3_500L) ?: 1_000L

    private fun samePokemonIdentity(
        aName: String?,
        aSpeciesId: String?,
        bName: String?,
        bSpeciesId: String?
    ): Boolean {
        if (!aSpeciesId.isNullOrBlank() && !bSpeciesId.isNullOrBlank()) {
            return normalize(aSpeciesId) == normalize(bSpeciesId)
        }
        if (aName.isNullOrBlank() || bName.isNullOrBlank()) return aName == bName
        val ad = aSpeciesId?.let(repo::pokemon) ?: repo.pokemon(aName)
        val bd = bSpeciesId?.let(repo::pokemon) ?: repo.pokemon(bName)
        if (ad != null && bd != null) {
            return normalize(ad.speciesId) == normalize(bd.speciesId)
        }
        return aName.equals(bName, ignoreCase = true)
    }

    private fun slotDefinition(slot: OwnTeamSlot) =
        slot.speciesId?.let(repo::pokemon) ?: repo.pokemon(slot.name)

    private fun findOwnSlot(name: String, cp: Int?, speciesId: String?): OwnTeamSlot? {
        speciesId?.takeIf { it.isNotBlank() }?.let { exact ->
            ownTeamSlots.firstOrNull {
                !it.speciesId.isNullOrBlank() && normalize(it.speciesId!!) == normalize(exact)
            }?.let { return it }
        }
        cp?.let { value ->
            ownTeamSlots.filter { it.cp == value }.singleOrNull()?.let { return it }
        }
        return ownTeamSlots.firstOrNull {
            it.name.equals(name, ignoreCase = true) &&
                (cp == null || it.cp == null || it.cp == cp)
        }
    }

    private fun rememberOwnPokemon(
        name: String,
        cp: Int?,
        speciesId: String? = null,
        dex: Int? = null,
        confidence: Float = 0f
    ) {
        val existingKey = ownTeam.keys.firstOrNull { it.equals(name, ignoreCase = true) }
        if (existingKey == null && ownTeam.size < 3) ownTeam[name] = cp
        else if (existingKey != null && cp != null) ownTeam[existingKey] = cp

        val slot = findOwnSlot(name, cp, speciesId)
        if (slot != null) {
            if (cp != null) slot.cp = cp
            if (!speciesId.isNullOrBlank()) slot.speciesId = speciesId
            if (dex != null && dex > 0) slot.dex = dex
            slot.identityConfidence = maxOf(slot.identityConfidence, confidence)
            slotDefinition(slot)?.let { def ->
                slot.name = def.speciesName
                slot.speciesId = def.speciesId
                if (def.dex > 0) slot.dex = def.dex
            }
        } else if (
            ownTeamSlots.size < 3 &&
            !speciesId.isNullOrBlank() &&
            confidence >= ReserveMatchupPolicy.MIN_STABLE_TEAM_IDENTITY_CONFIDENCE
        ) {
            val nextSlot = (0..2).firstOrNull { candidate ->
                ownTeamSlots.none { it.slot == candidate }
            } ?: ownTeamSlots.size
            val def = repo.pokemon(speciesId)
            ownTeamSlots += OwnTeamSlot(
                slot = nextSlot,
                name = def?.speciesName ?: name,
                cp = cp,
                speciesId = def?.speciesId ?: speciesId,
                dex = dex ?: def?.dex?.takeIf { it > 0 },
                identityConfidence = confidence
            )
        }
    }

    private fun ownIdentityKey(
        name: String,
        cp: Int?,
        speciesId: String? = null
    ): String {
        val exact = findOwnSlot(name, cp, speciesId ?: repo.pokemon(name)?.speciesId)
        return if (exact != null) {
            val identity = exact.speciesId ?: slotDefinition(exact)?.speciesId ?: exact.name
            "slot${exact.slot}:${normalize(identity)}:${exact.cp ?: cp ?: 0}"
        } else {
            val identity = speciesId ?: repo.pokemon(name)?.speciesId ?: name
            "own:${normalize(identity)}:${cp ?: 0}"
        }
    }

    private fun rebindOwnActiveStateToStableSlot() {
        val name = playerName ?: return
        val old = ownActiveState ?: return
        val stableKey = ownIdentityKey(name, playerCp, playerSpeciesId)
        if (old.key == stableKey) {
            if (!playerSpeciesId.isNullOrBlank()) old.speciesId = playerSpeciesId
            return
        }
        val target = ownStates.getOrCreate(stableKey, name, playerSpeciesId)
        target.speciesId = playerSpeciesId ?: old.speciesId
        target.fastMoveId = old.fastMoveId
        target.chargedMove1Id = old.chargedMove1Id
        target.chargedMove2Id = old.chargedMove2Id
        target.fastMoveCount = old.fastMoveCount
        target.estimatedEnergy = old.estimatedEnergy
        target.minEnergy = old.minEnergy
        target.maxEnergy = old.maxEnergy
        target.energyUncertain = old.energyUncertain
        target.lastChargedMoveId = old.lastChargedMoveId
        target.charge1Confirmed = old.charge1Confirmed
        target.charge2Confirmed = old.charge2Confirmed
        target.fastMoveManual = old.fastMoveManual
        target.charge1Manual = old.charge1Manual
        target.charge2Manual = old.charge2Manual
        target.observedChargedMoveIds.clear()
        target.observedChargedMoveIds.addAll(old.observedChargedMoveIds)
        target.attackStageMin = old.attackStageMin
        target.attackStageMax = old.attackStageMax
        target.defenseStageMin = old.defenseStageMin
        target.defenseStageMax = old.defenseStageMax
        target.lastHpRatio = old.lastHpRatio
        target.fainted = old.fainted
        target.lastSeenAtMs = old.lastSeenAtMs
        ownActiveState = target
    }

    private fun onPlayerChanged(name: String, cp: Int?, speciesId: String? = null) {
        saveOwnActiveState()
        resetStages(ownActiveState)
        ownChargedCycle.reset()
        pendingIncomingChargedDamage = null
        pendingOutgoingChargedDamage = null
        pendingIncomingChargedHpBefore = null
        pendingOutgoingChargedHpBefore = null
        playerName = name
        playerSpeciesId = speciesId ?: repo.pokemon(name)?.speciesId
        playerCp = cp

        val exactSlot = findOwnSlot(name, cp, playerSpeciesId)
        if (exactSlot != null) {
            playerName = slotDefinition(exactSlot)?.speciesName ?: exactSlot.name
            playerSpeciesId = exactSlot.speciesId ?: slotDefinition(exactSlot)?.speciesId ?: playerSpeciesId
            // The exact form slot is safer than a display-name fallback when two forms
            // can share a name. Keep a valid observed CP, otherwise restore the slot CP.
            playerCp = cp ?: exactSlot.cp
        } else if (playerCp == null) {
            playerCp = ownTeam.entries.firstOrNull { it.key.equals(name, true) }?.value
        }

        val displayName = playerName ?: name
        val lookup = playerSpeciesId ?: displayName
        val key = ownIdentityKey(displayName, playerCp, playerSpeciesId)
        ownActiveState = ownStates.getOrCreate(key, displayName, playerSpeciesId)
        val saved = ownActiveState!!
        val likely = repo.likelyMoveset(lookup)
        ownFast = repo.move(saved.fastMoveId) ?: likely.first ?: repo.possibleFastMoves(lookup).firstOrNull()
        resolveChargedPair(lookup, saved.chargedMove1Id, saved.chargedMove2Id, likely.second, likely.third).also { (c1, c2) ->
            ownCharge1 = c1
            ownCharge2 = c2
        }
        val minE = if (saved.maxEnergy > 0 || saved.minEnergy > 0 || saved.energyUncertain) saved.minEnergy else saved.estimatedEnergy
        val maxE = if (saved.maxEnergy > 0 || saved.minEnergy > 0 || saved.energyUncertain) saved.maxEnergy else saved.estimatedEnergy
        ownTracker.restoreRange(minE, maxE, saved.fastMoveCount)
        lastHpRatio = saved.lastHpRatio
        saveOwnActiveState()
    }

    private fun onEnemyChanged(name: String, speciesId: String? = null) {
        saveActiveState()
        resetStages(activeState)
        pendingIncomingChargedDamage = null
        pendingOutgoingChargedDamage = null
        pendingIncomingChargedHpBefore = null
        pendingOutgoingChargedHpBefore = null
        localStrategyProfile = null
        enemyName = name
        enemySpeciesId = speciesId ?: repo.pokemon(name)?.speciesId
        chargedPendingSinceMs = null
        enemyChargedCycle.reset()
        ignoreFastUntilMs = 0L
        val lookup = enemySpeciesId ?: name
        val key = normalize(lookup)
        activeState = enemyStates.getOrCreate(key, name, enemySpeciesId)
        val saved = activeState!!
        val likely = repo.likelyMoveset(lookup)
        fast = repo.move(saved.fastMoveId) ?: likely.first ?: repo.possibleFastMoves(lookup).firstOrNull()
        resolveChargedPair(lookup, saved.chargedMove1Id, saved.chargedMove2Id, likely.second, likely.third).also { (c1, c2) ->
            charge1 = c1
            charge2 = c2
        }
        val weightedFast = repo.weightedFastMoves(lookup)
        predictiveEnergy.reset(weightedFast, saved.predictiveEnergySnapshot)
        if (saved.fastMoveManual || weightedFast.size <= 1) {
            fast?.let(predictiveEnergy::confirmFastMove)
        }
        if (predictiveEnergy.isInitialized) {
            syncLegacyEnemyEnergyFromPredictive()
        } else {
            val minE = if (saved.maxEnergy > 0 || saved.minEnergy > 0 || saved.energyUncertain) saved.minEnergy else saved.estimatedEnergy
            val maxE = if (saved.maxEnergy > 0 || saved.minEnergy > 0 || saved.energyUncertain) saved.maxEnergy else saved.estimatedEnergy
            tracker.restoreRange(minE, maxE, saved.fastMoveCount)
        }
        lastEnemyFastEventAtMs = 0L
        lastPlayerHpVisibleAtMs = 0L
        saveActiveState()
    }

    fun selectFast(move: MoveDef) {
        fast = move
        activeState?.apply { fastMoveId = move.moveId; fastMoveManual = true }
        ensurePredictiveEnergyInitialized()
        predictiveEnergy.confirmFastMove(move)
        syncLegacyEnemyEnergyFromPredictive()
        saveActiveState()
    }

    fun clearFastManualOverride() {
        val state = activeState ?: return
        state.fastMoveManual = false
        val recommended = (activeState?.speciesId ?: enemySpeciesId ?: enemyName)
            ?.let(repo::likelyMoveset)
            ?.first ?: return
        fast = recommended
        state.fastMoveId = recommended.moveId
        saveActiveState()
    }

    fun selectCharged1(move: MoveDef) {
        charge1 = move
        activeState?.apply { chargedMove1Id = move.moveId; charge1Manual = true; charge1Confirmed = false }
        saveActiveState()
    }

    fun selectCharged2(move: MoveDef) {
        charge2 = move
        activeState?.apply { chargedMove2Id = move.moveId; charge2Manual = true; charge2Confirmed = false }
        saveActiveState()
    }

    fun clearChargedManualOverride(index: Int) {
        val state = activeState ?: return
        val rankingKey = activeState?.speciesId ?: enemySpeciesId ?: enemyName ?: return
        val likely = repo.likelyMoveset(rankingKey)
        when (index) {
            0 -> {
                state.charge1Manual = false
                val recommended = likely.second ?: repo.possibleChargedMoves(state.speciesId ?: state.speciesName).getOrNull(0)
                if (recommended != null && recommended.moveId != charge2?.moveId) {
                    charge1 = recommended; state.chargedMove1Id = recommended.moveId
                    state.charge1Confirmed = recommended.moveId in state.observedChargedMoveIds
                }
            }
            1 -> {
                state.charge2Manual = false
                val recommended = likely.third ?: repo.possibleChargedMoves(state.speciesId ?: state.speciesName).getOrNull(1)
                if (recommended != null && recommended.moveId != charge1?.moveId) {
                    charge2 = recommended; state.chargedMove2Id = recommended.moveId
                    state.charge2Confirmed = recommended.moveId in state.observedChargedMoveIds
                }
            }
        }
        saveActiveState()
    }

    fun manualFastDelta(delta: Int) {
        val f = fast ?: return
        ensurePredictiveEnergyInitialized()
        if (predictiveEnergy.isInitialized) {
            predictiveEnergy.manualFastDelta(delta, if (activeState?.fastMoveManual == true) f else null)
            syncLegacyEnemyEnergyFromPredictive()
        } else {
            if (delta > 0) tracker.onFastMove(f, delta)
            if (delta < 0) tracker.undoFastMove(f, -delta)
        }
        lastAutoSource = "manual"
        saveActiveState()
    }

    fun onFastDetected(
        count: Int,
        confidence: Float,
        source: String,
        hpRatio: Float?,
        motion: Float,
        damageFraction: Float? = null,
        nowMs: Long = System.currentTimeMillis()
    ) {
        lastDetectorConfidence = confidence
        lastAutoSource = source
        hpRatio?.let { lastHpRatio = it.coerceIn(0f, 1f) }
        lastMotion = motion
        if (count <= 0) return
        if (nowMs < ignoreFastUntilMs) { lastAutoSource = "charged-guard"; return }
        val f = effectiveEnemyFastMove() ?: return
        if (fast?.moveId != f.moveId) {
            fast = f
            activeState?.fastMoveId = f.moveId
        }
        ensurePredictiveEnergyInitialized()
        val observedInterval = lastEnemyFastEventAtMs
            .takeIf { it > 0L }
            ?.let { (nowMs - it).coerceAtLeast(0L) }
        enemyFastPhase.observeCompletion(
            candidate = f,
            count = count,
            nowMs = nowMs,
            detectorConfidence = confidence,
            observedIntervalMs = observedInterval
        )
        if (predictiveEnergy.isInitialized) {
            predictiveEnergy.observeFastCompletion(
                count = count,
                eventId = nowMs,
                confidence = confidence,
                observedIntervalMs = observedInterval
            )
            predictiveEnergy.reweightByDamageEvidence(
                observedDamageFraction = damageFraction,
                hits = count,
                expectedDamagePercent = ::expectedEnemyFastDamagePercent
            )
            if (activeState?.fastMoveManual == true) {
                predictiveEnergy.confirmFastMove(f)
            } else {
                predictiveEnergy.fastMoveInference()?.let { inference ->
                    val strongEnough = inference.observedEvents >= 2 &&
                        ((inference.probability >= .72f && inference.margin >= .18f) ||
                            inference.probability >= .88f)
                    if (strongEnough) {
                        val inferredFast = inference.move
                        fast = inferredFast
                        activeState?.fastMoveId = inferredFast.moveId
                        if (inferredFast.moveId != f.moveId) {
                            // Re-anchor this same observed completion to the newly identified
                            // legal Fast Move instead of losing phase until the next event.
                            enemyFastPhase.observeCompletion(
                                candidate = inferredFast,
                                count = count,
                                nowMs = nowMs,
                                detectorConfidence = confidence,
                                observedIntervalMs = observedInterval
                            )
                        } else {
                            enemyFastPhase.confirmMove(inferredFast)
                        }
                        lastAutoSource = source + "+cadencia/dano:" + inferredFast.moveId
                    }
                }
            }
            syncLegacyEnemyEnergyFromPredictive()
        }
        lastEnemyFastEventAtMs = nowMs
        incomingDamageTracker.record(
            attackerKey = currentEnemyDamageKey(),
            defenderKey = currentPlayerDamageKey(),
            moveKey = ObservedDamageTracker.FAST_MOVE_KEY,
            totalDamageFraction = damageFraction,
            hits = count,
            confidence = confidence,
            charged = false
        )
        if (!predictiveEnergy.isInitialized) tracker.onFastMove(f, count)
        saveActiveState()
    }

    fun onOwnFastDetected(count: Int, confidence: Float, source: String, opponentHpRatio: Float?) {
        lastDetectorConfidence = maxOf(lastDetectorConfidence, confidence)
        lastOwnAutoSource = source
        lastOpponentHpRatio = opponentHpRatio
        if (count <= 0) return
        val f = ownFast ?: return
        ownTracker.onFastMove(f, count)
        saveOwnActiveState()
    }

    fun onHpObservation(
        playerHpRatio: Float?,
        opponentHpRatio: Float?,
        nowMs: Long = System.currentTimeMillis()
    ): List<DamageObservation> {
        playerHpRatio?.let { value ->
            lastHpRatio = value.coerceIn(0f, 1f)
            lastPlayerHpVisibleAtMs = nowMs
            ownActiveState?.let { state ->
                state.lastHpRatio = lastHpRatio
                if (value <= .01f) state.fainted = true
            }
        }
        opponentHpRatio?.let { lastOpponentHpRatio = it.coerceIn(0f, 1f) }

        val outcomes = mutableListOf<DamageObservation>()
        processChargedOutcome(
            pending = pendingIncomingChargedDamage,
            hpRatio = playerHpRatio,
            nowMs = nowMs,
            targetIsOwn = true
        )?.let { outcome ->
            outcomes += outcome
            pendingIncomingChargedDamage = null
        }
        processChargedOutcome(
            pending = pendingOutgoingChargedDamage,
            hpRatio = opponentHpRatio,
            nowMs = nowMs,
            targetIsOwn = false
        )?.let { outcome ->
            outcomes += outcome
            pendingOutgoingChargedDamage = null
        }

        if (pendingIncomingChargedDamage?.let { nowMs - it.armedAtMs > CHARGED_DAMAGE_OBSERVATION_WINDOW_MS } == true) {
            pendingIncomingChargedDamage = null
        }
        if (pendingOutgoingChargedDamage?.let { nowMs - it.armedAtMs > CHARGED_DAMAGE_OBSERVATION_WINDOW_MS } == true) {
            pendingOutgoingChargedDamage = null
        }
        return outcomes
    }

    private fun processChargedOutcome(
        pending: PendingChargedDamage?,
        hpRatio: Float?,
        nowMs: Long,
        targetIsOwn: Boolean
    ): DamageObservation? {
        val sample = pending ?: return null
        if (nowMs - sample.armedAtMs > CHARGED_DAMAGE_OBSERVATION_WINDOW_MS) return null
        val hp = hpRatio ?: return null
        sample.observations++
        if (hp < sample.minimumHpAfter) sample.minimumHpAfter = hp

        val damage = (sample.hpBefore - sample.minimumHpAfter).coerceIn(0f, 1f)
        if (damage >= MIN_CHARGED_DAMAGE_SAMPLE_FRACTION && sample.firstDropAtMs == null) {
            sample.firstDropAtMs = nowMs
        }

        val firstDropAt = sample.firstDropAtMs
        if (firstDropAt != null && nowMs - firstDropAt >= CHARGED_DAMAGE_SETTLE_MS) {
            val accepted = incomingDamageTracker.record(
                attackerKey = sample.attackerKey,
                defenderKey = sample.defenderKey,
                moveKey = sample.moveId,
                totalDamageFraction = damage,
                hits = 1,
                confidence = 1f,
                charged = true
            )
            if (!accepted) return DamageObservation(
                actor = sample.actor,
                moveId = sample.moveId,
                moveName = sample.moveName,
                damagePercent = damage * 100f,
                damagePoints = (damage * 1000f).toInt(),
                learnedSamples = 0
            )
            val estimate = incomingDamageTracker.estimate(
                sample.attackerKey,
                sample.defenderKey,
                sample.moveId,
                if (targetIsOwn) lastHpRatio else lastOpponentHpRatio
            )
            return DamageObservation(
                actor = sample.actor,
                moveId = sample.moveId,
                moveName = sample.moveName,
                damagePercent = damage * 100f,
                damagePoints = (damage * 1000f).toInt(),
                learnedSamples = estimate?.samples ?: 1
            )
        }

        // A shielded Charged Move normally changes the visible HP bar by virtually
        // nothing. Wait long enough for the impact animation and require several
        // stable HP observations before consuming a shield, so a delayed frame cannot
        // turn into a false shield event.
        val shieldsKnown = if (targetIsOwn) ownShieldsKnown else opponentShieldsKnown
        val defenderShields = if (targetIsOwn) ownShieldsRemaining else opponentShieldsRemaining
        if (
            ShieldObservationPolicy.isShieldLikely(
                damageFraction = damage,
                observations = sample.observations,
                elapsedMs = nowMs - sample.armedAtMs,
                // When capture started mid-battle, only the use itself can be inferred;
                // the exact remaining count is intentionally unknown.
                shieldsRemaining = if (shieldsKnown) defenderShields else 1
            )
        ) {
            val remaining = if (!shieldsKnown) {
                null
            } else if (targetIsOwn) {
                if (ownShieldsRemaining <= 0) return null
                ownShieldsRemaining = (ownShieldsRemaining - 1).coerceAtLeast(0)
                ownShieldsRemaining
            } else {
                if (opponentShieldsRemaining <= 0) return null
                opponentShieldsRemaining = (opponentShieldsRemaining - 1).coerceAtLeast(0)
                opponentShieldsRemaining
            }
            return DamageObservation(
                actor = sample.actor,
                moveId = sample.moveId,
                moveName = sample.moveName,
                damagePercent = damage * 100f,
                damagePoints = (damage * 1000f).toInt(),
                learnedSamples = 0,
                shielded = true,
                shieldsRemaining = remaining
            )
        }
        return null
    }

    private fun armIncomingChargedDamage(move: MoveDef, nowMs: Long) {
        val before = pendingIncomingChargedHpBefore ?: lastHpRatio ?: return
        val attacker = currentEnemyDamageKey() ?: return
        val defender = currentPlayerDamageKey() ?: return
        pendingIncomingChargedDamage = PendingChargedDamage(
            actor = "INIMIGO",
            moveId = move.moveId,
            moveName = move.name,
            attackerKey = attacker,
            defenderKey = defender,
            hpBefore = before.coerceIn(0f, 1f),
            armedAtMs = nowMs
        )
        pendingIncomingChargedHpBefore = null
    }

    private fun armOutgoingChargedDamage(move: MoveDef, nowMs: Long) {
        val before = pendingOutgoingChargedHpBefore ?: lastOpponentHpRatio ?: return
        val attacker = currentPlayerDamageKey() ?: return
        val defender = currentEnemyDamageKey() ?: return
        pendingOutgoingChargedDamage = PendingChargedDamage(
            actor = "VOCÊ",
            moveId = move.moveId,
            moveName = move.name,
            attackerKey = attacker,
            defenderKey = defender,
            hpBefore = before.coerceIn(0f, 1f),
            armedAtMs = nowMs
        )
        pendingOutgoingChargedHpBefore = null
    }

    private fun currentEnemyDamageKey(): String? =
        (enemySpeciesId ?: enemyName)?.takeIf { it.isNotBlank() }?.let(::normalize)

    private fun currentPlayerDamageKey(): String? =
        (playerSpeciesId ?: playerName)?.takeIf { it.isNotBlank() }?.let(::normalize)

    fun chargedUsed(move: MoveDef): Boolean {
        ensurePredictiveEnergyInitialized()
        val ok = if (predictiveEnergy.isInitialized) {
            predictiveEnergy.observeCharged(move).also { accepted ->
                if (accepted) syncLegacyEnemyEnergyFromPredictive()
            }
        } else {
            tracker.onChargedMove(move)
        }
        if (ok) {
            applyChargedStatEffects(move, actorIsEnemy = true)
            recordChargedMove(move, observed = false)
        }
        return ok
    }

    private fun observedChargedUsed(move: MoveDef, nowMs: Long = System.currentTimeMillis()): Boolean {
        ensurePredictiveEnergyInitialized()
        if (predictiveEnergy.isInitialized) {
            predictiveEnergy.observeCharged(move)
            syncLegacyEnemyEnergyFromPredictive()
        } else {
            tracker.onObservedChargedMove(move)
        }
        applyChargedStatEffects(move, actorIsEnemy = true)
        recordChargedMove(move, observed = true)
        chargedPendingSinceMs = null
        lastChargedConfirmedAtMs = nowMs
        lastPlayerHpVisibleAtMs = nowMs
        ignoreFastUntilMs = maxOf(ignoreFastUntilMs, nowMs + CHARGED_FAST_GUARD_MS)
        lastAutoSource = "charged:${move.moveId}"
        saveActiveState()
        return true
    }

    private fun observedUnknownChargedUsed(nowMs: Long = System.currentTimeMillis()): Boolean {
        val candidates = legalEnemyChargedMoves().ifEmpty { possibleChargedMoves().ifEmpty { listOfNotNull(charge1, charge2) } }
        ensurePredictiveEnergyInitialized()
        val accepted = if (predictiveEnergy.isInitialized) {
            predictiveEnergy.observeUnknownCharged(candidates).also {
                if (it) syncLegacyEnemyEnergyFromPredictive()
            }
        } else {
            tracker.onObservedUnknownCharged(candidates)
        }
        if (!accepted) return false
        chargedPendingSinceMs = null
        lastChargedConfirmedAtMs = nowMs
        lastPlayerHpVisibleAtMs = nowMs
        ignoreFastUntilMs = maxOf(ignoreFastUntilMs, nowMs + CHARGED_FAST_GUARD_MS)
        lastAutoSource = "charged:unknown-range"
        saveActiveState()
        return true
    }

    private fun observedOwnChargedUsed(move: MoveDef): Boolean {
        ownTracker.onObservedChargedMove(move)
        applyChargedStatEffects(move, actorIsEnemy = false)
        // During our Charged sequence the opponent may have a Fast Move register ("float").
        // Preserve both worlds instead of silently assuming zero gained energy.
        ensurePredictiveEnergyInitialized()
        if (predictiveEnergy.isInitialized) {
            predictiveEnergy.widenForPossibleMissedFast(1)
            syncLegacyEnemyEnergyFromPredictive()
        }
        val state = ownActiveState
        if (state != null) {
            state.lastChargedMoveId = move.moveId
            state.observedChargedMoveIds += move.moveId
            if (move.moveId == ownCharge1?.moveId) state.charge1Confirmed = true
            if (move.moveId == ownCharge2?.moveId) state.charge2Confirmed = true
        }
        lastOwnAutoSource = "charged:${move.moveId}"
        saveOwnActiveState()
        return true
    }

    private fun recordChargedMove(move: MoveDef, observed: Boolean) {
        val state = activeState
        if (state != null) {
            state.lastChargedMoveId = move.moveId
            if (observed) state.observedChargedMoveIds += move.moveId
            when {
                move.moveId == charge1?.moveId -> state.charge1Confirmed = observed || state.charge1Confirmed
                move.moveId == charge2?.moveId -> state.charge2Confirmed = observed || state.charge2Confirmed
                observed -> autoLearnObservedCharged(state, move)
            }
        }
        saveActiveState()
    }

    private fun autoLearnObservedCharged(state: BattlePokemonState, move: MoveDef) {
        val replaceIndex = ChargedMoveSlotResolver.choose(
            observedMoveId = move.moveId,
            slot1Id = charge1?.moveId,
            slot2Id = charge2?.moveId,
            slot1Manual = state.charge1Manual,
            slot2Manual = state.charge2Manual,
            slot1Confirmed = state.charge1Confirmed,
            slot2Confirmed = state.charge2Confirmed
        )
        when (replaceIndex) {
            0 -> {
                charge1 = move
                state.chargedMove1Id = move.moveId
                state.charge1Confirmed = true
            }
            1 -> {
                charge2 = move
                state.chargedMove2Id = move.moveId
                state.charge2Confirmed = true
            }
        }
    }

    fun onBattleText(raw: String, nowMs: Long = System.currentTimeMillis()): BattleTextResult {
        if (raw.isBlank()) {
            ownChargedCycle.observe(null, nowMs)
            enemyChargedCycle.observe(null, nowMs)
            return BattleTextResult()
        }

        val text = normalizeText(raw)
        var newPrompt = false

        if (CHARGED_PROMPTS.any { text.contains(it) }) {
            if (chargedPendingSinceMs == null && nowMs - lastChargedConfirmedAtMs > UNKNOWN_CHARGED_DEBOUNCE_MS) {
                chargedPendingSinceMs = nowMs
                pendingIncomingChargedHpBefore = lastHpRatio
                newPrompt = true
            }
        }

        val actionVerbPresent = ACTION_VERBS.any { text.contains(it) }
        if (!actionVerbPresent) {
            ownChargedCycle.observe(null, nowMs)
            enemyChargedCycle.observe(null, nowMs)
            return BattleTextResult(newPrompt, false, false)
        }

        val normalizedPlayer = playerName?.let(::normalizeText).orEmpty()
        val normalizedEnemy = enemyName?.let(::normalizeText).orEmpty()
        val playerActor = normalizedPlayer.isNotBlank() && text.contains(normalizedPlayer)
        val enemyActor = normalizedEnemy.isNotBlank() && text.contains(normalizedEnemy)

        if (playerActor) {
            enemyChargedCycle.observe(null, nowMs)
            val ownCandidates = (ownActiveState?.speciesId ?: playerSpeciesId ?: playerName)
                ?.let(repo::possibleChargedMoves)
                .orEmpty()
                .ifEmpty { listOfNotNull(ownCharge1, ownCharge2) }
            val ownMatched = findMoveMention(text, ownCandidates)
            if (ownMatched != null) {
                val signature = ChargedAnimationCycleGate.Signature(
                    actorKey = normalizeText(playerName ?: "voce"),
                    moveId = ownMatched.moveId
                )
                if (ownChargedCycle.observe(signature, nowMs)) {
                    pendingOutgoingChargedHpBefore = lastOpponentHpRatio
                    armOutgoingChargedDamage(ownMatched, nowMs)
                    observedOwnChargedUsed(ownMatched)
                    return BattleTextResult(newPrompt, false, true, ownChargedMove = ownMatched)
                }
                return BattleTextResult(newPrompt, false, false)
            }
            ownChargedCycle.observe(null, nowMs)
        }

        if (enemyActor || !playerActor) {
            ownChargedCycle.observe(null, nowMs)
            val candidates = possibleChargedMoves().ifEmpty { listOfNotNull(charge1, charge2) }
            val matched = findMoveMention(text, candidates)
            if (matched != null) {
                val signature = ChargedAnimationCycleGate.Signature(
                    actorKey = normalizeText(enemyName ?: "inimigo"),
                    moveId = matched.moveId
                )
                if (enemyChargedCycle.observe(signature, nowMs)) {
                    armIncomingChargedDamage(matched, nowMs)
                    observedChargedUsed(matched, nowMs)
                    return BattleTextResult(newPrompt, true, false, enemyChargedMove = matched)
                }
                return BattleTextResult(newPrompt, false, false)
            }
            enemyChargedCycle.observe(null, nowMs)
            if (chargedPendingSinceMs == null && nowMs - lastChargedConfirmedAtMs > UNKNOWN_CHARGED_DEBOUNCE_MS) {
                chargedPendingSinceMs = nowMs
                newPrompt = true
            }
        }
        return BattleTextResult(newPrompt, false, false)
    }

    private fun findMoveMention(text: String, candidates: List<MoveDef>): MoveDef? = candidates
        .distinctBy { it.moveId }
        .map { it to normalizedMoveNames(it) }
        .firstOrNull { (_, names) -> names.any { n -> n.length >= 4 && text.contains(n) } }
        ?.first

    fun maybeConfirmPendingCharged(nowMs: Long = System.currentTimeMillis()): Boolean {
        val since = chargedPendingSinceMs ?: return false
        if (nowMs - since < PENDING_CONFIRM_DELAY_MS) return false
        if (nowMs - lastChargedConfirmedAtMs <= UNKNOWN_CHARGED_DEBOUNCE_MS) {
            chargedPendingSinceMs = null
            return false
        }
        return observedUnknownChargedUsed(nowMs)
    }

    /**
     * Model damage for a legal enemy Fast Move. It is used only to identify which Fast
     * Move best explains an HP drop that the detector already confirmed.
     */
    private fun expectedEnemyFastDamagePercent(move: MoveDef): Float? {
        val attacker = repo.pokemon(enemySpeciesId ?: enemyName) ?: return null
        val defender = repo.pokemon(playerSpeciesId ?: playerName) ?: return null
        val attackerStats = repo.estimatedBattleStats(attacker.speciesId, enemyCp) ?: return null
        val defenderStats = repo.estimatedBattleStats(defender.speciesId, playerCp) ?: return null
        return DamageForecastEngine.modelDamagePercent(
            attacker = attacker,
            attackerStats = attackerStats,
            defender = defender,
            defenderStats = defenderStats,
            move = move,
            attackerAttackStage = stages(activeState).attack,
            defenderDefenseStage = stages(ownActiveState).defense
        )
    }

    /**
     * If the HP bar is hidden by a large Pokemon/VFX, project only a possible energy
     * window from elapsed Fast-Move durations. No permanent Fast count is added here.
     */
    private fun unobservedEnemyFastWindowMs(nowMs: Long = System.currentTimeMillis()): Long {
        if (lastPlayerHpVisibleAtMs <= 0L) return 0L
        if (lastBattleConfidence < .45f) return 0L
        if (chargedPendingSinceMs != null || nowMs < ignoreFastUntilMs) return 0L
        if (captureHealth == CaptureHealth.STALE || captureHealth == CaptureHealth.PAUSED) return 0L
        val gap = (nowMs - lastPlayerHpVisibleAtMs).coerceAtLeast(0L)
        return if (gap >= 600L) gap.coerceAtMost(MAX_PASSIVE_FAST_PROJECTION_MS) else 0L
    }

    /**
     * Defensive fallback: energy tracking must use an actual Fast Move with positive
     * energy gain. If ranking/default data selected an invalid/zero-energy move,
     * use the first valid Fast Move available for that species.
     */
    private fun effectiveEnemyFastMove(): MoveDef? {
        fast?.takeIf { it.energyGain > 0 }?.let { return it }
        val fallback = (activeState?.speciesId ?: enemySpeciesId ?: enemyName)
            ?.let(repo::possibleFastMoves)
            .orEmpty()
            .firstOrNull { it.energyGain > 0 }
        return fallback
    }

    fun possibleFastMoves(): List<MoveDef> =
        (activeState?.speciesId ?: enemySpeciesId ?: enemyName)
            ?.let(repo::possibleFastMoves)
            .orEmpty()

    fun possibleChargedMoves(): List<MoveDef> =
        (activeState?.speciesId ?: enemySpeciesId ?: enemyName)
            ?.let(repo::possibleChargedMoves)
            .orEmpty()

    private fun legalEnemyFastMoves() =
        (activeState?.speciesId ?: enemySpeciesId ?: enemyName)
            ?.let(repo::weightedFastMoves)
            .orEmpty()

    private fun legalEnemyChargedMoves(): List<MoveDef> =
        (activeState?.speciesId ?: enemySpeciesId ?: enemyName)
            ?.let(repo::legalChargedMoves)
            .orEmpty()

    private fun weightedEnemyChargedMoves() =
        (activeState?.speciesId ?: enemySpeciesId ?: enemyName)
            ?.let(repo::weightedChargedMoves)
            .orEmpty()

    private fun ensurePredictiveEnergyInitialized() {
        if (predictiveEnergy.isInitialized) return
        val candidates = legalEnemyFastMoves()
        if (candidates.isEmpty()) return
        predictiveEnergy.reset(candidates, activeState?.predictiveEnergySnapshot)
        if (activeState?.fastMoveManual == true || candidates.size <= 1) {
            fast?.let(predictiveEnergy::confirmFastMove)
        }
        syncLegacyEnemyEnergyFromPredictive()
    }

    private fun stages(state: BattlePokemonState?): StatStageTracker.Stages =
        StatStageTracker.Stages(
            attack = StatStageRange(
                state?.attackStageMin ?: 0,
                state?.attackStageMax ?: 0
            ).normalized(),
            defense = StatStageRange(
                state?.defenseStageMin ?: 0,
                state?.defenseStageMax ?: 0
            ).normalized()
        )

    private fun writeStages(state: BattlePokemonState?, value: StatStageTracker.Stages) {
        state ?: return
        val attack = value.attack.normalized()
        val defense = value.defense.normalized()
        state.attackStageMin = attack.min
        state.attackStageMax = attack.max
        state.defenseStageMin = defense.min
        state.defenseStageMax = defense.max
    }

    private fun applyChargedStatEffects(move: MoveDef, actorIsEnemy: Boolean) {
        if (move.buffs.size < 2 || move.buffChance <= 0f) return
        val enemy = stages(activeState)
        val own = stages(ownActiveState)
        if (actorIsEnemy) {
            val result = StatStageTracker.applyMove(move, self = enemy, opponent = own)
            writeStages(activeState, result.self)
            writeStages(ownActiveState, result.opponent)
        } else {
            val result = StatStageTracker.applyMove(move, self = own, opponent = enemy)
            writeStages(ownActiveState, result.self)
            writeStages(activeState, result.opponent)
        }
    }

    private fun switchSeconds(readyAtMs: Long, nowMs: Long = System.currentTimeMillis()): Int? {
        val remaining = readyAtMs - nowMs
        if (remaining <= 0L) return null
        return ((remaining + 999L) / 1000L).toInt()
    }

    fun uiState(debug: Boolean = false): BattleUiState {
        val pTypes = repo.pokemon(playerSpeciesId ?: playerName)?.types.orEmpty()
        val eTypes = repo.pokemon(enemySpeciesId ?: enemyName)?.types.orEmpty()
        val matchup = classify(pTypes, eTypes)
        val f = fast
        val of = ownFast

        val resolvedByCard = resolvedOwnReserves().associateBy { it.cardIndex }
        val rawReserves = (0..1).map { cardIndex ->
            val resolved = resolvedByCard[cardIndex]
            if (resolved == null) {
                ReserveState(
                    name = null,
                    speciesId = null,
                    cp = reserveCardCps.getOrNull(cardIndex),
                    types = emptyList(),
                    matchup = MatchupState.UNKNOWN,
                    visible = reserveSlotVisible.getOrNull(cardIndex) != false,
                    score = 0.0,
                    teamSlot = null,
                    identityKey = null,
                    cardCp = reserveCardCps.getOrNull(cardIndex),
                    identityConfirmed = false,
                    cardMappingConfirmed = false,
                    identitySource = "unresolved",
                    hpRatio = reserveCardHpRatios.getOrNull(cardIndex),
                    status = TeamPokemonStatus.UNKNOWN
                )
            } else {
                val slot = resolved.slot
                val def = slotDefinition(slot)
                val name = def?.speciesName ?: slot.name
                val cp = slot.cp
                val speciesKey = slot.speciesId ?: def?.speciesId ?: name
                val types = repo.pokemon(speciesKey)?.types.orEmpty()
                val identity = ownIdentityKey(name, cp, slot.speciesId ?: def?.speciesId)
                val saved = ownStates.find(identity)
                val rankingKey = saved?.speciesId ?: speciesKey
                val likely = repo.likelyMoveset(rankingKey)
                val reserveFast = repo.move(saved?.fastMoveId)
                    ?: likely.first
                    ?: repo.possibleFastMoves(rankingKey).firstOrNull()
                val (reserveC1, reserveC2) = resolveChargedPair(
                    rankingKey,
                    saved?.chargedMove1Id,
                    saved?.chargedMove2Id,
                    likely.second,
                    likely.third
                )
                val stableSpeciesId = slot.speciesId ?: def?.speciesId
                val identityReady = ReserveMatchupPolicy.canEvaluate(
                    speciesId = stableSpeciesId,
                    identityConfidence = slot.identityConfidence,
                    types = types,
                    enemyTypes = eTypes,
                    cardMappingConfirmed = resolved.confirmed
                )
                val hp = if (resolved.confirmed) {
                    reserveCardHpRatios.getOrNull(cardIndex) ?: saved?.lastHpRatio
                } else {
                    saved?.lastHpRatio
                }
                val score = if (identityReady) {
                    reserveScore(
                        name = name,
                        cp = cp,
                        speciesId = stableSpeciesId,
                        myTypes = types,
                        enemyTypes = eTypes,
                        hpRatio = hp
                    )
                } else 0.0
                ReserveState(
                    name = name,
                    speciesId = stableSpeciesId,
                    cp = cp,
                    types = types,
                    matchup = if (identityReady) ReserveMatchupPolicy.classifyScore(score) else MatchupState.UNKNOWN,
                    visible = if (resolved.confirmed) {
                        reserveSlotVisible.getOrNull(cardIndex) != false
                    } else true,
                    score = score,
                    teamSlot = slot.slot,
                    identityKey = identity,
                    cardCp = resolved.cardCp,
                    identityConfirmed = identityReady,
                    cardMappingConfirmed = resolved.confirmed,
                    identitySource = resolved.source,
                    fastMove = reserveFast,
                    chargedMove1 = reserveC1,
                    chargedMove2 = reserveC2,
                    fastMoveKnowledge = moveKnowledge(saved, 0, reserveFast),
                    chargedMove1Knowledge = moveKnowledge(saved, 1, reserveC1),
                    chargedMove2Knowledge = moveKnowledge(saved, 2, reserveC2),
                    hpRatio = hp,
                    status = when {
                        saved?.fainted == true -> TeamPokemonStatus.FAINTED
                        resolved.confirmed && reserveSlotVisible.getOrNull(cardIndex) == false -> TeamPokemonStatus.FAINTED
                        hp != null && hp <= .01f -> TeamPokemonStatus.FAINTED
                        else -> TeamPokemonStatus.RESERVE
                    }
                )
            }
        }
        val bestVisibleScore = rawReserves
            .filter { it.visible && it.identityConfirmed }
            .maxOfOrNull { it.score }
        val reserveStates = rawReserves.map { r ->
            val label = when {
                !r.visible || !r.identityConfirmed -> null
                bestVisibleScore != null && r.score >= bestVisibleScore - .08 && r.score > .05 -> "MELHOR"
                r.score < -.35 -> "EVITAR"
                else -> "NEUTRO"
            }
            r.copy(recommendation = label)
        }

        ensurePredictiveEnergyInitialized()
        val enemyStages = stages(activeState)
        val ownStages = stages(ownActiveState)
        val predictiveForecast = if (predictiveEnergy.isInitialized) {
            ChargedMovePredictor.forecast(
                repo = repo,
                energyTracker = predictiveEnergy,
                chargedMoves = weightedEnemyChargedMoves(),
                attackerId = enemySpeciesId ?: enemyName,
                attackerCp = enemyCp,
                defenderId = playerSpeciesId ?: playerName,
                defenderCp = playerCp,
                currentHpRatio = lastHpRatio,
                reaction = reactionWindow,
                observedDamage = { move ->
                    incomingDamageTracker.estimate(
                        currentEnemyDamageKey(),
                        currentPlayerDamageKey(),
                        move.moveId,
                        lastHpRatio
                    )
                },
                attackerAttackStages = enemyStages.attack,
                defenderDefenseStages = ownStages.defense,
                unobservedFastWindowMs = unobservedEnemyFastWindowMs(),
                fastPhase = enemyFastPhase.snapshot()
            )
        } else null

        val enemyConf = enemyEnergyConfidence()
        val ownConf = ownEnergyConfidence()
        val enemyCharged1Prediction = if (f != null && charge1 != null) tracker.predict(charge1!!, f, enemyConf) else null
        val enemyCharged2Prediction = if (f != null && charge2 != null) tracker.predict(charge2!!, f, enemyConf) else null
        val ownCharged1Prediction = if (of != null && ownCharge1 != null) ownTracker.predict(ownCharge1!!, of, ownConf) else null
        val ownCharged2Prediction = if (of != null && ownCharge2 != null) ownTracker.predict(ownCharge2!!, of, ownConf) else null
        val incomingPreview = if (
            captureHealth != CaptureHealth.STALE &&
            captureHealth != CaptureHealth.PAUSED
        ) {
            if (chargedPendingSinceMs != null) {
                combinedIncomingDamageForecast(
                    listOfNotNull(enemyCharged1Prediction, enemyCharged2Prediction)
                )
            } else {
                predictiveForecast?.candidates
                    ?.firstOrNull {
                        it.readyPossible ||
                            it.turnsRemainingMin <= predictiveForecast.reactionTurns
                    }
                    ?.damageForecast
            }
        } else null
        val cmpForecast = CmpForecastEngine.evaluate(
            repo.attackRangeEstimate(playerSpeciesId ?: playerName, playerCp),
            repo.attackRangeEstimate(enemySpeciesId ?: enemyName, enemyCp)
        )
        val shieldDecision = ShieldDecisionEngine.evaluate(
            forecast = predictiveForecast,
            hpRatio = lastHpRatio,
            shieldsKnown = ownShieldsKnown,
            shieldsRemaining = ownShieldsRemaining
        )
        val ownFastDamagePercent = of?.let { ownFastMove ->
            DamageForecastEngine.forecast(
                repo = repo,
                attackerId = playerSpeciesId ?: playerName,
                attackerCp = playerCp,
                defenderId = enemySpeciesId ?: enemyName,
                defenderCp = enemyCp,
                move = ownFastMove,
                observed = null,
                currentHpRatio = lastOpponentHpRatio,
                attackerAttackStages = ownStages.attack,
                defenderDefenseStages = enemyStages.defense
            )?.averagePercent
        }
        val farmDownForecast = TacticalForecastEngine.farmDown(
            ownFastDamagePercent = ownFastDamagePercent,
            enemyHpRatio = lastOpponentHpRatio,
            ownFastTurns = of?.turns ?: 1,
            enemyForecast = predictiveForecast
        )
        val timingCharged = listOfNotNull(ownCharged1Prediction, ownCharged2Prediction)
            .filter { it.ready }
            .maxByOrNull { prediction ->
                val eff = if (eTypes.isEmpty()) 1.0 else TypeChart.multiplier(prediction.move.type, eTypes)
                prediction.move.power.toDouble() * eff / prediction.energyCost.coerceAtLeast(1)
            }
        val optimalThrowForecast = if (of != null && timingCharged != null) {
            TacticalForecastEngine.optimalThrow(
                phase = predictiveForecast?.fastPhase ?: enemyFastPhase.snapshot(),
                ownFastTurns = of.turns,
                ownEnergy = ownTracker.energy,
                ownFastEnergyGain = of.energyGain,
                chargedCost = timingCharged.energyCost
            )
        } else null
        val sacSwapForecast = TacticalForecastEngine.sacSwap(
            forecast = predictiveForecast,
            currentTypes = pTypes,
            reserves = reserveStates,
            switchSeconds = switchSeconds(ownSwitchReadyAtMs)
        )

        val enemyHistory = enemyStates.all()
            .sortedByDescending { it.lastSeenAtMs }
            .take(3)
            .map { s ->
                val e = if (s.energyUncertain || s.minEnergy != s.maxEnergy) "${s.minEnergy}-${s.maxEnergy}E" else "${s.estimatedEnergy}E"
                val seen = s.lastChargedMoveId?.let(repo::move)?.name
                buildString {
                    append(s.speciesName)
                    append(": ${s.fastMoveCount}F • $e")
                    if (!seen.isNullOrBlank()) append(" • $seen")
                }
            }

        return BattleUiState(
            playerName = playerName,
            playerCp = playerCp,
            playerTypes = pTypes,
            opponentName = enemyName,
            opponentCp = enemyCp,
            opponentTypes = eTypes,
            playerMatchup = matchup,
            strongTypesAgainstOpponent = if (eTypes.isEmpty()) emptyList() else TypeChart.strongTypesAgainst(eTypes),
            reserves = reserveStates,
            fastMove = f,
            charged1 = enemyCharged1Prediction,
            charged2 = enemyCharged2Prediction,
            fastMoveKnowledge = moveKnowledge(activeState, 0, f),
            charged1Knowledge = moveKnowledge(activeState, 1, charge1),
            charged2Knowledge = moveKnowledge(activeState, 2, charge2),
            ownFastMove = of,
            ownCharged1 = ownCharged1Prediction,
            ownCharged2 = ownCharged2Prediction,
            ownFastMoveKnowledge = moveKnowledge(ownActiveState, 0, of),
            ownCharged1Knowledge = moveKnowledge(ownActiveState, 1, ownCharge1),
            ownCharged2Knowledge = moveKnowledge(ownActiveState, 2, ownCharge2),
            ownSwitchSeconds = switchSeconds(ownSwitchReadyAtMs),
            opponentSwitchSeconds = switchSeconds(opponentSwitchReadyAtMs),
            chargedIncoming = chargedPendingSinceMs != null,
            playerHpRatio = lastHpRatio,
            opponentHpRatio = lastOpponentHpRatio,
            playerHpBarYFraction = lastPlayerHpBarYFraction,
            ownShieldsRemaining = ownShieldsRemaining,
            opponentShieldsRemaining = opponentShieldsRemaining,
            ownShieldsKnown = ownShieldsKnown,
            opponentShieldsKnown = opponentShieldsKnown,
            incomingDamagePreview = incomingPreview,
            captureHealth = captureHealth,
            captureStatusText = captureStatusText,
            strategyPatternHint = localStrategyProfile?.let { profile ->
                when {
                    profile.firstChargedMove != null &&
                        profile.chargedSamples >= 3 &&
                        profile.firstChargedRate >= .60f ->
                        "Histórico local: " + profile.firstChargedMove + " foi o primeiro carregado em " +
                            "%.0f".format(profile.firstChargedRate * 100f) + "% de " + profile.battles + " batalha(s)"
                    profile.mostCommonChargedMove != null && profile.chargedSamples >= 4 ->
                        "Histórico local: " + profile.mostCommonChargedMove + " apareceu em " +
                            "%.0f".format(profile.mostCommonChargedRate * 100f) + "% dos carregados observados"
                    else -> null
                }
            },
            strategyPatternConfidence = localStrategyProfile?.let { profile ->
                when {
                    profile.chargedSamples >= 8 -> .85f
                    profile.chargedSamples >= 4 -> .68f
                    profile.chargedSamples >= 3 -> .58f
                    else -> 0f
                }
            } ?: 0f,
            enemyEnergyForecast = predictiveForecast,
            cmpForecast = cmpForecast,
            shieldDecision = shieldDecision,
            optimalThrow = optimalThrowForecast,
            farmDown = farmDownForecast,
            sacSwap = sacSwapForecast,
            enemyAttackStages = enemyStages.attack,
            enemyDefenseStages = enemyStages.defense,
            playerAttackStages = ownStages.attack,
            playerDefenseStages = ownStages.defense,
            incomingFastDamage = incomingDamageTracker.estimate(
                currentEnemyDamageKey(),
                currentPlayerDamageKey(),
                ObservedDamageTracker.FAST_MOVE_KEY,
                lastHpRatio
            ),
            incomingCharged1Damage = charge1?.let { move ->
                incomingDamageTracker.estimate(
                    currentEnemyDamageKey(),
                    currentPlayerDamageKey(),
                    move.moveId,
                    lastHpRatio
                )
            },
            incomingCharged2Damage = charge2?.let { move ->
                incomingDamageTracker.estimate(
                    currentEnemyDamageKey(),
                    currentPlayerDamageKey(),
                    move.moveId,
                    lastHpRatio
                )
            },
            detectorConfidence = lastDetectorConfidence,
            battleConfidence = lastBattleConfidence,
            enemyHistory = enemyHistory,
            debugText = if (debug) buildString {
                val s = activeState
                val os = ownActiveState
                appendLine("Jogador: ${playerName ?: "?"} PC ${playerCp ?: "?"} | slots=${ownTeamSlots.joinToString { "#${it.slot + 1} ${it.name}/${it.cp ?: "?"}" }}")
                appendLine("Own fast: ${of?.name ?: "?"} count=${ownTracker.fastMoveCount} E=${ownTracker.minEnergy}-${ownTracker.maxEnergy} src=$lastOwnAutoSource")
                appendLine("Inimigo: ${enemyName ?: "?"} PC ${enemyCp ?: "?"}")
                appendLine("Fast: ${f?.name ?: "?"} | contagem=${tracker.fastMoveCount} manual=${s?.fastMoveManual == true}")
                appendLine("Energia=${tracker.minEnergy}-${tracker.maxEnergy}/100 | fonte=$lastAutoSource conf=${"%.2f".format(lastDetectorConfidence)}")
                predictiveForecast?.let { pf ->
                    val next = pf.candidates.firstOrNull()
                    appendLine(
                        "Predict=" + pf.energyMin + "-" + pf.energyMax + "E hyp=" + pf.hypothesisCount +
                            " lead=" + pf.leadTimeMs + "ms/" + pf.reactionTurns + "T next=" +
                            (next?.move?.name ?: "?") + ":" + (next?.turnsRemainingMin ?: -1) + "T" +
                            " consistency=" + "%.2f".format(pf.consistencyScore) +
                            " anomalies=" + pf.anomalyCount
                    )
                    pf.fastPhase?.let { phase ->
                        appendLine(
                            "FastPhase=" + phase.phaseTurn + "/" + phase.moveTurns +
                                " reg=" + phase.turnsUntilRegistration + "T conf=" +
                                "%.2f".format(phase.confidence)
                        )
                    }
                }
                appendLine(
                    "Stages E atk=" + enemyStages.attack.min + ".." + enemyStages.attack.max +
                        " def=" + enemyStages.defense.min + ".." + enemyStages.defense.max +
                        " | Own atk=" + ownStages.attack.min + ".." + ownStages.attack.max +
                        " def=" + ownStages.defense.min + ".." + ownStages.defense.max
                )
                appendLine("Battle conf=${"%.2f".format(lastBattleConfidence)} HP=${lastHpRatio?.let { "%.3f".format(it) } ?: "?"}/${lastOpponentHpRatio?.let { "%.3f".format(it) } ?: "?"}")
                appendLine("Reservas vis=${reserveSlotVisible.joinToString { it?.toString() ?: "?" }} CPcards=${reserveCardCps.joinToString { it?.toString() ?: "?" }} DEXcards=${reserveCardDexes.joinToString { it?.toString() ?: "?" }} FORMS=${reserveCardSpeciesIds.joinToString { it ?: "?" }}")
                appendLine("C1=${charge1?.name ?: "?"} conf=${s?.charge1Confirmed} manual=${s?.charge1Manual}")
                appendLine("C2=${charge2?.name ?: "?"} conf=${s?.charge2Confirmed} manual=${s?.charge2Manual}")
                append("Own C1=${ownCharge1?.name ?: "?"} C2=${ownCharge2?.name ?: "?"} state=${os?.speciesName ?: "?"}")
            } else null
        )
    }

    private fun combinedIncomingDamageForecast(
        predictions: List<com.lucianotoscano.pvppokego.data.ChargedPrediction>
    ): DamageForecast? {
        if (predictions.isEmpty()) return null
        val plausible = predictions.filter {
            it.ready || it.maxEnergy >= it.energyCost || it.progress >= .72f
        }.ifEmpty { predictions }

        val forecasts = plausible.mapNotNull { prediction ->
            val move = prediction.move
            val observed = incomingDamageTracker.estimate(
                currentEnemyDamageKey(),
                currentPlayerDamageKey(),
                move.moveId,
                lastHpRatio
            )
            DamageForecastEngine.forecast(
                repo = repo,
                attackerId = enemySpeciesId ?: enemyName,
                attackerCp = enemyCp,
                defenderId = playerSpeciesId ?: playerName,
                defenderCp = playerCp,
                move = move,
                observed = observed,
                currentHpRatio = lastHpRatio,
                attackerAttackStages = stages(activeState).attack,
                defenderDefenseStages = stages(ownActiveState).defense
            )?.let { forecast -> move to forecast }
        }
        if (forecasts.isEmpty()) return null
        if (forecasts.size == 1) return forecasts.first().second

        val minDamage = forecasts.minOf { it.second.minPercent }
        val maxDamage = forecasts.maxOf { it.second.maxPercent }
        val avgDamage = forecasts.maxOf { it.second.averagePercent }
        val currentHpPercent = lastHpRatio?.times(100f)
        val names = forecasts.map { repo.localizedMoveName(it.first) }.distinct()
        val allObserved = forecasts.all {
            it.second.confidence != DamageForecastConfidence.MODEL
        }
        val stableObserved = forecasts.all {
            it.second.confidence == DamageForecastConfidence.OBSERVED_STABLE
        }
        return DamageForecast(
            moveName = names.joinToString(" / ").take(50),
            averagePercent = avgDamage,
            minPercent = minDamage,
            maxPercent = maxDamage,
            projectedRemainingPercent = currentHpPercent?.let { (it - avgDamage).coerceIn(0f, 100f) },
            source = if (allObserved) "dano observado • golpes possíveis" else "modelo PvPoke • golpes possíveis",
            confidence = when {
                stableObserved -> DamageForecastConfidence.OBSERVED_STABLE
                allObserved -> DamageForecastConfidence.OBSERVED_ESTIMATED
                else -> DamageForecastConfidence.MODEL
            }
        )
    }

    private fun enemyEnergyConfidence(): EnergyConfidence = when {
        tracker.isRange -> EnergyConfidence.RANGE
        activeState?.fastMoveManual == true || possibleFastMoves().size <= 1 -> EnergyConfidence.CONFIRMED
        else -> EnergyConfidence.ESTIMATED
    }

    private fun ownEnergyConfidence(): EnergyConfidence = when {
        ownTracker.isRange -> EnergyConfidence.RANGE
        ((ownActiveState?.speciesId ?: playerSpeciesId ?: playerName)
            ?.let(repo::possibleFastMoves)
            ?.size ?: 0) <= 1 -> EnergyConfidence.CONFIRMED
        else -> EnergyConfidence.ESTIMATED
    }

    private fun reserveScore(
        name: String,
        cp: Int?,
        speciesId: String?,
        myTypes: List<String>,
        enemyTypes: List<String>,
        hpRatio: Float?
    ): Double {
        if (myTypes.isEmpty() || enemyTypes.isEmpty()) return 0.0
        if (hpRatio != null && hpRatio <= .01f) return -10.0

        val saved = ownStates.find(ownIdentityKey(name, cp, speciesId))
        val ownRankingKey = saved?.speciesId ?: speciesId ?: name
        val likely = repo.likelyMoveset(ownRankingKey)
        val resolvedFast = repo.move(saved?.fastMoveId)
            ?: likely.first
            ?: repo.possibleFastMoves(ownRankingKey).firstOrNull()
        val (resolvedC1, resolvedC2) = resolveChargedPair(
            ownRankingKey,
            saved?.chargedMove1Id,
            saved?.chargedMove2Id,
            likely.second,
            likely.third
        )
        val myMoves = listOfNotNull(resolvedFast, resolvedC1, resolvedC2)

        val liveEnemyMoves = listOfNotNull(
            fast?.let { it to moveKnowledge(activeState, 0, it) },
            charge1?.let { it to moveKnowledge(activeState, 1, it) },
            charge2?.let { it to moveKnowledge(activeState, 2, it) }
        )
        val trustedEnemyMoves = liveEnemyMoves.filter {
            it.second == MoveKnowledgeConfidence.CONFIRMED ||
                it.second == MoveKnowledgeConfidence.MANUAL
        }.map { it.first }

        val enemyRankingKey = activeState?.speciesId ?: enemySpeciesId ?: enemyName
        val enemyLikely = enemyRankingKey?.let(repo::likelyMoveset)
        val inferredEnemyMoves = listOfNotNull(enemyLikely?.first, enemyLikely?.second, enemyLikely?.third)
        val offense = myMoves.maxOfOrNull { TypeChart.multiplier(it.type, enemyTypes) } ?: 1.0
        val trustedDanger = trustedEnemyMoves.maxOfOrNull { TypeChart.multiplier(it.type, myTypes) }
        val inferredDanger = inferredEnemyMoves.maxOfOrNull { TypeChart.multiplier(it.type, myTypes) } ?: 1.0
        val danger = trustedDanger?.let { maxOf(it, 1.0 + (inferredDanger - 1.0) * .35) } ?: inferredDanger

        val typeBase = when (classify(myTypes, enemyTypes)) {
            MatchupState.FAVORABLE -> .35
            MatchupState.UNFAVORABLE -> -.35
            else -> 0.0
        }
        val hpAdjustment: Double = hpRatio?.let { (it.coerceIn(0f, 1f).toDouble() - 0.5) * 0.34 } ?: 0.0
        val cheapestCharge = listOfNotNull(resolvedC1, resolvedC2)
            .map { it.chargedCost }
            .filter { it > 0 }
            .minOrNull()
        val storedEnergy = saved?.estimatedEnergy ?: 0
        val energyAdjustment = when {
            cheapestCharge != null && storedEnergy >= cheapestCharge -> .16
            storedEnergy >= 70 -> .07
            else -> 0.0
        }
        val shieldAdjustment =
            (if (opponentShieldsRemaining == 0 && offense > 1.0) .06 else 0.0) -
                (if (ownShieldsRemaining == 0 && danger > 1.0) .08 else 0.0)
        val finishingAdjustment = if (
            (lastOpponentHpRatio ?: 1f) <= .22f &&
            resolvedFast != null &&
            TypeChart.multiplier(resolvedFast.type, enemyTypes) > 1.0
        ) .08 else 0.0

        return typeBase +
            (offense - 1.0) * .75 -
            (danger - 1.0) * .65 +
            hpAdjustment +
            energyAdjustment +
            shieldAdjustment +
            finishingAdjustment
    }

    private data class ResolvedOwnReserve(
        val slot: OwnTeamSlot,
        val cardIndex: Int,
        val cardCp: Int?,
        val confirmed: Boolean,
        val source: String
    )

    /**
     * Native reserve-card CPs are authoritative. This replaces the old circular-slot
     * assumption that could place Houndoom's Fire/Dark types under a Rillaboom card.
     */
    private fun resolvedOwnReserves(): List<ResolvedOwnReserve> {
        val slots = if (ownTeamSlots.isNotEmpty()) ownTeamSlots.toList() else {
            ownTeam.entries.mapIndexed { index, (name, cp) -> OwnTeamSlot(index, name, cp) }
        }
        if (slots.isEmpty()) return emptyList()

        val members = slots.map { slot ->
            val def = slotDefinition(slot)
            ReserveCardMatcher.Member(
                slot = slot.slot,
                name = def?.speciesName ?: slot.name,
                cp = slot.cp,
                dex = slot.dex ?: def?.dex?.takeIf { it > 0 },
                speciesId = slot.speciesId ?: def?.speciesId
            )
        }
        return ReserveCardMatcher.resolve(
            team = members,
            activeName = playerName,
            activeCp = playerCp,
            cardCps = reserveCardCps.toList(),
            cardDexes = reserveCardDexes.toList(),
            cardSpeciesIds = reserveCardSpeciesIds.toList()
        ).mapNotNull { match ->
            slots.firstOrNull { it.slot == match.member.slot }?.let { slot ->
                ResolvedOwnReserve(slot, match.cardIndex, match.cardCp, match.confirmed, match.source)
            }
        }
    }

    /** Picks two distinct charged moves and never reuses slot #1 as slot #2. */
    private fun resolveChargedPair(
        speciesName: String,
        savedMove1Id: String?,
        savedMove2Id: String?,
        preferred1: MoveDef?,
        preferred2: MoveDef?
    ): Pair<MoveDef?, MoveDef?> {
        val candidates = linkedMapOf<String, MoveDef>()
        fun add(move: MoveDef?) {
            if (move != null) candidates.putIfAbsent(move.moveId, move)
        }
        val saved1 = repo.move(savedMove1Id)
        val saved2 = repo.move(savedMove2Id)
        add(saved1); add(saved2); add(preferred1); add(preferred2)
        repo.possibleChargedMoves(speciesName).forEach(::add)

        val first = saved1 ?: preferred1 ?: candidates.values.firstOrNull()
        val second = saved2?.takeIf { it.moveId != first?.moveId }
            ?: preferred2?.takeIf { it.moveId != first?.moveId }
            ?: candidates.values.firstOrNull { it.moveId != first?.moveId }
        return first to second
    }

    private fun moveKnowledge(state: BattlePokemonState?, slot: Int, move: MoveDef?): MoveKnowledgeConfidence {
        if (move == null) return MoveKnowledgeConfidence.UNKNOWN
        if (state == null) return MoveKnowledgeConfidence.RANKED
        return when (slot) {
            0 -> if (state.fastMoveManual) MoveKnowledgeConfidence.MANUAL else MoveKnowledgeConfidence.RANKED
            1 -> when {
                state.charge1Manual -> MoveKnowledgeConfidence.MANUAL
                state.charge1Confirmed || move.moveId in state.observedChargedMoveIds -> MoveKnowledgeConfidence.CONFIRMED
                else -> MoveKnowledgeConfidence.RANKED
            }
            2 -> when {
                state.charge2Manual -> MoveKnowledgeConfidence.MANUAL
                state.charge2Confirmed || move.moveId in state.observedChargedMoveIds -> MoveKnowledgeConfidence.CONFIRMED
                else -> MoveKnowledgeConfidence.RANKED
            }
            else -> MoveKnowledgeConfidence.UNKNOWN
        }
    }

    private fun classify(myTypes: List<String>, enemyTypes: List<String>): MatchupState {
        if (myTypes.isEmpty() || enemyTypes.isEmpty()) return MatchupState.UNKNOWN
        return when (TypeChart.classify(myTypes, enemyTypes)) {
            1 -> MatchupState.FAVORABLE
            -1 -> MatchupState.UNFAVORABLE
            else -> MatchupState.NEUTRAL
        }
    }

    private fun saveActiveState() {
        val state = activeState ?: return
        if (!enemySpeciesId.isNullOrBlank()) state.speciesId = enemySpeciesId
        state.fastMoveId = fast?.moveId
        state.chargedMove1Id = charge1?.moveId
        state.chargedMove2Id = charge2?.moveId
        if (predictiveEnergy.isInitialized) {
            state.predictiveEnergySnapshot = predictiveEnergy.snapshot()
            state.fastMoveCount = predictiveEnergy.hypotheses().maxOfOrNull { it.completedFastMoves } ?: tracker.fastMoveCount
            state.estimatedEnergy = predictiveEnergy.energyLikely()
            state.minEnergy = predictiveEnergy.energyMin()
            state.maxEnergy = predictiveEnergy.energyMax()
            state.energyUncertain = state.minEnergy != state.maxEnergy || predictiveEnergy.hypothesisCount() > 1
        } else {
            state.fastMoveCount = tracker.fastMoveCount
            state.estimatedEnergy = tracker.energy
            state.minEnergy = tracker.minEnergy
            state.maxEnergy = tracker.maxEnergy
            state.energyUncertain = tracker.isRange
        }
        state.lastSeenAtMs = System.currentTimeMillis()
    }

    private fun saveOwnActiveState() {
        val state = ownActiveState ?: return
        if (!playerSpeciesId.isNullOrBlank()) state.speciesId = playerSpeciesId
        state.fastMoveId = ownFast?.moveId
        state.chargedMove1Id = ownCharge1?.moveId
        state.chargedMove2Id = ownCharge2?.moveId
        state.fastMoveCount = ownTracker.fastMoveCount
        state.estimatedEnergy = ownTracker.energy
        state.minEnergy = ownTracker.minEnergy
        state.maxEnergy = ownTracker.maxEnergy
        state.energyUncertain = ownTracker.isRange
        lastHpRatio?.let { hp ->
            state.lastHpRatio = hp.coerceIn(0f, 1f)
            if (hp <= .01f) state.fainted = true
        }
        state.lastSeenAtMs = System.currentTimeMillis()
    }

    private fun resetStages(state: BattlePokemonState?) {
        state ?: return
        state.attackStageMin = 0
        state.attackStageMax = 0
        state.defenseStageMin = 0
        state.defenseStageMax = 0
    }

    private fun syncLegacyEnemyEnergyFromPredictive() {
        if (!predictiveEnergy.isInitialized) return
        val count = predictiveEnergy.hypotheses().maxOfOrNull { it.completedFastMoves } ?: 0
        tracker.restoreRange(
            predictiveEnergy.energyMin(),
            predictiveEnergy.energyMax(),
            count
        )
    }

    private fun normalizedMoveNames(move: MoveDef): Set<String> = buildSet {
        add(normalizeText(repo.localizedMoveName(move)))
        add(normalizeText(move.name))
        add(normalizeText(move.moveId.replace('_', ' ')))
    }.filterTo(linkedSetOf()) { it.isNotBlank() }

    private fun normalize(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "")

    private fun normalizeText(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^a-z0-9]+"), "")

    private object IdentityThresholds {
        const val MIN_STABLE_TEAM_CONFIDENCE = 0.90f
        const val TEAM_VISUAL_MIN_CONFIDENCE = 0.76f
        const val ACTIVE_VISUAL_MIN_CONFIDENCE = 0.80f
    }

    companion object {
        private const val RESERVE_VISUAL_MIN_CONFIDENCE = 0.76f
        private const val RESERVE_VISUAL_IMMEDIATE_CONFIDENCE = 0.88f
        private const val RESERVE_VISUAL_CONFIRM_FRAMES = 2
        private const val UNKNOWN_CHARGED_DEBOUNCE_MS = 4_200L
        private const val SWITCH_COOLDOWN_MS = 50_000L
        private const val RESERVE_MISSING_CONFIRM_FRAMES = 12
        private const val CHARGED_DAMAGE_OBSERVATION_WINDOW_MS = 6_000L
        private const val CHARGED_DAMAGE_SETTLE_MS = 420L
        private const val MIN_CHARGED_DAMAGE_SAMPLE_FRACTION = 0.010f
        private const val CHARGED_FAST_GUARD_MS = 2_200L
        private const val PENDING_CONFIRM_DELAY_MS = 2_400L
        private const val MAX_PASSIVE_FAST_PROJECTION_MS = 5_000L

        private val CHARGED_PROMPTS = listOf(
            "ataquecarregado", "ataquechegando", "ataquecarregadoagora",
            "attackincoming", "chargemoveincoming", "usarumescudo", "useumescudo", "protectshield"
        )
        private val ACTION_VERBS = listOf("usou", "used", "utilizou")
    }
}

