package com.lucianotoscano.pvppokego.data

import kotlinx.serialization.Serializable

@Serializable
data class MoveDef(
    val moveId: String,
    val name: String = moveId,
    val type: String = "none",
    val power: Int = 0,
    val energyGain: Int = 0,
    val energy: Int = 0,
    val cooldown: Int = 500,
    val buffs: List<Int> = emptyList(),
    /** PvPoke uses these for moves such as Obstruct that affect both sides differently. */
    val buffsSelf: List<Int> = emptyList(),
    val buffsOpponent: List<Int> = emptyList(),
    val buffTarget: String? = null,
    /** PvPoke currently stores this value as a JSON string (for example "1" or ".125"). */
    val buffApplyChance: String? = null,
    val damageMethod: String? = null,
    val archetype: String? = null,
    val isMegaMove: Boolean = false
) {
    val turns: Int get() = (cooldown / 500).coerceAtLeast(1)
    val isFast: Boolean get() = energyGain > 0
    val chargedCost: Int get() = if (energy < 0) -energy else energy
    val buffChance: Float get() = buffApplyChance?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f
}

@Serializable
data class PokemonBaseStats(
    val atk: Int = 0,
    val def: Int = 0,
    val hp: Int = 0
)

@Serializable
data class PokemonDef(
    val speciesId: String,
    val speciesName: String,
    val types: List<String> = emptyList(),
    /** National Pokédex number from the PvPoke gamemaster. Used by visual recognition. */
    val dex: Int = 0,
    /** PvPoke/Pokémon GO base stats used only for offline damage forecasting. */
    val baseStats: PokemonBaseStats = PokemonBaseStats(),
    /** PvPoke default IV combinations, e.g. cp1500 -> [level, atkIV, defIV, hpIV]. */
    val defaultIVs: Map<String, List<Double>> = emptyMap(),
    val tags: List<String> = emptyList(),
    /** Complete legal pools from the bundled PvPoke Game Master. */
    val fastMoves: List<String> = emptyList(),
    val chargedMoves: List<String> = emptyList()
)

data class EstimatedBattleStats(
    val attack: Double,
    val defense: Double,
    val hp: Int,
    val level: Double,
    val atkIv: Int,
    val defIv: Int,
    val hpIv: Int,
    val cp: Int
)

@Serializable
data class GameMaster(
    val pokemon: List<PokemonDef> = emptyList(),
    val moves: List<MoveDef> = emptyList()
)

@Serializable
data class RankingMove(val moveId: String, val uses: Double = 0.0)

@Serializable
data class RankingMoves(
    val fastMoves: List<RankingMove> = emptyList(),
    val chargedMoves: List<RankingMove> = emptyList()
)

@Serializable
data class RankingEntry(
    val speciesId: String,
    val speciesName: String,
    val moves: RankingMoves = RankingMoves(),
    val moveset: List<String> = emptyList(),
    val score: Double = 0.0
)

data class WeightedMove(
    val move: MoveDef,
    /** Normalized 0..1 prior from PvPoke usage; uniform when ranking usage is unavailable. */
    val weight: Float
)

data class DetectedPokemon(
    val name: String,
    val cp: Int?,
    val confidence: Float,
    val rawText: String,
    /** Optional independent visual evidence from the Pokémon portrait. */
    val visualDex: Int? = null,
    val visualSpeciesId: String? = null,
    val visualConfidence: Float? = null
)

data class BattleDetection(
    val player: DetectedPokemon?,
    val opponent: DetectedPokemon?,
    val battleText: String = ""
)

data class ReserveCardEvidence(
    val cp: Int?,
    val visualDex: Int? = null,
    val visualSpeciesId: String? = null,
    val visualConfidence: Float? = null
)

enum class MatchupState { FAVORABLE, NEUTRAL, UNFAVORABLE, UNKNOWN }
enum class EnergyConfidence { CONFIRMED, ESTIMATED, RANGE }

enum class DamageConfidence { FIRST_SAMPLE, ESTIMATED, STABLE }
enum class DamageForecastConfidence { MODEL, OBSERVED_ESTIMATED, OBSERVED_STABLE }

data class DamageForecast(
    val moveName: String,
    val averagePercent: Float,
    val minPercent: Float,
    val maxPercent: Float,
    val projectedRemainingPercent: Float? = null,
    val source: String,
    val confidence: DamageForecastConfidence
)

data class StatStageRange(
    val min: Int = 0,
    val max: Int = 0
) {
    fun normalized(): StatStageRange {
        val a = min.coerceIn(-4, 4)
        val b = max.coerceIn(-4, 4)
        return if (a <= b) copy(min = a, max = b) else copy(min = b, max = a)
    }
}

