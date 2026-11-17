package io.github.sfzhou.lingotrail

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import io.github.sfzhou.lingotrail.data.HistoryRepository
import io.github.sfzhou.lingotrail.data.SettingsStore
import io.github.sfzhou.lingotrail.pipeline.TranslationPipeline
import io.github.sfzhou.lingotrail.util.MediaCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Hand-written dependency container. The app is small enough that a DI framework
 * would only add build time and indirection.
 */
class AppContainer(context: Context) {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsStore(context)
    val history = HistoryRepository(context)
    val pipeline = TranslationPipeline(history, settings, appScope)
    val media = MediaCache()

    init {
        appScope.launch { history.load() }
    }
}

/** Resolves the application container from any composition. */
@Composable
fun rememberAppContainer(): AppContainer {
    val context = LocalContext.current
    return (context.applicationContext as LingoTrailApp).container
}
