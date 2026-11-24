package com.gndec.timetable.ui.home

import com.gndec.timetable.data.db.LectureEntity
import com.gndec.timetable.domain.AppContainer
import com.gndec.timetable.domain.ExamGate
import com.gndec.timetable.domain.ExamState
import com.gndec.timetable.domain.Holiday
import com.gndec.timetable.domain.HolidayGate
import com.gndec.timetable.domain.NextLectureEngine
import com.gndec.timetable.domain.RefreshResult
import com.gndec.timetable.util.Formatters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

sealed class FetchState {
    object Idle : FetchState()
    object Running : FetchState()
    data class Ok(val count: Int) : FetchState()
    object UpToDate : FetchState()
    data class Failed(val reason: String) : FetchState()
}

data class HomeUiState(
    val loading: Boolean = true,
    val group: String? = null,
    val lastFetch: Long? = null,
    val nowMillis: Long = System.currentTimeMillis(),
    val status: NextLectureEngine.Status = NextLectureEngine.Status.NoData,
    /** Every lecture of the selected group — the holiday gate searches these for the
     * next teaching day, not just today’s rows. */
    val lectures: List<LectureEntity> = emptyList(),
    val upcomingToday: List<LectureEntity> = emptyList(),
    val todayLectures: List<LectureEntity> = emptyList(),
    val freeGapMinutes: Int? = null,
    val stale: Boolean = false,
    /**
     * The date sheet that is on, read for this student. Null while exams are off;
     * the home page puts its exam in the big card instead of the next lecture.
     */
    val exam: ExamState? = null
)

class HomeViewModel(private val c: AppContainer) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _fetchState = MutableStateFlow<FetchState>(FetchState.Idle)
    val fetchState: StateFlow<FetchState> = _fetchState.asStateFlow()

    // The UI displays minute-level countdowns, so align one update per minute.
    private val ticker = flow {
        emit(System.currentTimeMillis())
        while (true) {
            val waitMillis = 60_000L - (System.currentTimeMillis() % 60_000L)
            delay(waitMillis.coerceAtLeast(1_000L))
            emit(System.currentTimeMillis())
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val lecturesFlow = c.settings.flow
        .map { it.group }
        .distinctUntilChanged()
        .flatMapLatest { g -> if (g == null) flowOf(emptyList()) else c.db.lectureDao().observeForGroup(g) }

    // Home loads instantly from Room — NO network access happens here.
    val ui: StateFlow<HomeUiState> =
        combine(c.settings.flow, c.db.metaDao().observe(), lecturesFlow, ticker, c.holidayManager.holidays) { s, m, lectures, now, holidays ->
            val zdt = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
            val calendarDow = zdt.dayOfWeek.value
            val todayIso = zdt.toLocalDate().toString()
            // Weekend follow: a free calendar day can run another weekday's lectures.
            val dow = if (
                s.weekendFollowDayIso == todayIso && s.weekendFollowDow in 1..7
            ) s.weekendFollowDow else calendarDow
            val nowMin = zdt.hour * 60 + zdt.minute
            val todayLectures = lectures.filter { it.dayOfWeek == dow }.sortedBy { it.startMinutes }
            HomeUiState(
                loading = false,
                group = s.group,
                lastFetch = m?.lastSuccessfulFetch,
                nowMillis = now,
                status = holidayAwareStatus(lectures, dow, nowMin, holidays, todayIso),
                lectures = lectures,
                upcomingToday = todayLectures.filter { it.startMinutes > nowMin },
                todayLectures = todayLectures,
                freeGapMinutes = NextLectureEngine.freeGapMinutes(lectures, dow, nowMin),
                stale = Formatters.isStale(m?.lastSuccessfulFetch, now),
                // Read from the cached feed, so the home page agrees with the alarms online or not.
                exam = ExamGate.resolve(s, zdt.toLocalDate())
            )
        }.stateIn(scope, SharingStarted.WhileSubscribed(5000), HomeUiState())

    /**
     * The engine's "later" search jumps by day-of-week, so once today's classes
     * are over it happily points the hero at tomorrow's first lecture — even
     * when the college calendar says tomorrow is a full holiday (the home
     * banners say "holiday" while the hero says "Tomorrow"). Route that search
     * through [HolidayGate.nextLectureAfter] instead: it anchors to real dates
     * and skips every full holiday within its horizon, so the next lecture
     * lands on the first actual teaching day. Today's statuses are untouched —
     * a holiday TODAY is already gated off by the home screen's holiday hero.
     */
    private fun holidayAwareStatus(
        lectures: List<LectureEntity>,
        dow: Int,
        nowMin: Int,
        holidays: List<Holiday>,
        todayIso: String
    ): NextLectureEngine.Status = when (val base = NextLectureEngine.compute(lectures, dow, nowMin)) {
        is NextLectureEngine.Status.DoneForToday ->
            NextLectureEngine.Status.DoneForToday(HolidayGate.nextLectureAfter(lectures, holidays, todayIso))
        is NextLectureEngine.Status.FreeDay ->
            NextLectureEngine.Status.FreeDay(HolidayGate.nextLectureAfter(lectures, holidays, todayIso))
        else -> base
    }

    /**
     * Pull-to-refresh. Besides the timetable it fetches the announcement feed,
     * so a date sheet published since the last background run reaches the home
     * page (and pauses the reminders) as soon as the student pulls.
     */
    fun fetchAgain() {
        if (_fetchState.value == FetchState.Running) return
        scope.launch {
            _fetchState.value = FetchState.Running
            _fetchState.value = when (val r = c.refreshManager.refresh(force = true)) {
                is RefreshResult.Success -> FetchState.Ok(r.lecturesForGroup)
                RefreshResult.UpToDate -> FetchState.UpToDate
                is RefreshResult.Failed -> FetchState.Failed(r.reason)
            }
        }
        // Its own launch: a feed problem must not turn an otherwise good refresh into a failure.
        scope.launch { runCatching { c.announcementManager.refreshAndNotify() } }
    }

    fun clearFetchState() { _fetchState.value = FetchState.Idle }

    fun clear() { scope.cancel() }
}
