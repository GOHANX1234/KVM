package com.kvm.presentation.apps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kvm.domain.model.AppInfo
import com.kvm.domain.usecase.CloneAppUseCase
import com.kvm.domain.usecase.GetInstalledAppsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppsViewModel @Inject constructor(
    private val getInstalledApps: GetInstalledAppsUseCase,
    private val cloneAppUseCase:  CloneAppUseCase,
) : ViewModel() {

    sealed class Event {
        data class Cloned(val label: String) : Event()
        data class Error(val message: String) : Event()
    }

    private val _apps       = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps.asStateFlow()

    private val _isLoading  = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _events     = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var allApps     = emptyList<AppInfo>()
    private var query       = ""
    private var showSystem  = false

    fun loadApps() {
        viewModelScope.launch {
            _isLoading.update { true }
            runCatching {
                allApps = getInstalledApps(showSystem)
                applyFilter()
            }.onFailure {
                _events.send(Event.Error("Failed to load apps: ${it.message}"))
            }
            _isLoading.update { false }
        }
    }

    fun search(q: String) {
        query = q
        applyFilter()
    }

    fun toggleSystemApps(show: Boolean) {
        showSystem = show
        loadApps()
    }

    fun cloneApp(packageName: String) {
        val app = allApps.find { it.packageName == packageName } ?: return
        viewModelScope.launch {
            when (val result = cloneAppUseCase(packageName)) {
                is CloneAppUseCase.Result.Success ->
                    _events.send(Event.Cloned(app.label))
                is CloneAppUseCase.Result.Error   ->
                    _events.send(Event.Error("Clone failed: ${result.message}"))
            }
        }
    }

    private fun applyFilter() {
        _apps.update {
            if (query.isBlank()) allApps
            else allApps.filter {
                it.label.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
            }
        }
    }
}
