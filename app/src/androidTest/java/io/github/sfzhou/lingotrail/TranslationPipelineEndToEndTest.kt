package io.github.sfzhou.lingotrail

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.sfzhou.lingotrail.data.ScanStatus
import io.github.sfzhou.lingotrail.data.TargetLanguage
import io.github.sfzhou.lingotrail.overlay.OverlayPreparer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end check against the real DeepSeek platform: import a bundled travel
 * photo, run OCR + translation, and verify that overlay geometry is produced.
 *
 * Run with a private demo key:
 * `./gradlew :app:connectedDebugAndroidTest -PdefaultApiKey=sk-...`
 * Without a key the test is skipped, so CI stays free of secrets.
 */
@RunWith(AndroidJUnit4::class)
class TranslationPipelineEndToEndTest {

    @Test
    fun translatesBundledTravelPhotos() = runBlocking {
        assumeTrue(
            "Build with -PdefaultApiKey=... to run the real-API test",
            BuildConfig.DEFAULT_API_KEY.isNotBlank(),
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val container = (instrumentation.targetContext.applicationContext as LingoTrailApp).container
        val assets = instrumentation.context.assets

        val assetNames = listOf("test_sign.png", "test_menu.png", "test_palace.png")
        for (assetName in assetNames) {
            val item = assets.open(assetName).use { stream ->
                container.history.importImage(stream, TargetLanguage.CHINESE_SIMPLIFIED)
            }
            container.pipeline.start(item.id)

            val finished = withTimeout(180_000) {
                container.history.items
                    .first { items -> items.any { it.id == item.id && it.status != ScanStatus.PROCESSING } }
                    .first { it.id == item.id }
            }

            assertEquals("$assetName failed with ${finished.errorKind}", ScanStatus.DONE, finished.status)
            assertTrue("$assetName produced no text blocks", finished.blocks.isNotEmpty())
            assertTrue(
                "$assetName produced no overlayable translations",
                finished.blocks.any { it.boxes.isNotEmpty() && it.translation.isNotBlank() },
            )
            assertTrue(
                "$assetName was not actually translated",
                finished.blocks.any { it.translation.trim() != it.original.trim() },
            )

            val bitmap = BitmapFactory.decodeFile(container.history.imageFile(item.id).absolutePath)
            try {
                val overlay = OverlayPreparer.prepare(bitmap, finished)
                assertTrue("$assetName produced no drawable overlay blocks", overlay.blocks.isNotEmpty())
            } finally {
                bitmap.recycle()
            }
        }
    }
}
