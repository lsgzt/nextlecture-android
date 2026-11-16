package com.gndec.timetable.ui.faculty

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gndec.timetable.domain.AppContainer
import com.gndec.timetable.domain.FacultyBirthday
import com.gndec.timetable.domain.FacultyBirthdays
import com.gndec.timetable.domain.FacultyDirectoryManager
import com.gndec.timetable.domain.FacultyRecord
import com.gndec.timetable.ui.Header
import com.gndec.timetable.ui.PremiumBottomBarContentClearance
import com.gndec.timetable.ui.PremiumScreenBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * One rendered entry of the dedicated Faculty Birthday list. The screen builds
 * this immutable model up front (inside `remember`) and the LazyColumn only
 * walks it — no per-recomposition date math, no mutable locals.
 *
 * [stableId] is stamped with the entry's POSITION as well as its content, so
 * no dataset — today's or a future directory refresh — can ever produce two
 * items with the same key: duplicate LazyColumn keys crash the list while
 * scrolling, and this list feeds from a pool the background doc refresher
 * rebuilds, so uniqueness is proven by construction instead of trusted.
 */
private sealed interface BirthdayItem {
    val stableId: String

    data class MonthHeader(val label: String, val month: YearMonth, val position: Int) : BirthdayItem {
        override val stableId get() = "month-$position-${month.year}-${month.monthValue}"
    }

    data class Row(val birthday: FacultyBirthday, val position: Int) : BirthdayItem {
        override val stableId get() = "row-$position-${birthday.record.profileId}"
    }
}

/** "NOVEMBER 2026" — full month name so a year-long list stays unambiguous. */
private fun monthLabel(month: YearMonth): String =
    "${month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH).uppercase()} ${month.year}"

/**
 * The grouped section of the list: every birthday AFTER the hero, in
 * ascending order, with a month separator whenever the calendar month rolls
 * over (the hero's own month starts unlabelled — the hero card carries it).
 */
private fun birthdayItems(upcoming: List<FacultyBirthday>): List<BirthdayItem> {
    if (upcoming.size < 2) return emptyList()
    val out = mutableListOf<BirthdayItem>()
    var currentMonth = YearMonth.from(upcoming.first().nextDate)
    upcoming.drop(1).forEach { next ->
        val month = YearMonth.from(next.nextDate)
        if (month != currentMonth) {
            currentMonth = month
            out += BirthdayItem.MonthHeader(monthLabel(month), month, out.size)
        }
        out += BirthdayItem.Row(next, out.size)
    }
    return out
}

/**
 * Case-insensitive match of a typed query against a teacher's name,
 * designation or department — both the full department name ("Electronics &
 * Communication Engineering") and its short code ("ECE") — so "prof", "cse"
 * and partial names all find their teachers.
 */
private fun birthdayMatchesQuery(birthday: FacultyBirthday, needle: String): Boolean {
    val record = birthday.record
    return record.name.lowercase().contains(needle) ||
        record.designation.lowercase().contains(needle) ||
        record.department.lowercase().contains(needle) ||
        FacultyDirectoryManager.shortDepartment(record.department).lowercase().contains(needle)
}

/**
 * Dedicated Faculty Birthday browser — opened from the "Faculty Birthday"
 * card in the More tab. Separated from the faculty members directory so each
 * screen does one job: this one answers "whose birthday is next?".
 *
 * A hero card celebrates the next upcoming teacher (photo, name, the age
 * they are turning and a daily wish), and directly under it sits a search
 * field: typing a teacher's name, designation or department swaps the
 * month-grouped list for the flat set of matching birthdays (a count label
 * replaces the month separators while searching). Tapping a teacher opens
 * their profile; tapping the hero PHOTO opens the zoomable viewer instead.
 */
