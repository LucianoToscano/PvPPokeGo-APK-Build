package com.lucianotoscano.pvppokego.data

import java.text.Normalizer

/** Parsed, untrusted evidence from a user-selected screenshot. Nothing is saved automatically. */
data class TeamScanEvidence(
    val speciesId: String? = null,
    val speciesName: String? = null,
    val cp: Int? = null,
    val maxHp: Int? = null,
    val gender: String? = null,
    val fastMoveId: String? = null,
    val chargedMove1Id: String? = null,
    val chargedMove2Id: String? = null,
    val matchedMoveNames: List<String> = emptyList(),
    val notes: List<String> = emptyList()
) {
    val hasData: Boolean get() = speciesId != null || cp != null ||
        maxHp != null || fastMoveId != null || chargedMove1Id != null ||
        chargedMove2Id != null
}

object TeamScanParser {
    private fun normalized(raw: String): String = Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private val cpPattern = Regex("(?i)\\b(?:pc|cp)\\s*[:.]?\\s*([0-9]{2,5})\\b")
    private val hpPattern = Regex("(?i)\\b([0-9]{1,4})\\s*/\\s*([0-9]{1,4})\\s*(?:ps|hp)\\b")
    private val hpPrefixPattern = Regex("(?i)\\b(?:ps|hp)\\s*[:.]?\\s*[0-9]{1,4}\\s*/\\s*([0-9]{1,4})\\b")
    private val hpSinglePattern = Regex("(?i)\\b(?:ps|hp)\\s*[:.]?\\s*([0-9]{1,4})\\b")

    /** A nickname such as "Cramorant 6 9 14" is NEVER used as IV evidence. */
    fun parse(
        raw: String,
        species: List<PokemonDef>,
        moves: List<MoveDef>,
        moveNamesPtBr: Map<String, String>
    ): TeamScanEvidence {
        val lines = raw.lines().map(::normalized).filter { it.isNotBlank() }
        val whole = lines.joinToString(" ")
        val pokemon = species.filter { it.speciesId.isNotBlank() && it.speciesName.isNotBlank() }
            .sortedWith(compareByDescending<PokemonDef> { normalized(it.speciesName).length })
            .firstOrNull { candidate ->
                val name = normalized(candidate.speciesName)
                val id = normalized(candidate.speciesId)
                (name.length >= 3 && Regex("(?:^| )" + Regex.escape(name) + "(?: |$)").containsMatchIn(whole)) ||
                    (id.length >= 3 && Regex("(?:^| )" + Regex.escape(id) + "(?: |$)").containsMatchIn(whole))
            }
        // Explicit "PC"/"CP" only: never confuse candies/stardust with CP.
        val cp = cpPattern.find(raw)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 10..10000 }
        val hp = hpPattern.find(raw)?.let { match ->
            val current = match.groupValues[1].toIntOrNull()
            val max = match.groupValues[2].toIntOrNull()
            max?.takeIf { it in 1..2000 && current != null && current in 0..it }
        } ?: hpPrefixPattern.find(raw)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..2000 }
            ?: hpSinglePattern.find(raw)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..2000 }
        val gender = when {
            raw.contains("♀") -> "FEMALE"
            raw.contains("♂") -> "MALE"
            else -> null
        }

        val legal = pokemon?.let { found ->
            moves.filter { it.moveId in found.fastMoves || it.moveId in found.chargedMoves }
        }.orEmpty()
        // Longest full line match avoids short move names being hidden in other text.
        val ordered = legal.sortedByDescending { normalized(moveNamesPtBr[it.moveId] ?: it.name).length }
            .filter { move ->
                val aliases = listOfNotNull(moveNamesPtBr[move.moveId], move.name)
                aliases.any { alias ->
                    val key = normalized(alias)
                    key.length >= 3 && lines.any { line ->
                        Regex("(?:^| )" + Regex.escape(key) + "(?: |$)").containsMatchIn(line)
                    }
                }
            }
        val fast = ordered.firstOrNull { it.moveId in pokemon?.fastMoves.orEmpty() }
        val charged = ordered.filter { it.moveId in pokemon?.chargedMoves.orEmpty() }.take(2)
        val notes = buildList {
            if (pokemon == null) add("Espécie não identificada com segurança; escolha-a manualmente.")
            if (cp == null) add("PC não lido; confira a parte superior da ficha.")
            if (hp == null) add("PS máximo não localizado; use a captura com a barra de PS.")
            add("IVs não são inferidos de apelidos: use Avaliar ou preencha manualmente.")
            if (charged.isEmpty()) add("Golpes não reconhecidos ou não constam do catálogo offline.")
        }
        return TeamScanEvidence(
            pokemon?.speciesId, pokemon?.speciesName, cp, hp, gender,
            fast?.moveId, charged.getOrNull(0)?.moveId, charged.getOrNull(1)?.moveId,
            listOfNotNull(fast, *charged.toTypedArray()).map { moveNamesPtBr[it.moveId] ?: it.name },
            notes
        )
    }
}
