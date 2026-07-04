package com.kvm.domain.usecase

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.kvm.core.virtual.VirtualPackageManager
import com.kvm.domain.model.AppInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Returns all user-installed apps on the device, annotated with whether
 * each app already has a virtual clone in KVM.
 */
class GetInstalledAppsUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vpm: VirtualPackageManager,
) {
    suspend operator fun invoke(includeSystemApps: Boolean = false): List<AppInfo> =
        withContext(Dispatchers.IO) {
            val pm      = context.packageManager
            val cloned  = vpm.getAllVirtualApps().map { it.packageName }.toSet()

            val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(
                    PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
            }

            packages.filter { ai ->
                // Skip our own package
                if (ai.packageName == context.packageName) return@filter false
                // Optionally skip system apps
                if (!includeSystemApps && isSystemApp(ai)) return@filter false
                true
            }.map { ai ->
                val pi = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        pm.getPackageInfo(ai.packageName,
                            PackageManager.PackageInfoFlags.of(0L))
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getPackageInfo(ai.packageName, 0)
                    }
                }.getOrNull()

                AppInfo(
                    packageName = ai.packageName,
                    label       = ai.loadLabel(pm).toString(),
                    versionName = pi?.versionName ?: "?",
                    versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                                      pi?.longVersionCode ?: 0L
                                  else
                                      @Suppress("DEPRECATION") pi?.versionCode?.toLong() ?: 0L,
                    icon        = runCatching { ai.loadIcon(pm) }.getOrNull(),
                    sourceDir   = ai.sourceDir,
                    isSystemApp = isSystemApp(ai),
                    installedAt = pi?.firstInstallTime ?: 0L,
                    isCloned    = ai.packageName in cloned,
                )
            }.sortedBy { it.label.lowercase() }
        }

    private fun isSystemApp(ai: ApplicationInfo): Boolean =
        (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
}
