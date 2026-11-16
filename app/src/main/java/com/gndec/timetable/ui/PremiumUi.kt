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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
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
import androidx.compose.ui.text.style.TextAlign
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
import com.gndec.timetable.domain.AttendanceManager
import com.gndec.timetable.domain.DailyQuote
import com.gndec.timetable.domain.ErpNotice
import com.gndec.timetable.domain.Holiday
import com.gndec.timetable.domain.HolidayGate
import com.gndec.timetable.domain.NextLectureEngine
import com.gndec.timetable.ui.attendance.SubjectAttendance
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import com.gndec.timetable.ui.motion.Motion
import com.gndec.timetable.ui.motion.hapticTick
import com.gndec.timetable.ui.motion.pressFeedback
import com.gndec.timetable.ui.motion.motionTween
import com.gndec.timetable.ui.theme.LocalLectureTypePalette
import com.gndec.timetable.ui.theme.lectureTypeColors
import com.gndec.timetable.util.Formatters
import com.gndec.timetable.util.displayTeacher
import com.gndec.timetable.util.displayVenue
import com.gndec.timetable.util.resolvedVenue
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

/**
 * The signature aqua wash, used by the sync strip and the vacant-rooms header.
 * The home hero card does not use it — it paints the lecture's own type tint
 * instead (see [PremiumNextLectureCard]).
 */
@Composable
@ReadOnlyComposable
fun premiumAquaBrush(): Brush = LocalLectureTypePalette.current.lecture.brush

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
    onSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(group, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
            }
            IconButton(onClick = onSearch) {
                Icon(Icons.Default.Search, contentDescription = "Search", tint = MaterialTheme.colorScheme.primary)
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
fun PremiumNextLectureCard(
    lecture: LectureEntity,
    dayLabel: String,
    countdown: String,
    isHappening: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    attendance: SubjectAttendance? = null,
    mentorName: String = "",
    mentorVenue: String = ""
) {
    // The hero wears the same type tint as the Today tab: teal for lectures,
    // amber for tutorials, blue for practicals.
    val heroBrush = lectureTypeColors(lecture.lectureType).brush
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
            lecture.resolvedVenue(mentorVenue)?.let {
                PremiumMetaRow(Icons.Default.LocationOn, it)
                Spacer(Modifier.height(7.dp))
            }
            PremiumMetaRow(Icons.Default.Person, lecture.displayTeacher(mentorName))
            if (attendance != null) {
                Spacer(Modifier.height(13.dp))
                SubjectAttendanceStrip(attendance = attendance, lectureType = lecture.lectureType)
            }
        }
    }
}

/**
 * Verdict on the lecture the hero is pointing at, judged on this subject alone.
 *
 * "Safely miss" only when [SubjectAttendance.canAffordToMiss] (at least one more
 * absence still stays at/above target). If missing this class would fall below
 * the target — even from a percentage that is currently above it — the strip
 * states the projected percentage after the miss.
 */
