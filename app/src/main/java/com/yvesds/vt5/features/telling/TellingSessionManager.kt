package com.yvesds.vt5.features.telling

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Houdt de (tijdelijke) preselectie bij die je maakt in SoortSelectieScherm,
 * zodat TellingScherm ze kan overnemen. Later kan je hier ook header/onlineid aan toevoegen.
 */
object TellingSessionManager {

    data class PreselectState(
        val telpostId: String? = null,
        val selectedSoortIds: List<String> = emptyList()
    )

    private val _preselectState = MutableStateFlow(PreselectState())
    val preselectState: StateFlow<PreselectState> = _preselectState

    fun setTelpost(telpostId: String) {
        _preselectState.value = _preselectState.value.copy(telpostId = telpostId)
    }

    fun setPreselectedSoorten(ids: List<String>) {
        _preselectState.value = _preselectState.value.copy(selectedSoortIds = ids.distinct())
    }

    fun clear() {
        _preselectState.value = PreselectState()
    }
}