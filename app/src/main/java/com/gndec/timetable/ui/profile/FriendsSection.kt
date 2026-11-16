package com.gndec.timetable.ui.profile

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gndec.timetable.domain.AppContainer
import com.gndec.timetable.domain.Friend
import com.gndec.timetable.ui.motion.Motion
import com.gndec.timetable.ui.motion.motionTween
import kotlinx.coroutines.launch

/**
 * Local-only friend list for the friends-aware timetable. Nothing here is
 * uploaded, shared or synced — it exists so the Today tab can compare the
 * student's free periods against classmates whose groups this device has already
 * parsed.
 */
@Composable
fun FriendsSection(
    container: AppContainer,
    friends: List<Friend>,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    // Friend names stay collapsed by default so the profile stays compact;
    // tapping the header reveals the list with animateContentSize.
    var listExpanded by rememberSaveable { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var group by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }

    // Group names come from the parsed document, so a typo cannot silently add a
    // friend whose timetable will never resolve.
    val knownGroups by produceState(emptyList<String>(), expanded) {
        if (!expanded) return@produceState
        value = runCatching { container.db.lectureDao().distinctGroups() }
            .getOrDefault(emptyList())
            .filter { it.isNotBlank() }
            .sorted()
    }
    val groupsReady = knownGroups.isNotEmpty()

    fun clearForm() {
        name = ""
        group = ""
    }

    /**
     * Saves the draft friend using a canonical group name from the timetable.
     * Returns true when a friend was added so the form can close/reset.
     */
    suspend fun tryAddFriend(): Boolean {
        val trimmedName = name.trim()
        val trimmedGroup = group.trim()
        if (trimmedName.isEmpty()) {
            message = "Enter your friend’s name"
            messageIsError = true
            return false
        }
        if (trimmedGroup.isEmpty()) {
            message = "Type a group, then tap a match below"
            messageIsError = true
            return false
        }
        val resolved = resolveFriendGroup(trimmedGroup, knownGroups)
        if (resolved == null) {
            message = if (!groupsReady) {
                "No timetable groups on this device yet. Refresh your timetable first."
            } else {
                "Tap a group chip below so it matches the timetable exactly"
            }
            messageIsError = true
            return false
        }
        container.settings.addFriend(Friend(name = trimmedName, group = resolved))
        message = "Added $trimmedName · $resolved"
        messageIsError = false
        clearForm()
        return true
    }

    fun onDoneClick() {
        val hasDraft = name.isNotBlank() || group.isNotBlank()
        if (!hasDraft) {
            expanded = false
            message = null
            return
        }
        scope.launch {
            // Users often hit Done instead of Add friend — commit the draft first.
            if (tryAddFriend()) {
                expanded = false
            }
            // If validation failed, stay open so they can fix the group.
        }
    }

    Card(
        modifier = modifier.fillMaxWidth().animateContentSize(motionTween(Motion.Normal)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { listExpanded = !listExpanded }
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Groups,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Friends",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (friends.isEmpty()) "Add classmates to compare free periods"
                        else if (listExpanded || expanded) "${friends.size} added · shown on your Today tab"
                        else "${friends.size} added · tap to view",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (friends.isNotEmpty()) {
                    Icon(
                        if (listExpanded || expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (listExpanded || expanded) "Collapse friends" else "Expand friends",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                }
                TextButton(
                    onClick = {
                        if (expanded) onDoneClick() else {
                            expanded = true
                            listExpanded = true
                            message = null
                        }
                    }
                ) {
                    Text(if (expanded) "Done" else "Add")
                }
            }

            if (friends.isNotEmpty() && (listExpanded || expanded)) {
                Spacer(Modifier.height(10.dp))
                friends.forEach { friend ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                friend.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                friend.group,
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (expanded) {
                            IconButton(onClick = { scope.launch { container.settings.removeFriend(friend.name) } }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Remove ${friend.name}",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            if (expanded) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Enter a name, then pick their section from the list so free periods match correctly.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; message = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Friend’s name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Next
                    )
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = group,
                    onValueChange = { typed ->
                        group = typed
                        message = null
                        // Auto-canonicalise when the user types an exact match
                        // ignoring case (e.g. "itb2" → "ITB2").
                        val exact = knownGroups.firstOrNull { it.equals(typed.trim(), ignoreCase = true) }
                        if (exact != null && exact != typed) {
                            group = exact
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Timetable group") },
                    placeholder = { Text("Type to filter, then tap a group") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { scope.launch { tryAddFriend() } }
                    ),
                    supportingText = {
                        val resolved = resolveFriendGroup(group, knownGroups)
                        when {
                            group.isBlank() -> Text("Suggestions update as you type")
                            resolved != null -> Text("Matched: $resolved")
                            groupsReady -> Text("Tap a chip below — lowercase alone is not enough")
                            else -> Text("Refresh the timetable to load section names")
                        }
                    }
                )
                val suggestions = remember(group, knownGroups) {
                    val q = group.trim()
                    knownGroups
                        .filter { q.isEmpty() || it.contains(q, ignoreCase = true) }
                        .sortedWith(
                            compareByDescending<String> { it.equals(q, ignoreCase = true) }
                                .thenByDescending { it.startsWith(q, ignoreCase = true) }
                                .thenBy { it }
                        )
                        .take(8)
                }
                if (suggestions.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Tap to select",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        suggestions.forEach { candidate ->
                            val isSelected = candidate == group
                            androidx.compose.material3.Surface(
                                onClick = {
                                    group = candidate
                                    message = null
                                },
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.primaryContainer
                                },
                                contentColor = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                },
                                shape = RoundedCornerShape(50)
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                    Text(
                                        candidate,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
                if (!groupsReady) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "No sections found yet. Open Home and refresh the timetable so group names can be suggested.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        enabled = name.isNotBlank() && group.isNotBlank(),
                        onClick = { scope.launch { tryAddFriend() } }
                    ) {
                        Text("Add friend")
                    }
                    message?.let {
                        Spacer(Modifier.width(10.dp))
                        Text(
                            it,
                            color = if (messageIsError) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "Stored only on this device. Free periods use timetables already downloaded here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

/**
 * Maps free-typed group text to a real group from the parsed timetable.
 * Prefer exact ignore-case match; if exactly one partial match exists, use it.
 */
private fun resolveFriendGroup(input: String, knownGroups: List<String>): String? {
    val q = input.trim()
    if (q.isEmpty()) return null
    if (knownGroups.isEmpty()) return null
    knownGroups.firstOrNull { it.equals(q, ignoreCase = true) }?.let { return it }
    val partial = knownGroups.filter { it.contains(q, ignoreCase = true) }
    return partial.singleOrNull()
}
