package com.lucianotoscano.pvppokego.data

import com.lucianotoscano.pvppokego.BuildConfig

import android.content.Context
import org.json.JSONObject

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var overlayEnabled: Boolean
        get() = prefs.getBoolean("overlay_enabled", true)
        set(v) = prefs.edit().putBoolean("overlay_enabled", v).apply()

    var autoRecognition: Boolean
        get() = prefs.getBoolean("auto_recognition", true)
        set(v) = prefs.edit().putBoolean("auto_recognition", v).apply()

    var battleAssistEnabled: Boolean
        get() = prefs.getBoolean("battle_assist_enabled", true)
        set(v) = prefs.edit().putBoolean("battle_assist_enabled", v).apply()

    var leagueMode: BattleLeagueMode
        get() = runCatching {
            BattleLeagueMode.valueOf(prefs.getString("league_mode", BattleLeagueMode.AUTO.name)!!)
        }.getOrDefault(BattleLeagueMode.AUTO)
        set(v) = prefs.edit().putString("league_mode", v.name).apply()

    var lastDetectedLeagueCp: Int
        get() = prefs.getInt("last_detected_league_cp", 1500)
        set(v) = prefs.edit().putInt("last_detected_league_cp", v).apply()

    fun configuredLeagueCp(): Int? = leagueMode.cpCap

    var showStrongTypes: Boolean
        get() = prefs.getBoolean("show_strong_types", true)
        set(v) = prefs.edit().putBoolean("show_strong_types", v).apply()

    var showCurrentIndicator: Boolean
        get() = prefs.getBoolean("show_current_indicator", true)
        set(v) = prefs.edit().putBoolean("show_current_indicator", v).apply()

    var analyzeReserves: Boolean
        get() = prefs.getBoolean("analyze_reserves", true)
        set(v) = prefs.edit().putBoolean("analyze_reserves", v).apply()

    var showReserveTypes: Boolean
        get() = prefs.getBoolean("show_reserve_types", true)
        set(v) = prefs.edit().putBoolean("show_reserve_types", v).apply()

    var showEnemyMoves: Boolean
        get() = prefs.getBoolean("show_enemy_moves", true)
        set(v) = prefs.edit().putBoolean("show_enemy_moves", v).apply()

    var showChargedCounter: Boolean
        get() = prefs.getBoolean("show_charged_counter", true)
        set(v) = prefs.edit().putBoolean("show_charged_counter", v).apply()

    var showEnergyProgress: Boolean
        get() = prefs.getBoolean("show_energy_progress", true)
        set(v) = prefs.edit().putBoolean("show_energy_progress", v).apply()

    var showHudText: Boolean
        get() = prefs.getBoolean("show_hud_text", true)
        set(v) = prefs.edit().putBoolean("show_hud_text", v).apply()

    var showHpAssist: Boolean
        get() = prefs.getBoolean("show_hp_assist", true)
        set(v) = prefs.edit().putBoolean("show_hp_assist", v).apply()

    /** Whether the forecast also labels approximate HP left after an incoming charged move. */
    var showHpRemainingForecast: Boolean
        get() = prefs.getBoolean("show_hp_remaining_forecast", true)
        set(v) = prefs.edit().putBoolean("show_hp_remaining_forecast", v).apply()

    var showEnemyHistory: Boolean
        get() = prefs.getBoolean("show_enemy_history", false)
        set(v) = prefs.edit().putBoolean("show_enemy_history", v).apply()

    var quickMenuAdvanced: Boolean
        get() = prefs.getBoolean("quick_menu_advanced", false)
        set(v) = prefs.edit().putBoolean("quick_menu_advanced", v).apply()

    /** Menu can switch between PGSharp-like icon+text rows and compact icons only. */
    var quickMenuShowLabels: Boolean
        get() = prefs.getBoolean("quick_menu_show_labels", true)
        set(v) = prefs.edit().putBoolean("quick_menu_show_labels", v).apply()

    var quickMenuIconsOnly: Boolean
        get() = !quickMenuShowLabels
        set(v) { quickMenuShowLabels = !v }

    var quickMenuXFraction: Float
        get() = prefs.getFloat("quick_menu_x_frac", 1f)
        set(v) = prefs.edit().putFloat("quick_menu_x_frac", v.coerceIn(0f, 1f)).apply()

    var quickMenuYFraction: Float
        get() = prefs.getFloat("quick_menu_y_frac", 1f)
        set(v) = prefs.edit().putFloat("quick_menu_y_frac", v.coerceIn(0f, 1f)).apply()

    /** Independent league badge position; it never follows the gear. */
    var leagueBadgeXFraction: Float
        get() = prefs.getFloat("league_badge_x_frac", .82f)
        set(v) = prefs.edit().putFloat("league_badge_x_frac", v.coerceIn(0f, 1f)).apply()

    var leagueBadgeYFraction: Float
        get() = prefs.getFloat("league_badge_y_frac", .91f)
        set(v) = prefs.edit().putFloat("league_badge_y_frac", v.coerceIn(0f, 1f)).apply()

    fun ensureQuickMenuBottomRightV2() {
        if (prefs.getInt("quick_menu_position_version", 0) >= 2) return
        prefs.edit()
            .putFloat("quick_menu_x_frac", 1f)
            .putFloat("quick_menu_y_frac", 1f)
            .putInt("quick_menu_position_version", 2)
            .apply()
    }

    /** Default is icon+text like the PGSharp reference; users can collapse to icons only. */
    fun ensureQuickMenuStyleV3() {
        if (prefs.getInt("quick_menu_style_version", 0) >= 4) return
        prefs.edit()
            .putBoolean("quick_menu_show_labels", true)
            .putBoolean("quick_menu_advanced", false)
            .putInt("quick_menu_style_version", 4)
            .apply()
    }

    /**
     * V6 stops forcing a fixed top Y. The detector now anchors against GO's actual white cards.
     * We only clear legacy auto-generated high positions once; manual edits made after V6 persist.
     */
    fun ensureTopHudSafeV4() {
        if (prefs.getInt("top_hud_position_version", 0) >= 6) return
        val editor = prefs.edit()
        listOf("hud_player_matchup", "hud_strong_types").forEach { block ->
            editor.remove("${block}_x")
            editor.remove("${block}_y")
        }
        editor.putInt("top_hud_position_version", 6).apply()
    }

    /**
     * 0.5.13 layout preset based on the user's approved screenshots/config.
     * Existing manual positions are preserved. The only migration performed on
     * an existing layout is to make reserve #1 the upper native card and
     * reserve #2 the lower card while keeping the same two screen positions.
     */
    fun ensureApprovedLayoutV13() {
        if (prefs.getInt("layout_preset_version", 0) >= 13) return
        val editor = prefs.edit()

        fun putPositionIfMissing(block: String, x: Float, y: Float) {
            if (!prefs.contains("${block}_x")) editor.putFloat("${block}_x", x)
            if (!prefs.contains("${block}_y")) editor.putFloat("${block}_y", y)
        }
        fun putFloatIfMissing(key: String, value: Float) {
            if (!prefs.contains(key)) editor.putFloat(key, value)
        }

        putPositionIfMissing("hud_player_matchup", 430.97968f, 28.51471f)
        putPositionIfMissing("hud_strong_types", 710.88904f, 12.45274f)
        putPositionIfMissing("hud_enemy_moves", 698.45935f, 184.32745f)
        putPositionIfMissing("hud_battle_assist", 418.4f, 169.02563f)
        putPositionIfMissing("hud_enemy_history", 816f, 782.44104f)
        putPositionIfMissing("hud_toggle", 44.10156f, 1495.1797f)
        putPositionIfMissing("hud_lock", 90.11641f, 1493.2362f)

        val hasR1 = prefs.contains("hud_reserve_1_x") && prefs.contains("hud_reserve_1_y")
        val hasR2 = prefs.contains("hud_reserve_2_x") && prefs.contains("hud_reserve_2_y")
        if (hasR1 && hasR2) {
            val r1x = prefs.getFloat("hud_reserve_1_x", 686.4f)
            val r1y = prefs.getFloat("hud_reserve_1_y", 894.03076f)
            val r2x = prefs.getFloat("hud_reserve_2_x", 686.4f)
            val r2y = prefs.getFloat("hud_reserve_2_y", 1039.0974f)
            if (r1y > r2y) {
                editor.putFloat("hud_reserve_1_x", r2x)
                editor.putFloat("hud_reserve_1_y", r2y)
                editor.putFloat("hud_reserve_2_x", r1x)
                editor.putFloat("hud_reserve_2_y", r1y)
            }
        } else {
            putPositionIfMissing("hud_reserve_1", 686.4f, 894.03076f)
            putPositionIfMissing("hud_reserve_2", 686.4f, 1039.0974f)
        }

        putFloatIfMissing("league_badge_x_frac", 0.49793816f)
        putFloatIfMissing("league_badge_y_frac", 0.07623319f)
        putFloatIfMissing("quick_menu_x_frac", 0.9786802f)
        putFloatIfMissing("quick_menu_y_frac", 0.9933185f)

        editor.putInt("layout_preset_version", 13)
        editor.apply()
    }

    /**
     * 0.5.15 position preset taken directly from the user's exported 0.5.14 config.
     * This migration intentionally applies the approved coordinates once so the next
     * APK opens aligned with the layout the user validated on-device.
     */
    fun ensureApprovedLayoutV15() {
        if (prefs.getInt("layout_preset_version", 0) >= 15) return
        prefs.edit()
            .putFloat("hud_battle_assist_x", 429.6000061f)
            .putFloat("hud_battle_assist_y", 237.9487152f)
            .putFloat("hud_enemy_history_x", 832.3187256f)
            .putFloat("hud_enemy_history_y", 793.2551880f)
            .putFloat("hud_enemy_moves_x", 723.6773682f)
            .putFloat("hud_enemy_moves_y", 233.8441772f)
            .putFloat("hud_lock_x", 90.1164093f)
            .putFloat("hud_lock_y", 1493.2362061f)
            .putFloat("hud_player_matchup_x", 430.9796753f)
            .putFloat("hud_player_matchup_y", 28.5147095f)
            .putFloat("hud_reserve_1_x", 686.4000244f)
            .putFloat("hud_reserve_1_y", 894.0307617f)
            .putFloat("hud_reserve_2_x", 686.4000244f)
            .putFloat("hud_reserve_2_y", 1039.0974121f)
            .putFloat("hud_strong_types_x", 710.8890381f)
            .putFloat("hud_strong_types_y", 12.4527397f)
            .putFloat("hud_toggle_x", 44.1015587f)
            .putFloat("hud_toggle_y", 1495.1796875f)
            .putFloat("league_badge_x_frac", 0.49793816f)
            .putFloat("league_badge_y_frac", 0.07623319f)
            .putFloat("quick_menu_x_frac", 1f)
            .putFloat("quick_menu_y_frac", 1f)
            .putInt("layout_preset_version", 15)
            .apply()
    }

    var showSwitchTimer: Boolean
        get() = prefs.getBoolean("show_switch_timer", false)
        set(v) = prefs.edit().putBoolean("show_switch_timer", v).apply()

    var debugMode: Boolean
        get() = prefs.getBoolean("debug_mode", false)
        set(v) = prefs.edit().putBoolean("debug_mode", v).apply()

    var editMode: Boolean
        get() = prefs.getBoolean("edit_mode", false)
        set(v) = prefs.edit().putBoolean("edit_mode", v).apply()

    /** Global HUD position lock. Unlocking enables direct drag without opening edit mode. */
    var layoutLocked: Boolean
        get() = prefs.getBoolean("layout_locked", true)
        set(v) = prefs.edit().putBoolean("layout_locked", v).apply()

    var scale: Float
        get() = prefs.getFloat("overlay_scale", 0.8f)
        set(v) = prefs.edit().putFloat("overlay_scale", v.coerceIn(.5f, 1.2f)).apply()

    var opacity: Float
        get() = prefs.getFloat("overlay_opacity", .9f)
        set(v) = prefs.edit().putFloat("overlay_opacity", v.coerceIn(.3f, 1f)).apply()

    var overlayServiceRunning: Boolean
        get() = prefs.getBoolean("overlay_service_running", false)
        set(v) = prefs.edit().putBoolean("overlay_service_running", v).apply()

    fun setPosition(block: String, x: Float, y: Float) {
        prefs.edit().putFloat("${block}_x", x).putFloat("${block}_y", y).apply()
    }

    fun getPosition(block: String, defaultX: Float, defaultY: Float): Pair<Float, Float> =
        prefs.getFloat("${block}_x", defaultX) to prefs.getFloat("${block}_y", defaultY)

    fun hasPosition(block: String): Boolean = prefs.contains("${block}_x") && prefs.contains("${block}_y")

    fun blockScale(block: String): Float = prefs.getFloat("${block}_scale", 1f).coerceIn(.55f, 1.6f)
    fun setBlockScale(block: String, value: Float) = prefs.edit().putFloat("${block}_scale", value.coerceIn(.55f, 1.6f)).apply()

    fun blockOpacity(block: String): Float = prefs.getFloat("${block}_opacity", 1f).coerceIn(.25f, 1f)
    fun setBlockOpacity(block: String, value: Float) = prefs.edit().putFloat("${block}_opacity", value.coerceIn(.25f, 1f)).apply()

    fun isBlockHidden(block: String): Boolean = prefs.getBoolean("${block}_hidden", false)
    fun setBlockHidden(block: String, hidden: Boolean) = prefs.edit().putBoolean("${block}_hidden", hidden).apply()

    fun isBlockLocked(block: String): Boolean = prefs.getBoolean("${block}_locked", false)
    fun setBlockLocked(block: String, locked: Boolean) = prefs.edit().putBoolean("${block}_locked", locked).apply()

    fun clearHudPositions() {
        val blocks = HUD_BLOCKS
        prefs.edit().also { editor ->
            blocks.forEach { block ->
                editor.remove("${block}_x")
                editor.remove("${block}_y")
            }
        }.apply()
    }

    fun resetHudCustomization() {
        prefs.edit().also { editor ->
            HUD_BLOCKS.forEach { block ->
                listOf("x", "y", "scale", "opacity", "hidden", "locked").forEach { suffix ->
                    editor.remove("${block}_$suffix")
                }
            }
            // Full reset must also leave the overlay in a safe, non-editing state.
            editor.putBoolean("edit_mode", false)
            editor.putBoolean("layout_locked", true)
            editor.putFloat("overlay_scale", 0.8f)
            editor.putFloat("overlay_opacity", 0.9f)
        }.apply()
    }

    fun resetQuickMenuPosition() {
        prefs.edit()
            .putFloat("quick_menu_x_frac", 1f)
            .putFloat("quick_menu_y_frac", 1f)
            .putFloat("league_badge_x_frac", .82f)
            .putFloat("league_badge_y_frac", .91f)
            .putInt("quick_menu_position_version", 2)
            .apply()
    }

    /**
     * Portable backup of user-visible configuration, including HUD block positions.
     * Runtime-only flags are deliberately excluded so importing a backup cannot revive
     * a stale overlay/edit state.
     */
    fun exportBackupJson(): String {
        val root = JSONObject()
        root.put("format", "PvPPokeGo-settings")
        root.put("formatVersion", 1)
        root.put("appVersion", BuildConfig.VERSION_NAME)
        val values = JSONObject()
        prefs.all.toSortedMap().forEach { (key, value) ->
            if (!isBackupKeyAllowed(key) || value == null) return@forEach
            val item = JSONObject()
            when (value) {
                is Boolean -> { item.put("type", "boolean"); item.put("value", value) }
                is Int -> { item.put("type", "int"); item.put("value", value) }
                is Long -> { item.put("type", "long"); item.put("value", value) }
                is Float -> { item.put("type", "float"); item.put("value", value.toDouble()) }
                is String -> { item.put("type", "string"); item.put("value", value) }
                else -> return@forEach
            }
            values.put(key, item)
        }
        root.put("settings", values)
        return root.toString(2)
    }

    /** Returns the number of restored values. */
    fun importBackupJson(raw: String): Int {
        val root = JSONObject(raw)
        require(root.optString("format") == "PvPPokeGo-settings") { "Arquivo não é um backup do PvPPokeGo" }
        val values = root.getJSONObject("settings")
        val editor = prefs.edit()
        var restored = 0
        val keys = values.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (!isBackupKeyAllowed(key)) continue
            val item = values.optJSONObject(key) ?: continue
            when (item.optString("type")) {
                "boolean" -> editor.putBoolean(key, item.getBoolean("value"))
                "int" -> editor.putInt(key, item.getInt("value"))
                "long" -> editor.putLong(key, item.getLong("value"))
                "float" -> editor.putFloat(key, item.getDouble("value").toFloat())
                "string" -> editor.putString(key, item.getString("value"))
                else -> continue
            }
            restored++
        }
        // Always return to a safe state after an import. Positions/customizations remain restored.
        editor.putBoolean("edit_mode", false)
        editor.putBoolean("layout_locked", true)
        // Mark the current top-HUD migration as applied so restored manual positions
        // are not cleared on the next overlay start.
        editor.putInt("top_hud_position_version", 6)
        editor.remove("overlay_service_running")
        editor.apply()
        return restored
    }

    private fun isBackupKeyAllowed(key: String): Boolean {
        if (key == "overlay_service_running" || key == "edit_mode") return false
        if (key.startsWith("hud_")) return true
        if (key.startsWith("quick_menu_")) return true
        if (key.startsWith("league_badge_")) return true
        return key in BACKUP_KEYS
    }

    companion object {
        private const val FILE = "pvppokego_prefs"
        private val BACKUP_KEYS = setOf(
            "overlay_enabled", "auto_recognition", "battle_assist_enabled",
            "league_mode", "last_detected_league_cp", "show_strong_types",
            "show_current_indicator", "analyze_reserves", "show_reserve_types",
            "show_enemy_moves", "show_charged_counter", "show_energy_progress",
            "show_hud_text", "show_hp_assist", "show_hp_remaining_forecast", "show_enemy_history", "show_switch_timer", "debug_mode",
            "layout_locked", "overlay_scale", "overlay_opacity", "top_hud_position_version", "layout_preset_version", "manual_team_v1", "team_recognition_mode_v1",
            TrainerPreferencesRepository.KEY_LEVEL, TrainerPreferencesRepository.KEY_FACTION,
            TrainerPreferencesRepository.KEY_NAME, TrainerPreferencesRepository.KEY_DATE
        )
        val HUD_BLOCKS = listOf(
            "hud_player_matchup", "hud_strong_types", "hud_enemy_moves",
            "hud_reserve_1", "hud_reserve_2", "hud_reserves",
            "hud_toggle", "hud_lock", "hud_battle_assist", "hud_enemy_history"
        )
    }
}
