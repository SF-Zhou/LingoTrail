package io.github.sfzhou.lingotrail.ui.onboarding

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.sfzhou.lingotrail.AppContainer
import io.github.sfzhou.lingotrail.R
import io.github.sfzhou.lingotrail.data.TargetLanguage
import io.github.sfzhou.lingotrail.net.ApiErrorKind
import io.github.sfzhou.lingotrail.net.ApiException
import io.github.sfzhou.lingotrail.net.DeepSeekApi
import io.github.sfzhou.lingotrail.ui.components.LanguagePickerDialog
import io.github.sfzhou.lingotrail.ui.userMessage
import kotlinx.coroutines.launch

private sealed interface SetupError {
    data object MissingKey : SetupError
    data object InvalidKey : SetupError
    data class Api(val kind: ApiErrorKind) : SetupError
}

/**
 * First-run setup: explains the app, then collects the user's own DeepSeek key.
 * The key is verified against `GET /models` before onboarding completes, so a
 * typo is caught here instead of on the first scan.
 */
@Composable
fun OnboardingScreen(container: AppContainer, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var apiKey by rememberSaveable { mutableStateOf(container.settings.current.apiKey) }
    var targetLanguageTag by rememberSaveable {
        mutableStateOf(container.settings.current.targetLanguage.tag)
    }
    var showLanguagePicker by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<SetupError?>(null) }
    val targetLanguage = TargetLanguage.fromTag(targetLanguageTag) ?: TargetLanguage.default

    fun submit() {
        val key = apiKey.trim()
        if (key.isEmpty()) {
            error = SetupError.MissingKey
            return
        }
        if (verifying) return
        verifying = true
        error = null
        scope.launch {
            try {
                DeepSeekApi(
                    baseUrl = container.settings.current.baseUrl,
                    apiKey = key,
                    model = container.settings.current.model,
                ).verifyKey()
                container.settings.setApiKey(key)
                container.settings.setTargetLanguage(targetLanguage)
                container.settings.completeOnboarding()
                onDone()
            } catch (e: ApiException) {
                error = if (e.kind == ApiErrorKind.AUTH) SetupError.InvalidKey else SetupError.Api(e.kind)
            } catch (e: Exception) {
                error = SetupError.Api(ApiErrorKind.UNKNOWN)
            } finally {
                verifying = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(28.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_splash_logo),
                    contentDescription = null,
                    modifier = Modifier.padding(8.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.app_tagline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(36.dp))
        Text(
            text = stringResource(R.string.onboarding_headline),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.onboarding_subheadline),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(28.dp))
        FeatureRow(
            iconRes = R.drawable.ic_camera,
            title = stringResource(R.string.onboarding_feature_capture_title),
            body = stringResource(R.string.onboarding_feature_capture_text),
        )
        FeatureRow(
            iconRes = R.drawable.ic_translate,
            title = stringResource(R.string.onboarding_feature_overlay_title),
            body = stringResource(R.string.onboarding_feature_overlay_text),
        )
        FeatureRow(
            iconRes = R.drawable.ic_history,
            title = stringResource(R.string.onboarding_feature_history_title),
            body = stringResource(R.string.onboarding_feature_history_text),
        )

        Spacer(Modifier.height(24.dp))
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(R.string.onboarding_setup_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(14.dp))
                var visible by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = {
                        apiKey = it
                        if (error != null) error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.api_key_label)) },
                    placeholder = { Text(stringResource(R.string.api_key_placeholder)) },
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                painter = painterResource(
                                    if (visible) R.drawable.ic_visibility else R.drawable.ic_visibility_off,
                                ),
                                contentDescription = null,
                            )
                        }
                    },
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.api_key_helper),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://platform.deepseek.com/api_keys"),
                            ),
                        )
                    }
                }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_open_in_new),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.get_api_key))
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.target_language_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = targetLanguage.displayName(),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { showLanguagePicker = true }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_language),
                                contentDescription = stringResource(R.string.cd_target_language),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.target_language_helper),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                error?.let { setupError ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = when (setupError) {
                            SetupError.MissingKey -> stringResource(R.string.api_key_required)
                            SetupError.InvalidKey -> stringResource(R.string.api_key_invalid)
                            is SetupError.Api -> setupError.kind.userMessage()
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { submit() },
                    enabled = !verifying,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                ) {
                    if (verifying) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.verifying))
                    } else {
                        Text(stringResource(R.string.start_button))
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.onboarding_privacy_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start,
        )
        Spacer(Modifier.height(24.dp))
    }

    if (showLanguagePicker) {
        LanguagePickerDialog(
            current = targetLanguage,
            onSelect = {
                targetLanguageTag = it.tag
                showLanguagePicker = false
            },
            onDismiss = { showLanguagePicker = false },
        )
    }
}

@Composable
private fun FeatureRow(iconRes: Int, title: String, body: String) {
    Row(
        modifier = Modifier.padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.padding(9.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
