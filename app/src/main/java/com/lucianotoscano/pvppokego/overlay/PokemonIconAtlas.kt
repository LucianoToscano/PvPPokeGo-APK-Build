package com.lucianotoscano.pvppokego.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
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
internal class PokemonIconAtlas(context: Context, onLoaded: (() -> Unit)? = null) {
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
        val uniqueByDex: Map<Int, Entry>
    )

    // Constructed by BattleOverlayView on the UI thread. Decoding a large bitmap or
    // parsing an atlas index here would stall rendering, even before the first draw.
    @Volatile
    private var loaded: AtlasData? = null
    @Volatile private var released: Boolean = false

    init {
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
                AtlasData(
                    bitmap = bitmap,
                    bySpecies = entries.associateBy { normalize(it.speciesId) },
                    uniqueByDex = entries.asSequence()
                        .filter { it.dex > 0 }
                        .groupBy { it.dex }
                        .mapNotNull { (dex, matches) ->
                            matches.singleOrNull()?.let { dex to it }
                        }.toMap()
                )
            } else {
                // No display fallback may promote a guessed visual identity.
                bitmap?.recycle()
                null
            }
            withContext(Dispatchers.Main) {
                if (released) {
                    data?.bitmap?.recycle()
                } else {
                    loaded = data
                    if (data != null) onLoaded?.invoke()
                }
            }
        }
    }

    fun release() {
        released = true
        loaded?.bitmap?.takeUnless { it.isRecycled }?.recycle()
        loaded = null
    }

    val isAvailable: Boolean get() = loaded != null

    fun draw(
        canvas: Canvas,
        speciesId: String?,
        dex: Int?,
        destination: RectF,
        paint: Paint
    ): Boolean {
        val data = loaded ?: return false
        val atlas = data.bitmap.takeUnless { it.isRecycled } ?: return false
        val entry = speciesId
            ?.takeIf { it.isNotBlank() }
            ?.let { data.bySpecies[normalize(it)] }
            ?: dex?.let(data.uniqueByDex::get)
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
