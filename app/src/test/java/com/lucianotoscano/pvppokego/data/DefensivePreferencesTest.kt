package com.lucianotoscano.pvppokego.data

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class DefensivePreferencesTest {
    @Test fun badStoredTypesFallBackWithoutMutatingOtherEntries() {
        val preferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getBoolean", "getInt", "getLong", "getFloat", "getString", "getStringSet" ->
                    throw ClassCastException("bad legacy type")
                "contains" -> false
                else -> throw UnsupportedOperationException(method.name)
            }
        } as SharedPreferences
        val safe = DefensivePreferences(preferences)
        assertTrue(safe.getBoolean("auto", true))
        assertEquals(1500, safe.getInt("league", 1500))
        assertEquals(0L, safe.getLong("last", 0L))
        assertEquals(.8f, safe.getFloat("scale", .8f), .0001f)
        assertEquals(null, safe.getString("pending_version", null))
    }
}
