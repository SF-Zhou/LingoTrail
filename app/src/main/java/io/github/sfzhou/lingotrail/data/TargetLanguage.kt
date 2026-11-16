package io.github.sfzhou.lingotrail.data

import java.util.Locale

/**
 * Languages the app can translate into.
 *
 * [promptName] is the English name handed to the translation model; UI labels are
 * resolved with [displayName] so they follow the device locale automatically.
 */
enum class TargetLanguage(val tag: String, val promptName: String) {
    ENGLISH("en", "English"),
    CHINESE_SIMPLIFIED("zh-CN", "Simplified Chinese"),
    CHINESE_TRADITIONAL("zh-TW", "Traditional Chinese"),
    JAPANESE("ja", "Japanese"),
    KOREAN("ko", "Korean"),
    SPANISH("es", "Spanish"),
    FRENCH("fr", "French"),
    GERMAN("de", "German"),
    ITALIAN("it", "Italian"),
    PORTUGUESE("pt", "Portuguese"),
    RUSSIAN("ru", "Russian"),
    THAI("th", "Thai"),
    VIETNAMESE("vi", "Vietnamese"),
    INDONESIAN("id", "Indonesian"),
    MALAY("ms", "Malay"),
    ARABIC("ar", "Arabic"),
    HINDI("hi", "Hindi"),
    TURKISH("tr", "Turkish"),
    DUTCH("nl", "Dutch"),
    GREEK("el", "Greek"),
    HEBREW("he", "Hebrew"),
    POLISH("pl", "Polish"),
    SWEDISH("sv", "Swedish"),
    CZECH("cs", "Czech"),
    DANISH("da", "Danish"),
    NORWEGIAN("nb", "Norwegian"),
    FINNISH("fi", "Finnish"),
    HUNGARIAN("hu", "Hungarian"),
    ROMANIAN("ro", "Romanian"),
    UKRAINIAN("uk", "Ukrainian"),
    BULGARIAN("bg", "Bulgarian"),
    CROATIAN("hr", "Croatian"),
    SERBIAN("sr", "Serbian"),
    SLOVAK("sk", "Slovak"),
    PERSIAN("fa", "Persian"),
    URDU("ur", "Urdu"),
    BENGALI("bn", "Bengali"),
    TAMIL("ta", "Tamil"),
    FILIPINO("fil", "Filipino"),
    KHMER("km", "Khmer"),
    LAO("lo", "Lao"),
    BURMESE("my", "Burmese"),
    NEPALI("ne", "Nepali"),
    MONGOLIAN("mn", "Mongolian"),
    GEORGIAN("ka", "Georgian"),
    ARMENIAN("hy", "Armenian"),
    AZERBAIJANI("az", "Azerbaijani"),
    KAZAKH("kk", "Kazakh"),
    UZBEK("uz", "Uzbek"),
    ;

    fun displayName(locale: Locale = Locale.getDefault()): String {
        // Locale gives "Chinese (China)" for zh-CN in English, which hides the
        // simplified/traditional distinction; spell the two variants out instead.
        return when (this) {
            CHINESE_SIMPLIFIED -> if (locale.language == "zh") "简体中文" else "Chinese (Simplified)"
            CHINESE_TRADITIONAL -> if (locale.language == "zh") "繁體中文" else "Chinese (Traditional)"
            else -> Locale.forLanguageTag(tag).getDisplayName(locale)
        }
    }

    companion object {
        val default: TargetLanguage = forSystemLocale()

        fun fromTag(tag: String?): TargetLanguage? =
            tag?.let { value -> entries.firstOrNull { it.tag.equals(value, ignoreCase = true) } }

        /** Best guess for the device language, falling back to English. */
        fun forSystemLocale(locale: Locale = Locale.getDefault()): TargetLanguage {
            if (locale.language == "iw") return HEBREW
            if (locale.language == "in") return INDONESIAN
            if (locale.language == "no") return NORWEGIAN
            if (locale.language == "zh") {
                val traditional = locale.script == "Hant" ||
                    locale.country in setOf("TW", "HK", "MO")
                return if (traditional) CHINESE_TRADITIONAL else CHINESE_SIMPLIFIED
            }
            return entries.firstOrNull { it.tag.substringBefore('-') == locale.language }
                ?: ENGLISH
        }
    }
}