data class EnemyEnergyHypothesisSnapshot(
    val fastMoveId: String,
    val energy: Int,
    val completedFastMoves: Int,
    val elapsedTurns: Int,
    val weight: Float
)

data class EnemyEnergySnapshot(
    val hypotheses: List<EnemyEnergyHypothesisSnapshot> = emptyList(),
    val observedFastEvents: Int = 0,
    val lastObservedTurn: Int = 0,
    /** 0..1 health of the mathematical reconstruction; lowered only by contradictory evidence. */
    val consistencyScore: Float = 1f,
    val anomalyCount: Int = 0
)

data class FastMovePhaseSnapshot(
    val moveId: String,
    val moveName: String,
    val moveTurns: Int,
    /** 1-based current turn inside the next perfectly-tapped Fast Move. */
    val phaseTurn: Int,
    /** Turns remaining until damage + energy register at the end of the Fast Move. */
    val turnsUntilRegistration: Int,
    val nextRegistrationInMs: Long,
    val confidence: Float,
    val observedCompletions: Int
)

data class AttackRangeEstimate(
    val minAttack: Double,
    val likelyAttack: Double,
    val maxAttack: Double,
    val sampleCount: Int,
    /** Absolute CP error of the closest legal IV/level combinations used. Zero means exact CP match. */
    val cpError: Int
)

enum class CmpOutcome { WIN, LOSE, UNCERTAIN, UNKNOWN }

data class CmpForecast(
    val outcome: CmpOutcome,
    val own: AttackRangeEstimate?,
    val opponent: AttackRangeEstimate?,
    /** Worst and best own Attack minus opponent Attack margins. */
    val marginMin: Double = 0.0,
    val marginMax: Double = 0.0,
    val confidence: Float = 0f
)

enum class ShieldAction { SHIELD, HOLD, OPTIONAL, UNKNOWN }

data class ShieldDecisionForecast(
    val action: ShieldAction,
    val moveName: String? = null,
    val confidence: Float = 0f,
    val reason: String = "",
    val baitProbability: Float = 0f
)

data class OptimalThrowForecast(
    /** 0 means throw now; positive means use this many own Fast Moves first. */
    val waitFastMoves: Int,
    val confidence: Float,
    val reason: String
)

data class FarmDownForecast(
    val safe: Boolean,
    val hitsToKo: Int,
    val turnsToKo: Int,
    val enemyThreatTurns: Int?,
    val confidence: Float
)

data class SacSwapForecast(
    val targetName: String,
    val targetTeamSlot: Int?,
    val confidence: Float,
    val reason: String
)

enum class PredictionEvidenceClass {
    OBSERVED_FACT,
    MATHEMATICALLY_POSSIBLE,
    ESTIMATE,
    HEURISTIC
}

data class ChargedThreatForecast(
    val move: MoveDef,
    val energyCost: Int,
    val currentEnergyMin: Int,
    val currentEnergyMax: Int,
    val fastMovesRemainingMin: Int,
    val fastMovesRemainingLikely: Int,
    val fastMovesRemainingMax: Int,
    val turnsRemainingMin: Int,
    val turnsRemainingLikely: Int,
    val turnsRemainingMax: Int,
    val earliestTimeMs: Long,
    val likelyTimeMs: Long,
    val prepareInTurns: Int,
    val readyPossible: Boolean,
    val readyCertain: Boolean,
    val doubleReadyPossible: Boolean,
    val confidence: Float,
    val evidenceClass: PredictionEvidenceClass,
    val eptMin: Float,
    val eptMax: Float,
    val dptMin: Float? = null,
    val dptMax: Float? = null,
    val dpeMin: Float? = null,
    val dpeMax: Float? = null,
    /** Fast-Move HP pressure accumulated before this Charged can become available. */
    val fastDamageUntilReadyMin: Float? = null,
    val fastDamageUntilReadyMax: Float? = null,
    val mayKoBeforeCharged: Boolean = false,
    val baitPotential: Boolean = false,
    val shieldPressureScore: Float = 0f,
    val damageForecast: DamageForecast? = null,
    val likelyKo: Boolean = false,
    val threatScore: Float = 0f,
    /** Repeated same-Charged Fast counts from the likely residual-energy state, e.g. 5,4,5,4. */
    val fastCountSequenceLikely: List<Int> = emptyList(),
    /** Heuristic probability that this cheaper move is the bait among currently affordable options. */
    val baitProbability: Float = 0f
)

