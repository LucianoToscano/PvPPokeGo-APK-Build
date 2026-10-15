package com.lucianotoscano.pvppokego.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.lucianotoscano.pvppokego.data.PokemonImageCatalog
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Offline display-only Pokémon sprite atlas generated from the same form-aware source
 * used by PokemonVisualRecognizer.
 *
 * Recognition and display remain independent: failure to load an icon never changes
 * species identity, typing, matchup or battle state.
 */
internal class PokemonIconAtlas(context: Context, onImageReady: () -> Unit = {}) {
    private val currentImages = PokemonImageStore(context, onImageReady)
    @Serializable
    private data class Entry(
        val speciesId: String,
        val dex: Int = 0,
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int
    )

    @Serializable
    private data class AtlasIndex(
        val format: String = "",
        val version: Int = 0,
        val entries: List<Entry> = emptyList()
    )

    private data class AtlasData(
        val bitmap: Bitmap,
        val bySpecies: Map<String, Entry>,
        val baseSpeciesByDex: Map<Int, String>,
        val uniqueByDex: Map<Int, Entry>
    )

    @Volatile private var atlasData: AtlasData? = null
    @Volatile private var released: Boolean = false

    init {
        // Decoding the form-aware fallback atlas on View construction blocked
        // the battle/UI thread. Keep personal image cache and exact-form rules.
        val assets = context.applicationContext.assets
        CoroutineScope(Dispatchers.IO).launch {
            val bitmap = runCatching {
                assets.open(ATLAS_ASSET).use(BitmapFactory::decodeStream)
            }.getOrNull()
            val entries = runCatching {
                assets.open(INDEX_ASSET).bufferedReader().use { reader ->
                    Json { ignoreUnknownKeys = true }
                        .decodeFromString<AtlasIndex>(reader.readText()).entries
                }
            }.getOrDefault(emptyList())
            val data = if (bitmap != null && entries.isNotEmpty()) {
                val bySpecies = entries.associateBy { normalize(it.speciesId) }
                val baseSpeciesByDex = entries
                    .filter { it.dex > 0 }.groupBy { it.dex }
                    .mapNotNull { (dex, matches) ->
                        PokemonImageCatalog.inferBaseSpeciesKey(
                            matches.map { normalize(it.speciesId) }
                        )?.let { dex to it }
                    }.toMap()
                val uniqueByDex = entries
                    .filter { it.dex > 0 }.groupBy { it.dex }
                    .mapNotNull { (dex, matches) ->
                        matches.singleOrNull()?.let { dex to it }
                    }.toMap()
                AtlasData(bitmap, bySpecies, baseSpeciesByDex, uniqueByDex)
            } else {
                bitmap?.recycle()
                null
            }
            withContext(Dispatchers.Main) {
                if (released) data?.bitmap?.recycle()
                else {
                    atlasData = data
                    if (data != null) onImageReady()
                }
            }
        }
    }

    fun release() {
        released = true
        atlasData?.bitmap?.takeUnless { it.isRecycled }?.recycle()
        atlasData = null
    }

    val isAvailable: Boolean get() = atlasData != null

    fun draw(
        canvas: Canvas,
        speciesId: String?,
        dex: Int?,
        destination: RectF,
        paint: Paint
    ): Boolean {
        val snapshot = atlasData
        val exact = speciesId?.let { snapshot?.bySpecies?.get(normalize(it)) }
        val resolvedDex = PokemonImageCatalog.preferExactDex(exact?.dex, dex)
        val isKnownAlternate = exact != null &&
            snapshot?.baseSpeciesByDex?.get(exact.dex) != normalize(exact.speciesId)
        val requestedMega = PokemonImageCatalog.lookup(resolvedDex, speciesId)
            ?.form?.startsWith("mega") == true
        // Preserve the exact offline GO form whenever HOME only has its base.
        if (!isKnownAlternate || requestedMega) {
            currentImages.find(resolvedDex, speciesId)?.let {
                canvas.drawBitmap(it, null, destination, paint)
                return true
            }
        }
        val data = snapshot ?: return false
        val atlas = data.bitmap.takeUnless { it.isRecycled } ?: return false
        val entry = speciesId
            ?.takeIf { it.isNotBlank() }
            ?.let { data.bySpecies[normalize(it)] }
            ?: dex?.takeUnless { PokemonImageCatalog.isDistinctForm(speciesId) }?.let(data.uniqueByDex::get)
            ?: return false

        val source = Rect(entry.x, entry.y, entry.x + entry.w, entry.y + entry.h)
        canvas.drawBitmap(atlas, source, destination, paint)
        return true
    }

    private fun normalize(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

    companion object {
        private const val ATLAS_ASSET = "pokemon_icon_atlas.png"
        private const val INDEX_ASSET = "pokemon_icon_atlas.json"
    }
}
