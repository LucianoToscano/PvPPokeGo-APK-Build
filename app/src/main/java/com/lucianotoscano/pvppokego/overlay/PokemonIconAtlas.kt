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

    /**
     * Only a true base-form entry may be replaced by the generic HOME portrait.
     * Alternate Pokémon GO art (Armored Mewtwo, Rotom forms, Pikachu Libre, etc.)
     * is retained even when an alternate name is not in the catalog token list.
     */
    private val baseSpeciesByDex: Map<Int, String> = entries
        .filter { it.dex > 0 }
        .groupBy { it.dex }
        .mapNotNull { (dex, matches) ->
            val keys = matches.map { normalize(it.speciesId) }
            val base = PokemonImageCatalog.inferBaseSpeciesKey(keys)
            base?.let { dex to it }
        }.toMap()

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
        val resolvedDex = dex ?: speciesId?.let { bySpecies[normalize(it)]?.dex }?.takeIf { it > 0 }
        val exact = speciesId?.let { bySpecies[normalize(it)] }
        val isKnownAlternate = exact != null &&
            baseSpeciesByDex[exact.dex] != normalize(exact.speciesId)
        val requestedMega = PokemonImageCatalog.lookup(resolvedDex, speciesId)
            ?.form?.startsWith("mega") == true
        // Preserve the exact offline GO form whenever HOME only has its base.
        if (!isKnownAlternate || requestedMega) {
            currentImages.find(resolvedDex, speciesId)?.let {
                canvas.drawBitmap(it, null, destination, paint)
                return true
            }
        }
        val atlas = bitmap?.takeUnless { it.isRecycled } ?: return false
        val entry = speciesId
            ?.takeIf { it.isNotBlank() }
            ?.let { bySpecies[normalize(it)] }
            ?: dex?.takeUnless { PokemonImageCatalog.isDistinctForm(speciesId) }?.let(uniqueByDex::get)
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
