package com.lucianotoscano.pvppokego.detect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight offline visual recognizer used as an independent third identity signal.
 *
 * It compares a Pokémon portrait against compact perceptual fingerprints generated from
 * Pokémon GO addressable artwork (PokeMiners). Source artwork is not stored in the APK;
 * only dHash + color histogram fingerprints are packaged.
 *
 * Fingerprints are form-aware: the exact PvPoke speciesId is carried together with the
 * National Dex number. Consumers may still fall back to dex-only evidence when an older
 * bank does not contain speciesId.
 */
class PokemonVisualRecognizer(context: Context) {
    data class Match(
        val dex: Int,
        val speciesId: String?,
        val confidence: Float,
        val hashSimilarity: Float,
        val colorSimilarity: Float
    )

    @Serializable
    private data class FingerprintEntry(
        val dex: Int,
        val speciesId: String? = null,
        val dhash: String,
        val hue: List<Int> = emptyList()
    )

    @Serializable
    private data class FingerprintBank(
        val format: String = "",
        val version: Int = 0,
        val entries: List<FingerprintEntry> = emptyList()
    )

    private data class RuntimeEntry(
        val dex: Int,
        val speciesId: String?,
        val hash: Long,
        val hue: IntArray
    )

    private data class Fingerprint(
        val hash: Long,
        val hue: IntArray
    )

    private val entries: List<RuntimeEntry> = runCatching {
        context.assets.open(ASSET_NAME).bufferedReader().use { reader ->
            Json { ignoreUnknownKeys = true }
                .decodeFromString<FingerprintBank>(reader.readText())
                .entries
                .mapNotNull { entry ->
                    runCatching {
                        RuntimeEntry(
                            dex = entry.dex,
                            speciesId = entry.speciesId?.takeIf { it.isNotBlank() },
                            hash = entry.dhash.toULong(16).toLong(),
                            hue = entry.hue.toIntArray()
                        )
                    }.getOrNull()
                }
        }
    }.getOrDefault(emptyList())

    val isAvailable: Boolean get() = entries.isNotEmpty()

    fun match(bitmap: Bitmap): Match? =
        matchWithThresholds(bitmap, MIN_SCORE, MIN_MARGIN)

    /**
     * Reserve cards have a dark green native background plus CP text. Feeding that whole
     * card directly to the regular fingerprint path makes the background dominate dHash
     * and hue. Clean the card first, then require a stronger score/margin than normal so
     * a noisy thumbnail can never overwrite a stable team identity.
     */
    fun matchReserveCard(bitmap: Bitmap): Match? {
        if (bitmap.isRecycled || entries.isEmpty()) return null
        val cleaned = cleanReserveCard(bitmap) ?: return null
        return try {
            matchWithThresholds(
                cleaned,
                RESERVE_MIN_SCORE,
                RESERVE_MIN_MARGIN
            )
        } finally {
            cleaned.recycle()
        }
    }

    private fun matchWithThresholds(
        bitmap: Bitmap,
        minScore: Double,
        minMargin: Double
    ): Match? {
        if (bitmap.isRecycled || entries.isEmpty()) return null
        val fp = fingerprint(bitmap) ?: return null

        var best: RuntimeEntry? = null
        var bestScore = -1.0
        var bestHash = 0.0
        var bestColor = 0.0
        var secondScore = -1.0

        for (entry in entries) {
            val hamming = java.lang.Long.bitCount(fp.hash xor entry.hash)
            val hashScore = 1.0 - hamming / 64.0
            val colorScore = hueSimilarity(fp.hue, entry.hue)
            val score = hashScore * HASH_WEIGHT + colorScore * COLOR_WEIGHT

            if (score > bestScore) {
                secondScore = bestScore
                bestScore = score
                best = entry
                bestHash = hashScore
                bestColor = colorScore
            } else if (score > secondScore) {
                secondScore = score
            }
        }

        val winner = best ?: return null
        val margin = bestScore - secondScore
        if (bestScore < minScore || margin < minMargin) return null
        return Match(
            dex = winner.dex,
            speciesId = winner.speciesId,
            confidence = bestScore.toFloat().coerceIn(0f, 1f),
            hashSimilarity = bestHash.toFloat().coerceIn(0f, 1f),
            colorSimilarity = bestColor.toFloat().coerceIn(0f, 1f)
        )
    }

    private fun cleanReserveCard(source: Bitmap): Bitmap? {
        if (source.width < 8 || source.height < 8) return null
        val output = source.copy(Bitmap.Config.ARGB_8888, true) ?: return null
        val hsv = FloatArray(3)

        // Left-middle is normally empty card background, unlike the centered Pokémon.
        val sampleX = (output.width * 0.06f).toInt().coerceIn(0, output.width - 1)
        val sampleY = (output.height * 0.52f).toInt().coerceIn(0, output.height - 1)
        val bgColor = output.getPixel(sampleX, sampleY)
        val bgHsv = FloatArray(3)
        Color.colorToHSV(bgColor, bgHsv)

        val topTextCut = (output.height * 0.28f).toInt()
        for (y in 0 until output.height) {
            for (x in 0 until output.width) {
                val color = output.getPixel(x, y)
                Color.colorToHSV(color, hsv)

                val hueDistance = kotlin.math.min(
                    kotlin.math.abs(hsv[0] - bgHsv[0]),
                    360f - kotlin.math.abs(hsv[0] - bgHsv[0])
                )
                val closeToCardBackground =
                    hueDistance < 35f &&
                        kotlin.math.abs(hsv[1] - bgHsv[1]) < 0.50f &&
                        kotlin.math.abs(hsv[2] - bgHsv[2]) < 0.12f
                val brightNeutralText = hsv[1] < 0.20f && hsv[2] > 0.62f

                if (y < topTextCut || closeToCardBackground || brightNeutralText) {
                    output.setPixel(x, y, Color.WHITE)
                }
            }
        }
        return output
    }

