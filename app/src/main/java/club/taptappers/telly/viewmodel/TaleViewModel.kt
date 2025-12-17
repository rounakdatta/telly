package club.taptappers.telly.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import club.taptappers.telly.data.model.Tale
import club.taptappers.telly.data.model.TaleLog
import club.taptappers.telly.data.repository.TaleRepository
import club.taptappers.telly.worker.TaleScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TaleViewModel @Inject constructor(
    private val repository: TaleRepository,
    private val scheduler: TaleScheduler
) : ViewModel() {

    val tales: StateFlow<List<Tale>> = repository.getAllTales()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedTale = MutableStateFlow<Tale?>(null)
    val selectedTale: StateFlow<Tale?> = _selectedTale.asStateFlow()

    private val _selectedTaleLogs = MutableStateFlow<List<TaleLog>>(emptyList())
    val selectedTaleLogs: StateFlow<List<TaleLog>> = _selectedTaleLogs.asStateFlow()

    fun selectTale(taleId: String) {
        viewModelScope.launch {
            _selectedTale.value = repository.getTaleById(taleId)
        }
        viewModelScope.launch {
            repository.getLogsForTale(taleId).collect { logs ->
                _selectedTaleLogs.value = logs
            }
        }
    }

    fun clearSelectedTale() {
        _selectedTale.value = null
        _selectedTaleLogs.value = emptyList()
    }

    fun createTale(tale: Tale) {
        viewModelScope.launch {
            repository.insertTale(tale)
            scheduler.scheduleTale(tale)
        }
    }

    fun updateTale(tale: Tale) {
        viewModelScope.launch {
            repository.updateTale(tale)
            scheduler.scheduleTale(tale)
            _selectedTale.value = tale
        }
    }

    fun deleteTale(tale: Tale) {
        viewModelScope.launch {
            scheduler.cancelTale(tale.id)
            repository.deleteTale(tale)
            clearSelectedTale()
        }
    }

    fun toggleTaleEnabled(tale: Tale, enabled: Boolean) {
        viewModelScope.launch {
            repository.setTaleEnabled(tale.id, enabled)
            val updatedTale = tale.copy(isEnabled = enabled)
            scheduler.scheduleTale(updatedTale)
            if (_selectedTale.value?.id == tale.id) {
                _selectedTale.value = updatedTale
            }
        }
    }

    fun runTaleNow(tale: Tale) {
        scheduler.runTaleNow(tale)
    }
}
