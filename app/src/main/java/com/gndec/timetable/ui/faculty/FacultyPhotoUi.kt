package com.gndec.timetable.ui.faculty

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gndec.timetable.domain.FacultyPhotoStore

/**
 * Circular faculty photo the size of the old initials avatar. Shows the
 * two-letter initials while the photo streams in (or forever, for the two
 * teachers whose website page carries no picture) — so rows never jump in
 * height and the fallback is already in place.
 *
 * [onPhotoClick] is invoked only when there IS a photo to open; the initials
 * fallback is not clickable, so the row's own click still opens the detail
 * page for teachers without one.
 */
@Composable
fun FacultyAvatar(
    store: FacultyPhotoStore,
    url: String,
    name: String,
    size: Dp,
    modifier: Modifier = Modifier,
    onPhotoClick: (() -> Unit)? = null
) {
    var photo by remember(url) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(url) { mutableStateOf(url.isNotBlank()) }
    LaunchedEffect(url) {
        if (url.isBlank()) return@LaunchedEffect
        loading = true
        photo = store.load(url, FacultyPhotoStore.THUMB_MAX_DIM)
        loading = false
    }

    val base = modifier.size(size).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape)
    val loaded = photo
    if (loaded != null) {
        Box(
            base.clip(CircleShape).let { m ->
                if (onPhotoClick != null) m.clickable(onClick = onPhotoClick) else m
            },
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = loaded.asImageBitmap(),
                contentDescription = "${name.trim()} photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    } else {
        Box(base, contentAlignment = Alignment.Center) {
            if (loading) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(size / 3))
            } else {
                Text(
                    facultyInitials(name),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * Two-letter avatar initials: the first letters of the first two non-honorific
 * name words ("DR. HARPREET KAUR GREWAL" → "HK").
 */
fun facultyInitials(name: String): String {
    val words = name.split(Regex("[^A-Za-z]+"))
        .filter { it.isNotBlank() }
        .map { it.trim('.', ',') }
        .filter { !it.lowercase().matches(Regex("dr|er|prof|professor|pf|ms|mrs|mr|sgs|sggs|ar")) }
    val letters = words.mapNotNull { it.firstOrNull()?.uppercase() }
    val first = letters.getOrNull(0) ?: "?"
    val second = letters.getOrNull(1) ?: ""
    return first + second
}

/**
 * Fullscreen, pinch-zoomable faculty photo.
 *
 * Pinch scales between 1× and 8×; one finger pans while zoomed; double-tap
 * toggles between 1× and 2.5×; a single tap on the photo (only while at 1×,
 * so inspecting a zoomed photo is never dismissed by accident), a tap on the
 * dark scrim, or the Close button dismisses. While the full-resolution decode
 * streams in, a spinner shows; a photo that cannot be loaded gets an honest
 * "Photo not available".
 *
 * Layering note: the gesture area is composed FIRST so the name and the Close
 * button draw (and receive taps) ON TOP of it — a fullscreen gesture box
 * composed last would swallow every tap and kill the button.
 */
@Composable
fun FacultyPhotoViewer(
    store: FacultyPhotoStore,
    url: String,
    name: String,
    onDismiss: () -> Unit
) {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url) {
        if (url.isBlank()) {
            failed = true
            return@LaunchedEffect
        }
        failed = false
        bitmap = store.load(url, FacultyPhotoStore.MAX_DETAIL_DIM)?.asImageBitmap()
        if (bitmap == null) failed = true
    }

    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
        ) {
            // Gesture + photo area FIRST — underneath the header controls.
            val photo = bitmap
            when {
                photo != null -> Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 8f)
                                offset = if (scale > 1f) offset + pan else Offset.Zero
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                // At 1× a single tap closes the viewer; zoomed
                                // in, a stray tap must not throw the photo away.
                                onTap = { if (scale <= 1.01f) onDismiss() },
                                onDoubleTap = {
                                    if (scale > 1.05f) {
                                        scale = 1f
                                        offset = Offset.Zero
                                    } else {
                                        scale = 2.5f
                                    }
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    // Raw gesture values inside the layer block: the photo tracks
                    // fingers exactly, and only the layer is invalidated.
                    Image(
                        bitmap = photo,
                        contentDescription = "${name.trim()} photo, pinch to zoom",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            }
                    )
                }
                failed -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Photo not available", color = Color.White.copy(alpha = 0.7f))
                    Text(
                        "This teacher's picture could not be loaded.",
                        color = Color.White.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                else -> CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center).size(34.dp)
                )
            }

            // Header controls LAST — they draw (and take taps) above the
            // fullscreen gesture area, so Close actually works.
            Text(
                name,
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(top = 46.dp, start = 64.dp, end = 64.dp)
            )
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 28.dp, end = 14.dp)
            ) {
                Text("Close", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}
