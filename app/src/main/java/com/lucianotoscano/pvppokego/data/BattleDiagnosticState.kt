package com.lucianotoscano.pvppokego.data

import com.lucianotoscano.pvppokego.engine.OpponentTeamTracker
import org.json.JSONArray
import org.json.JSONObject

/** Latest read-only OCR evidence/slots, intended only for user-triggered ZIP export. */
object BattleDiagnosticState {
    @Volatile private var snapshot = "{\"status\":\"sem observações\"}"

    fun update(
        slots: List<OpponentTeamTracker.Slot>,
        ownOcrConfidence: Float?, opponentOcrConfidence: Float?,
        ownVisualConfidence: Float?, opponentVisualConfidence: Float?
    ) {
        val obj = JSONObject().apply {
            put("timestamp_ms", System.currentTimeMillis())
            put("own_ocr_confidence", ownOcrConfidence?.toDouble() ?: JSONObject.NULL)
            put("opponent_ocr_confidence", opponentOcrConfidence?.toDouble() ?: JSONObject.NULL)
            put("own_visual_confidence", ownVisualConfidence?.toDouble() ?: JSONObject.NULL)
            put("opponent_visual_confidence", opponentVisualConfidence?.toDouble() ?: JSONObject.NULL)
            put("opponents", JSONArray().apply {
                slots.forEach { slot -> put(JSONObject().apply {
                    put("slot", slot.index + 1)
                    put("species_id", slot.speciesId ?: JSONObject.NULL)
                    put("name", slot.name ?: JSONObject.NULL)
                    put("cp", slot.cp ?: JSONObject.NULL)
                    put("hp", slot.hpRatio?.toDouble() ?: JSONObject.NULL)
                    put("confidence", slot.confidence.toDouble())
                    put("last_seen_at_ms", slot.lastSeenAtMs)
                    put("active", slot.active)
                    put("fainted", slot.fainted)
                }) }
            })
        }
        snapshot = obj.toString(2)
    }

    fun export(): String = snapshot
    fun clear() { snapshot = "{\"status\":\"batalha encerrada\"}" }
}
