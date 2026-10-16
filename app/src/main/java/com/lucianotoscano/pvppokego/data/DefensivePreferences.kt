package com.lucianotoscano.pvppokego.data

import android.content.SharedPreferences

/** A malformed legacy preference type must not crash startup or overwrite healthy keys. */
internal class DefensivePreferences(private val delegate: SharedPreferences) : SharedPreferences by delegate {
    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        runCatching { delegate.getBoolean(key, defValue) }.getOrDefault(defValue)

    override fun getInt(key: String?, defValue: Int): Int =
        runCatching { delegate.getInt(key, defValue) }.getOrDefault(defValue)

    override fun getLong(key: String?, defValue: Long): Long =
        runCatching { delegate.getLong(key, defValue) }.getOrDefault(defValue)

    override fun getFloat(key: String?, defValue: Float): Float =
        runCatching { delegate.getFloat(key, defValue) }
            .getOrDefault(defValue).takeIf(Float::isFinite) ?: defValue

    override fun getString(key: String?, defValue: String?): String? =
        runCatching { delegate.getString(key, defValue) }.getOrDefault(defValue)

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        runCatching { delegate.getStringSet(key, defValues) }.getOrDefault(defValues)
}
