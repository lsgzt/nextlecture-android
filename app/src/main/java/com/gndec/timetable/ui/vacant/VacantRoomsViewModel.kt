package com.gndec.timetable.ui.vacant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gndec.timetable.domain.AppContainer
import com.gndec.timetable.domain.VacantRoomsManager
import com.gndec.timetable.domain.VacantRoomsState
import com.gndec.timetable.parse.GlobalRoomData
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Bridges [VacantRoomsManager] into Compose; selection state lives in the screen. */
class VacantRoomsViewModel(private val container: AppContainer) : ViewModel() {

    val state: StateFlow<VacantRoomsState> = container.vacantRoomsManager.state

    fun open() {
        viewModelScope.launch { container.vacantRoomsManager.ensureLoaded() }
    }

    fun forceRefresh() {
        viewModelScope.launch { container.vacantRoomsManager.refresh(force = true) }
    }

    companion object {
        /** Default day chip when the screen opens. */
        fun defaultDay(): Int = VacantRoomsManager.defaultDayIndex()

        /** Default slot chip when the screen opens. */
        fun defaultSlot(data: GlobalRoomData): Int =
            VacantRoomsManager.defaultSlotIndex(data.slotStarts)
    }
}
