package com.gndec.timetable.ui.lostfound

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gndec.timetable.domain.ImageCompressor
import com.gndec.timetable.net.LostFoundClient
import com.gndec.timetable.net.LostFoundItem
import com.gndec.timetable.ui.motion.Motion
import com.gndec.timetable.ui.motion.hapticTick
import com.gndec.timetable.ui.motion.motionTween
import com.gndec.timetable.ui.motion.pressFeedback

/**
 * Full-screen "report a lost / found item" composer: photo (optional),
 * required title, optional description, required contact info and a
 * delete PIN. The photo is compressed before it ever leaves the phone — a
 * 3–8 MB camera shot becomes a ~200 KB upload.
 */
@Composable
fun LostFoundComposer(
    vm: LostFoundViewModel,
    initialKind: String,
    onClose: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var kind by remember { mutableStateOf(initialKind) }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    val posting by vm.posting.collectAsStateWithLifecycle()
    val postError by vm.postError.collectAsStateWithLifecycle()
    var inFlight by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> pickedUri = uri }

    // A tiny in-memory preview of the picked photo (never the full bitmap).
    val preview by produceState<Bitmap?>(initialValue = null, key1 = pickedUri) {
        value = pickedUri?.let { ImageCompressor.preview(context, it.toString()) }
    }

    Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            // Header
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose, enabled = !posting) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Spacer(Modifier.width(2.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (kind == LostFoundItem.KIND_LOST) "Report a lost item" else "Report a found item",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Visible to everyone on campus",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
            Spacer(Modifier.height(6.dp))

            // Lost / Found toggle
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                KindOption("Lost", selected = kind == LostFoundItem.KIND_LOST, modifier = Modifier.weight(1f)) {
                    kind = LostFoundItem.KIND_LOST
                }
                KindOption("Found", selected = kind == LostFoundItem.KIND_FOUND, modifier = Modifier.weight(1f)) {
                    kind = LostFoundItem.KIND_FOUND
                }
            }
            Spacer(Modifier.height(16.dp))

            // Photo picker
            if (pickedUri != null && preview != null) {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))) {
                    Image(
                        bitmap = preview!!.asImageBitmap(),
                        contentDescription = "Picked photo",
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Crop
                    )
                    Surface(
                        color = Color.Black.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
                    ) {
                        IconButton(onClick = { pickedUri = null }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove photo", tint = Color.White)
                        }
                    }
                }
            } else {
                val view = LocalView.current
                val pressInteraction = remember { MutableInteractionSource() }
                Card(
                    onClick = {
                        view.hapticTick()
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    modifier = Modifier.fillMaxWidth().pressFeedback(pressInteraction, pressedScale = 0.99f),
                    interactionSource = pressInteraction,
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    elevation = CardDefaults.cardElevation(0.dp)
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 26.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.AddAPhoto,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(30.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Add a photo (optional)",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Compressed automatically — uses minimal data",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = title,
                onValueChange = { title = it.take(120) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("What is it? e.g. black water bottle") },
                supportingText = { Text("${title.length}/120") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp)
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = description,
                onValueChange = { description = it.take(1000) },
                modifier = Modifier.fillMaxWidth().height(150.dp),
                placeholder = { Text("Where and when did you ${if (kind == LostFoundItem.KIND_LOST) "lose" else "find"} it? Any identifying details…") },
                shape = RoundedCornerShape(14.dp)
            )
            Spacer(Modifier.height(10.dp))

            // How the other student reaches the poster.
            OutlinedTextField(
                value = contact,
                onValueChange = { contact = it.take(120) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Your contact info — phone, email or @handle") },
                supportingText = { Text("Shown on the post so the ${if (kind == LostFoundItem.KIND_LOST) "finder" else "owner"} can reach you") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp)
            )
            Spacer(Modifier.height(10.dp))

            // The PIN that later unlocks deletion of this post.
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it.filter { c -> c.isDigit() }.take(8) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Choose a delete PIN (4–8 digits)") },
                supportingText = { Text("Save it — it's the only way to delete this post once the item is collected or returned") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                shape = RoundedCornerShape(14.dp)
            )
            Spacer(Modifier.height(10.dp))

            (localError ?: postError)?.let { message ->
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                )
                Spacer(Modifier.height(4.dp))
            }

            Button(
                onClick = {
                    val trimmedTitle = title.trim()
                    val trimmedContact = contact.trim()
                    when {
                        trimmedTitle.isEmpty() -> {
                            localError = "Give the item a short title"
                            return@Button
                        }
                        trimmedContact.isEmpty() -> {
                            localError = "Add your contact info so the other student can reach you"
                            return@Button
                        }
                        !LostFoundClient.isValidDeletePin(pin) -> {
                            localError = "The delete PIN must be 4–8 digits"
                            return@Button
                        }
                    }
                    localError = null
                    inFlight = true
                    vm.post(
                        kind = kind,
                        title = trimmedTitle,
                        description = description.trim(),
                        contact = trimmedContact,
                        pin = pin,
                        imageUri = pickedUri
                    ) {
                        inFlight = false
                        // postError null => success => close the composer.
                        if (vm.postError.value == null) onClose()
                        // On failure the composer stays open with the error shown.
                    }
                },
                enabled = !posting && title.isNotBlank() && contact.isNotBlank() && LostFoundClient.isValidDeletePin(pin),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                if (posting) {
                    CircularProgressIndicator(
                        Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Uploading…", fontWeight = FontWeight.SemiBold)
                } else {
                    Text(
                        if (kind == LostFoundItem.KIND_LOST) "Post to Lost" else "Post to Found",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Your post is public and anonymous — no name or account is attached, but the contact info you entered is shown so other students can reach you.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
            )
        }
    }
}

@Composable
private fun KindOption(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val view = LocalView.current
    val containerColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        animationSpec = motionTween(Motion.Normal),
        label = "kindOptionContainer"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = motionTween(Motion.Normal),
        label = "kindOptionBorder"
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        animationSpec = motionTween(Motion.Normal),
        label = "kindOptionContent"
    )
    val pressInteraction = remember { MutableInteractionSource() }
    Card(
        onClick = {
            view.hapticTick()
            onClick()
        },
        modifier = modifier.pressFeedback(pressInteraction, pressedScale = 0.96f),
        interactionSource = pressInteraction,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, borderColor),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Box(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                color = contentColor,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}
