package com.gndec.timetable.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gndec.timetable.data.db.LectureEntity
import com.gndec.timetable.domain.Announcement
import com.gndec.timetable.util.Formatters

private val PremiumDarkHero = Color(0xFF087F78)
private val PremiumLightHero = Color(0xFF0B7978)
private val PremiumDarkCard = Color(0xFF132521)
private val PremiumDarkLine = Color(0xFF28443F)
private val PremiumLightTint = Color(0xFFE4F4F0)

@Composable
fun PremiumScreenBackground(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
}

@Composable
fun PremiumHeroColors(): Pair<Color, Color> {
    val light = MaterialTheme.colorScheme.background.luminance() > 0.5f
    return if (light) PremiumLightHero to Color.White else PremiumDarkHero to Color.White
}

@Composable
fun PremiumBrandHeader(
    group: String,
    greeting: String,
    onSettings: () -> Unit,
    onProfile: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("NextLecture", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("GNDEC TIMETABLE", color = MaterialTheme.colorScheme.primary, letterSpacing = 2.sp, style = MaterialTheme.typography.labelMedium)
            }
            IconButton(onClick = onProfile) {
                Icon(Icons.Default.Person, contentDescription = "Profile", tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(group, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(greeting, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun PremiumPageHeader(
    title: String,
    subtitle: String,
    onBack: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
            Spacer(Modifier.size(4.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        if (onSettings != null) {
            IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.primary) }
        }
    }
}

@Composable
fun PremiumStatusRow(
    updatedText: String,
    onFetch: () -> Unit,
    fetchEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val tint = MaterialTheme.colorScheme.primary
    Column(modifier.padding(horizontal = 20.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 18.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(34.dp).background(tint.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.CloudDone, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Timetable updated", fontWeight = FontWeight.SemiBold)
                Text(updatedText, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(
                onClick = onFetch,
                enabled = fetchEnabled,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                border = BorderStroke(1.dp, tint),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(7.dp))
                Text("Fetch")
            }
        }
    }
}

@Composable
fun PremiumNextLectureCard(
    lecture: LectureEntity,
    dayLabel: String,
    countdown: String,
    isHappening: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (hero, onHero) = PremiumHeroColors()
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(containerColor = hero),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(26.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("NEXT LECTURE", color = onHero.copy(alpha = 0.78f), letterSpacing = 2.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                PremiumPill(if (isHappening) "Now" else dayLabel, onHero.copy(alpha = 0.16f), onHero)
            }
            Spacer(Modifier.height(24.dp))
            Text(lecture.subject ?: "Lecture", color = onHero, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(9.dp))
            Text(
                if (isHappening) "Happening now · ${Formatters.range(lecture.startMinutes, lecture.endMinutes)}" else "$dayLabel · ${Formatters.range(lecture.startMinutes, lecture.endMinutes)}",
                color = onHero.copy(alpha = 0.82f), style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(20.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(onHero.copy(alpha = 0.22f)))
            Spacer(Modifier.height(16.dp))
            lecture.venue?.takeIf { it.isNotBlank() }?.let {
                PremiumMetaRow(Icons.Default.LocationOn, it, onHero)
                Spacer(Modifier.height(10.dp))
            }
            PremiumMetaRow(Icons.Default.Person, lecture.teacher?.takeIf { it.isNotBlank() } ?: "Teacher unavailable", onHero)
            if (countdown.isNotBlank() && !isHappening) {
                Spacer(Modifier.height(16.dp))
                Text(countdown.uppercase(), color = onHero, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }
    }
}

@Composable
fun PremiumAnnouncementCard(announcement: Announcement, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Campaign, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text("ANNOUNCEMENT", color = MaterialTheme.colorScheme.primary, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                Text(announcement.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(announcement.message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun PremiumTodayPreview(lectures: List<LectureEntity>, dateLabel: String, onOpen: () -> Unit, onLecture: (LectureEntity) -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = if (MaterialTheme.colorScheme.background.luminance() > 0.5f) Color.White else PremiumDarkCard),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(23.dp))
                }
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Today’s timetable", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(dateLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Default.ChevronRight, contentDescription = "Open Today", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(16.dp))
            if (lectures.isEmpty()) {
                Text("No lectures scheduled today", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                lectures.take(3).forEach { lecture ->
                    PremiumPreviewRow(lecture, onClick = { onLecture(lecture) })
                    Spacer(Modifier.height(8.dp))
                }
            }
            Text("Open full timetable", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable(onClick = onOpen).padding(top = 6.dp))
        }
    }
}

@Composable
private fun PremiumPreviewRow(lecture: LectureEntity, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(78.dp)) {
            Text(Formatters.range(lecture.startMinutes, lecture.endMinutes).substringBefore(" – "), fontWeight = FontWeight.Bold)
            Text(Formatters.range(lecture.startMinutes, lecture.endMinutes).substringAfter(" – "), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Box(Modifier.width(3.dp).height(34.dp).background(MaterialTheme.colorScheme.primary))
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(lecture.subject ?: "Lecture", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(lecture.venue ?: "Venue unavailable", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun PremiumOfflineCard(modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.CloudDone, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
        Spacer(Modifier.size(13.dp))
        Column {
            Text("Works offline", fontWeight = FontWeight.Bold)
            Text("Your saved timetable keeps reminders running without internet.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun PremiumPill(text: String, background: Color, content: Color) {
    Surface(color = background, contentColor = content, shape = CircleShape) {
        Text(text, modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun PremiumMetaRow(icon: ImageVector, text: String, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint.copy(alpha = 0.9f), modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(10.dp))
        Text(text, color = tint, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun PremiumBottomBar(selected: String, onNavigate: (String) -> Unit) {
    val items = listOf(
        Triple("home", "Home", Icons.Default.Home),
        Triple("today", "Today", Icons.Default.Today),
        Triple("alerts", "Alerts", Icons.Default.Notifications)
    )
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(78.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            items.forEach { (route, label, icon) ->
                val active = route == selected
                Column(Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(18.dp)).clickable { onNavigate(route) }.padding(vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Box(Modifier.size(40.dp).background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = label, tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(23.dp))
                    }
                    Text(label, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
}
