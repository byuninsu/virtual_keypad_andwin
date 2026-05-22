package com.virtualkeypad.android

import android.content.Context

data class KeypadButtonConfig(
    val prefKey: String,
    val defaultLabel: String,
    val defaultKeys: String
)

data class KeypadButtonState(
    val prefKey: String,
    val label: String,
    val key: String,
    val delayMs: Long
)

data class OverlayAnchor(
    val x: Int,
    val y: Int
)

class KeypadSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("virtual_keypad", Context.MODE_PRIVATE)

    fun getServerUrl(): String {
        return preferences.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
    }

    fun getRoomId(): String {
        return preferences.getString(KEY_ROOM_ID, DEFAULT_ROOM_ID) ?: DEFAULT_ROOM_ID
    }

    fun saveConnection(serverUrl: String, roomId: String) {
        preferences.edit()
            .putString(KEY_SERVER_URL, serverUrl)
            .putString(KEY_ROOM_ID, roomId)
            .apply()
    }

    fun loadButtons(): List<KeypadButtonState> {
        return DEFAULT_BUTTONS.map { config ->
            KeypadButtonState(
                prefKey = config.prefKey,
                label = preferences.getString("${config.prefKey}_label", config.defaultLabel)
                    ?: config.defaultLabel,
                key = preferences.getString("${config.prefKey}_key", config.defaultKeys)
                    ?: config.defaultKeys,
                delayMs = preferences.getLong("${config.prefKey}_delay_ms", 0L)
            )
        }
    }

    fun saveButtons(buttons: List<KeypadButtonState>) {
        val editor = preferences.edit()
        buttons.forEach { button ->
            editor.putString("${button.prefKey}_label", button.label)
            editor.putString("${button.prefKey}_key", button.key)
            editor.putLong("${button.prefKey}_delay_ms", button.delayMs)
        }
        editor.apply()
    }

    fun loadOverlayAnchor(): OverlayAnchor {
        return OverlayAnchor(
            x = preferences.getInt(KEY_OVERLAY_X, 32),
            y = preferences.getInt(KEY_OVERLAY_Y, 320)
        )
    }

    fun saveOverlayAnchor(anchor: OverlayAnchor) {
        preferences.edit()
            .putInt(KEY_OVERLAY_X, anchor.x)
            .putInt(KEY_OVERLAY_Y, anchor.y)
            .apply()
    }

    companion object {
        const val DEFAULT_SERVER_URL = "wss://virtualkeypadandwin-production.up.railway.app"
        const val DEFAULT_ROOM_ID = "test123"

        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_ROOM_ID = "room_id"
        private const val KEY_OVERLAY_X = "overlay_x"
        private const val KEY_OVERLAY_Y = "overlay_y"

        val DEFAULT_BUTTONS = listOf(
            KeypadButtonConfig("up", "Up", "ArrowUp"),
            KeypadButtonConfig("down", "Down", "ArrowDown"),
            KeypadButtonConfig("left", "Left", "ArrowLeft"),
            KeypadButtonConfig("right", "Right", "ArrowRight"),
            KeypadButtonConfig("top1", "F1", "1"),
            KeypadButtonConfig("top2", "F2", "2"),
            KeypadButtonConfig("top3", "F3", "3"),
            KeypadButtonConfig("top4", "F4", "4"),
            KeypadButtonConfig("top5", "F5", "5"),
            KeypadButtonConfig("primary", "A", "Space"),
            KeypadButtonConfig("secondary", "B", "Enter"),
            KeypadButtonConfig("tertiary", "C", "Escape"),
            KeypadButtonConfig("quaternary", "D", "Tab")
        )
    }
}
