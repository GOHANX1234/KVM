package com.kvm.core.engine

import android.content.Context
import com.kvm.core.hook.BinderHookManager
import com.kvm.core.hook.InstrumentationHook
import com.kvm.core.virtual.VirtualActivityManager
import com.kvm.core.virtual.VirtualAppKey
import com.kvm.core.virtual.VirtualFileSystem
import com.kvm.core.virtual.VirtualPackageManager
import com.kvm.core.virtual.VirtualProcessManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * KVMEngine — the root coordinator of the virtual space.
 *
 * Lifecycle:
 *   1. [initialize] — called once from KVMApplication.onCreate()
 *   2. [startVirtualApp] — called per virtual app launch
 *   3. [stopVirtualApp] / [stopAllApps] — teardown
 *
 * All subsystems are injected via Hilt; KVMEngine wires them together and
 * enforces the correct initialization order.
 *
 * Crash-safety: [initialize] wraps its entire body in a try-catch so that
 * NO exception — including UnsatisfiedLinkError from native methods — can
 * propagate out and kill the process in Application.onCreate().  Individual
 * subsystem failures are logged and skipped; the engine marks itself as
 * initialized in degraded mode rather than crashing.
 */
@Singleton
class KVMEngine @Inject constructor(
    private val virtualPackageManager : VirtualPackageManager,
    private val virtualActivityManager: VirtualActivityManager,
    private val virtualFileSystem     : VirtualFileSystem,
    private val virtualProcessManager : VirtualProcessManager,
    private val binderHookManager     : BinderHookManager,
    private val instrumentationHook   : InstrumentationHook,
) {
    private var initialized = false
    private var nativeReady = false
    private lateinit var appContext: Context
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Initialize the engine.  Safe to call only once; subsequent calls are no-ops.
     *
     * Any uncaught exception is caught here so the process never dies in
     * Application.onCreate() due to an engine error.
     */
    fun initialize(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        Timber.i("KVMEngine: initializing…")

        try {
            val dataRoot = "${context.dataDir.absolutePath}/virtual"

            // 1. Boot native layer — use the safe wrapper so an unloaded library
            //    does not throw UnsatisfiedLinkError.
            nativeReady = KVMNative.safeNativeInit(context.packageName, dataRoot)
            Timber.i("KVMEngine: native init %s  (library loaded=%s)",
                if (nativeReady) "OK" else "FAILED/SKIPPED",
                KVMNative.isLibraryLoaded)

            // 2. Prepare VFS root directory structure
            virtualFileSystem.initRoot(dataRoot)

            // 3. Install Java-level Binder proxies — replaces ServiceManager stubs
            //    so every IPC call from a guest app goes through our interceptors.
            runCatching { binderHookManager.installHooks(context) }
                .onFailure { Timber.e(it, "KVMEngine: BinderHookManager setup failed") }

            // 4. Hook Instrumentation so AMS's newActivity() is intercepted and
            //    the StubActivity is swapped for the real guest Activity.
            runCatching { instrumentationHook.install(context) }
                .onFailure { Timber.e(it, "KVMEngine: InstrumentationHook setup failed") }

            // 4b. Reload the virtual-app registry from Room — required in every
            //     process (this Application.onCreate runs in the main process AND
            //     every stub process :p0-:p4), otherwise VirtualPackageManager's
            //     in-memory registry is empty until an app is freshly cloned.
            engineScope.launch {
                runCatching { virtualPackageManager.loadFromDatabase(context) }
                    .onFailure { Timber.e(it, "KVMEngine: failed to reload virtual app registry") }
            }

            // 5. Restore previously-running virtual apps (after a reboot or crash).
            runCatching { virtualProcessManager.restoreRunningApps() }
                .onFailure { Timber.e(it, "KVMEngine: failed to restore running apps") }

            initialized = true
            Timber.i("KVMEngine: initialized (native=%s)", if (nativeReady) "active" else "degraded")

        } catch (t: Throwable) {
            // Catch-all: ensures Application.onCreate() NEVER crashes due to engine errors.
            // The engine is marked initialized in degraded mode so the UI can still launch.
            initialized = true
            Timber.e(t, "KVMEngine: initialization failed — running in degraded mode")
        }
    }

    /** Returns true if the engine is ready. */
    val isReady: Boolean get() = initialized

    /** Returns true if the native library loaded and nativeInit succeeded. */
    val isNativeReady: Boolean get() = nativeReady

    /**
     * Launch a virtual app identified by [packageName] with instance [userId].
     * [userId] = "0" for the primary clone; "1", "2", … for additional instances.
     */
    suspend fun startVirtualApp(packageName: String, userId: String = "0") {
        check(initialized) { "KVMEngine not initialized" }
        val key = VirtualAppKey(packageName, userId.toIntOrNull() ?: 0)
        val loaded = virtualProcessManager.startVirtualApp(appContext, key)
        if (!loaded) {
            Timber.e("KVMEngine: aborting launch — failed to load %s", packageName)
            return
        }
        virtualActivityManager.launchApp(packageName, userId, appContext)
    }

    fun stopVirtualApp(packageName: String, userId: String = "0") {
        virtualProcessManager.killVirtualProcess(packageName, userId.toIntOrNull() ?: 0)
    }

    fun stopAllApps() {
        virtualProcessManager.killAll()
    }
}
