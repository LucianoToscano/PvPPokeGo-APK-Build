package com.lucianotoscano.pvppokego.engine

import com.lucianotoscano.pvppokego.data.DetectedPokemon
import com.lucianotoscano.pvppokego.data.GameDataRepository

/**
 * Pure CP/OCR/visual identity decisions. Team slot binding and battle continuity
 * remain in the facade until they can be moved with replay-equivalence tests.
 */
internal class IdentityFusion(private val repo: GameDataRepository) {
    private companion object { const val TEAM_VISUAL_MIN_CONFIDENCE = 0.76f }
    private fun normalize(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]+"), "")

    fun samePokemonIdentity(
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

    fun resolveTeamIdentityCandidate(pokemon: DetectedPokemon): TeamIdentityCandidate? {
        val ocrCanonical = repo.canonicalPokemonName(pokemon.name) ?: pokemon.name
        val ocrDef = repo.pokemon(ocrCanonical)
        val visualDef = if (
            (pokemon.visualConfidence ?: 0f) >= TEAM_VISUAL_MIN_CONFIDENCE
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

}
