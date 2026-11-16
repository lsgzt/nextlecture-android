package com.gndec.timetable.ui.lostfound

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.gndec.timetable.domain.AppContainer
import com.gndec.timetable.domain.LostFoundImageStore
import com.gndec.timetable.domain.LostFoundManager
import com.gndec.timetable.domain.LostFoundState
import com.gndec.timetable.net.LostFoundClient
import com.gndec.timetable.net.LostFoundItem
import com.gndec.timetable.ui.motion.Motion
import com.gndec.timetable.ui.motion.hapticTick
import com.gndec.timetable.ui.motion.itemEntrance
import com.gndec.timetable.ui.motion.motionTween
import com.gndec.timetable.ui.motion.pressFeedback
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Bridges the manager into Compose; tab/composer state lives in the screen. */
class LostFoundViewModel(private val container: AppContainer) : ViewModel() {
    val state: StateFlow<LostFoundState> = container.lostFoundManager.state
    val posting: StateFlow<Boolean> = container.lostFoundManager.posting
    val postError: StateFlow<String?> = container.lostFoundManager.lastPostError

    fun open() {
        viewModelScope.launch { container.lostFoundManager.ensureLoaded() }
    }

    fun forceRefresh() {
        viewModelScope.launch { container.lostFoundManager.refresh(force = true) }
    }

    /** Runs the post and always calls [onDone] — check [postError] afterwards. */
    fun post(
        kind: String,
        title: String,
        description: String,
        contact: String,
        pin: String,
        imageUri: Uri?,
        onDone: () -> Unit
    ) {
        viewModelScope.launch {
            runCatching {
                container.lostFoundManager.post(
                    kind = kind,
                    title = title,
                    description = description,
                    contact = contact,
                    pin = pin,
                    imageUri = imageUri?.toString()
                )
            }
            onDone()
        }
    }

    /** Deletes with the poster's PIN; [onDone] receives whether it worked. */
    fun delete(item: LostFoundItem, pin: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = runCatching { container.lostFoundManager.delete(item, pin) }.getOrDefault(false)
            onDone(ok)
        }
    }
}

/**
 * Campus Lost & Found noticeboard: one tab for things lost, one for things
 * found. Anyone can post a photo with a title and description; images are
 * compressed before upload and lists only ever load thumbnails.
 */
@Composable
fun LostFoundScreen(
    container: AppContainer,
    onBack: () -> Unit
) {
    val vm = remember { LostFoundViewModel(container) }
    LaunchedEffect(Unit) { vm.open() }
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(LostFoundItem.KIND_LOST) }
    var composing by rememberSaveable { mutableStateOf(false) }
    var detail by remember { mutableStateOf<LostFoundItem?>(null) }
    var deleteTarget by remember { mutableStateOf<LostFoundItem?>(null) }
    val view = LocalView.current

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (composing) {
            LostFoundComposer(
                vm = vm,
                initialKind = tab,
                onClose = { composing = false }
            )
        } else {
            when (val current = state) {
                is LostFoundState.Loading -> LostFoundLoading()
                is LostFoundState.Error -> LostFoundLoadError(message = current.message, onRetry = vm::forceRefresh)
                is LostFoundState.Ready -> {
                    val forTab = current.items.filter { it.kind == tab }
                    Box(Modifier.fillMaxSize()) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
                            contentPadding = PaddingValues(bottom = 110.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            item(key = "header") {
                                LostFoundHeader(
                                    refreshing = current.refreshing,
                                    onBack = onBack,
                                    onRefresh = vm::forceRefresh
                                )
                            }
                            item(key = "tabs") {
                                LostFoundTabSelector(tab = tab, onSelect = { tab = it })
                            }
                            if (current.error != null) {
                                item(key = "error") {
                                    LostFoundErrorBanner(
                                        text = current.error!!,
                                        modifier = Modifier.padding(horizontal = 20.dp)
                                    )
                                }
                            }
                            if (forTab.isEmpty()) {
                                item(key = "empty") {
                                    LostFoundEmpty(tab = tab)
                                }
                            } else {
                                items(forTab, key = { it.id }) { item ->
                                    LostFoundCard(
                                        item = item,
                                        store = container.lostFoundImages,
                                        onClick = { detail = item },
                                        onDelete = {
                                            view.hapticTick()
                                            deleteTarget = item
                                        },
                                        modifier = Modifier.padding(horizontal = 20.dp).itemEntrance(2)
                                    )
                                }
                            }
                            item(key = "footer") {
                                Text(
                                    "Posts are public — anyone on campus can see them, including the contact details. Keep the delete PIN you set when posting: it's the only way to take the post down once the item is collected or returned.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp, start = 20.dp, end = 20.dp)
                                )
                            }
                        }
                        ExtendedFloatingActionButton(
                            onClick = {
                                view.hapticTick()
                                composing = true
                            },
                            icon = { Icon(Icons.Default.Add, contentDescription = null) },
                            text = { Text(if (tab == LostFoundItem.KIND_LOST) "Report lost item" else "Report found item") },
                            shape = RoundedCornerShape(18.dp),
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 20.dp, bottom = 24.dp)
                        )
                    }
                }
            }
        }
    }

    detail?.let { item ->
        LostFoundDetailDialog(
            item = item,
            store = container.lostFoundImages,
            onDismiss = { detail = null },
            onDelete = {
                detail = null
                deleteTarget = item
            }
        )
    }

    deleteTarget?.let { target ->
        LostFoundDeleteDialog(
            item = target,
            vm = vm,
            onDismiss = { deleteTarget = null },
            onDeleted = {
                deleteTarget = null
                if (detail?.id == target.id) detail = null
            }
        )
    }
}