@Composable
private fun SubjectAttendanceStrip(attendance: SubjectAttendance, lectureType: String?) {
    val percent = attendance.percentage?.let { "${"%.1f".format(it)}%" } ?: "—"
    val projected = attendance.percentageIfMissed?.let { "${"%.1f".format(it)}%" }
    val noun = when (AttendanceManager.normalizeLectureType(lectureType)) {
        "practical" -> "practical"
        "tutorial" -> "tutorial"
        else -> "lecture"
    }
    val subject = attendance.subject
    val content = when {
        attendance.canAffordToMiss && attendance.isComfortable -> ComfortTone()
        attendance.canAffordToMiss -> SafeTone()
        else -> DangerTone()
    }
    val bold = SpanStyle(fontWeight = FontWeight.Bold, color = content)
    val normal = SpanStyle(fontWeight = FontWeight.Medium, color = content)
    val verdict = when {
        attendance.canAffordToMiss && attendance.isComfortable -> buildAnnotatedString {
            withStyle(normal) { append("Good job! You have ") }
            withStyle(bold) { append(percent) }
            withStyle(normal) { append(" attendance in $subject.") }
        }
        attendance.canAffordToMiss -> buildAnnotatedString {
            withStyle(normal) { append("You can ") }
            withStyle(bold) { append("safely miss") }
            withStyle(normal) { append(" this $noun — ") }
            withStyle(bold) { append(percent) }
            withStyle(normal) { append(" in $subject.") }
        }
        projected != null -> buildAnnotatedString {
            withStyle(normal) { append("Missing this $noun would ") }
            withStyle(bold) { append("drop your attendance to $projected") }
            withStyle(normal) { append(" in $subject.") }
        }
        else -> buildAnnotatedString {
            withStyle(normal) { append("You ") }
            withStyle(bold) { append("can't afford to miss") }
            withStyle(normal) { append(" this $noun — ") }
            withStyle(bold) { append(percent) }
            withStyle(normal) { append(" in $subject.") }
        }
    }
    // Plain text, no chip or container: inside the hero card a wrapped box read as
    // clutter, so the verdict is just a line whose keywords carry the tone color.
    Text(
        verdict,
        color = content,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * Text colors for the three verdicts. Danger and Safe come from the Material
 * roles, so they follow the app's own palette; Comfort has no such role and is
 * pinned here, chosen to read clearly on the hero card in both modes.
 */
@Composable
private fun DangerTone(): Color = MaterialTheme.colorScheme.onErrorContainer

@Composable
private fun SafeTone(): Color = MaterialTheme.colorScheme.onSecondaryContainer

@Composable
private fun ComfortTone(): Color {
    val light = MaterialTheme.colorScheme.background.luminance() > 0.5f
    return if (light) Color(0xFF0E5132) else Color(0xFF9EE6C0)
}

/**
 * Home announcement card.
 *
 * JSON fields:
 * - type: info | notice | warn | happy | urgent | update
 * - link: optional URL — when set, the whole card opens it
 * - message: supports lightweight markdown (**bold**, *italic*, ~~strikethrough~~, [label](url), bare https:// links)
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
    val strike = Regex("""~~(.+?)~~""")
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
    strike.findAll(text).forEach { m ->
        if (tokens.none { m.range.first >= it.start && m.range.last < it.end }) {
            tokens += Tok(m.range.first, m.range.last + 1, "strike", m.groupValues[1])
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
            "strike" -> withStyle(SpanStyle(color = color, textDecoration = TextDecoration.LineThrough)) { append(t.display) }
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
    /** ErpNotice.id when this banner is a notice (null for holidays). */
    val noticeId: String? = null,
)

/**
 * Extra calendar days after the start date that a notice remains eligible for
 * the homepage carousel when the feed gives no explicit window. 1 means the
 * arrival day plus the following day (two days total, both bounds inclusive).
 */
private const val FALLBACK_BANNER_WINDOW_DAYS = 1L

private fun String.asIsoDateOrNull(): LocalDate? {
    val head = trim().take(10)
    if (head.length != 10) return null
    return runCatching { LocalDate.parse(head) }.getOrNull()
}

/**
 * Whether this notice should appear in the homepage carousel on [todayIso].
 *
 * Notices show for the calendar day they arrive and the following day only
 * (full day of arrival + next day). The backend may supply an explicit banner
 * window; when it does not, we fall back to start..start+1 so old notices do
 * not linger for a week. Explicit windows are also capped to that same length.
 */
fun ErpNotice.isBannerEligibleOn(todayIso: String): Boolean {
    val today = todayIso.asIsoDateOrNull() ?: return false
    val start = bannerStartDate.asIsoDateOrNull()
        ?: firstSeenAt.asIsoDateOrNull()
        ?: publishedDate.asIsoDateOrNull()
        ?: return false
    // Cap at arrival day + next day even if the backend supplies a longer window.
    val end = (bannerUntilDate.asIsoDateOrNull() ?: start.plusDays(FALLBACK_BANNER_WINDOW_DAYS))
        .coerceAtMost(start.plusDays(FALLBACK_BANNER_WINDOW_DAYS))
    // Both bounds inclusive: arrival day and the next day.
    return !today.isBefore(start) && !today.isAfter(end)
}

fun ErpNotice.toHomeBannerItem(): HomeBannerItem = HomeBannerItem(
    id = "erp:$id",
    // Name the feed this came from, so a carousel spanning several sources says so.
    eyebrow = "NEW · ${source.removePrefix("GNDEC ").ifBlank { "NOTICE" }.uppercase()}",
    title = title,
    subtitle = author.ifBlank { "Tap to read full notice" },
    url = url.takeIf { it.isNotBlank() },
    isHoliday = false,
    noticeId = id
)

/** Today / tomorrow holiday and half-day cards for the homepage banner carousel. */
fun holidayHomeBannerItems(holidays: List<Holiday>, todayIso: String, tomorrowIso: String): List<HomeBannerItem> {
    fun isHalfDay(category: String) = category.contains("half", ignoreCase = true)
    fun isFullHoliday(category: String) = HolidayGate.isFullHolidayCategory(category)

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

/**
 * Full-holiday hero. Replaces the next-lecture hero on Home and the timeline on the
 * Today screen: no class is scheduled, so the schedule itself steps aside for the
 * reason the campus is closed. "Not a holiday?" is the escape hatch for a wrong
 * entry — tapping it reveals the regular timetable for the rest of that day (a
 * day-scoped choice, closable again from the override strip).
 */
@Composable
fun PremiumHolidayHeroCard(
    holidayNames: String,
    onViewTimetable: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier.size(56.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Celebration, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text("Today is a holiday", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (holidayNames.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "on account of $holidayNames",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Not a holiday? Tap here to view today’s timetable",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clickable(onClick = onViewTimetable)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

/** Long-day label format for the next-lecture card: "Monday, 9 March". */
private val NextAfterHolidayDateFormat = DateTimeFormatter.ofPattern("EEEE, d MMMM")

/**
 * Small card under the holiday hero: when classes actually resume. It already
 * skips any further full holidays, so a run of holidays reads as one break.
 */
@Composable
fun PremiumNextAfterHolidayCard(
    next: NextLectureEngine.TimedLecture,
    todayIso: String,
    modifier: Modifier = Modifier
) {
    val dateLabel = remember(todayIso, next.daysAhead) {
        runCatching {
            NextAfterHolidayDateFormat.format(LocalDate.parse(todayIso).plusDays(next.daysAhead.toLong()))
        }.getOrNull()
    }
    val dayLabel = when (next.daysAhead) {
        1 -> "Tomorrow"
        else -> "In ${next.daysAhead} days"
    }
    val lecture = next.lecture
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
                    Icon(Icons.Default.Event, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Next lecture after the holiday", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    val whenLabel = if (dateLabel != null) "$dayLabel · $dateLabel" else dayLabel
                    Text(whenLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(13.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
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
                    Text(lecture.venue ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * Slim strip shown while the timetable is displayed despite today being a full
 * holiday (after "Not a holiday?" was used). Close returns to the holiday notice —
 * the escape hatch for an accidental tap.
 */
@Composable
fun PremiumHolidayOverrideStrip(
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f))
            .padding(start = 14.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Celebration, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            "Today is still a holiday — timetable shown",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onClose, contentPadding = PaddingValues(horizontal = 10.dp)) {
            Text("Close")
        }
    }
}

/**
 * Every banner card is at least this tall, so a one-line notice and a three-line
 * one present the same box in the carousel and swiping never changes the page
 * height. Sized for the worst case the card can reach: 15dp padding top and
 * bottom, a one-line eyebrow, a 3dp gap, a two-line title and a one-line
 * subtitle. `heightIn` rather than a hard `height`, so a large system font scale
 * grows every card together instead of clipping.
 */
private val BannerCardMinHeight = 113.dp

/**
 * Banner card colours, kept apart so the carousel reads as two kinds of thing at
 * a glance: notices stay on the theme's amber tertiary, holidays take the blue
 * the practical lecture cards use.
 *
 * The accent is a separate step from the card because it has to clear AA as
 * 11sp bold eyebrow text, not just pass as a graphic. The theme's own tertiary
 * measures 2.1:1 as text on its own container, so light mode darkens it.
 */
private data class BannerColors(val container: Color, val accent: Color)

private val NoticeBannerDark = BannerColors(
    container = Color(0xFF6B4708),
    accent = Color(0xFFFFC766)
)
private val NoticeBannerLight = BannerColors(
    container = Color(0xFFFFF1DE),
    accent = Color(0xFFA35A00)
)
private val HolidayBannerDark = BannerColors(
    container = Color(0xFF123A5C),
    accent = Color(0xFF7DB4FF)
)
private val HolidayBannerLight = BannerColors(
    container = Color(0xFFE6F0FD),
    accent = Color(0xFF1D4ED8)
)

@Composable
fun PremiumHomeBannerCard(item: HomeBannerItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val pressInteraction = remember { MutableInteractionSource() }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val colors = when {
        item.isHoliday && dark -> HolidayBannerDark
        item.isHoliday -> HolidayBannerLight
        dark -> NoticeBannerDark
        else -> NoticeBannerLight
    }
    Card(
        onClick = onClick,
        modifier = modifier.pressFeedback(pressInteraction),
        interactionSource = pressInteraction,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colors.container),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            Modifier
                .heightIn(min = BannerCardMinHeight)
                .padding(15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(38.dp).background(colors.accent.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(
                    if (item.isHoliday) Icons.Default.CalendarMonth else Icons.Default.Campaign,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(item.eyebrow, color = colors.accent, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(item.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        Spacer(Modifier.height(3.dp))
        // Slim segmented bar rather than a row of chunky dots: the visible mark is
        // only 3dp tall, and each segment still owns a 10dp tap target so a banner
        // can be jumped to directly. Segments share the width evenly, so the
        // indicator never grows into a barcode when more banners are queued.
        Row(
            Modifier.fillMaxWidth().height(10.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.indices.forEach { index ->
                val selected = pagerState.currentPage == index
                val segmentColor by animateColorAsState(
                    targetValue = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                    animationSpec = motionTween(Motion.Normal),
                    label = "bannerIndicator"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(10.dp)
                        .clickable { scope.launch { pagerState.animateScrollToPage(index) } },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(segmentColor)
                    )
                }
            }
        }
    }
}

@Composable
fun PremiumTodayPreview(lectures: List<LectureEntity>, dateLabel: String, onOpen: () -> Unit, onLecture: (LectureEntity) -> Unit, modifier: Modifier = Modifier, mentorName: String = "", mentorVenue: String = "") {
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
                    PremiumPreviewRow(lecture, onClick = { onLecture(lecture) }, mentorVenue = mentorVenue)
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
private fun PremiumPreviewRow(lecture: LectureEntity, onClick: () -> Unit, mentorVenue: String = "") {
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
            Text(lecture.displayVenue(mentorVenue), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

/**
 * Daily quote strip, shown between the hero lecture card and the notice card.
 *
 * Deliberately not a card: quiet centered italic text between denser blocks.
 * Side inset grows with length so longer quotes wrap with fuller last lines
 * instead of two stranded words. Attribution sits under the quote, right-aligned
 * with a modest trailing inset — classic web “— Author” style, not flush to the
 * screen edge.
 *
 * The author is skipped when ZenQuotes returns it blank rather than rendering a lone dash.
 */
@Composable
fun PremiumDailyQuote(quote: DailyQuote, modifier: Modifier = Modifier) {
    val sideInset = when {
        quote.text.length <= 50 -> 20.dp
        quote.text.length <= 90 -> 32.dp
        else -> 40.dp
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = sideInset),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            quote.text,
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
            fontStyle = FontStyle.Italic,
            lineHeight = 25.sp,
            textAlign = TextAlign.Center
        )
        quote.author.takeIf { it.isNotBlank() }?.let { author ->
            Spacer(Modifier.height(4.dp))
            Text(
                "— $author",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 6.dp),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.End
            )
        }
    }
}

@Composable
fun PremiumPill(text: String, background: Color, content: Color) {
    // Capsule, not CircleShape: long labels (e.g. "BBA1 Automatic Subgroup") must
    // stay a horizontal pill instead of ballooning into a tall circle.
    Surface(color = background, contentColor = content, shape = RoundedCornerShape(50)) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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
        Triple("syllabus", "Syllabus", Icons.Default.MenuBook),
        Triple("more", "More", Icons.Default.Apps)
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
