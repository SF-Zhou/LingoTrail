package io.github.sfzhou.lingotrail.ui.viewer

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sfzhou.lingotrail.AppContainer
import io.github.sfzhou.lingotrail.R
import io.github.sfzhou.lingotrail.data.HistoryItem
import io.github.sfzhou.lingotrail.data.ScanStatus
import io.github.sfzhou.lingotrail.net.ApiErrorKind
import io.github.sfzhou.lingotrail.overlay.PhotoOverlayRenderer
import io.github.sfzhou.lingotrail.overlay.PreparedOverlay
import io.github.sfzhou.lingotrail.ui.components.ConfirmDialog
import io.github.sfzhou.lingotrail.ui.theme.ViewerBackground
import io.github.sfzhou.lingotrail.ui.userMessage
import io.github.sfzhou.lingotrail.util.ShareUtils
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

private const val MAX_ZOOM = 6f
private const val DOUBLE_TAP_ZOOM = 2.6f

/**
 * Full-screen viewer: a photo-browser style surface with pinch-zoom, panning and
 * double-tap zoom. The translated text is drawn on top of the photo in the same
 * coordinate space and can be hidden with one thumb-reachable button.
 */
@Composable
fun ViewerScreen(
    container: AppContainer,
    scanId: String,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val items by container.history.items.collectAsStateWithLifecycle()
    val item = remember(items, scanId) { items.firstOrNull { it.id == scanId } }

    if (item == null) {
        LaunchedEffect(scanId) { onBack() }
        return
    }

    var photo by remember(item.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.id) {
        photo = container.media.photo(item, container.history.imageFile(item.id))
    }
    var overlay by remember(item.id) { mutableStateOf<PreparedOverlay?>(null) }
    LaunchedEffect(item.id, item.blocks) {
        overlay = container.media.preparedOverlay(item, container.history.imageFile(item.id))
    }

    var showOverlay by rememberSaveable(item.id) { mutableStateOf(true) }
    var chromeVisible by rememberSaveable(item.id) { mutableStateOf(true) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val shareFailedMessage = stringResource(R.string.share_failed)

    LaunchedEffect(item.id, item.status) {
        if (item.status == ScanStatus.PROCESSING) container.pipeline.start(item.id)
    }

    fun shareScan() {
        if (sharing) return
        sharing = true
        scope.launch {
            val intent = ShareUtils.buildShareIntent(
                context = context,
                item = item,
                overlay = overlay,
                photoFile = container.history.imageFile(item.id),
            )
            sharing = false
            if (intent != null) {
                runCatching {
                    context.startActivity(
                        Intent.createChooser(intent, context.getString(R.string.share_sheet_title)),
                    )
                }
            } else {
                snackbarHostState.showSnackbar(shareFailedMessage)
            }
        }
    }

    val overlayVisible = showOverlay && item.status == ScanStatus.DONE && overlay != null

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ViewerBackground)
            .clipToBounds(),
    ) {
        val bitmap = photo
        if (bitmap != null) {
            ZoomablePhoto(
                bitmap = bitmap,
                overlay = if (item.status == ScanStatus.DONE) overlay else null,
                showOverlay = overlayVisible,
                modifier = Modifier.fillMaxSize(),
                onTap = { chromeVisible = !chromeVisible },
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White.copy(alpha = 0.7f),
            )
        }

        // Top chrome: back, language pair, overflow actions.
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(180)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ViewerTopBar(
                item = item,
                onBack = onBack,
                onRetranslate = { container.pipeline.start(item.id) },
                onDelete = { showDeleteDialog = true },
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f)),
                    ),
                )
                .navigationBarsPadding()
                .padding(top = 40.dp, bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ViewerStateCard(
                item = item,
                onRetry = { container.pipeline.start(item.id) },
                onOpenSettings = onOpenSettings,
            )

            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn(tween(180)),
                exit = fadeOut(tween(180)),
            ) {
                Row(
                    modifier = Modifier.padding(top = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(36.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ViewerActionButton(
                        iconRes = if (showOverlay) R.drawable.ic_visibility_off else R.drawable.ic_visibility,
                        label = stringResource(
                            if (showOverlay) R.string.hide_translation else R.string.show_translation,
                        ),
                        enabled = item.hasOverlay,
                        active = true,
                        onClick = { showOverlay = !showOverlay },
                    )
                    ViewerActionButton(
                        iconRes = R.drawable.ic_share,
                        label = stringResource(R.string.share),
                        enabled = !sharing && item.status == ScanStatus.DONE,
                        active = false,
                        onClick = ::shareScan,
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 200.dp),
        )
    }

    if (showDeleteDialog) {
        ConfirmDialog(
            title = stringResource(R.string.delete_scan_title),
            message = stringResource(R.string.delete_scan_message),
            confirmLabel = stringResource(R.string.delete),
            onConfirm = {
                showDeleteDialog = false
                scope.launch {
                    container.history.delete(item.id)
                    onBack()
                }
            },
            onDismiss = { showDeleteDialog = false },
        )
    }
}

@Composable
private fun ViewerTopBar(
    item: HistoryItem,
    onBack: () -> Unit,
    onRetranslate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Black.copy(alpha = 0.70f), Color.Transparent),
                ),
            )
            .statusBarsPadding()
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.cd_back),
                    tint = Color.White,
                )
            }
            Spacer(Modifier.weight(1f))
            Surface(
                shape = RoundedCornerShape(50),
                color = Color.Black.copy(alpha = 0.45f),
                contentColor = Color.White,
            ) {
                Text(
                    text = stringResource(
                        R.string.viewer_language_pair,
                        item.sourceLanguage ?: stringResource(R.string.viewer_source_auto),
                        item.targetLanguage.displayName(),
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_more_vert),
                        contentDescription = stringResource(R.string.cd_more),
                        tint = Color.White,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.retranslate)) },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_refresh),
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onRetranslate()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.delete),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_delete),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ViewerStateCard(
    item: HistoryItem,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    when (item.status) {
        ScanStatus.PROCESSING -> InfoCard(
            title = stringResource(R.string.processing_title),
            body = stringResource(R.string.processing_hint),
            loading = true,
        )

        ScanStatus.FAILED -> InfoCard(
            title = stringResource(R.string.scan_failed_title),
            body = item.errorKind?.userMessage() ?: stringResource(R.string.error_unknown),
            action = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) { Text(stringResource(R.string.retry)) }
                    if (item.errorKind == ApiErrorKind.AUTH) {
                        TextButton(onClick = onOpenSettings) {
                            Text(
                                text = stringResource(R.string.open_settings),
                                color = Color.White,
                            )
                        }
                    }
                }
            },
        )

        ScanStatus.DONE -> {
            if (!item.hasOverlay) {
                InfoCard(
                    title = stringResource(R.string.no_text_title),
                    body = stringResource(R.string.no_text_body),
                    action = {
                        Button(
                            onClick = onRetry,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        ) { Text(stringResource(R.string.retranslate)) }
                    },
                )
            }
        }
    }
}

