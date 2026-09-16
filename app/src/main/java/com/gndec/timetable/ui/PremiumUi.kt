package com.gndec.timetable.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MenuBook
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.navigationBars
import com.gndec.timetable.data.db.LectureEntity
import com.gndec.timetable.domain.Announcement
import com.gndec.timetable.domain.ErpNotice
import com.gndec.timetable.ui.motion.Motion
import com.gndec.timetable.ui.motion.hapticTick
import com.gndec.timetable.ui.motion.pressFeedback
import com.gndec.timetable.ui.motion.motionTween
import com.gndec.timetable.util.Formatters
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val LightAqua = Color(0xFFE8F6F4)
private val LightAquaStrong = Color(0xFFD8F0ED)
private val DarkAqua = Color(0xFF183B3A)

/** Signature aqua wash shared by the hero card and the sync strip. */
@Composable
fun premiumAquaBrush(): Brush = Brush.linearGradient(
    if (MaterialTheme.colorScheme.background.luminance() > 0.5f) listOf(LightAqua, LightAquaStrong)
    else listOf(Color(0xFF1C4443), DarkAqua)
)

@Composable
fun PremiumScreenBackground(content: @Composable () -> Unit) {
    Box(androidx.compose.ui.Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
}

@Composable
fun PremiumBrandHeader(
    group: String,
    greeting: String,
    studentName: String = "",
    title: String = "NextLecture",
    onSettings: () -> Unit,
    onProfile: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(group, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
            }
            IconButton(onClick = onProfile) {
                Icon(Icons.Default.Person, contentDescription = "Profile", tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(greeting, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
        if (studentName.isNotBlank()) {
            Text(
                studentName,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
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
        modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(Modifier.width(2.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
        }
        if (onSettings != null) {
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.primary)
            }
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
    Row(
        modifier.fillMaxWidth().padding(horizontal = 20.dp).clip(RoundedCornerShape(18.dp))
            .background(premiumAquaBrush(), RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.CloudDone, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text("Using saved timetable", fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(updatedText, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val updatePress = remember { MutableInteractionSource() }
        OutlinedButton(
            onClick = onFetch,
            enabled = fetchEnabled,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.height(38.dp).pressFeedback(updatePress, pressedScale = 0.96f),
            interactionSource = updatePress
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(5.dp))
            Text("Update", maxLines = 1)
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
    val heroBrush = premiumAquaBrush()
    val titleColor = MaterialTheme.colorScheme.onSurface
    val countdownColor by animateColorAsState(
        targetValue = if (isHappening) MaterialTheme.colorScheme.primary else Color(0xFFE89A4A),
        animationSpec = motionTween(Motion.Normal),
        label = "nextLectureCountdownColor"
    )
    val pressInteraction = remember { MutableInteractionSource() }
    val badgeFade = motionTween<Float>(Motion.Fast)
    Card(
        onClick = onClick,
        modifier = modifier.pressFeedback(pressInteraction).background(heroBrush, RoundedCornerShape(24.dp)),
        interactionSource = pressInteraction,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), contentColor = MaterialTheme.colorScheme.primary, shape = CircleShape) {
                    AnimatedContent(
                        targetState = isHappening,
                        transitionSpec = {
                            (fadeIn(badgeFade) togetherWith fadeOut(badgeFade))
                        },
                        label = "nextLectureBadge"
                    ) { happening ->
                        Text(
                            if (happening) "NOW" else "NEXT",
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(dayLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Icon(Icons.Default.ChevronRight, contentDescription = "Open lecture", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(15.dp))
            Text(lecture.subject ?: "Lecture", color = titleColor, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (countdown.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                // Text updates per minute without animation; only the state color animates.
                Text(
                    if (isHappening) "ENDS IN $countdown" else "STARTS IN $countdown",
                    color = countdownColor,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(Formatters.range(lecture.startMinutes, lecture.endMinutes), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(13.dp))
            lecture.venue?.takeIf { it.isNotBlank() }?.let {
                PremiumMetaRow(Icons.Default.LocationOn, it)
                Spacer(Modifier.height(7.dp))
            }
            PremiumMetaRow(Icons.Default.Person, lecture.teacher?.takeIf { it.isNotBlank() } ?: "Teacher unavailable")
        }
    }
}

/**
 * Home announcement card.
 *
 * JSON fields:
 * - type: info | notice | warn | happy | urgent | update
 * - link: optional URL — when set, the whole card opens it
 * - message: supports lightweight markdown (**bold**, *italic*, [label](url), bare https:// links)
 * Inline markdown links stay tappable even when the card has a [Announcement.link].
 */
@Composable
fun PremiumAnnouncementCard(announcement: Announcement, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val style = announcementVisualStyle(announcement.type)
    val cardLink = announcement.link.trim().takeIf { it.isNotBlank() }
    val cardModifier = if (cardLink != null) {
        modifier.clickable {
            runCatching { uriHandler.openUri(cardLink) }
        }
    } else {
        modifier
    }
    Card(
        modifier = cardModifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = style.container),
        border = BorderStroke(1.dp, style.border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .size(36.dp)
                    .background(style.accent.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (style.emoji != null) {
                    Text(style.emoji, style = MaterialTheme.typography.titleMedium)
                } else {
                    Icon(
                        Icons.Default.Campaign,
                        contentDescription = null,
                        tint = style.accent,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    style.label,
                    color = style.accent,
                    letterSpacing = 1.2.sp,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelSmall
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    announcement.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = style.onContainer
                )
                Spacer(Modifier.height(3.dp))
                AnnouncementMarkdownText(
                    markdown = announcement.message,
                    color = style.onContainer.copy(alpha = 0.82f),
                    linkColor = style.accent
                )
                if (cardLink != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Tap card to open",
                        color = style.accent.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

private data class AnnouncementVisualStyle(
    val label: String,
    val emoji: String?,
    val container: Color,
    val accent: Color,
    val onContainer: Color,
    val border: Color
)

@Composable
private fun announcementVisualStyle(rawType: String): AnnouncementVisualStyle {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    return when (rawType.trim().lowercase()) {
        "warn", "warning" -> AnnouncementVisualStyle(
            label = "WARNING",
            emoji = "⚠️",
            container = if (dark) Color(0xFF3D3218) else Color(0xFFFFF4D6),
            accent = if (dark) Color(0xFFF5C542) else Color(0xFFB45309),
            onContainer = if (dark) Color(0xFFFFF3C4) else Color(0xFF422006),
            border = if (dark) Color(0xFF6B5420) else Color(0xFFF0D48A)
        )
        "happy", "celebrate", "celebration" -> AnnouncementVisualStyle(
            label = "CELEBRATION",
            emoji = "🎉",
            container = if (dark) Color(0xFF1A3D2E) else Color(0xFFE8F8EF),
            accent = if (dark) Color(0xFF6EE7B7) else Color(0xFF047857),
            onContainer = if (dark) Color(0xFFD1FAE5) else Color(0xFF064E3B),
            border = if (dark) Color(0xFF2F6B50) else Color(0xFFA7F3D0)
        )
        "urgent", "critical", "alert" -> AnnouncementVisualStyle(
            label = "URGENT",
            emoji = "🚨",
            container = if (dark) Color(0xFF3F1D1D) else Color(0xFFFFE8E8),
            accent = if (dark) Color(0xFFFCA5A5) else Color(0xFFB91C1C),
            onContainer = if (dark) Color(0xFFFECACA) else Color(0xFF7F1D1D),
            border = if (dark) Color(0xFF7F2A2A) else Color(0xFFFECACA)
        )
        "update", "feature" -> AnnouncementVisualStyle(
            label = "UPDATE",
            emoji = "✨",
            container = if (dark) Color(0xFF1A2F3D) else Color(0xFFE8F4FC),
            accent = if (dark) Color(0xFF7DD3FC) else Color(0xFF0369A1),
            onContainer = if (dark) Color(0xFFE0F2FE) else Color(0xFF0C4A6E),
            border = if (dark) Color(0xFF2A4A60) else Color(0xFFBAE6FD)
        )
        "notice" -> AnnouncementVisualStyle(
            label = "NOTICE",
            emoji = "📌",
            container = if (dark) Color(0xFF1E2A3D) else Color(0xFFEEF2FF),
            accent = if (dark) Color(0xFFA5B4FC) else Color(0xFF4338CA),
            onContainer = if (dark) Color(0xFFE0E7FF) else Color(0xFF1E1B4B),
            border = if (dark) Color(0xFF334155) else Color(0xFFC7D2FE)
        )
        else -> AnnouncementVisualStyle(
            label = "ANNOUNCEMENT",
            emoji = null,
            container = scheme.secondaryContainer,
            accent = scheme.primary,
            onContainer = scheme.onSecondaryContainer,
            border = scheme.outlineVariant
        )
    }
}

/**
 * Lightweight markdown for announcement bodies: **bold**, *italic* / _italic_,
 * [label](url), and bare https:// URLs. Inline links remain independently tappable.
 */
@Composable
private fun AnnouncementMarkdownText(
    markdown: String,
    color: Color,
    linkColor: Color,
    modifier: Modifier = Modifier
) {
    val uriHandler = LocalUriHandler.current
    val annotated = remember(markdown, color, linkColor) {
        announcementInlineAnnotated(markdown, color, linkColor)
    }
    ClickableText(
        text = annotated,
        modifier = modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodyMedium.copy(color = color),
        onClick = { offset ->
            annotated.getStringAnnotations("URL", offset, offset).firstOrNull()?.let { match ->
                runCatching { uriHandler.openUri(match.item) }
            }
        }
    )
}

private fun announcementInlineAnnotated(
    source: String,
    color: Color,
    linkColor: Color
): AnnotatedString = buildAnnotatedString {
    // Order: links first, then bold, then italic. Process line-by-line for readability.
    val linkMd = Regex("""\[([^\]]+)\]\((https?://[^\s)]+)\)""")
    val bareUrl = Regex("""(?<![(\["'])(https?://[^\s)\]>]+)""")
    val bold = Regex("""\*\*(.+?)\*\*|__(.+?)__""")
    val italic = Regex("""(?<!\*)\*(?!\*)(.+?)(?<!\*)\*(?!\*)|(?<!_)_(?!_)(.+?)(?<!_)_(?!_)""")

    var i = 0
    val text = source
    // Combined token scan left-to-right
    data class Tok(val start: Int, val end: Int, val kind: String, val display: String, val url: String? = null)
    val tokens = mutableListOf<Tok>()
    linkMd.findAll(text).forEach { m ->
        tokens += Tok(m.range.first, m.range.last + 1, "link", m.groupValues[1], m.groupValues[2])
    }
    bareUrl.findAll(text).forEach { m ->
        // skip if already inside a markdown link span
        if (tokens.none { m.range.first >= it.start && m.range.last < it.end }) {
            tokens += Tok(m.range.first, m.range.last + 1, "link", m.value, m.value)
        }
    }
    bold.findAll(text).forEach { m ->
        if (tokens.none { m.range.first >= it.start && m.range.last < it.end }) {
            val display = m.groupValues[1].ifBlank { m.groupValues[2] }
            tokens += Tok(m.range.first, m.range.last + 1, "bold", display)
        }
    }
    italic.findAll(text).forEach { m ->
        if (tokens.none { m.range.first >= it.start && m.range.last < it.end }) {
            val display = m.groupValues[1].ifBlank { m.groupValues[2] }
            tokens += Tok(m.range.first, m.range.last + 1, "italic", display)
        }
    }
    tokens.sortBy { it.start }
    // Drop overlapping later tokens
    val kept = mutableListOf<Tok>()
    var cursor = 0
    for (t in tokens) {
        if (t.start < cursor) continue
        kept += t
        cursor = t.end
    }
    cursor = 0
    for (t in kept) {
        if (t.start > cursor) {
            withStyle(SpanStyle(color = color)) { append(text.substring(cursor, t.start)) }
        }
        when (t.kind) {
            "link" -> {
                val url = t.url.orEmpty()
                pushStringAnnotation("URL", url)
                withStyle(
                    SpanStyle(
                        color = linkColor,
                        textDecoration = TextDecoration.Underline,
                        fontWeight = FontWeight.SemiBold
                    )
                ) { append(t.display) }
                pop()
            }
            "bold" -> withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) { append(t.display) }
            "italic" -> withStyle(SpanStyle(color = color, fontStyle = FontStyle.Italic)) { append(t.display) }
        }
        cursor = t.end
    }
    if (cursor < text.length) {
        withStyle(SpanStyle(color = color)) { append(text.substring(cursor)) }
    }
}

data class HomeBannerItem(
    val id: String,
    val eyebrow: String,
    val title: String,
    val subtitle: String,
    val url: String? = null,
    val isHoliday: Boolean = false,
)

fun ErpNotice.toHomeBannerItem(): HomeBannerItem = HomeBannerItem(
    id = "erp:$id",
    eyebrow = "NEW · GNDEC NOTICE",
    title = title,
    subtitle = "Tap to open official notice",
    url = url.takeIf { it.isNotBlank() },
    isHoliday = false
)

/** Today / tomorrow holiday and half-day cards for the homepage banner carousel. */
fun holidayHomeBannerItems(holidays: List<com.gndec.timetable.domain.Holiday>, todayIso: String, tomorrowIso: String): List<HomeBannerItem> {
    fun isHalfDay(category: String) = category.equals("Half-day holiday", ignoreCase = true) ||
        category.contains("half", ignoreCase = true)
    fun isFullHoliday(category: String) = !isHalfDay(category) && (
        category.equals("Public holiday", ignoreCase = true) ||
            category.equals("Restricted holiday", ignoreCase = true) ||
            category.contains("holiday", ignoreCase = true)
    )

    val out = mutableListOf<HomeBannerItem>()
    val today = holidays.firstOrNull { it.date == todayIso }
    val tomorrow = holidays.firstOrNull { it.date == tomorrowIso }

    if (today != null) {
        if (isHalfDay(today.category)) {
            out += HomeBannerItem(
                id = "holiday-today-${today.id}",
                eyebrow = "HALF DAY · TODAY",
                title = "🌤️ Today is a half day",
                subtitle = today.name.ifBlank { "Official half-day holiday" },
                isHoliday = true
            )
        } else if (isFullHoliday(today.category)) {
            out += HomeBannerItem(
                id = "holiday-today-${today.id}",
                eyebrow = "HOLIDAY · TODAY",
                title = "🎉 Today is a holiday",
                subtitle = today.name.ifBlank { "Official college holiday" },
                isHoliday = true
            )
        }
    }
    if (tomorrow != null) {
        if (isHalfDay(tomorrow.category)) {
            out += HomeBannerItem(
                id = "holiday-tomorrow-${tomorrow.id}",
                eyebrow = "HALF DAY · TOMORROW",
                title = "🌤️ Tomorrow is a half day",
                subtitle = tomorrow.name.ifBlank { "Official half-day holiday" },
                isHoliday = true
            )
        } else if (isFullHoliday(tomorrow.category)) {
            out += HomeBannerItem(
                id = "holiday-tomorrow-${tomorrow.id}",
                eyebrow = "HOLIDAY · TOMORROW",
                title = "🎉 Tomorrow is a holiday",
                subtitle = tomorrow.name.ifBlank { "Official college holiday" },
                isHoliday = true
            )
        }
    }
    return out
}

@Composable
fun PremiumHomeBannerCard(item: HomeBannerItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val pressInteraction = remember { MutableInteractionSource() }
    Card(
        onClick = onClick,
        modifier = modifier.pressFeedback(pressInteraction),
        interactionSource = pressInteraction,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(
                    if (item.isHoliday) Icons.Default.CalendarMonth else Icons.Default.Campaign,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(item.eyebrow, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(3.dp))
                Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(item.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = "Open banner", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Backward-compatible ERP-only banner card. */
@Composable
fun PremiumErpNoticeBanner(notice: ErpNotice, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PremiumHomeBannerCard(item = notice.toHomeBannerItem(), onClick = onClick, modifier = modifier)
}

/**
 * Homepage banner carousel for ERP notices and/or holiday messages.
 * Single item: one card. Multiple: horizontal pager with auto-advance and swipe.
 */
@Composable
fun PremiumErpNoticeBannerCarousel(
    notices: List<ErpNotice>,
    onOpen: (ErpNotice) -> Unit,
    modifier: Modifier = Modifier,
    autoAdvanceMillis: Long = 4_000L
) {
    PremiumHomeBannerCarousel(
        items = notices.map { it.toHomeBannerItem() },
        onOpen = { item ->
            val notice = notices.firstOrNull { "erp:${it.id}" == item.id }
            if (notice != null) onOpen(notice)
        },
        modifier = modifier,
        autoAdvanceMillis = autoAdvanceMillis
    )
}

@Composable
fun PremiumHomeBannerCarousel(
    items: List<HomeBannerItem>,
    onOpen: (HomeBannerItem) -> Unit,
    modifier: Modifier = Modifier,
    autoAdvanceMillis: Long = 4_000L
) {
    if (items.isEmpty()) return
    if (items.size == 1) {
        PremiumHomeBannerCard(item = items.first(), onClick = { onOpen(items.first()) }, modifier = modifier)
        return
    }

    val pagerState = rememberPagerState(pageCount = { items.size })
    val scope = rememberCoroutineScope()

    // Auto-advance; restarts whenever the settled page changes (including after a manual swipe).
    LaunchedEffect(pagerState.settledPage, items.size) {
        delay(autoAdvanceMillis)
        val next = (pagerState.settledPage + 1) % items.size
        pagerState.animateScrollToPage(next)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth(),
            beyondViewportPageCount = 1
        ) { page ->
            val item = items[page]
            PremiumHomeBannerCard(
                item = item,
                onClick = { onOpen(item) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.indices.forEach { index ->
                val selected = pagerState.currentPage == index
                Box(
                    Modifier
                        .padding(horizontal = 3.dp)
                        .size(if (selected) 8.dp else 6.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                        )
                        .clickable {
                            scope.launch { pagerState.animateScrollToPage(index) }
                        }
                )
            }
        }
    }
}

@Composable
fun PremiumTodayPreview(lectures: List<LectureEntity>, dateLabel: String, onOpen: () -> Unit, onLecture: (LectureEntity) -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.13f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Today’s timetable", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(dateLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                Icon(Icons.Default.ChevronRight, contentDescription = "Open Today", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(13.dp))
            if (lectures.isEmpty()) {
                Text("No lectures scheduled today", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                lectures.take(3).forEach { lecture ->
                    PremiumPreviewRow(lecture, onClick = { onLecture(lecture) })
                    Spacer(Modifier.height(7.dp))
                }
            }
            val openPress = remember { MutableInteractionSource() }
            Text(
                "Open full timetable",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .pressFeedback(openPress, pressedScale = 0.96f)
                    .clickable(interactionSource = openPress, indication = LocalIndication.current, onClick = onOpen)
                    .padding(top = 5.dp)
            )
        }
    }
}

@Composable
private fun PremiumPreviewRow(lecture: LectureEntity, onClick: () -> Unit) {
    val pressInteraction = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .pressFeedback(pressInteraction, pressedScale = 0.98f)
            .clickable(interactionSource = pressInteraction, indication = LocalIndication.current, onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.width(68.dp)) {
            val range = Formatters.range(lecture.startMinutes, lecture.endMinutes)
            Text(range.substringBefore(" – "), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
            Text(range.substringAfter(" – "), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
        Box(Modifier.width(3.dp).height(34.dp).background(MaterialTheme.colorScheme.primary))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(lecture.subject ?: "Lecture", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(lecture.venue ?: "Venue unavailable", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun PremiumOfflineCard(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.CloudDone, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(23.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text("Works offline", fontWeight = FontWeight.Bold)
            Text("Saved timetable and reminders stay available without internet.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun PremiumPill(text: String, background: Color, content: Color) {
    Surface(color = background, contentColor = content, shape = CircleShape) {
        Text(text, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun PremiumMetaRow(icon: ImageVector, text: String, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = tint, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Bottom clearance tab screens must add so scrollable content clears the overlaid bar. */
val PremiumBottomBarContentClearance = 78.dp

@Composable
fun PremiumBottomBar(
    selected: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val items = listOf(
        Triple("home", "Home", Icons.Default.Home),
        Triple("today", "Today", Icons.Default.Today),
        Triple("notice", "Notice", Icons.Default.Campaign),
        Triple("syllabus", "Syllabus", Icons.Default.MenuBook)
    )
    // Every dimension below is FIXED; selection is expressed purely through animated
    // color fills and draw-phase scaling, so tapping a destination never shifts layout.
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.background, tonalElevation = 0.dp, shadowElevation = 0.dp) {
        val hapticView = LocalView.current
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)
                .height(78.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { (route, label, icon) ->
                val active = route == selected
                val indicatorColor by animateColorAsState(
                    targetValue = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent,
                    animationSpec = motionTween(Motion.Normal),
                    label = "navIndicatorColor"
                )
                val contentTint by animateColorAsState(
                    targetValue = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = motionTween(Motion.Normal),
                    label = "navContentTint"
                )
                val iconScale = animateFloatAsState(
                    targetValue = if (active) 1f else 0.92f,
                    animationSpec = motionTween(Motion.Normal),
                    label = "navIconScale"
                )
                val pressInteraction = remember { MutableInteractionSource() }
                Column(
                    Modifier.weight(1f).fillMaxHeight()
                        .clip(RoundedCornerShape(18.dp))
                        .pressFeedback(pressInteraction, pressedScale = 0.95f)
                        .clickable(interactionSource = pressInteraction, indication = LocalIndication.current) {
                            hapticView.hapticTick()
                            onNavigate(route)
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        Modifier.size(40.dp).background(indicatorColor, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            icon,
                            contentDescription = label,
                            tint = contentTint,
                            modifier = Modifier.size(23.dp).graphicsLayer {
                                val s = iconScale.value
                                scaleX = s
                                scaleY = s
                            }
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        label,
                        color = contentTint,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip
                    )
                }
            }
        }
    }
}
