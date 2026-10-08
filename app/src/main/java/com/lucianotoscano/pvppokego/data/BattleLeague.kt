package com.lucianotoscano.pvppokego.data

import java.text.Normalizer

enum class BattleLeagueMode(val label: String, val cpCap: Int?) {
    AUTO("Automático", null),
    GREAT("Liga Grande", 1500),
    ULTRA("Liga Ultra", 2500),
    MASTER("Liga Mestra", 10000)
}

object BattleLeagueTextDetector {
    fun detectCp(raw: String): Int? {
        val text = normalize(raw)
        if (text.isBlank()) return null
        return when {
            containsAny(text,
                "liga mestra", "liga mestre", "master league", "sem limite de pc",
                "sem limite de cp", "no cp limit", "pc maximo por pokemon nao ha",
                "pc maximo nao ha", "cp max no limit"
            ) || (text.contains("pc maximo") && text.contains("nao ha")) -> 10000
            containsAny(text,
                "liga ultra", "ultra league", "pc max 2500", "max pc 2500",
                "cp 2500", "2500 cp", "limite 2500", "ate 2500", "pc maximo por pokemon 2500"
            ) || (text.contains("2500") && (text.contains("pc max") || text.contains("cp max") || text.contains("limite"))) -> 2500
            containsAny(text,
                "liga grande", "great league", "pc max 1500", "max pc 1500",
                "cp 1500", "1500 cp", "limite 1500", "ate 1500", "pc maximo por pokemon 1500"
            ) || (text.contains("1500") && (text.contains("pc max") || text.contains("cp max") || text.contains("limite") || text.contains("copa"))) -> 1500
            else -> null
        }
    }

    private fun containsAny(text: String, vararg values: String): Boolean = values.any(text::contains)

    private fun normalize(value: String): String {
        val noAccents = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return noAccents.lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }
}
