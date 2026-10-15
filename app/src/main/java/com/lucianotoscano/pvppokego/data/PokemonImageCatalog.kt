package com.lucianotoscano.pvppokego.data

import java.util.Locale

/**
 * Form-aware image keys for the download worker and display-only renderer.
 * Never changes Pokémon recognition, identity confidence, typing or battle math.
 * Mega IDs are candidates and require image/source validation before release.
 */
internal object PokemonImageCatalog {
    data class Image(val dex: Int, val form: String, val sourceId: Int) {
        val key: String get() = "%04d_%s".format(Locale.ROOT, dex, form)
        val fileName: String get() = "$key.webp"
        val url: String get() = "https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/home/$sourceId.png"
    }

    private val megaIds: Map<Pair<Int, String>, Int> = buildMap {
        val entries = "3:10033,6x:10034,6y:10035,9:10036,65:10037,94:10038,115:10039,127:10040,130:10041,142:10042,150x:10043,150y:10044,181:10045,212:10046,214:10047,229:10048,248:10049,254:10050,257:10051,260:10052,306:10053,308:10054,310:10055,354:10056,359:10057,445:10058,448:10059,460:10060,380:10062,381:10063,302:10066,334:10067,475:10068,531:10069,319:10070,80:10071,208:10072,18:10073,362:10074,719:10075,376:10076,384:10079,323:10087,428:10088,373:10089,15:10090"
        for (item in entries.split(",")) {
            val pair = item.split(":")
            val name = pair[0]
            val dex = name.trimEnd('x', 'y').toInt()
            val form = when {
                name.endsWith("x") -> "mega_x"
                name.endsWith("y") -> "mega_y"
                else -> "mega"
            }
            put(dex to form, pair[1].toInt())
        }
    }

    /**
     * Only use the downloaded BASE render when the identity is genuinely the
     * default form. Existing atlas forms have priority for all other variants.
     * Token matching prevents confusing a species such as Meganium with Mega.
     */
    private val nonBaseFormTokens = setOf(
        "alola", "alolan", "galar", "galarian", "hisui", "hisuian",
        "paldea", "paldean", "primal", "origin", "therian", "incarnate",
        "gigantamax", "gmax", "attack", "defense", "speed", "altered",
        "sky", "zen", "dusk", "dawn", "crowned", "resolute", "ultra",
        "black", "white", "sunny", "rainy", "snowy", "baile", "pom",
        "sensu", "pau", "sandy", "trash", "east", "west",
        "winter", "summer", "autumn", "spring", "male", "female",
        "plant", "midday", "midnight", "dusk", "hero", "blade",
        "land", "flying", "complete", "ten", "fifty", "rapid",
        "single", "striking", "school", "solo", "ice", "shadowrider",
        "ice_rider"
    )

    private fun tokens(speciesId: String?): List<String> =
        speciesId.orEmpty().lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_').split('_').filter(String::isNotBlank)

    private fun hasMegaToken(parts: List<String>): Boolean =
        parts.any { it == "mega" || it == "megax" || it == "megay" }

    /** Maintain the original atlas rule: a known exact species beats a CP/OCR Dex hint. */
    fun preferExactDex(exactDex: Int?, hintDex: Int?): Int? =
        exactDex?.takeIf { it > 0 } ?: hintDex?.takeIf { it > 0 }

    /** Base-form detection using the actual packaged atlas, not a fixed suffix list. */
    fun inferBaseSpeciesKey(keys: List<String>): String? {
        if (keys.isEmpty()) return null
        if (keys.size == 1) return keys.single()
        return keys
            .filter { candidate -> keys.any { other ->
                other != candidate && other.startsWith(candidate + "_")
            } }
            .minByOrNull(String::length)
    }

    fun allImages(): Sequence<Image> = sequence {
        megaIds.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))
            .forEach { (key, id) -> yield(Image(key.first, key.second, id)) }
        for (dex in 1..1025) yield(Image(dex, "base", dex))
    }

    fun lookup(dex: Int?, speciesId: String?): Image? {
        if (dex == null || dex !in 1..1025 || speciesId.isNullOrBlank()) return null
        val tokens = tokens(speciesId)
        val isMega = hasMegaToken(tokens)
        if (isMega) {
            val form = when {
                "megax" in tokens || ("mega" in tokens && "x" in tokens) -> "mega_x"
                "megay" in tokens || ("mega" in tokens && "y" in tokens) -> "mega_y"
                else -> "mega"
            }
            return megaIds[dex to form]?.let { Image(dex, form, it) }
        }
        // Do not silently substitute an incorrect base image for a known variant.
        if (tokens.any { it in nonBaseFormTokens }) return null
        return Image(dex, "base", dex)
    }

    fun isDistinctForm(speciesId: String?): Boolean {
        val parts = tokens(speciesId)
        return hasMegaToken(parts) || parts.any { it in nonBaseFormTokens }
    }
}
