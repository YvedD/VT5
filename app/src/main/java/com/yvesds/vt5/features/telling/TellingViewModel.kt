package com.yvesds.vt5.features.telling

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yvesds.vt5.features.telling.TellingScherm.SoortRow
import com.yvesds.vt5.net.ServerTellingDataItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel that coordinates with RecordsBeheer for collecting finals and exposes LiveData for UI.
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

    // Expose repository error messages as single LiveData string (UI can display Toast)
    private val _repoError = MutableLiveData<String?>(null)
    val repoError: LiveData<String?> = _repoError

    // RecordsBeheer injected at runtime
    private lateinit var recordsBeheer: RecordsBeheer
    private var recordsCollectorJob: Job? = null

    fun setRecordsBeheer(rb: RecordsBeheer) {
        this.recordsBeheer = rb

        // cancel old collector (if any) then collect new flow to keep LiveData up-to-date
        recordsCollectorJob?.cancel()
        recordsCollectorJob = viewModelScope.launch {
            rb.pendingRecordsFlow.collect { list ->
                _pendingRecords.postValue(list)
            }
        }
        // seed current list
        _pendingRecords.value = rb.getPendingRecordsSnapshot()
    }

    fun setTiles(list: List<SoortRow>) { _tiles.value = list }
    fun updateTiles(list: List<SoortRow>) { _tiles.value = list }
    fun clearTiles() { _tiles.value = emptyList() }

    fun setPartials(list: List<TellingScherm.SpeechLogRow>) { _partials.value = list }
    fun appendPartial(row: TellingScherm.SpeechLogRow) { _partials.value = (_partials.value ?: emptyList()) + row }
    fun clearPartials() { _partials.value = emptyList() }

    fun setFinals(list: List<TellingScherm.SpeechLogRow>) { _finals.value = list }
    fun appendFinal(row: TellingScherm.SpeechLogRow) { _finals.value = (_finals.value ?: emptyList()) + row }
    fun clearFinals() { _finals.value = emptyList() }

    /**
     * Collect a final via the repository. The ViewModel orchestrates the call and updates LiveData.
     * UI should call this (keeps Activity thin).
     */
    fun collectFinal(soortId: String, amount: Int, explicitTijdstipSeconds: Long? = null) {
        if (!::recordsBeheer.isInitialized) {
            _repoError.value = "Repository niet geïnitialiseerd"
            return
        }

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                recordsBeheer.collectFinalAsRecord(soortId, amount, explicitTijdstipSeconds)
            }
            when (result) {
                is OperationResult.Success -> {
                    // pendingRecordsFlow will update LiveData via collector; clear any previous error
                    _repoError.value = null
                }
                is OperationResult.Failure -> {
                    _repoError.value = result.reason
                }
            }
        }
    }

    // allow Activity to directly append a pending record (compat)
    fun addPendingRecord(item: ServerTellingDataItem) {
        _pendingRecords.value = (_pendingRecords.value ?: emptyList()) + item
    }

    fun setPendingRecords(list: List<ServerTellingDataItem>) { _pendingRecords.value = list }
    fun clearPendingRecords() { _pendingRecords.value = emptyList() }
}