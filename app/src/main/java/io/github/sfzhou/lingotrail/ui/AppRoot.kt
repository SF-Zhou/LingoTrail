package io.github.sfzhou.lingotrail.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sfzhou.lingotrail.AppContainer
import io.github.sfzhou.lingotrail.ui.components.SystemBarsIcons
import io.github.sfzhou.lingotrail.ui.home.HomeScreen
import io.github.sfzhou.lingotrail.ui.onboarding.OnboardingScreen
import io.github.sfzhou.lingotrail.ui.settings.PrivacyScreen
import io.github.sfzhou.lingotrail.ui.settings.SettingsScreen
import io.github.sfzhou.lingotrail.ui.viewer.ViewerScreen

/** Top-level destinations. The app uses a tiny hand-rolled stack instead of Navigation Compose. */
sealed interface Screen {
    data object Onboarding : Screen
    data object Home : Screen
    data object Settings : Screen
    data object Privacy : Screen
    data class Viewer(val scanId: String) : Screen
}

private val screenStackSaver = listSaver<List<Screen>, String>(
    save = { stack -> stack.map { it.encode() } },
    restore = { encoded -> encoded.mapNotNull { decode(it) } },
)

private fun Screen.encode(): String = when (this) {
    Screen.Onboarding -> "onboarding"
    Screen.Home -> "home"
    Screen.Settings -> "settings"
    Screen.Privacy -> "privacy"
    is Screen.Viewer -> "viewer:$scanId"
}

private fun decode(encoded: String): Screen? = when {
    encoded == "onboarding" -> Screen.Onboarding
    encoded == "home" -> Screen.Home
    encoded == "settings" -> Screen.Settings
    encoded == "privacy" -> Screen.Privacy
    encoded.startsWith("viewer:") -> Screen.Viewer(encoded.removePrefix("viewer:"))
    else -> null
}

@Composable
fun AppRoot(container: AppContainer) {
    val settings by container.settings.settings.collectAsStateWithLifecycle()

    var screens by rememberSaveable(stateSaver = screenStackSaver) {
        mutableStateOf(
            listOf(if (settings.onboardingComplete) Screen.Home else Screen.Onboarding),
        )
    }
    val current = screens.last()
    val inDarkTheme = isSystemInDarkTheme()
    SystemBarsIcons(useDarkIcons = current !is Screen.Viewer && !inDarkTheme)

    fun push(screen: Screen) {
        screens = screens + screen
    }

    fun pop() {
        if (screens.size > 1) screens = screens.dropLast(1)
    }

    BackHandler(enabled = screens.size > 1) { pop() }

    Crossfade(targetState = current, animationSpec = tween(220), label = "screen") { screen ->
        when (screen) {
            Screen.Onboarding -> OnboardingScreen(
                container = container,
                onDone = { screens = listOf(Screen.Home) },
            )

            Screen.Home -> HomeScreen(
                container = container,
                onOpenScan = { scanId -> push(Screen.Viewer(scanId)) },
                onOpenSettings = { push(Screen.Settings) },
            )

            Screen.Settings -> SettingsScreen(
                container = container,
                onBack = { pop() },
                onOpenPrivacy = { push(Screen.Privacy) },
            )

            Screen.Privacy -> PrivacyScreen(onBack = { pop() })

            is Screen.Viewer -> ViewerScreen(
                container = container,
                scanId = screen.scanId,
                onBack = { pop() },
                onOpenSettings = { push(Screen.Settings) },
            )
        }
    }
}