@Composable
private fun LostFoundHeader(refreshing: Boolean, onBack: () -> Unit, onRefresh: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Spacer(Modifier.width(2.dp))
        Column(Modifier.weight(1f)) {
            Text("Lost & Found", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Campus noticeboard — post it, find it",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge
            )
        }
        if (refreshing) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
        } else {
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun LostFoundTabSelector(tab: String, onSelect: (String) -> Unit) {
    val view = LocalView.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        listOf(
            LostFoundItem.KIND_LOST to "Lost",
            LostFoundItem.KIND_FOUND to "Found"
        ).forEach { (kind, label) ->
            val active = kind == tab
            val containerColor by animateColorAsState(
                targetValue = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                animationSpec = motionTween(Motion.Normal),
                label = "lostFoundTabContainer"
            )
            val borderColor by animateColorAsState(
                targetValue = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                animationSpec = motionTween(Motion.Normal),
                label = "lostFoundTabBorder"
            )
            val contentColor by animateColorAsState(
                targetValue = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                animationSpec = motionTween(Motion.Normal),
                label = "lostFoundTabContent"
            )
            val pressInteraction = remember { MutableInteractionSource() }
            Card(
                onClick = {
                    if (!active) view.hapticTick()
                    onSelect(kind)
                },
                modifier = Modifier.weight(1f).pressFeedback(pressInteraction, pressedScale = 0.96f),
                interactionSource = pressInteraction,
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = containerColor),
                border = BorderStroke(1.dp, borderColor),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Inventory2,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        label,
                        color = contentColor,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun LostFoundCard(
    item: LostFoundItem,
    store: LostFoundImageStore,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val pressInteraction = remember { MutableInteractionSource() }
    Card(
        onClick = {
            view.hapticTick()
            onClick()
        },
        modifier = modifier.fillMaxWidth().pressFeedback(pressInteraction, pressedScale = 0.98f),
        interactionSource = pressInteraction,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.fillMaxWidth()) {
            if (item.image_path != null) {
                val bitmap by produceState<Bitmap?>(initialValue = null, key1 = item.image_path) {
                    value = store.loadThumb(item.image_path)
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.45f)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap!!.asImageBitmap(),
                            contentDescription = item.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            Icons.Default.Inventory2,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }
            }
            Column(Modifier.padding(horizontal = 15.dp, vertical = 13.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        color = if (item.kind == LostFoundItem.KIND_FOUND)
                            MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = if (item.kind == LostFoundItem.KIND_FOUND)
                            MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onTertiaryContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            item.kind.uppercase(),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete post",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
                if (item.description.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        item.description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (item.contact_info.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Call,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            item.contact_info,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    LostFoundManager.relativeTime(item.created_at),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun LostFoundEmpty(tab: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.SearchOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(42.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            if (tab == LostFoundItem.KIND_LOST) "Nothing reported lost yet"
            else "Nothing reported found yet",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (tab == LostFoundItem.KIND_LOST) "Be the first to post a lost item"
            else "Found something? Post it so its owner can find it",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun LostFoundErrorBanner(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Refresh,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(9.dp))
        Text(text, color = MaterialTheme.colorScheme.onTertiaryContainer, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LostFoundLoading() {
    Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text("Loading Lost & Found…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LostFoundLoadError(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().statusBarsPadding().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Inventory2,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(42.dp)
            )
            Spacer(Modifier.height(14.dp))
            Text("Couldn’t load Lost & Found", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onRetry,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Try again")
            }
        }
    }
}

/**
 * Asks for the delete PIN the poster chose when creating this post — the
 * only key that unlocks deletion ("item collected", "founder returned it").
 * A wrong PIN only gets a retry hint; a matching one removes the post and
 * closes whatever view was showing it.
 */
@Composable
private fun LostFoundDeleteDialog(
    item: LostFoundItem,
    vm: LostFoundViewModel,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        confirmButton = {
            TextButton(
                enabled = !busy && LostFoundClient.isValidDeletePin(pin),
                onClick = {
                    busy = true
                    error = null
                    vm.delete(item, pin) { ok ->
                        busy = false
                        if (ok) {
                            onDeleted()
                        } else {
                            error = "That PIN doesn’t match this post. Check the PIN you set when posting it."
                        }
                    }
                }
            ) {
                Text("Delete", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Text("Cancel")
            }
        },
        title = { Text("Delete this post?", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    if (item.kind == LostFoundItem.KIND_LOST)
                        "Got the item back? Enter the delete PIN you set when posting “${item.title}”. This removes the post and its photo for good."
                    else
                        "Returned it to its owner? Enter the delete PIN you set when posting “${item.title}”. This removes the post and its photo for good.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter { c -> c.isDigit() }.take(8) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Delete PIN") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    shape = RoundedCornerShape(14.dp)
                )
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        error ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (busy) {
                    Spacer(Modifier.height(10.dp))
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    )
}