@Composable
fun FacultyBirthdaysScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenFaculty: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    val focusManager = LocalFocusManager.current

    // The teacher whose photo the user tapped open in the zoomable viewer.
    var photoViewerFor by remember { mutableStateOf<FacultyRecord?>(null) }

    // Prefer the process-wide snapshot (instant). Never parse the bundled
    // directory on the main thread — that froze the UI for hundreds of ms.
    var faculty by remember {
        mutableStateOf(container.facultyTimetables.combinedFacultyFast().orEmpty())
    }
    var listLoading by remember { mutableStateOf(faculty.isEmpty()) }

    LaunchedEffect(Unit) {
        if (faculty.isEmpty()) {
            // The pool is also rebuilt by background doc refreshes; any failure
            // here must degrade to the empty state, never crash the app.
            val base = withContext(Dispatchers.Default) {
                runCatching { container.facultyTimetables.combinedFaculty() }
                    .getOrDefault(emptyList())
            }
            faculty = base
        }
        listLoading = false
    }

    val today = remember { LocalDate.now() }
    val upcoming = remember(faculty, today) {
        FacultyBirthdays.upcoming(faculty, today, limit = FACULTY_BIRTHDAY_LIST_LIMIT)
            // Belt-and-braces: the combined pool is directory + live-discovered
            // extras rebuilt on a worker thread; a duplicate record must never
            // reach a keyed LazyColumn, so collapse by profile id (soonest wins).
            .distinctBy { it.record.profileId }
    }
    val listItems = remember(upcoming) { birthdayItems(upcoming) }

    // Search lives right under the hero card. The query and the scroll
    // position are saveable, so both survive a trip into a teacher's profile
    // and back (this composable is disposed while the detail route is on top
    // of the stack).
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val needle = searchQuery.trim().lowercase()
    val searching = needle.isNotBlank()

    // Matches keep their POSITION in `upcoming` (index in the full list), so
    // row keys stay unique no matter how the query changes — the crash class
    // this screen already guards against in the grouped section.
    val searchMatches = remember(upcoming, needle) {
        if (!searching) emptyList()
        else upcoming.withIndex()
            .filter { (_, birthday) -> birthdayMatchesQuery(birthday, needle) }
            .map { (index, birthday) -> BirthdayItem.Row(birthday, index) }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        PremiumScreenBackground {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(
                    top = 8.dp,
                    bottom = 26.dp + PremiumBottomBarContentClearance
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item(key = "header") {
                    Header(
                        title = "Faculty Birthday",
                        subtitle = if (upcoming.isEmpty()) null
                        else "Upcoming birthdays, soonest first",
                        onBack = onBack,
                        onSettings = onOpenSettings
                    )
                }

                if (upcoming.isEmpty()) {
                    item(key = if (listLoading) "loading" else "empty") {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            elevation = CardDefaults.cardElevation(0.dp)
                        ) {
                            if (listLoading) {
                                Row(
                                    Modifier.fillMaxWidth().padding(18.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        "Loading the college directory…",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            } else {
                                Column(Modifier.padding(18.dp)) {
                                    Text(
                                        "No birthdays to show",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "No teacher in the college directory publishes a birth date.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // The next birthday always leads the screen as the hero.
                    item(key = "hero") {
                        val hero = upcoming.first()
                        BirthdayHeroCard(
                            birthday = hero,
                            store = container.facultyPhotos,
                            onClick = { onOpenFaculty(hero.record.profileId) },
                            onPhotoClick = { photoViewerFor = hero.record }
                        )
                    }

                    // Search field right under the hero card.
                    item(key = "search") {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                            placeholder = { Text("Type name or department") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear search")
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() })
                        )
                    }

                    if (searching) {
                        if (searchMatches.isEmpty()) {
                            item(key = "no-results") {
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    elevation = CardDefaults.cardElevation(0.dp)
                                ) {
                                    Column(Modifier.padding(18.dp)) {
                                        Text(
                                            "No birthdays match",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            "Try a shorter name or a different spelling, or search by department (e.g. CSE).",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        } else {
                            // Flat results while searching — each row carries its
                            // own date, so month separators are not needed.
                            item(key = "search-count") {
                                Text(
                                    if (searchMatches.size == 1) "1 BIRTHDAY MATCHES"
                                    else "${searchMatches.size} BIRTHDAYS MATCH",
                                    modifier = Modifier.padding(start = 22.dp, top = 4.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.2.sp
                                )
                            }
                            items(searchMatches, key = { it.stableId }) { row ->
                                BirthdayRow(
                                    birthday = row.birthday,
                                    store = container.facultyPhotos,
                                    onClick = { onOpenFaculty(row.birthday.record.profileId) }
                                )
                            }
                        }
                    } else {
                        items(listItems, key = { it.stableId }) { entry ->
                            when (entry) {
                                is BirthdayItem.MonthHeader -> Text(
                                    entry.label,
                                    modifier = Modifier.padding(start = 22.dp, top = 8.dp, bottom = 2.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.2.sp
                                )
                                is BirthdayItem.Row -> BirthdayRow(
                                    birthday = entry.birthday,
                                    store = container.facultyPhotos,
                                    onClick = { onOpenFaculty(entry.birthday.record.profileId) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Zoomable photo viewer — opened by tapping a teacher's avatar.
    photoViewerFor?.let { record ->
        FacultyPhotoViewer(
            store = container.facultyPhotos,
            url = record.imageUrl,
            name = prettyName(record.name),
            onDismiss = { photoViewerFor = null }
        )
    }
}
