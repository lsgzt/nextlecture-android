package com.gndec.timetable.ui.settings

import android.content.Context
import android.os.PowerManager
import com.gndec.timetable.data.db.TimetableMetaEntity
import com.gndec.timetable.data.prefs.AppSettings
import com.gndec.timetable.domain.AppContainer
import com.gndec.timetable.domain.NotificationHelper
import com.gndec.timetable.domain.RefreshResult
import com.gndec.timetable.net.GroqClient
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

    init { scope.launch { _groups.value = c.db.lectureDao().distinctGroups() } }

    fun changeGroup(g: String) = scope.launch {
        _message.value = if (c.refreshManager.changeGroup(g)) "✓ Group changed to $g — alarms rescheduled"
        else "Group \"$g\" is not in the cached timetable. Fetch first."
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
    fun setBackendUrl(u: String) = scope.launch { c.settings.setBackendUrl(u); _message.value = "Backend URL saved" }
    fun setModel(m: String) = scope.launch { c.settings.setModel(m); _message.value = "Model set to $m" }

    private suspend fun reschedule() {
        val cfg = c.settings.flow.first()
        cfg.group?.let { g -> c.scheduler.rescheduleAll(c.db, g, com.gndec.timetable.domain.ReminderConfig.from(cfg)) }
    }

    fun hasUserKey(): Boolean = c.keys.getGroqKey() != null

    fun saveKey(key: String) {
        if (key.isBlank()) return
        c.keys.setGroqKey(key) // encrypted at rest; never logged, never sent to our backend
        _message.value = "API key saved (stored encrypted on this device)"
    }

    fun removeKey() {
        c.keys.removeGroqKey()
        _message.value = "API key removed"
    }

    fun testKey() = scope.launch {
        val key = c.keys.getGroqKey()
        if (key == null) { _message.value = "Enter and save a Groq API key first"; return@launch }
        val model = settings.value.model
        _busy.value = true
        _message.value = "Testing API key…"
        val (keyOk, modelOk) = GroqClient().test(key, model)
        _message.value = buildString {
            append(if (keyOk) "✓ API key works" else "✕ API key rejected by Groq")
            if (keyOk) append("\n" + (if (modelOk) "✓ Model available ($model)"
            else "✕ Selected model unavailable — please choose another model"))
        }.trim()
        _busy.value = false
        if (keyOk && !modelOk) refreshModels()
    }

    fun refreshModels() = scope.launch {
        val key = c.keys.getGroqKey()
        if (key == null) {
            _message.value = "A Groq API key is required to list available models"
            return@launch
        }
        _busy.value = true
        try {
            _models.value = GroqClient().listModels(key)
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
