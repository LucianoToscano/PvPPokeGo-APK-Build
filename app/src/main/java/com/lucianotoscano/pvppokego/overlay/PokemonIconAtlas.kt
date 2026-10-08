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

/**
 * Offline display-only Pokémon sprite atlas generated from the same form-aware source
 * used by PokemonVisualRecognizer.
 *
 * Recognition and display remain independent: failure to load an icon never changes
 * species identity, typing, matchup or battle state.
 */
internal class PokemonIconAtlas(context: Context) {
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

    private val bitmap: Bitmap? = runCatching {
        context.assets.open(ATLAS_ASSET).use(BitmapFactory::decodeStream)
    }.getOrNull()

    private val entries: List<Entry> = runCatching {
        context.assets.open(INDEX_ASSET).bufferedReader().use { reader ->
            Json { ignoreUnknownKeys = true }
                .decodeFromString<AtlasIndex>(reader.readText())
                .entries
        }
    }.getOrDefault(emptyList())

    private val bySpecies: Map<String, Entry> = entries.associateBy {
        normalize(it.speciesId)
    }

    private val uniqueByDex: Map<Int, Entry> = entries
        .filter { it.dex > 0 }
        .groupBy { it.dex }
        .mapNotNull { (dex, matches) -> matches.singleOrNull()?.let { dex to it } }
        .toMap()

    val isAvailable: Boolean
        get() = bitmap != null && entries.isNotEmpty()

    fun draw(
        canvas: Canvas,
        speciesId: String?,
        dex: Int?,
        destination: RectF,
        paint: Paint
    ): Boolean {
        val atlas = bitmap?.takeUnless { it.isRecycled } ?: return false
        val entry = speciesId
            ?.takeIf { it.isNotBlank() }
            ?.let { bySpecies[normalize(it)] }
            ?: dex?.let(uniqueByDex::get)
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
