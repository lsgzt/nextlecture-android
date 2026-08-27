package com.gndec.timetable.ui.attendance

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gndec.timetable.net.LeaderboardResponse
import com.gndec.timetable.net.LeaderboardRow
import com.gndec.timetable.ui.motion.Motion
import com.gndec.timetable.ui.motion.itemEntrance
import com.gndec.timetable.ui.motion.motionTween
import com.gndec.timetable.ui.motion.pressFeedback
import com.gndec.timetable.ui.theme.GndecMuted
import com.gndec.timetable.ui.theme.GndecOrange
import com.gndec.timetable.ui.theme.GndecTeal
import java.util.Locale

private data class LeaderboardScopeOption(
    val key: String,
    val title: String,
    val helper: String
)

private val leaderboardScopeOptions = listOf(
    LeaderboardScopeOption("subsection", "Subsection-wise", "Your saved subsection"),
    LeaderboardScopeOption("section", "Section-wise", "Your saved section"),
    LeaderboardScopeOption("branch", "Branch-wise", "Your saved branch"),
    LeaderboardScopeOption("all", "All branches", "Everyone who has started marking")
)

@Composable
fun AttendanceLeaderboardSection(
    response: LeaderboardResponse?,
    selectedScope: String,
    loading: Boolean,
    error: String?,
    onScopeChange: (String) -> Unit,
    onRetry: () -> Unit
) {
    val selectedOption = leaderboardScopeOptions.firstOrNull { it.key == selectedScope }
        ?: leaderboardScopeOptions.first()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = motionTween<IntSize>(Motion.Emphasized))
    ) {
        AttendanceStreakCard(response)
        Spacer(Modifier.height(12.dp))
        AttendanceLeaderboardCard(
            response = response,
            selectedOption = selectedOption,
            loading = loading,
            error = error,
            onScopeChange = onScopeChange,
            onRetry = onRetry
        )
    }
}

@Composable
private fun AttendanceStreakCard(response: LeaderboardResponse?) {
    val fadeInSpec = motionTween<Float>(Motion.Normal)
    val fadeOutSpec = motionTween<Float>(Motion.Fast)
    val streak = response?.me?.currentStreak ?: 0
    val percentage = response?.me?.percentage
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .animateContentSize(animationSpec = motionTween<IntSize>(Motion.Normal)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(22.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Whatshot,
                contentDescription = null,
                tint = GndecOrange,
                modifier = Modifier.size(34.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "YOUR CURRENT STREAK",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.05.sp
                )
                AnimatedContent(
                    targetState = streak,
                    transitionSpec = { fadeIn(fadeInSpec) togetherWith fadeOut(fadeOutSpec) },
                    label = "streakCount"
                ) { currentStreak ->
                    Text(
                        if (response == null) "—" else "$currentStreak ${if (currentStreak == 1) "day" else "days"}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    when {
                        response == null -> "Loading your same-day attendance…"
                        response.me == null -> "Mark attendance on the day of the lecture to begin."
                        percentage == null -> "Mark attendance on the day of the lecture to begin."
                        else -> "${formatPercentage(percentage)}% attendance in your board"
                    },
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun AttendanceLeaderboardCard(
    response: LeaderboardResponse?,
    selectedOption: LeaderboardScopeOption,
    loading: Boolean,
    error: String?,
    onScopeChange: (String) -> Unit,
    onRetry: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val fadeInSpec = motionTween<Float>(Motion.Normal)
    val fadeOutSpec = motionTween<Float>(Motion.Fast)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .animateContentSize(animationSpec = motionTween<IntSize>(Motion.Emphasized)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.EmojiEvents,
                    contentDescription = null,
                    tint = GndecOrange,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Attendance leaderboard", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        response?.scopeLabel?.takeIf { it.isNotBlank() } ?: selectedOption.title,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            Spacer(Modifier.height(12.dp))
            Box {
                val scopePress = remember { MutableInteractionSource() }
                OutlinedButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressFeedback(scopePress, pressedScale = 0.98f),
                    interactionSource = scopePress
                ) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                        Text(selectedOption.title, fontWeight = FontWeight.Bold)
                        Text(selectedOption.helper, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    }
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Choose leaderboard scope")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    leaderboardScopeOptions.forEach { option ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(option.title, fontWeight = FontWeight.Bold)
                                    Text(option.helper, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            onClick = {
                                menuExpanded = false
                                if (option.key != selectedOption.key) onScopeChange(option.key)
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            AnimatedContent(
                targetState = when {
                    error != null -> "error"
                    loading && response == null -> "loading"
                    response?.rows.isNullOrEmpty() -> "empty"
                    else -> "rows"
                },
                transitionSpec = { fadeIn(fadeInSpec) togetherWith fadeOut(fadeOutSpec) },
                label = "leaderboardContent"
            ) { state ->
                when (state) {
                    "loading" -> LeaderboardLoadingState()
                    "error" -> LeaderboardErrorState(error.orEmpty(), onRetry)
                    "empty" -> LeaderboardEmptyState()
                    else -> LeaderboardRows(response?.rows.orEmpty(), response?.participants ?: 0)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.Info, contentDescription = null, tint = GndecMuted, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    response?.eligibility?.takeIf { it.isNotBlank() }
                        ?: "Self-reported attendance. Same-day marks only; this does not prove physical presence.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun LeaderboardRows(rows: List<LeaderboardRow>, participants: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "$participants participating ${if (participants == 1) "student" else "students"}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall
        )
        rows.take(10).forEachIndexed { index, row ->
            LeaderboardRowItem(row, Modifier.itemEntrance(index))
        }
    }
}

@Composable
private fun LeaderboardRowItem(row: LeaderboardRow, modifier: Modifier = Modifier) {
    val rankColor = when (row.rank) {
        1 -> GndecOrange
        2 -> MaterialTheme.colorScheme.onSurfaceVariant
        3 -> GndecTeal
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val rowPress = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .pressFeedback(rowPress, pressedScale = 0.99f)
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("#${row.rank}", color = rankColor, fontWeight = FontWeight.Bold, modifier = Modifier.width(34.dp))
        Column(Modifier.weight(1f)) {
            Text(row.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${row.currentStreak} day streak · ${row.markedTotal} marked",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${formatPercentage(row.percentage)}%", color = GndecTeal, fontWeight = FontWeight.Bold)
            Text("attendance", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun LeaderboardLoadingState() {
    Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalArrangement = Arrangement.Center) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun LeaderboardEmptyState() {
    Text(
        "No eligible participants yet. Mark attendance on the day of your lectures to join.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun LeaderboardErrorState(message: String, onRetry: () -> Unit) {
    Column {
        Text(message.ifBlank { "Could not load the leaderboard." }, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) { Text("Retry", color = Color.White) }
    }
}

private fun formatPercentage(value: Double): String = String.format(Locale.US, "%.1f", value)
