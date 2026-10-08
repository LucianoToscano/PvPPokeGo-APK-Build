package com.lucianotoscano.pvppokego.detect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.lucianotoscano.pvppokego.data.BattleDetection
import com.lucianotoscano.pvppokego.data.BattleLeagueTextDetector
import com.lucianotoscano.pvppokego.data.DetectedPokemon
import com.lucianotoscano.pvppokego.data.GameDataRepository
import com.lucianotoscano.pvppokego.data.ReserveCardEvidence
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

/**
 * OCR uses small ROIs for the two top cards and a separate central/lower text ROI for
 * battle prompts. A larger party ROI is scanned less often to remember the user's team.
 */
class BattleOcrDetector(
    context: Context,
    private val repo: GameDataRepository
) : AutoCloseable {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val visualRecognizer = PokemonVisualRecognizer(context.applicationContext)
    private val reservePortraitLock = Any()
    private val reservePortraits = arrayOfNulls<Bitmap>(2)
    /** Stable portraits captured from the three Pokémon on the team-selection screen. */
    private val ownTeamPortraits = arrayOfNulls<Bitmap>(3)

    /**
     * Live thumbnails from Pokemon GO's native reserve cards.
     * Presentation-only fallback: identity and matchup still require CP/species/form confidence.
     */
    fun copyReservePortraits(): Pair<Bitmap?, Bitmap?> = synchronized(reservePortraitLock) {
        reservePortraits[0]?.takeUnless { it.isRecycled }?.copy(Bitmap.Config.ARGB_8888, false) to
            reservePortraits[1]?.takeUnless { it.isRecycled }?.copy(Bitmap.Config.ARGB_8888, false)
    }

    /**
     * Copies portraits in original team-slot order (0, 1, 2). These thumbnails are
     * presentation-only; they never change OCR/species confidence.
     */
    fun copyOwnTeamPortraits(): List<Bitmap?> = synchronized(reservePortraitLock) {
        ownTeamPortraits.map { portrait ->
            portrait?.takeUnless { it.isRecycled }?.copy(Bitmap.Config.ARGB_8888, false)
        }
    }

    fun clearCachedPortraits() = synchronized(reservePortraitLock) {
        reservePortraits.indices.forEach { index ->
            reservePortraits[index]?.let { if (!it.isRecycled) it.recycle() }
            reservePortraits[index] = null
        }
        ownTeamPortraits.indices.forEach { index ->
            ownTeamPortraits[index]?.let { if (!it.isRecycled) it.recycle() }
            ownTeamPortraits[index] = null
        }
    }

    private fun cacheReservePortrait(index: Int, source: Bitmap) {
        if (source.isRecycled || index !in 0..1) return
        // The caller already supplies a portrait-only ROI. Cropping it a second time was
        // cutting off tall/wide Pokémon and could leave an almost empty thumbnail.
        val thumb = Bitmap.createScaledBitmap(source, RESERVE_THUMB_SIZE, RESERVE_THUMB_SIZE, true)
        synchronized(reservePortraitLock) {
            reservePortraits[index]?.let { if (!it.isRecycled) it.recycle() }
            reservePortraits[index] = thumb
        }
    }

    private fun cacheOwnTeamPortrait(index: Int, source: Bitmap) {
        if (source.isRecycled || index !in 0..2) return
        val thumb = Bitmap.createScaledBitmap(source, TEAM_THUMB_SIZE, TEAM_THUMB_SIZE, true)
        synchronized(reservePortraitLock) {
            ownTeamPortraits[index]?.let { if (!it.isRecycled) it.recycle() }
            ownTeamPortraits[index] = thumb
        }
    }

    suspend fun detect(frame: Bitmap): BattleDetection = coroutineScope {
        if (frame.isRecycled) return@coroutineScope BattleDetection(null, null)
        val playerRoi = crop(frame, 0f, TOP_FRAC, PLAYER_RIGHT_FRAC, BOTTOM_FRAC)
        val opponentRoi = crop(frame, OPPONENT_LEFT_FRAC, TOP_FRAC, 1f, BOTTOM_FRAC)
        val battleRoi = crop(frame, BATTLE_LEFT_FRAC, BATTLE_TOP_FRAC, BATTLE_RIGHT_FRAC, BATTLE_BOTTOM_FRAC)
        try {
            val playerJob = async { recognizeCard(playerRoi) }
            val opponentJob = async { recognizeCard(opponentRoi) }
            val battleJob = async { recognize(battleRoi) }
            BattleDetection(playerJob.await(), opponentJob.await(), battleJob.await())
        } finally {
            playerRoi.recycle(); opponentRoi.recycle(); battleRoi.recycle()
        }
    }

    /**
     * Reads the lobby/rules area to determine Great/Ultra/Master. It is called slowly
     * and only before an opponent is active, so it does not add continuous battle OCR cost.
     */
    suspend fun detectLeagueCp(frame: Bitmap): Int? {
        if (frame.isRecycled) return null
        val roi = crop(frame, LEAGUE_LEFT_FRAC, LEAGUE_TOP_FRAC, LEAGUE_RIGHT_FRAC, LEAGUE_BOTTOM_FRAC)
        return try {
            BattleLeagueTextDetector.detectCp(recognize(roi))
        } finally {
            roi.recycle()
        }
    }

    /**
     * Reads only the two native reserve cards on the right side and extracts their CPs
     * in top-to-bottom order. CP is used to bind the HUD types/matchup to the correct
     * reserve after switches, avoiding slot-order guesses.
     */
    suspend fun detectReserveEvidence(frame: Bitmap): Pair<ReserveCardEvidence, ReserveCardEvidence> = coroutineScope {
        if (frame.isRecycled) {
            return@coroutineScope ReserveCardEvidence(null) to ReserveCardEvidence(null)
        }

        val topText = crop(frame, RESERVE_LEFT_FRAC, RESERVE_TOP_1, RESERVE_RIGHT_FRAC, RESERVE_BOTTOM_1)
        val bottomText = crop(frame, RESERVE_LEFT_FRAC, RESERVE_TOP_2, RESERVE_RIGHT_FRAC, RESERVE_BOTTOM_2)
        val topPortrait = crop(
            frame,
            RESERVE_PORTRAIT_LEFT,
            RESERVE_PORTRAIT_TOP_1,
            RESERVE_PORTRAIT_RIGHT,
            RESERVE_PORTRAIT_BOTTOM_1
        )
        val bottomPortrait = crop(
            frame,
            RESERVE_PORTRAIT_LEFT,
            RESERVE_PORTRAIT_TOP_2,
            RESERVE_PORTRAIT_RIGHT,
            RESERVE_PORTRAIT_BOTTOM_2
        )

        try {
            fun parseCp(raw: String): Int? = CP_REGEX.find(raw)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?.takeIf { it in 10..10000 }

            suspend fun read(index: Int, textRoi: Bitmap, portraitRoi: Bitmap): ReserveCardEvidence = coroutineScope {
                cacheReservePortrait(index, portraitRoi)
                val cpJob = async { parseCp(recognize(textRoi)) }
                val visualJob = async { visualRecognizer.matchReserveCard(portraitRoi) }
                val visual = visualJob.await()
                ReserveCardEvidence(
                    cp = cpJob.await(),
                    visualDex = visual?.dex,
                    visualSpeciesId = visual?.speciesId,
                    visualConfidence = visual?.confidence
                )
            }

            val topJob = async { read(0, topText, topPortrait) }
            val bottomJob = async { read(1, bottomText, bottomPortrait) }
            topJob.await() to bottomJob.await()
        } finally {
            topText.recycle(); bottomText.recycle()
            topPortrait.recycle(); bottomPortrait.recycle()
        }
    }

    suspend fun detectReserveCps(frame: Bitmap): Pair<Int?, Int?> {
        val evidence = detectReserveEvidence(frame)
        return evidence.first.cp to evidence.second.cp
    }

    /**
     * Scans the team/party area. This is intentionally throttled by the service because
     * it is a larger OCR region than the two battle cards.
     */
    suspend fun detectOwnTeam(frame: Bitmap): List<DetectedPokemon> = coroutineScope {
        if (frame.isRecycled) return@coroutineScope emptyList()

        // OCR and portrait recognition are independent signals. Every column is isolated so
        // name, CP and image can never slide into the neighbouring Pokémon.
        val textRois = listOf(
            crop(frame, TEAM_SLOT_1_LEFT, TEAM_SLOT_TOP, TEAM_SLOT_1_RIGHT, TEAM_SLOT_BOTTOM),
            crop(frame, TEAM_SLOT_2_LEFT, TEAM_SLOT_TOP, TEAM_SLOT_2_RIGHT, TEAM_SLOT_BOTTOM),
            crop(frame, TEAM_SLOT_3_LEFT, TEAM_SLOT_TOP, TEAM_SLOT_3_RIGHT, TEAM_SLOT_BOTTOM)
        )
        val portraitRois = listOf(
            crop(frame, TEAM_PORTRAIT_1_LEFT, TEAM_PORTRAIT_TOP, TEAM_PORTRAIT_1_RIGHT, TEAM_PORTRAIT_BOTTOM),
            crop(frame, TEAM_PORTRAIT_2_LEFT, TEAM_PORTRAIT_TOP, TEAM_PORTRAIT_2_RIGHT, TEAM_PORTRAIT_BOTTOM),
            crop(frame, TEAM_PORTRAIT_3_LEFT, TEAM_PORTRAIT_TOP, TEAM_PORTRAIT_3_RIGHT, TEAM_PORTRAIT_BOTTOM)
        )

        try {
            val jobs = textRois.indices.map { index ->
                async {
                    val ocrJob = async { recognizeTeamSlotEvidence(textRois[index]) }
                    val visualJob = async { visualRecognizer.match(portraitRois[index]) }
                    val detected = fuseTeamSlotEvidence(ocrJob.await(), visualJob.await())
                    // Cache only when this column produced real identity evidence. This prevents
                    // empty transition/battle frames from replacing a good team portrait.
                    if (detected != null && detected.confidence >= TEAM_PORTRAIT_MIN_CONFIDENCE) {
                        cacheOwnTeamPortrait(index, portraitRois[index])
                    }
                    detected
                }
            }
            jobs.mapNotNull { it.await() }
        } finally {
            textRois.forEach { it.recycle() }
            portraitRois.forEach { it.recycle() }
        }
    }

    private data class TeamSlotEvidence(
        val name: String?,
        val cp: Int?,
        val raw: String,
        val confidence: Float
    )

    private suspend fun recognizeTeamSlotEvidence(roi: Bitmap): TeamSlotEvidence {
        fun parse(raw: String, confidence: Float): TeamSlotEvidence {
            val cp = CP_REGEX.find(raw)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?.takeIf { it in 10..10000 }
            val name = repo.findPokemonMentions(raw, 1).firstOrNull()
                ?: raw.lines()
                    .asSequence()
                    .map { line ->
                        line.replace(CP_REGEX, " ")
                            .replace(Regex("[^\\p{L}♀♂' .-]+"), " ")
                            .replace(Regex("\\s+"), " ")
                            .trim()
                    }
                    .mapNotNull(repo::canonicalPokemonName)
                    .firstOrNull()
            return TeamSlotEvidence(name, cp, raw, confidence)
        }

        val raw = recognize(roi)
        val first = parse(raw, if (CP_REGEX.containsMatchIn(raw)) 0.98f else 0.90f)
        if (first.name != null || first.cp != null) return first

        val filtered = filterByColor(roi, TARGET_TEXT_COLOR, COLOR_TOLERANCE)
        return try {
            parse(recognize(filtered), 0.90f)
        } finally {
            filtered.recycle()
        }
    }

    private fun fuseTeamSlotEvidence(
        ocr: TeamSlotEvidence,
        visual: PokemonVisualRecognizer.Match?
    ): DetectedPokemon? {
        val visualCandidate = visual?.let { repo.pokemonForVisualIdentity(it.speciesId, it.dex) }
        val ocrPokemon = ocr.name?.let(repo::pokemon)
        val ocrDex = ocrPokemon?.dex?.takeIf { it > 0 }
        val ocrSpeciesId = ocrPokemon?.speciesId

        if (ocr.name != null && visual != null) {
            val sameDex = ocrDex != null && ocrDex == visual.dex
            val exactFormMatch = visual.speciesId == null ||
                ocrSpeciesId.equals(visual.speciesId, ignoreCase = true)

            // Exact form agreement is the strongest identity signal.
            if (sameDex && exactFormMatch) {
                return DetectedPokemon(
                    name = ocr.name,
                    cp = ocr.cp,
                    confidence = 0.995f,
                    rawText = ocr.raw,
                    visualDex = visual.dex,
                    visualSpeciesId = visual.speciesId,
                    visualConfidence = visual.confidence
                )
            }

            // Same dex but a form-aware image resolves a different PvPoke identity.
            // Prefer the image only at high confidence; OCR often shows only the base
            // display name and cannot distinguish regional/stance forms.
            if (
                sameDex &&
                visualCandidate != null &&
                visual.speciesId != null &&
                visual.confidence >= VISUAL_FORM_ACCEPT_CONFIDENCE
            ) {
                return DetectedPokemon(
                    name = visualCandidate.speciesName,
                    cp = ocr.cp,
                    confidence = 0.97f,
                    rawText = ocr.raw,
                    visualDex = visual.dex,
                    visualSpeciesId = visual.speciesId,
                    visualConfidence = visual.confidence
                )
            }

            // Different dex = true conflict. Keep OCR but lower confidence so one noisy
            // frame cannot redefine the stable team.
            val conflict = ocrDex != null && ocrDex != visual.dex &&
                visual.confidence >= VISUAL_CONFLICT_CONFIDENCE
            return DetectedPokemon(
                name = ocr.name,
                cp = ocr.cp,
                confidence = if (conflict) 0.70f else ocr.confidence,
                rawText = ocr.raw,
                visualDex = visual.dex,
                visualSpeciesId = visual.speciesId,
                visualConfidence = visual.confidence
            )
        }

        // Image-only recovery accepts an exact form-aware speciesId when available.
        // Older dex-only banks remain supported, but only for unambiguous dex entries.
        if (visualCandidate != null && visual != null && visual.confidence >= VISUAL_ACCEPT_CONFIDENCE) {
            return DetectedPokemon(
                name = visualCandidate.speciesName,
                cp = ocr.cp,
                confidence = if (ocr.cp != null) 0.94f else 0.86f,
                rawText = ocr.raw,
                visualDex = visual.dex,
                visualSpeciesId = visual.speciesId,
                visualConfidence = visual.confidence
            )
        }

        return null
    }

    private suspend fun recognizeCard(roi: Bitmap): DetectedPokemon? {
        // Try teal-text isolation first, but only accept it if the resulting text resolves
        // to a real known Pokemon. A few garbage OCR characters must not suppress the raw fallback.
        val filtered = filterByColor(roi, TARGET_TEXT_COLOR, COLOR_TOLERANCE)
        val filteredRaw = try {
            recognize(filtered)
        } finally {
            filtered.recycle()
        }
        parseCard(filteredRaw)?.let { return it }

        // Raw ROI is the authoritative fallback when color isolation removed/distorted glyphs.
        return parseCard(recognize(roi))
    }

    private fun parseCard(raw: String): DetectedPokemon? {
        if (raw.isBlank()) return null
        val cp = CP_REGEX.find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val candidates = raw.lines()
            .map { it.replace(CP_REGEX, "").trim() }
            .filter { it.length in 3..30 && it.any(Char::isLetter) }
        val canonical = candidates.asSequence().mapNotNull(repo::canonicalPokemonName).firstOrNull()
            ?: return null
        return DetectedPokemon(canonical, cp, 0.94f, raw)
    }

    private suspend fun recognize(bitmap: Bitmap): String {
        val image = InputImage.fromBitmap(bitmap, 0)
        val result = recognizer.process(image).await()
        return result.text.orEmpty()
    }

    private fun crop(src: Bitmap, l: Float, t: Float, r: Float, b: Float): Bitmap {
        val x = (src.width * l).toInt().coerceIn(0, src.width - 1)
        val y = (src.height * t).toInt().coerceIn(0, src.height - 1)
        val right = (src.width * r).toInt().coerceIn(x + 1, src.width)
        val bottom = (src.height * b).toInt().coerceIn(y + 1, src.height)
        return Bitmap.createBitmap(src, x, y, right - x, bottom - y)
    }

    private fun filterByColor(src: Bitmap, target: Int, tolerance: Int): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val px = IntArray(src.width * src.height)
        src.getPixels(px, 0, src.width, 0, 0, src.width, src.height)
        val tr = Color.red(target); val tg = Color.green(target); val tb = Color.blue(target)
        for (i in px.indices) {
            val p = px[i]
            val match = abs(Color.red(p) - tr) <= tolerance &&
                abs(Color.green(p) - tg) <= tolerance &&
                abs(Color.blue(p) - tb) <= tolerance
            px[i] = if (match) Color.BLACK else Color.WHITE
        }
        out.setPixels(px, 0, src.width, 0, 0, src.width, src.height)
        return out
    }

    override fun close() {
        clearCachedPortraits()
        recognizer.close()
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { if (cont.isActive) cont.resume(it) }
        addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }

    companion object {
        // Card values recovered from the working V03 base APK.
        private const val TOP_FRAC = 0.067f
        private const val BOTTOM_FRAC = 0.15f
        private const val PLAYER_RIGHT_FRAC = 0.43f
        private const val OPPONENT_LEFT_FRAC = 0.57f

        // Includes both the upper "Pokemon usou Golpe!" banner and the lower shield prompt.
        // The previous 0.50 bottom cutoff missed "Quer usar um escudo protetor?" on tall screens,
        // so the engine sometimes never registered the Charged event and energy stayed full.
        private const val BATTLE_LEFT_FRAC = 0.06f
        private const val BATTLE_TOP_FRAC = 0.12f
        private const val BATTLE_RIGHT_FRAC = 0.94f
        private const val BATTLE_BOTTOM_FRAC = 0.84f

        // Native reserve cards on the right side during battle.
        // Keep the OCR boxes tight around each card so confirmation is faster and
        // CP values cannot swap order.
        private const val RESERVE_LEFT_FRAC = 0.80f
        private const val RESERVE_RIGHT_FRAC = 0.995f
        private const val RESERVE_TOP_1 = 0.545f
        private const val RESERVE_BOTTOM_1 = 0.645f
        private const val RESERVE_TOP_2 = 0.640f
        private const val RESERVE_BOTTOM_2 = 0.750f

        // Portrait-only regions inside the two reserve cards. Visual recognition is
        // auxiliary: CP/OCR remains authoritative whenever available.
        private const val RESERVE_PORTRAIT_LEFT = 0.835f
        private const val RESERVE_PORTRAIT_RIGHT = 0.985f
        private const val RESERVE_PORTRAIT_TOP_1 = 0.565f
        private const val RESERVE_PORTRAIT_BOTTOM_1 = 0.620f
        private const val RESERVE_PORTRAIT_TOP_2 = 0.660f
        private const val RESERVE_PORTRAIT_BOTTOM_2 = 0.718f

        // Team selection card, read as three independent columns.
        private const val TEAM_SLOT_TOP = 0.615f
        private const val TEAM_SLOT_BOTTOM = 0.835f
        private const val TEAM_SLOT_1_LEFT = 0.065f
        private const val TEAM_SLOT_1_RIGHT = 0.355f
        private const val TEAM_SLOT_2_LEFT = 0.345f
        private const val TEAM_SLOT_2_RIGHT = 0.655f
        private const val TEAM_SLOT_3_LEFT = 0.645f
        private const val TEAM_SLOT_3_RIGHT = 0.945f

        // Portrait-only subregions used by the visual fingerprint recognizer.
        private const val TEAM_PORTRAIT_TOP = 0.675f
        private const val TEAM_PORTRAIT_BOTTOM = 0.790f
        private const val TEAM_PORTRAIT_1_LEFT = 0.105f
        private const val TEAM_PORTRAIT_1_RIGHT = 0.315f
        private const val TEAM_PORTRAIT_2_LEFT = 0.395f
        private const val TEAM_PORTRAIT_2_RIGHT = 0.605f
        private const val TEAM_PORTRAIT_3_LEFT = 0.685f
        private const val TEAM_PORTRAIT_3_RIGHT = 0.895f

        // Lobby/rules text can appear across a large central portion of the screen.
        private const val LEAGUE_LEFT_FRAC = 0.04f
        private const val LEAGUE_TOP_FRAC = 0.04f
        private const val LEAGUE_RIGHT_FRAC = 0.96f
        private const val LEAGUE_BOTTOM_FRAC = 0.82f

        private val TARGET_TEXT_COLOR = Color.parseColor("#2f5971")
        private const val COLOR_TOLERANCE = 34
        private val CP_REGEX = Regex("(?i)(?:PC|CP)\\s*[: ]?\\s*(\\d{2,5})")
        private const val VISUAL_ACCEPT_CONFIDENCE = 0.76f
        private const val VISUAL_FORM_ACCEPT_CONFIDENCE = 0.86f
        private const val VISUAL_CONFLICT_CONFIDENCE = 0.80f
        private const val RESERVE_THUMB_SIZE = 112
        private const val TEAM_THUMB_SIZE = 128
        private const val TEAM_PORTRAIT_MIN_CONFIDENCE = 0.70f
    }
}
