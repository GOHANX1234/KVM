package com.kvm.core.installer

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.kvm.core.virtual.VirtualApp
import java.util.zip.ZipFile
import com.kvm.core.virtual.VirtualFileSystem
import com.kvm.core.virtual.VirtualPackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AppCloner
 *
 * Clones an already-installed system app into the virtual space.
 *
 * Steps:
 *  1. Validate the source package is installed on the device.
 *  2. Copy the APK from the system's /data/app location to our private VFS.
 *  3. Extract native libraries for the current ABI.
 *  4. Register the new virtual app in VirtualPackageManager.
 *
 * Requires READ_EXTERNAL_STORAGE (pre-Android 10) or QUERY_ALL_PACKAGES.
 * No root is required — the system APK path is world-readable.
 */
@Singleton
class AppCloner @Inject constructor(
    private val vfs: VirtualFileSystem,
    private val vpm: VirtualPackageManager,
) {
    sealed class ClonerResult {
        data class Success(val app: VirtualApp) : ClonerResult()
        data class Error(val message: String, val cause: Throwable? = null) : ClonerResult()
    }

    /**
     * Clone [packageName] as instance [userId].
     * userId = 0 → primary clone; 1, 2 … → additional instances.
     */
    suspend fun cloneApp(
        context:     Context,
        packageName: String,
        userId:      Int = 0,
    ): ClonerResult = withContext(Dispatchers.IO) {
        Timber.i("AppCloner: cloning %s (userId=%d)", packageName, userId)

        // Guard — VFS must be initialized before cloning
        if (!vfs.isInitialized) {
            return@withContext ClonerResult.Error("Virtual file system not initialized — cannot clone $packageName")
        }

        // 1. Resolve source APK
        val sourceApkPath = getSourceApkPath(context, packageName)
            ?: return@withContext ClonerResult.Error("Package not found: $packageName")

        Timber.d("AppCloner: source APK = %s", sourceApkPath)

        // 2. Copy APK
        val destApk = runCatching {
            vfs.importApk(packageName, sourceApkPath)
        }.getOrElse {
            return@withContext ClonerResult.Error("Failed to copy APK", it)
        } ?: return@withContext ClonerResult.Error("VFS not ready — importApk returned null")

        // 3. Extract native libs for the current ABI
        val abi = getBestAbi()
        val nativeLibDir: File = vfs.getNativeLibDir(packageName, abi)
            ?: return@withContext ClonerResult.Error("VFS not ready — cannot get native lib dir")
        extractNativeLibs(destApk.absolutePath, nativeLibDir, abi)

        // 4. Provision isolated data dirs
        vfs.provisionInstance(packageName, userId)

        // 5. Data dir (guaranteed non-null after provisionInstance succeeds when VFS is ready)
        val dataDir: File = vfs.getDataDir(packageName, userId)
            ?: return@withContext ClonerResult.Error("VFS not ready — cannot get data dir")

        // 6. Register in VPM
        val app = runCatching {
            vpm.installVirtualApp(
                context      = context,
                apkPath      = destApk.absolutePath,
                packageName  = packageName,
                userId       = userId,
                dataDir      = dataDir.absolutePath,
                nativeLibDir = nativeLibDir.absolutePath,
            )
        }.getOrElse {
            return@withContext ClonerResult.Error("Failed to register virtual app", it)
        }

        Timber.i("AppCloner: clone complete — %s", app.label)
        ClonerResult.Success(app)
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun getSourceApkPath(context: Context, packageName: String): String? {
        return runCatching {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName,
                    PackageManager.ApplicationInfoFlags.of(0L)).sourceDir
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0).sourceDir
            }
        }.getOrNull()
    }

    /**
     * Extract .so files for [abi] from the APK (which is a ZIP) into [outDir].
     * Skips extraction if the lib already exists and has the same size.
     */
    private fun extractNativeLibs(apkPath: String, outDir: File, abi: String) {
        outDir.mkdirs()
        runCatching {
            ZipFile(apkPath).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    // lib/<abi>/<name>.so
                    if (!entry.name.startsWith("lib/$abi/") || !entry.name.endsWith(".so")) continue

                    val soName  = entry.name.substringAfterLast('/')
                    val outFile = File(outDir, soName)

                    // Skip if already extracted and same size
                    if (outFile.exists() && outFile.length() == entry.size) continue

                    zip.getInputStream(entry).use { input ->
                        outFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    Timber.d("AppCloner: extracted %s", soName)
                }
            }
        }.onFailure {
            Timber.w(it, "AppCloner: native lib extraction failed (app may still work)")
        }
    }

    private fun getBestAbi(): String {
        val supported = Build.SUPPORTED_ABIS
        return when {
            "arm64-v8a"   in supported -> "arm64-v8a"
            "armeabi-v7a" in supported -> "armeabi-v7a"
            "x86_64"      in supported -> "x86_64"
            "x86"         in supported -> "x86"
            else                       -> supported.firstOrNull() ?: "arm64-v8a"
        }
    }
}
