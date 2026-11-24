package com.aihuiji.meeting.data

import android.content.Context
import com.aihuiji.meeting.core.model.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.security.MessageDigest

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()
    val value: AppSettings get() = _settings.value

    private fun load(): AppSettings =
        prefs.getString(KEY, null)?.let { runCatching { json.decodeFromString<AppSettings>(it) }.getOrNull() } ?: AppSettings()

    @Synchronized
    fun update(block: (AppSettings) -> AppSettings) {
        val next = block(_settings.value)
        if (next == _settings.value) return
        _settings.value = next
        prefs.edit().putString(KEY, json.encodeToString(AppSettings.serializer(), next)).apply()
    }

    fun setAdminPin(pin: String) = update { it.copy(adminPinHash = hashPin(pin)) }

    fun checkAdminPin(pin: String): Boolean = value.adminPinHash == hashPin(pin)

    companion object {
        private const val KEY = "app_settings"

        fun isValidPin(pin: String) = pin.length in 4..6 && pin.all(Char::isDigit)

        fun hashPin(pin: String): String =
            MessageDigest.getInstance("SHA-256").digest("aihuiji:$pin".toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