@Composable
private fun InfoCard(
    title: String,
    body: String,
    loading: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = Color(0xFF1B2622).copy(alpha = 0.96f),
        contentColor = Color.White,
        modifier = Modifier
            .padding(horizontal = 28.dp)
            .fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(12.dp))
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_error_outline),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Text(text = title, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.78f),
            )
            if (action != null) {
                Spacer(Modifier.height(12.dp))
                action()
            }
        }
    }
}

@Composable
private fun ViewerActionButton(
    iconRes: Int,
    label: String,
    enabled: Boolean,
    active: Boolean,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(56.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (active) Color.White else Color.Black.copy(alpha = 0.5f),
                contentColor = if (active) Color(0xFF10201B) else Color.White,
                disabledContainerColor = Color.Black.copy(alpha = 0.28f),
                disabledContentColor = Color.White.copy(alpha = 0.35f),
            ),
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = label,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) Color.White.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.4f),
            maxLines = 1,
        )
    }
}

/**
 * Photo surface with photo-browser gestures:
 *  - pinch to zoom around the gesture centroid (1×–6×)
 *  - one-finger pan while zoomed, clamped to the photo edges
 *  - double-tap to zoom in at the tapped point, double-tap again to fit
 *  - single tap toggles the UI chrome
 */
@Composable
private fun ZoomablePhoto(
    bitmap: Bitmap,
    overlay: PreparedOverlay?,
    showOverlay: Boolean,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
) {
    var zoom by remember(bitmap) { mutableFloatStateOf(1f) }
    var pan by remember(bitmap) { mutableStateOf(Offset.Zero) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    val scope = rememberCoroutineScope()
    val image = remember(bitmap) { bitmap.asImageBitmap() }

    val virtualWidth = PreparedOverlay.VIRTUAL_WIDTH
    val virtualHeight = virtualWidth * bitmap.height / bitmap.width.coerceAtLeast(1)

    fun clampPan(targetZoom: Float, targetPan: Offset, viewport: IntSize): Offset {
        if (viewport.width == 0 || viewport.height == 0) return Offset.Zero
        val fit = min(viewport.width / virtualWidth, viewport.height / virtualHeight)
        val maxX = max(0f, (virtualWidth * fit * targetZoom - viewport.width) / 2f)
        val maxY = max(0f, (virtualHeight * fit * targetZoom - viewport.height) / 2f)
        return Offset(
            x = targetPan.x.coerceIn(-maxX, maxX),
            y = targetPan.y.coerceIn(-maxY, maxY),
        )
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { viewportSize = it },
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(bitmap) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onDoubleTap = { tap ->
                            val viewport = viewportSize
                            if (viewport.width == 0) return@detectTapGestures
                            val startZoom = zoom
                            val startPan = pan
                            val targetZoom = if (startZoom > 1.2f) 1f else DOUBLE_TAP_ZOOM
                            val center = Offset(viewport.width / 2f, viewport.height / 2f)
                            val focus = if (targetZoom == 1f) {
                                Offset.Zero
                            } else {
                                startPan + (tap - center) * (1f - targetZoom / startZoom)
                            }
                            val targetPan = clampPan(targetZoom, focus, viewport)
                            scope.launch {
                                animate(
                                    initialValue = 0f,
                                    targetValue = 1f,
                                    animationSpec = tween(durationMillis = 240),
                                ) { fraction, _ ->
                                    zoom = startZoom + (targetZoom - startZoom) * fraction
                                    pan = clampPan(
                                        zoom,
                                        lerp(startPan, targetPan, fraction),
                                        viewport,
                                    )
                                }
                            }
                        },
                    )
                }
                .pointerInput(bitmap, viewportSize) {
                    detectTransformGestures(panZoomLock = true) { centroid, panChange, zoomChange, _ ->
                        val viewport = viewportSize
                        if (viewport.width == 0) return@detectTransformGestures
                        val oldZoom = zoom
                        val newZoom = (oldZoom * zoomChange).coerceIn(1f, MAX_ZOOM)
                        val center = Offset(viewport.width / 2f, viewport.height / 2f)
                        val focused = pan + panChange + (centroid - center) * (1f - newZoom / oldZoom)
                        zoom = newZoom
                        pan = clampPan(newZoom, focused, viewport)
                    }
                },
        ) {
            val fit = min(size.width / virtualWidth, size.height / virtualHeight)
            val scale = fit * zoom
            val left = size.width / 2f - virtualWidth * scale / 2f + pan.x
            val top = size.height / 2f - virtualHeight * scale / 2f + pan.y
            withTransform({
                translate(left = left, top = top)
                scale(scaleX = scale, scaleY = scale, pivot = Offset.Zero)
            }) {
                drawImage(
                    image = image,
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(
                        ceil(virtualWidth).toInt(),
                        ceil(virtualHeight).toInt(),
                    ),
                    filterQuality = FilterQuality.Medium,
                )
                if (showOverlay && overlay != null) {
                    drawIntoCanvas { canvas ->
                        PhotoOverlayRenderer.draw(canvas.nativeCanvas, overlay)
                    }
                }
            }
        }
    }
}
