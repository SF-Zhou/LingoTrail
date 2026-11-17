package io.github.sfzhou.lingotrail.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sfzhou.lingotrail.AppContainer
import io.github.sfzhou.lingotrail.R
import io.github.sfzhou.lingotrail.data.HistoryItem
import io.github.sfzhou.lingotrail.data.ScanStatus
import io.github.sfzhou.lingotrail.ui.components.ConfirmDialog
import io.github.sfzhou.lingotrail.ui.components.ThumbnailImage
import io.github.sfzhou.lingotrail.util.ImageProcessingException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Home / gallery. Every scan is one tap away from its translated viewer, and the
 * two capture actions live in a thumb-reachable bottom bar.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenScan: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val history by container.history.items.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val badImageMessage = stringResource(R.string.error_bad_image)
    val unknownErrorMessage = stringResource(R.string.error_unknown)

    var importing by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<HistoryItem?>(null) }
    var cameraFile by remember { mutableStateOf<File?>(null) }

    fun startImport(openStream: () -> InputStream?) {
        if (importing) return
        importing = true
        scope.launch {
            try {
                val stream = withContext(Dispatchers.IO) { openStream() }
                    ?: throw ImageProcessingException("No input stream")
                val item = container.history.importImage(stream, settings.targetLanguage)
                container.pipeline.start(item.id)
                onOpenScan(item.id)
            } catch (e: ImageProcessingException) {
                snackbarHostState.showSnackbar(badImageMessage)
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(unknownErrorMessage)
            } finally {
                importing = false
            }
        }
    }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { success ->
        val file = cameraFile
        cameraFile = null
        if (file == null) return@rememberLauncherForActivityResult
        if (success) {
            startImport { FileInputStream(file) }
        } else {
            file.delete()
        }
    }

    val pickImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null) {
            startImport { context.contentResolver.openInputStream(uri) }
        }
    }

    fun launchCamera() {
        val captureDir = File(context.cacheDir, "capture").apply { mkdirs() }
        val file = File(captureDir, "capture_${System.currentTimeMillis()}.jpg")
        cameraFile = file
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        runCatching { takePictureLauncher.launch(uri) }
            .onFailure {
                cameraFile = null
                scope.launch { snackbarHostState.showSnackbar(unknownErrorMessage) }
            }
    }

    fun launchPicker() {
        pickImageLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            CaptureBar(
                importing = importing,
                onCamera = ::launchCamera,
                onImport = ::launchPicker,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            HomeHeader(
                scanCount = history.size,
                onOpenSettings = onOpenSettings,
            )
            if (history.isEmpty()) {
                EmptyState(modifier = Modifier.weight(1f))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(items = history, key = { it.id }) { item ->
                        ScanCard(
                            item = item,
                            thumbnailPath = container.history.thumbFile(item.id).absolutePath,
                            onClick = { onOpenScan(item.id) },
                            onLongClick = { pendingDelete = item },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { item ->
        ConfirmDialog(
            title = stringResource(R.string.delete_scan_title),
            message = stringResource(R.string.delete_scan_message),
            confirmLabel = stringResource(R.string.delete),
            onConfirm = {
                pendingDelete = null
                scope.launch { container.history.delete(item.id) }
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun HomeHeader(scanCount: Int, onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 24.dp, end = 12.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (scanCount > 0) {
                Text(
                    text = stringResource(R.string.photo_count, scanCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onOpenSettings) {
            Icon(
                painter = painterResource(R.drawable.ic_settings),
                contentDescription = stringResource(R.string.cd_settings),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ill_empty_state),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = 220.dp),
            contentScale = ContentScale.Fit,
        )
        Spacer(Modifier.height(28.dp))
        Text(
            text = stringResource(R.string.empty_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScanCard(
    item: HistoryItem,
    thumbnailPath: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        ) {
            ThumbnailImage(
                path = thumbnailPath,
                contentDescription = stringResource(R.string.cd_thumbnail),
                modifier = Modifier.fillMaxSize(),
            )
            if (item.status != ScanStatus.DONE) {
                StatusBadge(
                    status = item.status,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp),
                )
            }
            if (item.sourceLanguage != null) {
                Surface(
                    color = Color.Black.copy(alpha = 0.42f),
                    contentColor = Color.White,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                ) {
                    Text(
                        text = item.sourceLanguage.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: ScanStatus, modifier: Modifier = Modifier) {
    Surface(
        color = Color.Black.copy(alpha = 0.55f),
        contentColor = Color.White,
        shape = RoundedCornerShape(50),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (status == ScanStatus.PROCESSING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = Color.White,
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = stringResource(R.string.scan_processing),
                    style = MaterialTheme.typography.labelSmall,
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_error_outline),
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = stringResource(R.string.scan_failed),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun CaptureBar(importing: Boolean, onCamera: () -> Unit, onImport: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onImport,
                enabled = !importing,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_photo_library),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.import_photo))
            }
            Button(
                onClick = onCamera,
                enabled = !importing,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .weight(1.2f)
                    .height(56.dp),
            ) {
                if (importing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_camera),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.take_photo))
            }
        }
    }
}
