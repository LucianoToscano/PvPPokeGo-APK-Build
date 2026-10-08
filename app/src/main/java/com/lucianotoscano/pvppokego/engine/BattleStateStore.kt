package com.lucianotoscano.pvppokego.engine

import com.lucianotoscano.pvppokego.data.BattlePokemonState
import java.util.concurrent.ConcurrentHashMap

class BattleStateStore {
    private val states = ConcurrentHashMap<String, BattlePokemonState>()

    fun getOrCreate(
        key: String,
        speciesName: String,
        speciesId: String? = null
    ): BattlePokemonState {
        val state = states.getOrPut(key) {
            BattlePokemonState(key = key, speciesName = speciesName, speciesId = speciesId)
        }
        if (!speciesId.isNullOrBlank()) state.speciesId = speciesId
        return state
    }

    fun find(key: String): BattlePokemonState? = states[key]

    fun all(): List<BattlePokemonState> = states.values.toList()

    fun clearBattle() = states.clear()
}
