package com.gndec.timetable.ui.settings

import android.content.Context
import android.os.PowerManager
import com.gndec.timetable.data.db.TimetableMetaEntity
import com.gndec.timetable.data.prefs.AppSettings
import com.gndec.timetable.domain.AppContainer
import com.gndec.timetable.domain.GroupTimetableManager
import com.gndec.timetable.domain.NotificationHelper
import com.gndec.timetable.domain.RefreshResult
import com.gndec.timetable.net.GeminiClient
import com.gndec.timetable.parse.GroupMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ReliabilityStatus(
    val notificationsEnabled: Boolean,
    val exactAlarms: Boolean,
    val timetableCached: Boolean,
    val scheduledReminders: Int,
    val batteryUnrestricted: Boolean
)

/**
 * Live departmental section catalog for 2nd/3rd/4th-year students. Sections
 * come from the OFFICIAL document discovered on the department's own site —
 * never from the cached lecture database (which may still hold another
 * source's groups, e.g. the 1st-year appsc document).
 */
data class SeniorCatalogState(
    val loading: Boolean = false,
    /** Every group name in the validated official document. */
    val groups: List<String> = emptyList(),
    /** URL of the official document the sections come from. */
    val url: String = "",
    val fromCache: Boolean = false,
    val error: String? = null
)

