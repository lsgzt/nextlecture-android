package com.gndec.timetable.ui.more

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gndec.timetable.ui.motion.hapticTick
import com.gndec.timetable.ui.motion.itemEntrance
import com.gndec.timetable.ui.motion.pressFeedback
import com.gndec.timetable.ui.premiumAquaBrush
import androidx.compose.foundation.interaction.MutableInteractionSource

/**
 * "More" tab: the app's overflow home for features that don't belong on the
 * main bar. Each card opens one feature — Downloads (college forms), Lost &
 * Found (campus noticeboard) and Faculty Birthday lead; handy existing
 * screens follow.
 */
@Composable
fun MoreScreen(
    onOpenDownloads: () -> Unit,
    onOpenLostFound: () -> Unit,
    onOpenFacultyBirthdays: () -> Unit,
    onOpenVacantRooms: () -> Unit,
    onOpenFacultyMembers: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val view = LocalView.current
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "header") {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Text("More", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Everything else, one tap away",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
        item(key = "downloads") {
            FeatureCard(
                title = "Downloads",
                subtitle = "Bus pass, vehicle pass, ID card, bonafide — every official form from gndec.ac.in",
                icon = Icons.Default.FileDownload,
                badge = "SORTED FOR STUDENTS",
                onClick = { view.hapticTick(); onOpenDownloads() },
                modifier = Modifier.padding(horizontal = 20.dp).itemEntrance(1)
            )
        }
        item(key = "lostfound") {
            FeatureCard(
                title = "Lost & Found",
                subtitle = "Lost something on campus? Post it. Found something? Help it find its owner.",
                icon = Icons.Default.Inventory2,
                badge = "COMMUNITY",
                onClick = { view.hapticTick(); onOpenLostFound() },
                modifier = Modifier.padding(horizontal = 20.dp).itemEntrance(2)
            )
        }
        item(key = "faculty-birthdays") {
            FeatureCard(
                title = "Faculty Birthday",
                subtitle = "Whose special day is next? Every teacher's birthday, sorted by date — go wish them well.",
                icon = Icons.Default.Cake,
                badge = "WISH THEM WELL",
                onClick = { view.hapticTick(); onOpenFacultyBirthdays() },
                modifier = Modifier.padding(horizontal = 20.dp).itemEntrance(3)
            )
        }
        item(key = "label-also") {
            MoreSectionLabel("ALSO IN THE APP")
        }
        item(key = "vacant") {
            CompactCard(
                title = "Find vacant rooms",
                subtitle = "Free rooms right now, live from department timetables",
                icon = Icons.Default.MeetingRoom,
                onClick = { view.hapticTick(); onOpenVacantRooms() },
                modifier = Modifier.padding(horizontal = 20.dp).itemEntrance(4)
            )
        }
        item(key = "faculty") {
            CompactCard(
                title = "Faculty members",
                subtitle = "Every teacher, department and timetable",
                icon = Icons.Default.Groups,
                onClick = { view.hapticTick(); onOpenFacultyMembers() },
                modifier = Modifier.padding(horizontal = 20.dp).itemEntrance(5)
            )
        }
        item(key = "settings") {
            CompactCard(
                title = "Settings",
                subtitle = "Profile, theme, reminders and more",
                icon = Icons.Default.Settings,
                onClick = { view.hapticTick(); onOpenSettings() },
                modifier = Modifier.padding(horizontal = 20.dp).itemEntrance(6)
            )
        }
    }
}

@Composable
private fun FeatureCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    badge: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pressInteraction = remember { MutableInteractionSource() }
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().pressFeedback(pressInteraction, pressedScale = 0.97f),
        interactionSource = pressInteraction,
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.fillMaxWidth().background(premiumAquaBrush()).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(23.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    contentColor = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        badge,
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(3.dp))
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun CompactCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pressInteraction = remember { MutableInteractionSource() }
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().pressFeedback(pressInteraction, pressedScale = 0.97f),
        interactionSource = pressInteraction,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.11f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun MoreSectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
    )
}
