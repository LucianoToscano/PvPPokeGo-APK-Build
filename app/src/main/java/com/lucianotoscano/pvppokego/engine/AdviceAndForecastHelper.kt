package com.lucianotoscano.pvppokego.engine

import com.lucianotoscano.pvppokego.data.DamageForecast
import com.lucianotoscano.pvppokego.data.DamageForecastConfidence
import com.lucianotoscano.pvppokego.data.MoveDef

/** Aggregates candidate charged-move damage without mutating battle trackers. */
internal object AdviceAndForecastHelper {
    fun combine(
        forecasts: List<Pair<MoveDef, DamageForecast>>,
        playerHpRatio: Float?,
        localizedName: (MoveDef) -> String
    ): DamageForecast {
        val minDamage = forecasts.minOf { it.second.minPercent }
        val maxDamage = forecasts.maxOf { it.second.maxPercent }
        val avgDamage = forecasts.maxOf { it.second.averagePercent }
        val currentHpPercent = playerHpRatio?.times(100f)
        val names = forecasts.map { localizedName(it.first) }.distinct()
        val allObserved = forecasts.all {
            it.second.confidence != DamageForecastConfidence.MODEL
        }
        val stableObserved = forecasts.all {
            it.second.confidence == DamageForecastConfidence.OBSERVED_STABLE
        }
        return DamageForecast(
            moveName = names.joinToString(" / ").take(50),
            averagePercent = avgDamage,
            minPercent = minDamage,
            maxPercent = maxDamage,
            projectedRemainingPercent = currentHpPercent?.let { (it - avgDamage).coerceIn(0f, 100f) },
            source = if (allObserved) "dano observado • golpes possíveis" else "modelo PvPoke • golpes possíveis",
            confidence = when {
                stableObserved -> DamageForecastConfidence.OBSERVED_STABLE
                allObserved -> DamageForecastConfidence.OBSERVED_ESTIMATED
                else -> DamageForecastConfidence.MODEL
            }
        )
    }
}
