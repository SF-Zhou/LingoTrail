package io.github.sfzhou.lingotrail.data

import io.github.sfzhou.lingotrail.BuildConfig

/**
 * User-visible settings. Everything lives on this device only.
 */
data class AppSettings(
    val apiKey: String = BuildConfig.DEFAULT_API_KEY,
    val targetLanguage: TargetLanguage = TargetLanguage.default,
    val baseUrl: String = BuildConfig.DEEPSEEK_BASE_URL,
    val model: String = DEFAULT_MODEL,
    val onboardingComplete: Boolean = BuildConfig.DEFAULT_API_KEY.isNotBlank(),
) {
    val hasApiKey: Boolean get() = apiKey.isNotBlank()

    companion object {
        const val DEFAULT_MODEL = "deepseek-flash"
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
    }
}
