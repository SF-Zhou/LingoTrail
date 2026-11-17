package io.github.sfzhou.lingotrail.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import io.github.sfzhou.lingotrail.R
import io.github.sfzhou.lingotrail.rememberAppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Decodes a history thumbnail off the main thread and draws it with a subtle
 * placeholder until the bitmap is ready.
 */
@Composable
fun ThumbnailImage(
    path: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val container = rememberAppContainer()
    var bitmap by remember(path) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(path) {
        bitmap = withContext(Dispatchers.IO) { container.media.thumbnail(File(path)) }
    }
    val current = bitmap
    if (current != null) {
        Image(
            bitmap = current.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_image),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(0.28f),
                contentScale = ContentScale.Fit,
            )
        }
    }
}