    private fun fingerprint(source: Bitmap): Fingerprint? {
        val normalized = normalizePortrait(source) ?: return null
        return try {
            Fingerprint(
                hash = dHash(normalized),
                hue = hueHistogram(normalized)
            )
        } finally {
            normalized.recycle()
        }
    }

    /**
     * Removes most white/translucent card background and centers the visible subject in
     * a square, matching the build-time fingerprint normalization.
     */
    private fun normalizePortrait(source: Bitmap): Bitmap? {
        val sample = if (source.width > 180 || source.height > 180) {
            Bitmap.createScaledBitmap(
                source,
                min(180, source.width),
                min(180, source.height),
                true
            )
        } else {
            source.copy(Bitmap.Config.ARGB_8888, false)
        }

        try {
            var minX = sample.width
            var minY = sample.height
            var maxX = -1
            var maxY = -1
            val hsv = FloatArray(3)

            for (y in 0 until sample.height step 2) {
                for (x in 0 until sample.width step 2) {
                    val c = sample.getPixel(x, y)
                    Color.colorToHSV(c, hsv)
                    val saturation = hsv[1]
                    val value = hsv[2]
                    val foreground = saturation >= 0.13f || value <= 0.66f
                    if (foreground) {
                        minX = min(minX, x)
                        minY = min(minY, y)
                        maxX = max(maxX, x)
                        maxY = max(maxY, y)
                    }
                }
            }

            if (maxX <= minX || maxY <= minY) return null

            val marginX = ((maxX - minX + 1) * 0.06f).toInt().coerceAtLeast(2)
            val marginY = ((maxY - minY + 1) * 0.06f).toInt().coerceAtLeast(2)
            minX = (minX - marginX).coerceAtLeast(0)
            minY = (minY - marginY).coerceAtLeast(0)
            maxX = (maxX + marginX).coerceAtMost(sample.width - 1)
            maxY = (maxY + marginY).coerceAtMost(sample.height - 1)

            val crop = Bitmap.createBitmap(sample, minX, minY, maxX - minX + 1, maxY - minY + 1)
            try {
                val output = Bitmap.createBitmap(NORMALIZED_SIZE, NORMALIZED_SIZE, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(output)
                canvas.drawColor(Color.WHITE)
                val scale = min(
                    (NORMALIZED_SIZE * 0.88f) / crop.width.coerceAtLeast(1),
                    (NORMALIZED_SIZE * 0.88f) / crop.height.coerceAtLeast(1)
                )
                val w = crop.width * scale
                val h = crop.height * scale
                val left = (NORMALIZED_SIZE - w) / 2f
                val top = (NORMALIZED_SIZE - h) / 2f
                canvas.drawBitmap(
                    crop,
                    Rect(0, 0, crop.width, crop.height),
                    RectF(left, top, left + w, top + h),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                )
                return output
            } finally {
                crop.recycle()
            }
        } finally {
            sample.recycle()
        }
    }

    private fun dHash(bitmap: Bitmap): Long {
        val small = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        return try {
            var hash = 0L
            var bit = 0
            for (y in 0 until 8) {
                for (x in 0 until 8) {
                    val left = luminance(small.getPixel(x, y))
                    val right = luminance(small.getPixel(x + 1, y))
                    if (left > right) hash = hash or (1L shl bit)
                    bit++
                }
            }
            hash
        } finally {
            small.recycle()
        }
    }

    private fun hueHistogram(bitmap: Bitmap): IntArray {
        val small = Bitmap.createScaledBitmap(bitmap, 48, 48, true)
        return try {
            val counts = DoubleArray(HUE_BINS)
            var total = 0.0
            val hsv = FloatArray(3)
            for (y in 0 until small.height) {
                for (x in 0 until small.width) {
                    val c = small.getPixel(x, y)
                    Color.colorToHSV(c, hsv)
                    val saturation = hsv[1]
                    val value = hsv[2]
                    if (saturation < 0.12f && value > 0.72f) continue
                    val weight = max(0.08f, saturation).toDouble() *
                        (0.35 + 0.65 * (1.0 - abs(value - 0.55f)))
                    val idx = ((hsv[0] / 360f) * HUE_BINS)
                        .toInt()
                        .coerceIn(0, HUE_BINS - 1)
                    counts[idx] += weight
                    total += weight
                }
            }
            if (total <= 1e-9) {
                IntArray(HUE_BINS)
            } else {
                IntArray(HUE_BINS) { i -> ((counts[i] / total) * 255.0).toInt() }
            }
        } finally {
            small.recycle()
        }
    }

    private fun hueSimilarity(a: IntArray, b: IntArray): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.5
        val n = min(a.size, b.size)
        var l1 = 0
        var sumA = 0
        var sumB = 0
        for (i in 0 until n) {
            l1 += abs(a[i] - b[i])
            sumA += a[i]
            sumB += b[i]
        }
        val maxL1 = (sumA + sumB).coerceAtLeast(1)
        return (1.0 - l1.toDouble() / maxL1.toDouble()).coerceIn(0.0, 1.0)
    }

    private fun luminance(c: Int): Int =
        (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000

    companion object {
        private const val ASSET_NAME = "pokemon_visual_fingerprints.json"
        private const val NORMALIZED_SIZE = 72
        private const val HUE_BINS = 12
        private const val HASH_WEIGHT = 0.74
        private const val COLOR_WEIGHT = 0.26
        private const val MIN_SCORE = 0.69
        private const val MIN_MARGIN = 0.018
        private const val RESERVE_MIN_SCORE = 0.76
        private const val RESERVE_MIN_MARGIN = 0.025
    }
}
