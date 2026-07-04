package com.kvm.core.virtual

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.kvm.data.repository.VirtualAppRepository
import kotlinx.coroutines.flow.Flow
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * VirtualPackageManager (VPM)
 *
 * Maintains the registry of all apps installed inside the virtual space.
 * Acts as the in-memory source of truth; the Room database persists state
 * across process restarts.
 *
 * When a guest app calls PackageManager APIs (getPackageInfo, queryIntentActivities …),
 * the Binder proxy layer redirects those calls to [handleGuestPmQuery] so we can
 * answer with virtual-space data instead of the real system PMS data.
 */
@Singleton
class VirtualPackageManager @Inject constructor(
    private val repository: VirtualAppRepository,
) {
    /** In-memory registry: key → VirtualApp.  Populated at engine init time. */
    private val registry = ConcurrentHashMap<VirtualAppKey, VirtualApp>()

    // ── Registry access ───────────────────────────────────────────────────────

    fun getVirtualApp(packageName: String, userId: Int = 0): VirtualApp? =
        registry[VirtualAppKey(packageName, userId)]

    fun getAllVirtualApps(): List<VirtualApp> = registry.values.toList()

    fun isVirtualAppInstalled(packageName: String, userId: Int = 0): Boolean =
        registry.containsKey(VirtualAppKey(packageName, userId))

    /** Observe installed virtual apps as a Flow (Room-backed). */
    fun observeInstalledApps(): Flow<List<com.kvm.data.db.entities.VirtualAppEntity>> =
        repository.observeAll()

    // ── Install / uninstall ───────────────────────────────────────────────────

    /**
     * Register a newly installed virtual app.
     * The APK must already be copied to [apkPath] before calling this.
     *
     * @return the [VirtualApp] added to the registry.
     */
    suspend fun installVirtualApp(
        context:     Context,
        apkPath:     String,
        packageName: String,
        userId:      Int,
        dataDir:     String,
        nativeLibDir:String,
    ): VirtualApp {
        val pm = context.packageManager
        val pi = getPackageInfoCompat(pm, apkPath)
            ?: error("Cannot parse APK at $apkPath")

        val app = VirtualApp(
            id           = 0L,   // Room auto-generates
            packageName  = packageName,
            userId       = userId,
            apkPath      = apkPath,
            nativeLibDir = nativeLibDir,
            dataDir      = dataDir,
            label        = pi.applicationInfo?.loadLabel(pm)?.toString() ?: packageName,
            versionName  = pi.versionName ?: "?",
            versionCode  = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                               pi.longVersionCode
                           else
                               @Suppress("DEPRECATION") pi.versionCode.toLong(),
            targetSdk    = pi.applicationInfo?.targetSdkVersion ?: 0,
            minSdk       = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                               pi.applicationInfo?.minSdkVersion ?: 0
                           else 0,
            packageInfo  = pi,
            applicationInfo = pi.applicationInfo,
            activities   = pi.activities?.toList() ?: emptyList(),
        )

        // Persist to Room
        val persisted = repository.insert(app)
        val registered = app.copy(id = persisted)
        registry[VirtualAppKey(packageName, userId)] = registered

        Timber.i("VPM: installed %s (userId=%d)", packageName, userId)
        return registered
    }

    suspend fun uninstallVirtualApp(packageName: String, userId: Int) {
        registry.remove(VirtualAppKey(packageName, userId))
        repository.delete(packageName, userId)
        Timber.i("VPM: uninstalled %s (userId=%d)", packageName, userId)
    }

    /** Load all persisted apps from Room into the in-memory registry. */
    suspend fun loadFromDatabase(context: Context) {
        val entities = repository.getAll()
        val pm = context.packageManager
        entities.forEach { entity ->
            runCatching {
                val pi = getPackageInfoCompat(pm, entity.apkPath)
                val app = entity.toVirtualApp(pi)
                registry[VirtualAppKey(entity.packageName, entity.userId)] = app
            }.onFailure {
                Timber.w(it, "VPM: failed to reload %s", entity.packageName)
            }
        }
        Timber.i("VPM: loaded %d virtual apps from DB", registry.size)
    }

    // ── Guest PM query handler ────────────────────────────────────────────────

    /**
     * Answer a PackageManager query on behalf of a virtual app.
     * Called from the Binder proxy when the guest calls:
     *   context.packageManager.getPackageInfo(name, flags)
     */
    fun handleGuestPmQuery(packageName: String, flags: Int): PackageInfo? {
        val app = registry.values.find { it.packageName == packageName }
            ?: return null
        return app.packageInfo
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun getPackageInfoCompat(pm: PackageManager, apkPath: String): PackageInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(
                apkPath,
                PackageManager.PackageInfoFlags.of(
                    (PackageManager.GET_ACTIVITIES or
                     PackageManager.GET_SERVICES  or
                     PackageManager.GET_RECEIVERS or
                     PackageManager.GET_PROVIDERS or
                     PackageManager.GET_META_DATA).toLong()
                )
            )
        } else {
            pm.getPackageArchiveInfo(
                apkPath,
                PackageManager.GET_ACTIVITIES or
                PackageManager.GET_SERVICES   or
                PackageManager.GET_RECEIVERS  or
                PackageManager.GET_PROVIDERS  or
                PackageManager.GET_META_DATA
            )
        }?.also { pi ->
            pi.applicationInfo?.sourceDir        = apkPath
            pi.applicationInfo?.publicSourceDir  = apkPath
        }
    }
}

// Extension to convert persisted entity back to runtime object
private fun com.kvm.data.db.entities.VirtualAppEntity.toVirtualApp(
    pi: PackageInfo?
) = VirtualApp(
    id           = id,
    packageName  = packageName,
    userId       = userId,
    apkPath      = apkPath,
    nativeLibDir = nativeLibDir,
    dataDir      = dataDir,
    label        = label,
    versionName  = versionName,
    versionCode  = versionCode,
    targetSdk    = targetSdk,
    minSdk       = minSdk,
    autoStart    = autoStart,
    packageInfo  = pi,
    applicationInfo = pi?.applicationInfo,
    activities   = pi?.activities?.toList() ?: emptyList(),
    installedAt  = installedAt,
)
