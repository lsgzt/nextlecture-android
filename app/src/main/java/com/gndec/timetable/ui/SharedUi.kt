package com.gndec.timetable.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gndec.timetable.data.db.LectureEntity
import com.gndec.timetable.ui.theme.GndecAqua
import com.gndec.timetable.ui.theme.GndecAquaStrong
import com.gndec.timetable.ui.theme.GndecGreen
import com.gndec.timetable.ui.theme.GndecGreenSoft
import com.gndec.timetable.ui.theme.GndecInk
import com.gndec.timetable.ui.theme.GndecLine
import com.gndec.timetable.ui.theme.GndecMuted
import com.gndec.timetable.ui.theme.GndecOrange
import com.gndec.timetable.ui.theme.GndecOrangeSoft
import com.gndec.timetable.ui.theme.GndecTeal
import com.gndec.timetable.ui.theme.GndecTealDark
import com.gndec.timetable.util.Formatters

@Composable
fun ScreenSurface(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.TopStart) {
        content()
    }
}

@Composable
fun IconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    containerColor: Color? = null,
    tint: Color? = null,
    size: androidx.compose.ui.unit.Dp = 44.dp
) {
    val badgeColor = containerColor ?: MaterialTheme.colorScheme.secondaryContainer
    val iconColor = tint ?: MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier.size(size).background(badgeColor, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(size * 0.52f))
    }
}

@Composable
fun Header(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
    onProfile: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(42.dp)) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onBackground)
            }
            Spacer(Modifier.width(6.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground) }
        }
        if (onProfile != null) {
            IconButton(
                onClick = onProfile,
                modifier = Modifier.size(44.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
            ) {
                Icon(Icons.Default.Person, contentDescription = "Profile", tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(23.dp))
            }
            Spacer(Modifier.width(6.dp))
        }
        if (onSettings != null) {
            IconButton(
                onClick = onSettings,
                modifier = Modifier.size(44.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
            ) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(23.dp))
            }
        }
    }
}

private val BottomNavItems = listOf(
    Triple("home", "Home", Icons.Default.Home),
    Triple("today", "Today", Icons.Default.CalendarMonth),
    Triple("alerts", "Alerts", Icons.Default.Notifications)
)

@Composable
fun BottomBar(current: String, onNavigate: (String) -> Unit) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        modifier = Modifier.height(80.dp)
    ) {
        BottomNavItems.forEach { (route, label, icon) ->
            NavigationBarItem(
                selected = current == route,
                onClick = { onNavigate(route) },
                icon = { Icon(icon, contentDescription = label, modifier = Modifier.size(25.dp)) },
                label = { Text(label, fontSize = 14.sp) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurface,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    }
}

@Composable
fun TealOutlineButton(
    text: String,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = 50.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.5.dp, if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.primary
        )
    ) {
        icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(22.dp)) }
        if (icon != null) Spacer(Modifier.width(10.dp))
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun StatusPill(text: String, tone: PillTone = PillTone.TEAL, modifier: Modifier = Modifier) {
    val (bg, fg) = when (tone) {
        PillTone.TEAL -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        PillTone.ORANGE -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        PillTone.GREEN -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        PillTone.GRAY -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = bg, shape = RoundedCornerShape(50), modifier = modifier) {
        Text(text, color = fg, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

enum class PillTone { TEAL, ORANGE, GREEN, GRAY }

@Composable
fun InfoRow(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    iconContainer: Color? = null,
    divider: Boolean = false
) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, containerColor = iconContainer, size = 42.dp)
            Spacer(Modifier.width(14.dp))
            Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        }
        if (divider) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 56.dp))
    }
}

@Composable
fun NextLectureCard(
    lecture: LectureEntity,
    countdown: String,
    happening: Boolean,
    onOpen: () -> Unit
) {
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Default.CalendarMonth, containerColor = MaterialTheme.colorScheme.primary, tint = MaterialTheme.colorScheme.onPrimary, size = 46.dp)
                Spacer(Modifier.width(14.dp))
                Text(if (happening) "HAPPENING NOW" else "NEXT LECTURE", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
            }
            Spacer(Modifier.height(14.dp))
            Text(lecture.subject ?: "Lecture", style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text(if (happening) "ENDS IN $countdown" else "STARTS IN $countdown", color = if (happening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            InfoRow(Icons.Default.AccessTime, Formatters.range(lecture.startMinutes, lecture.endMinutes))
            lecture.venue?.takeIf { it.isNotBlank() }?.let { InfoRow(Icons.Default.LocationOn, it) }
            InfoRow(Icons.Default.Person, lecture.teacher?.takeIf { it.isNotBlank() } ?: "Teacher unavailable", iconContainer = MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}

@Composable
fun CompactUpcomingCard(lecture: LectureEntity, onOpen: () -> Unit) {
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Default.AccessTime, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(Formatters.hm(lecture.startMinutes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(3.dp))
                Text(lecture.subject ?: "Lecture", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                lecture.venue?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = GndecMuted) }
            }
            Icon(Icons.Default.Notifications, contentDescription = "Reminder", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
fun TimelineMarker(state: String, modifier: Modifier = Modifier) {
    val (color, bg) = when (state) {
        "Happening now" -> MaterialTheme.colorScheme.tertiary to MaterialTheme.colorScheme.tertiaryContainer
        "Completed" -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.secondaryContainer
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.width(3.dp).height(18.dp).background(color))
        Box(Modifier.size(30.dp).background(bg, CircleShape), contentAlignment = Alignment.Center) {
            Icon(if (state == "Completed") Icons.Default.Check else Icons.Default.AccessTime, contentDescription = null, tint = color, modifier = Modifier.size(17.dp))
        }
        Box(Modifier.width(3.dp).height(68.dp).background(color))
    }
}

@Composable
fun LectureTypeIcon(type: String?): ImageVector = when (type?.lowercase()) {
    "practical", "lab" -> Icons.Default.Code
    "tutorial" -> Icons.Default.School
    else -> Icons.Default.MenuBook
}

@Composable
fun EmptyStateCard(title: String, subtitle: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(22.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(subtitle, color = GndecMuted)
        }
    }
}
