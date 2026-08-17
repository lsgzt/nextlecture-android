package com.gndec.timetable.ui.home

import com.gndec.timetable.data.db.LectureEntity
import com.gndec.timetable.domain.AppContainer
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
    val upcomingToday: List<LectureEntity> = emptyList(),
    val todayLectures: List<LectureEntity> = emptyList(),
    val freeGapMinutes: Int? = null,
    val stale: Boolean = false
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
        combine(c.settings.flow, c.db.metaDao().observe(), lecturesFlow, ticker) { s, m, lectures, now ->
            val zdt = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
            val dow = zdt.dayOfWeek.value
            val nowMin = zdt.hour * 60 + zdt.minute
            val todayLectures = lectures.filter { it.dayOfWeek == dow }.sortedBy { it.startMinutes }
            HomeUiState(
                loading = false,
                group = s.group,
                lastFetch = m?.lastSuccessfulFetch,
                nowMillis = now,
                status = NextLectureEngine.compute(lectures, dow, nowMin),
                upcomingToday = todayLectures.filter { it.startMinutes > nowMin },
                todayLectures = todayLectures,
                freeGapMinutes = NextLectureEngine.freeGapMinutes(lectures, dow, nowMin),
                stale = Formatters.isStale(m?.lastSuccessfulFetch, now)
            )
        }.stateIn(scope, SharingStarted.WhileSubscribed(5000), HomeUiState())

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
    }

    fun clearFetchState() { _fetchState.value = FetchState.Idle }

    fun clear() { scope.cancel() }
}
