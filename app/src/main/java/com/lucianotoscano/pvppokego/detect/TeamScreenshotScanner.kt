package com.lucianotoscano.pvppokego.detect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.lucianotoscano.pvppokego.data.MoveDef
import com.lucianotoscano.pvppokego.data.PokemonDef
import com.lucianotoscano.pvppokego.data.TeamScanEvidence
import com.lucianotoscano.pvppokego.data.TeamScanParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class TeamScanKind { DETAIL, APPRAISAL }
data class TeamScreenshotResult(
    val kind: TeamScanKind,
    val evidence: TeamScanEvidence = TeamScanEvidence(),
    val suggestedIvs: Triple<Int, Int, Int>? = null,
    val appraisalWarning: String = ""
)

/**
 * User-initiated Photo Picker only. It does NOT inspect other apps in the background,
 * store screenshots, or touch the battle OCR service.
 */
object TeamScreenshotScanner {
    suspend fun scan(
        context: Context,
        uri: Uri,
        kind: TeamScanKind,
        species: List<PokemonDef>,
        moves: List<MoveDef>,
        namesPtBr: Map<String, String>
    ): TeamScreenshotResult = withContext(Dispatchers.IO) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val text = recognizer.process(InputImage.fromFilePath(context, uri)).await().text
            val evidence = TeamScanParser.parse(text, species, moves, namesPtBr)
            if (kind == TeamScanKind.DETAIL) {
                TeamScreenshotResult(kind, evidence)
            } else {
                // Pixel analyzer is deliberately conservative: unknown is safer than invented IVs.
                val bitmap = sampledBitmap(context, uri)
                val ivs = bitmap?.let(TeamAppraisalAnalyzer::suggest)
                bitmap?.recycle()
                TeamScreenshotResult(
                    kind, evidence, ivs,
                    if (ivs == null) "As três barras da avaliação não ficaram claras. Digite os IVs manualmente."
                    else "IVs sugeridos pelas barras: confira cada número antes de aplicar."
                )
            }
        } finally {
            recognizer.close()
        }
    }

    private fun sampledBitmap(context: Context, uri: Uri): Bitmap? =
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply {
                inSampleSize = 2
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
        addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
        addOnCanceledListener { continuation.cancel() }
    }
}

/**
 * Screenshots may use different DPI, theme and translation. This routine only returns
 * values when all 3 orange appraisal bars have convincing geometric evidence.
 * No result must ever be treated as an authoritative reading until user confirmation.
 */
internal object TeamAppraisalAnalyzer {
    fun suggest(bitmap: Bitmap): Triple<Int, Int, Int>? {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 220 || h < 400) return null
        val left = (w * .25).toInt()
        val right = (w * .93).toInt()
        val startY = (h * .30).toInt()
        val endY = (h * .88).toInt()
        val step = maxOf(1, w / 400)
        val candidates = mutableListOf<Pair<Int, Int>>()
        for (y in startY until endY step 2) {
            var count = 0
            for (x in left until right step step) {
                val c = bitmap.getPixel(x, y)
                val r = android.graphics.Color.red(c)
                val g = android.graphics.Color.green(c)
                val b = android.graphics.Color.blue(c)
                if (r > 175 && g in 75..190 && b < 150 && r > g + 35) count++
            }
            if (count >= (right - left) / step * .09) candidates.add(y to count)
        }
        // Cluster adjacent scanlines into 3 separate bars; if not exactly 3, refuse.
        val groups = mutableListOf<MutableList<Int>>()
        candidates.forEach { (y, _) ->
            if (groups.isEmpty() || y - groups.last().last() > 10) groups.add(mutableListOf(y))
            else groups.last().add(y)
        }
        val rows = groups.filter { it.size >= 3 }.map { it[it.size / 2] }
        if (rows.size != 3) return null
        if (rows[1] - rows[0] < h * .012 || rows[2] - rows[1] < h * .012) return null
        val extent = rows.map { y ->
            val xs = (left until right step step).filter { x ->
                val c = bitmap.getPixel(x, y)
                val r = android.graphics.Color.red(c)
                val g = android.graphics.Color.green(c)
                val b = android.graphics.Color.blue(c)
                r > 175 && g in 75..190 && b < 150 && r > g + 35
            }
            if (xs.size < 6) null else xs.first() to xs.last()
        }
        if (extent.any { it == null }) return null
        val beginnings = extent.filterNotNull().map { it.first }
        if (beginnings.maxOrNull()!! - beginnings.minOrNull()!! > w * .02) return null
        // A screenshot does not tell us the total bar width reliably when bars are empty.
        // We deliberately decline numerical guessing until reference layout calibration.
        return null
    }
}