data class EnemyEnergyForecast(
    val energyMin: Int,
    val energyLikely: Int,
    val energyMax: Int,
    val hypothesisCount: Int,
    val fastMoveNames: List<String>,
    val candidates: List<ChargedThreatForecast>,
    val leadTimeMs: Long,
    val reactionTurns: Int,
    val confidence: Float,
    val consistencyScore: Float = 1f,
    val anomalyCount: Int = 0,
    val fastPhase: FastMovePhaseSnapshot? = null
)

data class ObservedDamageEstimate(
    val averagePercent: Float,
    val minPercent: Float,
    val maxPercent: Float,
    /** Normalized bar points: 1000 = 100% HP. Not the Pokémon's real HP stat. */
    val averagePoints: Int,
    val samples: Int,
    val projectedRemainingPercent: Float? = null,
    val confidence: DamageConfidence = DamageConfidence.FIRST_SAMPLE
)

/**
 * How the app knows a move. CONFIRMED = observed in battle, MANUAL = user choice,
 * RANKED = PvPoke/default inference, UNKNOWN = no trustworthy move yet.
 */
enum class MoveKnowledgeConfidence { CONFIRMED, MANUAL, RANKED, UNKNOWN }

enum class TeamPokemonStatus { ACTIVE, RESERVE, FAINTED, UNKNOWN }

data class ReserveState(
    val name: String?,
    /** Exact PvPoke species/form id when known. */
    val speciesId: String? = null,
    val cp: Int?,
    val types: List<String>,
    val matchup: MatchupState,
    val visible: Boolean = true,
    val recommendation: String? = null,
    val score: Double = 0.0,
    val teamSlot: Int? = null,
    val identityKey: String? = null,
    /** CP read from Pokemon GO's native reserve card. */
    val cardCp: Int? = null,
    /** True when the reserve Pokémon identity is stable enough for type/matchup analysis. */
    val identityConfirmed: Boolean = false,
    /** True only when Pokémon GO's native reserve card was pinned by CP/image/elimination. */
    val cardMappingConfirmed: Boolean = false,
    /** cp, image-form, image, elimination or fallback. */
    val identitySource: String? = null,
    val fastMove: MoveDef? = null,
    val chargedMove1: MoveDef? = null,
    val chargedMove2: MoveDef? = null,
    val fastMoveKnowledge: MoveKnowledgeConfidence = MoveKnowledgeConfidence.UNKNOWN,
    val chargedMove1Knowledge: MoveKnowledgeConfidence = MoveKnowledgeConfidence.UNKNOWN,
    val chargedMove2Knowledge: MoveKnowledgeConfidence = MoveKnowledgeConfidence.UNKNOWN,
    /** Visible reserve-card HP estimate when the native card is mapped. */
    val hpRatio: Float? = null,
    val status: TeamPokemonStatus = TeamPokemonStatus.RESERVE
)

data class BattlePokemonState(
    val key: String,
    val speciesName: String,
    /** Exact PvPoke species/form id when known (for example ninetales_alolan). */
    var speciesId: String? = null,
    var fastMoveId: String? = null,
    var chargedMove1Id: String? = null,
    var chargedMove2Id: String? = null,
    var fastMoveCount: Int = 0,
    var estimatedEnergy: Int = 0,
    var minEnergy: Int = 0,
    var maxEnergy: Int = 0,
    var energyUncertain: Boolean = false,
    var lastChargedMoveId: String? = null,
    var charge1Confirmed: Boolean = false,
    var charge2Confirmed: Boolean = false,
    var fastMoveManual: Boolean = false,
    var charge1Manual: Boolean = false,
    var charge2Manual: Boolean = false,
    var observedChargedMoveIds: MutableSet<String> = linkedSetOf(),
    /** Full predictive energy hypotheses preserved through switches. */
    var predictiveEnergySnapshot: EnemyEnergySnapshot? = null,
    var attackStageMin: Int = 0,
    var attackStageMax: Int = 0,
    var defenseStageMin: Int = 0,
    var defenseStageMax: Int = 0,
    /** Last trustworthy visible HP estimate for this exact Pokémon. */
    var lastHpRatio: Float? = null,
    var fainted: Boolean = false,
    var lastSeenAtMs: Long = 0L
)

data class ChargedPrediction(
    val move: MoveDef,
    val fastMovesRemaining: Int,
    val progress: Float,
    val ready: Boolean,
    /** True only when the whole tracked energy range is above this move cost. */
    val definitelyReady: Boolean = ready,
    /** Best single-value estimate, used only for concise UI. */
    val currentEnergy: Int = 0,
    /** PvP cost of this specific Charged Move. */
    val energyCost: Int = 1,
    /** Lowest still-plausible energy. */
    val minEnergy: Int = currentEnergy,
    /** Highest still-plausible energy. */
    val maxEnergy: Int = currentEnergy,
    /** How trustworthy the current energy display is. */
    val confidence: EnergyConfidence = EnergyConfidence.ESTIMATED,
    /** Progress toward a second copy once one copy is already ready. */
    val overflowProgress: Float = 0f
)

