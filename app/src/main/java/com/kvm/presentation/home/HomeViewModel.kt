package com.kvm.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kvm.core.virtual.VirtualApp
import com.kvm.core.virtual.VirtualPackageManager
import com.kvm.domain.usecase.CloneAppUseCase
import com.kvm.domain.usecase.LaunchVirtualAppUseCase
import com.kvm.domain.usecase.UninstallVirtualAppUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val vpm:          VirtualPackageManager,
    private val launchApp:    LaunchVirtualAppUseCase,
    private val cloneApp:     CloneAppUseCase,
    private val uninstallApp: UninstallVirtualAppUseCase,
) : ViewModel() {

    sealed class UiEvent {
        object AppLaunched : UiEvent()
        data class Error(val message: String) : UiEvent()
        object AppUninstalled : UiEvent()
    }

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val uiEvents = _events.receiveAsFlow()

    val virtualApps = vpm.observeInstalledApps()
        .map { entities ->
            entities.map { e ->
                vpm.getVirtualApp(e.packageName, e.userId)
                    ?: com.kvm.core.virtual.VirtualApp(
                        id           = e.id,
                        packageName  = e.packageName,
                        userId       = e.userId,
                        apkPath      = e.apkPath,
                        nativeLibDir = e.nativeLibDir,
                        dataDir      = e.dataDir,
                        label        = e.label,
                        versionName  = e.versionName,
                        versionCode  = e.versionCode,
                        targetSdk    = e.targetSdk,
                        minSdk       = e.minSdk,
                        autoStart    = e.autoStart,
                        installedAt  = e.installedAt,
                    )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun launchVirtualApp(packageName: String, userId: Int) {
        viewModelScope.launch {
            runCatching { launchApp(packageName, userId) }
                .onSuccess { _events.send(UiEvent.AppLaunched) }
                .onFailure { _events.send(UiEvent.Error("Failed to launch: ${it.message}")) }
        }
    }

    fun addInstance(packageName: String) {
        val nextUserId = (vpm.getAllVirtualApps()
            .filter { it.packageName == packageName }
            .maxOfOrNull { it.userId } ?: -1) + 1

        viewModelScope.launch {
            when (val result = cloneApp(packageName, nextUserId)) {
                is CloneAppUseCase.Result.Success ->
                    _events.send(UiEvent.AppLaunched)
                is CloneAppUseCase.Result.Error   ->
                    _events.send(UiEvent.Error("Failed to add instance: ${result.message}"))
            }
        }
    }

    fun uninstall(app: VirtualApp) {
        viewModelScope.launch {
            runCatching { uninstallApp(app.packageName, app.userId) }
                .onSuccess  { _events.send(UiEvent.AppUninstalled) }
                .onFailure  { _events.send(UiEvent.Error("Uninstall failed: ${it.message}")) }
        }
    }
}
