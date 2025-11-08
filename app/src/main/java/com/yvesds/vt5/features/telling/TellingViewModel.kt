package com.yvesds.vt5.features.telling

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.yvesds.vt5.features.telling.TellingScherm.SoortRow
import com.yvesds.vt5.net.ServerTellingDataItem

/**
 * Lightweight ViewModel to hold UI state across configuration changes.
 * - tiles: current list of SoortRow shown in tiles
 * - partials/finals: speech log lists
 * - pendingRecords: collected ServerTellingDataItem list (for Afronden)
 *
 * This ViewModel is intentionally simple: it stores immutable lists and
 * exposes helper functions to update/append/clear. TellingScherm observes
 * these LiveData properties and updates adapters accordingly.
 */
class TellingViewModel : ViewModel() {

    private val _tiles = MutableLiveData<List<SoortRow>>(emptyList())
    val tiles: LiveData<List<SoortRow>> = _tiles

    private val _partials = MutableLiveData<List<TellingScherm.SpeechLogRow>>(emptyList())
    val partials: LiveData<List<TellingScherm.SpeechLogRow>> = _partials

    private val _finals = MutableLiveData<List<TellingScherm.SpeechLogRow>>(emptyList())
    val finals: LiveData<List<TellingScherm.SpeechLogRow>> = _finals

    private val _pendingRecords = MutableLiveData<List<ServerTellingDataItem>>(emptyList())
    val pendingRecords: LiveData<List<ServerTellingDataItem>> = _pendingRecords

    fun setTiles(list: List<SoortRow>) { _tiles.value = list }
    fun updateTiles(list: List<SoortRow>) { _tiles.value = list }
    fun clearTiles() { _tiles.value = emptyList() }

    fun setPartials(list: List<TellingScherm.SpeechLogRow>) { _partials.value = list }
    fun appendPartial(row: TellingScherm.SpeechLogRow) { _partials.value = (_partials.value ?: emptyList()) + row }
    fun clearPartials() { _partials.value = emptyList() }

    fun setFinals(list: List<TellingScherm.SpeechLogRow>) { _finals.value = list }
    fun appendFinal(row: TellingScherm.SpeechLogRow) { _finals.value = (_finals.value ?: emptyList()) + row }
    fun clearFinals() { _finals.value = emptyList() }

    fun addPendingRecord(item: ServerTellingDataItem) { _pendingRecords.value = (_pendingRecords.value ?: emptyList()) + item }
    fun setPendingRecords(list: List<ServerTellingDataItem>) { _pendingRecords.value = list }
    fun clearPendingRecords() { _pendingRecords.value = emptyList() }
}