package com.lucianotoscano.pvppokego.engine
import java.util.ArrayDeque

/** In-memory observations for the current match, never shared between opponents or games. */
internal class EnemyChargedHistory(private val maxEvents: Int = 12) {
    data class Event(val opponentSlot: Int, val speciesId: String, val moveId: String, val timestampMs: Long)
    private val events = ArrayDeque<Event>()
    fun clear() = events.clear()
    fun record(opponentSlot: Int?, speciesId: String?, moveId: String, timestampMs: Long) {
        if (opponentSlot == null || speciesId.isNullOrBlank() || moveId.isBlank()) return
        if (events.lastOrNull { it.opponentSlot == opponentSlot && it.speciesId == speciesId && it.moveId == moveId }?.let {
            it.opponentSlot == opponentSlot && it.moveId == moveId &&
                timestampMs - it.timestampMs in 0..600L
        } == true) return
        if (events.size >= maxEvents) events.removeFirst()
        events.addLast(Event(opponentSlot, speciesId, moveId, timestampMs))
    }
    fun observedMoves(opponentSlot: Int?, speciesId: String?): List<String> =
        if (opponentSlot == null || speciesId.isNullOrBlank()) emptyList()
        else events.filter { it.opponentSlot == opponentSlot && it.speciesId == speciesId }
            .map { it.moveId }
}