enum class CaptureHealth { FRESH, STALE, PAUSED, COMPATIBILITY }

data class BattleUiState(
    val playerName: String? = null,
    val playerCp: Int? = null,
    /** Exact current player types resolved from speciesId/form when available. */
    val playerTypes: List<String> = emptyList(),
    val opponentName: String? = null,
    val opponentCp: Int? = null,
    val opponentTypes: List<String> = emptyList(),
    val playerMatchup: MatchupState = MatchupState.UNKNOWN,
    val strongTypesAgainstOpponent: List<String> = emptyList(),
    val reserves: List<ReserveState> = emptyList(),
    /** Enemy move model. */
    val fastMove: MoveDef? = null,
    val charged1: ChargedPrediction? = null,
    val charged2: ChargedPrediction? = null,
    val fastMoveKnowledge: MoveKnowledgeConfidence = MoveKnowledgeConfidence.UNKNOWN,
    val charged1Knowledge: MoveKnowledgeConfidence = MoveKnowledgeConfidence.UNKNOWN,
    val charged2Knowledge: MoveKnowledgeConfidence = MoveKnowledgeConfidence.UNKNOWN,
    /** Player move/energy model, used only for recommendations. */
    val ownFastMove: MoveDef? = null,
    val ownCharged1: ChargedPrediction? = null,
    val ownCharged2: ChargedPrediction? = null,
    val ownFastMoveKnowledge: MoveKnowledgeConfidence = MoveKnowledgeConfidence.UNKNOWN,
    val ownCharged1Knowledge: MoveKnowledgeConfidence = MoveKnowledgeConfidence.UNKNOWN,
    val ownCharged2Knowledge: MoveKnowledgeConfidence = MoveKnowledgeConfidence.UNKNOWN,
    val ownSwitchSeconds: Int? = null,
    val opponentSwitchSeconds: Int? = null,
    val chargedIncoming: Boolean = false,
    /** Visible HP-bar estimates. 1.0 = 100%. */
    val playerHpRatio: Float? = null,
    val opponentHpRatio: Float? = null,
    /** Actual screen Y fraction for the player HP bar when visually detected. */
    val playerHpBarYFraction: Float? = null,
    /** Remaining Protect Shields when known. Mid-battle starts keep the value explicitly uncertain. */
    val ownShieldsRemaining: Int = 2,
    val opponentShieldsRemaining: Int = 2,
    val ownShieldsKnown: Boolean = true,
    val opponentShieldsKnown: Boolean = true,
    /** Best current incoming Charged-Attack forecast for the translucent HP preview. */
    val incomingDamagePreview: DamageForecast? = null,
    val captureHealth: CaptureHealth = CaptureHealth.FRESH,
    val captureStatusText: String? = null,
    /** Local-only history pattern, never stronger than current battle evidence. */
    val strategyPatternHint: String? = null,
    val strategyPatternConfidence: Float = 0f,
    /** Predictive enemy-energy model. Null until the enemy identity/movepool is usable. */
    val enemyEnergyForecast: EnemyEnergyForecast? = null,
    val enemyAttackStages: StatStageRange = StatStageRange(),
    val enemyDefenseStages: StatStageRange = StatStageRange(),
    val playerAttackStages: StatStageRange = StatStageRange(),
    val playerDefenseStages: StatStageRange = StatStageRange(),
    /** Damage learned from observations in this battle/matchup. */
    val incomingFastDamage: ObservedDamageEstimate? = null,
    val incomingCharged1Damage: ObservedDamageEstimate? = null,
    val incomingCharged2Damage: ObservedDamageEstimate? = null,
    val detectorConfidence: Float = 0f,
    val battleConfidence: Float = 0f,
    /** Compact per-enemy memory for optional history display. */
    val enemyHistory: List<String> = emptyList(),
    val debugText: String? = null,
    /** Range-based CMP, honest when hidden IVs overlap. Appended for constructor compatibility. */
    val cmpForecast: CmpForecast? = null,
    /** Formal shield decision derived from the same threat/damage ranges shown on HUD. */
    val shieldDecision: ShieldDecisionForecast? = null,
    val optimalThrow: OptimalThrowForecast? = null,
    val farmDown: FarmDownForecast? = null,
    val sacSwap: SacSwapForecast? = null
)