class SettingsViewModel(private val c: AppContainer) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings: StateFlow<AppSettings> =
        c.settings.flow.stateIn(scope, SharingStarted.Eagerly, AppSettings())
    val meta: StateFlow<TimetableMetaEntity?> =
        c.db.metaDao().observe().stateIn(scope, SharingStarted.Eagerly, null)

    private val _groups = MutableStateFlow<List<String>>(emptyList())
    val groups = _groups.asStateFlow()
    private val _models = MutableStateFlow<List<String>>(emptyList())
    val models = _models.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _reliability = MutableStateFlow<ReliabilityStatus?>(null)
    val reliability = _reliability.asStateFlow()
    private val _seniorCatalog = MutableStateFlow(SeniorCatalogState())
    val seniorCatalog = _seniorCatalog.asStateFlow()
    val releaseUpdate = c.releaseUpdateManager.state.stateIn(scope, SharingStarted.Eagerly, com.gndec.timetable.domain.ReleaseUpdateState())

    init { scope.launch { _groups.value = c.db.lectureDao().distinctGroups() } }

    fun changeGroup(g: String) = scope.launch {
        _message.value = if (c.refreshManager.changeGroup(g)) "✓ Group changed to $g — alarms rescheduled"
        else "Group \"$g\" is not in the cached timetable. Fetch first."
    }

    /**
     * Loads the official departmental sections for the settings screen's
     * group picker. [force] re-downloads the document so a freshly published
     * revision (the college republishes weekly under a new name) shows up.
     */
    fun loadSeniorCatalog(force: Boolean) {
        val cfg = settings.value
        if (cfg.academicYear < 2) return
        scope.launch {
            _seniorCatalog.value = _seniorCatalog.value.copy(loading = true, error = null)
            _seniorCatalog.value = when (val r = c.groupTimetableManager.load(cfg.branch, cfg.academicYear, force)) {
                is GroupTimetableManager.CatalogResult.Ready ->
                    SeniorCatalogState(loading = false, groups = r.groups, url = r.url, fromCache = r.fromCache)
                is GroupTimetableManager.CatalogResult.Failed ->
                    SeniorCatalogState(loading = false, groups = r.cached, error = r.reason)
            }
        }
    }

    /**
     * Senior group change: links the picked OFFICIAL section (its name also
     * carries the academic year, "D3…"), then validates it against a freshly
     * downloaded departmental document before committing. Never silently
     * keeps serving the previous (possibly 1st-year) source without saying so.
     */
    fun changeSeniorGroup(group: String) = scope.launch {
        _busy.value = true
        val cfg = settings.value
        val year = GroupMatcher.parseGroup(group).year ?: cfg.academicYear.coerceIn(2, 4)
        c.keys.removeAttendanceSession()
        c.settings.setAcademicYear(year)
        c.settings.saveStudentProfile(
            cfg.studentName, cfg.rollNumber, cfg.branch, cfg.registrationNumber,
            cfg.fatherName, cfg.motherName, cfg.mentorName,
            cfg.studentSection, group, "",
            cfg.mentorMobile, cfg.mentorVenue,
            "manual_departmental"
        )
        val refreshed = c.refreshManager.refresh(force = true, expectedGroup = group)
        val linked = runCatching { c.refreshManager.changeGroup(group) }.getOrDefault(false)
        _message.value = when {
            linked -> "✓ Group changed to $group — alarms rescheduled"
            refreshed is RefreshResult.Failed ->
                "Section $group saved, but the official timetable could not be downloaded " +
                    "(${refreshed.reason}) The previously cached timetable is still shown. " +
                    "Tap Fetch again once you are online."
            else ->
                "Section \"$group\" is not present in the downloaded official document. " +
                    "Reload the sections and pick again."
        }
        _busy.value = false
    }

    fun fetchAgain() = scope.launch {
        _busy.value = true
        _message.value = when (val r = c.refreshManager.refresh(force = true)) {
            is RefreshResult.Success -> "✓ Timetable updated (${r.lecturesForGroup} lectures for your group)"
            RefreshResult.UpToDate -> "✓ Timetable already up to date"
            is RefreshResult.Failed -> "Couldn't update timetable. Your previous timetable is still being used. (${r.reason})"
        }
        _busy.value = false
        _groups.value = c.db.lectureDao().distinctGroups()
    }

    fun setRemind15(b: Boolean) = scope.launch { c.settings.setRemind15(b); reschedule() }
    fun setRemind30(b: Boolean) = scope.launch { c.settings.setRemind30(b); reschedule() }
    fun setRemind5(b: Boolean) = scope.launch { c.settings.setRemind5(b); reschedule() }
    fun setRemindAtStart(b: Boolean) = scope.launch { c.settings.setRemindAtStart(b); reschedule() }
    fun setAiEnabled(b: Boolean) = scope.launch { c.settings.setAiEnabled(b) }
    fun setThemeMode(m: String) = scope.launch { c.settings.setThemeMode(m) }
    fun setAnnouncementNotifications(enabled: Boolean) = scope.launch { c.settings.setAnnouncementNotifications(enabled) }
    fun checkAnnouncements() = scope.launch {
        _busy.value = true
        c.announcementManager.refreshAndNotify()
        _message.value = "Announcement feed checked"
        _busy.value = false
    }
    fun checkForUpdates() = scope.launch {
        _busy.value = true
        val result = c.releaseUpdateManager.checkForUpdates(force = true)
        _message.value = when {
            result.error != null -> "Couldn’t check GitHub releases"
            result.updateAvailable -> "Update ${result.latestMarker} is available"
            else -> "You are using the latest release"
        }
        _busy.value = false
    }
    fun setBackendUrl(u: String) = scope.launch { c.settings.setBackendUrl(u); _message.value = "Backend URL saved" }
    fun setPyqRagBackendUrl(u: String) = scope.launch { c.settings.setPyqRagBackendUrl(u); _message.value = "PYQ analysis URL saved" }
    fun setModel(m: String) = scope.launch { c.settings.setModel(m); _message.value = "Model set to $m" }

    private suspend fun reschedule() {
        val cfg = c.settings.flow.first()
        cfg.group?.let { g -> c.scheduler.rescheduleAll(c.db, g, com.gndec.timetable.domain.ReminderConfig.from(cfg)) }
    }

    fun hasUserKey(): Boolean = c.keys.getGeminiKey() != null

    fun saveKey(key: String) {
        if (key.isBlank()) return
        c.keys.setGeminiKey(key) // encrypted at rest; never logged, never sent to our backend
        _message.value = "API key saved (stored encrypted on this device)"
    }

    fun removeKey() {
        c.keys.removeGeminiKey()
        _message.value = "API key removed"
    }

    fun testKey() = scope.launch {
        val key = c.keys.getGeminiKey()
        if (key == null) { _message.value = "Enter and save a Gemini API key first"; return@launch }
        val model = settings.value.model
        _busy.value = true
        _message.value = "Testing API key…"
        val (keyOk, modelOk) = GeminiClient().test(key, model)
        _message.value = buildString {
            append(if (keyOk) "✓ API key works" else "✕ API key rejected by Gemini")
            if (keyOk) append("\n" + (if (modelOk) "✓ Model available ($model)"
            else "✕ Selected model unavailable — please choose another model"))
        }.trim()
        _busy.value = false
        if (keyOk && !modelOk) refreshModels()
    }

    fun refreshModels() = scope.launch {
        val key = c.keys.getGeminiKey()
        if (key == null) {
            _message.value = "A Gemini API key is required to list available models"
            return@launch
        }
        _busy.value = true
        try {
            _models.value = GeminiClient().listModels(key)
            if (_models.value.isNotEmpty()) _message.value = "✓ ${_models.value.size} models loaded"
        } catch (e: Exception) {
            _message.value = "Couldn't fetch models (${e.message})"
        }
        _busy.value = false
    }

    fun scheduleTestNotification(delayMinutes: Int) = scope.launch {
        _message.value = if (c.scheduler.scheduleTestNotification(delayMinutes)) {
            "✓ Test notification scheduled for $delayMinutes minute${if (delayMinutes == 1) "" else "s"} from now"
        } else {
            "Couldn't schedule the test notification. Check alarm permission in system settings."
        }
    }

    fun runReliabilityCheck() = scope.launch {
        val ctx = c.context
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        _reliability.value = ReliabilityStatus(
            notificationsEnabled = NotificationHelper.notificationsEnabled(ctx),
            exactAlarms = c.scheduler.canScheduleExact(),
            timetableCached = c.db.lectureDao().countAll() > 0,
            scheduledReminders = c.db.alarmDao().countFuture(System.currentTimeMillis()),
            batteryUnrestricted = pm.isIgnoringBatteryOptimizations(ctx.packageName)
        )
    }

    fun clearMessage() { _message.value = null }
    fun clear() { scope.cancel() }
}
