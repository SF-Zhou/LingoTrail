package io.github.sfzhou.lingotrail.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists [AppSettings] in private SharedPreferences.
 *
 * The API key never leaves the app's private storage except as an Authorization
 * header on requests the user triggers.
 */
class SettingsStore(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val current: AppSettings get() = _settings.value

    fun setApiKey(apiKey: String) = update { it.copy(apiKey = apiKey.trim()) }

    fun setTargetLanguage(language: TargetLanguage) = update { it.copy(targetLanguage = language) }

    fun setBaseUrl(url: String) = update {
        val normalized = url.trim().trimEnd('/').ifBlank { AppSettings.DEFAULT_BASE_URL }
        it.copy(baseUrl = normalized)
    }

    fun completeOnboarding() = update { it.copy(onboardingComplete = true) }

    private fun update(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_settings.value)
        prefs.edit {
            putString(KEY_API_KEY, updated.apiKey)
            putString(KEY_TARGET_LANGUAGE, updated.targetLanguage.tag)
            putString(KEY_BASE_URL, updated.baseUrl)
            putBoolean(KEY_ONBOARDING_COMPLETE, updated.onboardingComplete)
        }
        _settings.value = updated
    }

    private fun read(): AppSettings {
        val defaults = AppSettings()
        return defaults.copy(
            apiKey = prefs.getString(KEY_API_KEY, defaults.apiKey).orEmpty(),
            targetLanguage = TargetLanguage.fromTag(prefs.getString(KEY_TARGET_LANGUAGE, null))
                ?: defaults.targetLanguage,
            baseUrl = prefs.getString(KEY_BASE_URL, defaults.baseUrl)
                ?.takeIf { it.isNotBlank() } ?: defaults.baseUrl,
            onboardingComplete = prefs.getBoolean(KEY_ONBOARDING_COMPLETE, defaults.onboardingComplete),
        )
    }

    private companion object {
        const val PREFS_NAME = "lingotrail_settings"
        const val KEY_API_KEY = "api_key"
        const val KEY_TARGET_LANGUAGE = "target_language"
        const val KEY_BASE_URL = "base_url"
        const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
    }
}
