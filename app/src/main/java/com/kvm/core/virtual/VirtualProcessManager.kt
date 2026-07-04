package com.kvm.core.virtual

import android.content.Context
import com.kvm.core.engine.KVMNative
import com.kvm.core.loader.VirtualApkLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * VirtualProcessManager (VPM)
 *
 * Manages the pool of stub processes (:p0 … :p4) declared in the manifest.
 * Each stub process can host one or more virtual app instances.
 *
 * Responsibilities:
 *  - Track which virtual app is assigned to which stub process
 *  - Install VFS native hooks before guest code runs in a process
 *  - Maintain runtime state per virtual app
 *  - Kill processes on demand (uninstall / user request)
 *  - Restore auto-start apps after device boot
 *
 * Crash-safety: All [KVMNative] calls use the safe wrapper variants so that
 * an unloaded native library never causes an UnsatisfiedLinkError here.
 */
@Singleton
class VirtualProcessManager @Inject constructor(
    private val virtualPackageManager: VirtualPackageManager,
    private val virtualFileSystem    : VirtualFileSystem,
    private val apkLoader            : VirtualApkLoader,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** processName → list of keys currently hosted in it */
    private val processMap = ConcurrentHashMap<String, MutableList<VirtualAppKey>>()

    /** Per-key runtime state */
    private val stateMap = ConcurrentHashMap<VirtualAppKey, VirtualAppState>()

    private val _runningApps = MutableStateFlow<Map<VirtualAppKey, VirtualAppState>>(emptyMap())
    val runningApps: StateFlow<Map<VirtualAppKey, VirtualAppState>> = _runningApps.asStateFlow()

    companion object {
        private val STUB_PROCESSES = listOf(":p0", ":p1", ":p2", ":p3", ":p4")
        private const val MAX_APPS_PER_PROCESS = 3
    }

    // ── Process allocation ────────────────────────────────────────────────────

    /**
     * Assign a stub process name for a new virtual app launch.
     * Prefers a process that already hosts apps of the same package (app affinity).
     */
    fun allocateProcess(key: VirtualAppKey): String {
        for ((proc, keys) in processMap) {
            if (keys.any { it.packageName == key.packageName } &&
                keys.size < MAX_APPS_PER_PROCESS) {
                keys.add(key)
                return proc
            }
        }
        val proc = STUB_PROCESSES.minByOrNull { processMap[it]?.size ?: 0 }
            ?: STUB_PROCESSES.first()
        processMap.getOrPut(proc) { mutableListOf() }.add(key)
        return proc
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Prepare and start a virtual app.
     *  1. Provision isolated directories
     *  2. Install VFS native hooks
     *  3. Load the guest APK via VirtualApkLoader
     *  4. Transition state to RUNNING
     */
    suspend fun startVirtualApp(context: Context, key: VirtualAppKey): Boolean = withContext(Dispatchers.IO) {
        val app = virtualPackageManager.getVirtualApp(key.packageName, key.userId)
            ?: run {
                Timber.e("VPM: unknown app %s", key)
                return@withContext false
            }

        updateState(key, VirtualAppState.STARTING)

        // 1. Provision storage
        virtualFileSystem.provisionInstance(key.packageName, key.userId)

        // 2. Install VFS hooks for this instance — uses safe wrapper (no UnsatisfiedLinkError)
        val hooksOk = KVMNative.safeInstallVfsHooks(
            hostPackage     = context.packageName,
            guestPackage    = key.packageName,
            userId          = key.userId.toString(),
            virtualDataRoot = "${context.dataDir.absolutePath}/virtual"
        )
        if (!hooksOk) {
            Timber.w("VPM: VFS hook installation failed for %s (falling back to Java-level redirect)", key)
        }

        // 3. Load the guest APK into this process (no-op if already loaded here)
        val loaded = runCatching {
            if (!com.kvm.core.loader.ClassLoaderManager.isLoaded(key.packageName)) {
                apkLoader.loadApk(
                    context      = context,
                    apkPath      = app.apkPath,
                    nativeLibDir = app.nativeLibDir,
                    optimizedDir = virtualFileSystem.getOdexDir(key.packageName)?.absolutePath ?: "",
                    packageName  = key.packageName,
                    userId       = key.userId,
                )
            }
        }

        if (loaded.isSuccess) {
            updateState(key, VirtualAppState.RUNNING)
            Timber.i("VPM: started %s (userId=%d)", key.packageName, key.userId)
            true
        } else {
            updateState(key, VirtualAppState.CRASHED)
            Timber.e(loaded.exceptionOrNull(), "VPM: failed to start %s", key)
            false
        }
    }

    fun killVirtualProcess(packageName: String, userId: Int) {
        val key = VirtualAppKey(packageName, userId)
        val proc = processMap.entries.find { it.value.contains(key) }?.key
        processMap[proc]?.remove(key)
        stateMap.remove(key)
        updateStateFlow()

        // Remove VFS hooks for this instance — safe wrapper handles unloaded library
        KVMNative.safeRemoveVfsHooks()
        Timber.i("VPM: killed %s", key)
    }

    fun killAll() {
        stateMap.keys.toList().forEach { key ->
            killVirtualProcess(key.packageName, key.userId)
        }
    }

    /** Called at engine init — restore auto-start apps. */
    fun restoreRunningApps() {
        scope.launch {
            val autoStartApps = virtualPackageManager.getAllVirtualApps()
                .filter { it.autoStart }
            Timber.i("VPM: restoring %d auto-start apps", autoStartApps.size)
            // Actual launch deferred to UI context; just log here.
        }
    }

    // ── State helpers ─────────────────────────────────────────────────────────

    private fun updateState(key: VirtualAppKey, state: VirtualAppState) {
        stateMap[key] = state
        updateStateFlow()
    }

    private fun updateStateFlow() {
        _runningApps.update { stateMap.toMap() }
    }

    fun getState(key: VirtualAppKey): VirtualAppState =
        stateMap[key] ?: VirtualAppState.STOPPED
}
