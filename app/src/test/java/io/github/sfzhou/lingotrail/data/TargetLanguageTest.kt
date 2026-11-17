package io.github.sfzhou.lingotrail.data

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetLanguageTest {

    @Test
    fun findsLanguagesByTag() {
        assertEquals(TargetLanguage.CHINESE_SIMPLIFIED, TargetLanguage.fromTag("zh-CN"))
        assertEquals(TargetLanguage.JAPANESE, TargetLanguage.fromTag("ja"))
        assertEquals(TargetLanguage.ENGLISH, TargetLanguage.fromTag("en"))
        assertNull(TargetLanguage.fromTag("klingon"))
        assertNull(TargetLanguage.fromTag(null))
    }

    @Test
    fun followsTheSystemLocale() {
        assertEquals(TargetLanguage.CHINESE_SIMPLIFIED, TargetLanguage.forSystemLocale(Locale.SIMPLIFIED_CHINESE))
        assertEquals(TargetLanguage.CHINESE_TRADITIONAL, TargetLanguage.forSystemLocale(Locale.TRADITIONAL_CHINESE))
        assertEquals(TargetLanguage.CHINESE_TRADITIONAL, TargetLanguage.forSystemLocale(Locale.forLanguageTag("zh-HK")))
        assertEquals(TargetLanguage.ENGLISH, TargetLanguage.forSystemLocale(Locale.US))
        assertEquals(TargetLanguage.JAPANESE, TargetLanguage.forSystemLocale(Locale.JAPAN))
        assertEquals(TargetLanguage.ENGLISH, TargetLanguage.forSystemLocale(Locale.forLanguageTag("xx-YY")))
    }

    @Test
    fun everyLanguageHasLabelsAndATag() {
        TargetLanguage.entries.forEach { language ->
            assertTrue(language.tag.isNotBlank())
            assertTrue(language.promptName.isNotBlank())
            assertNotNull(language.displayName(Locale.ENGLISH))
            assertTrue(language.displayName(Locale.ENGLISH).isNotBlank())
        }
    }

    @Test
    fun tagsAreUnique() {
        val tags = TargetLanguage.entries.map { it.tag }
        assertEquals(tags.size, tags.toSet().size)
    }
}
