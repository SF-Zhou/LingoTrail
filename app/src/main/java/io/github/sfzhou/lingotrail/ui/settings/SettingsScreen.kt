package io.github.sfzhou.lingotrail.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sfzhou.lingotrail.AppContainer
import io.github.sfzhou.lingotrail.BuildConfig
import io.github.sfzhou.lingotrail.R
import io.github.sfzhou.lingotrail.ui.components.ApiKeyDialog
import io.github.sfzhou.lingotrail.ui.components.BaseUrlDialog
import io.github.sfzhou.lingotrail.ui.components.ConfirmDialog
import io.github.sfzhou.lingotrail.ui.components.InfoRow
import io.github.sfzhou.lingotrail.ui.components.LanguagePickerDialog
import io.github.sfzhou.lingotrail.ui.components.RowDivider
import io.github.sfzhou.lingotrail.ui.components.SectionHeader
import io.github.sfzhou.lingotrail.ui.components.SettingsGroup
import io.github.sfzhou.lingotrail.ui.components.SettingsRow
import io.github.sfzhou.lingotrail.ui.components.SourceLanguagePickerDialog
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenPrivacy: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showLanguagePicker by remember { mutableStateOf(false) }
    var showSourceLanguagePicker by remember { mutableStateOf(false) }
    var showApiKeyDialog by remember { mutableStateOf(false) }
    var showBaseUrlDialog by remember { mutableStateOf(false) }
    var showClearHistoryDialog by remember { mutableStateOf(false) }

    fun openUrl(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 40.dp),
        ) {
            item {
                SectionHeader(stringResource(R.string.settings_section_language))
            }
            item {
                SettingsGroup {
                    SettingsRow(
                        title = stringResource(R.string.target_language_label),
                        value = settings.targetLanguage.displayName(),
                        leadingIconRes = R.drawable.ic_translate,
                        onClick = { showLanguagePicker = true },
                    )
                    RowDivider()
                    SettingsRow(
                        title = stringResource(R.string.source_language),
                        subtitle = stringResource(R.string.source_language_helper),
                        value = settings.sourceLanguage?.displayName()
                            ?: stringResource(R.string.source_language_auto),
                        leadingIconRes = R.drawable.ic_language,
                        onClick = { showSourceLanguagePicker = true },
                    )
                }
            }
            item {
                SectionHeader(stringResource(R.string.settings_section_service))
            }
            item {
                SettingsGroup {
                    SettingsRow(
                        title = stringResource(R.string.api_key),
                        value = if (settings.hasApiKey) {
                            "••••${settings.apiKey.takeLast(4)}"
                        } else {
                            stringResource(R.string.api_key_not_set)
                        },
                        leadingIconRes = R.drawable.ic_key,
                        onClick = { showApiKeyDialog = true },
                    )
                    RowDivider()
                    SettingsRow(
                        title = stringResource(R.string.base_url),
                        value = settings.baseUrl
                            .removePrefix("https://")
                            .removePrefix("http://"),
                        leadingIconRes = R.drawable.ic_cloud_off,
                        onClick = { showBaseUrlDialog = true },
                    )
                    RowDivider()
                    InfoRow(
                        title = stringResource(R.string.model),
                        value = settings.model,
                    )
                }
            }
            item {
                SectionHeader(stringResource(R.string.settings_section_storage))
            }
            item {
                SettingsGroup {
                    SettingsRow(
                        title = stringResource(R.string.clear_history),
                        leadingIconRes = R.drawable.ic_delete,
                        danger = true,
                        showChevron = false,
                        onClick = { showClearHistoryDialog = true },
                    )
                }
            }
            item {
                SectionHeader(stringResource(R.string.settings_section_about))
            }
            item {
                SettingsGroup {
                    InfoRow(
                        title = stringResource(R.string.version),
                        value = BuildConfig.VERSION_NAME,
                    )
                    RowDivider()
                    SettingsRow(
                        title = stringResource(R.string.source_code),
                        leadingIconRes = R.drawable.ic_open_in_new,
                        showChevron = false,
                        onClick = { openUrl(BuildConfig.SOURCE_URL) },
                    )
                    RowDivider()
                    SettingsRow(
                        title = stringResource(R.string.license),
                        leadingIconRes = R.drawable.ic_info,
                        showChevron = false,
                        onClick = { openUrl("https://www.apache.org/licenses/LICENSE-2.0") },
                    )
                    RowDivider()
                    SettingsRow(
                        title = stringResource(R.string.privacy),
                        leadingIconRes = R.drawable.ic_lock,
                        onClick = onOpenPrivacy,
                    )
                }
            }
        }
    }

    if (showLanguagePicker) {
        LanguagePickerDialog(
            current = settings.targetLanguage,
            onSelect = { language ->
                container.settings.setTargetLanguage(language)
                showLanguagePicker = false
            },
            onDismiss = { showLanguagePicker = false },
        )
    }

    if (showSourceLanguagePicker) {
        SourceLanguagePickerDialog(
            current = settings.sourceLanguage,
            onSelect = { language ->
                container.settings.setSourceLanguage(language)
                showSourceLanguagePicker = false
            },
            onDismiss = { showSourceLanguagePicker = false },
        )
    }

    if (showApiKeyDialog) {
        ApiKeyDialog(
            initialKey = settings.apiKey,
            onSave = { key ->
                container.settings.setApiKey(key)
                showApiKeyDialog = false
                scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.saved)) }
            },
            onDismiss = { showApiKeyDialog = false },
            title = stringResource(R.string.change),
        )
    }

    if (showBaseUrlDialog) {
        BaseUrlDialog(
            initialUrl = settings.baseUrl,
            onSave = { url ->
                container.settings.setBaseUrl(url)
                showBaseUrlDialog = false
                scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.saved)) }
            },
            onDismiss = { showBaseUrlDialog = false },
        )
    }

    if (showClearHistoryDialog) {
        ConfirmDialog(
            title = stringResource(R.string.clear_history_title),
            message = stringResource(R.string.clear_history_message),
            confirmLabel = stringResource(R.string.clear_history_confirm),
            onConfirm = {
                showClearHistoryDialog = false
                scope.launch {
                    container.history.clear()
                    snackbarHostState.showSnackbar(context.getString(R.string.history_cleared))
                }
            },
            onDismiss = { showClearHistoryDialog = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.privacy_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.privacy_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
