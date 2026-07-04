package com.kvm.core.installer

import android.content.Context
import android.net.Uri
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
 * AppInstaller
 *
 * Installs a user-provided APK file (from a Uri, e.g. SAF / Downloads)
 * into the virtual space without going through the system package installer.
 *
 * No root required — we only touch files within our own private storage.
 */
@Singleton
class AppInstaller @Inject constructor(
    private val vfs: VirtualFileSystem,
    private val vpm: VirtualPackageManager,
) {
    sealed class InstallResult {
        data class Success(val app: VirtualApp) : InstallResult()
        data class Error(val message: String, val cause: Throwable? = null) : InstallResult()
    }

    /**
     * Install an APK from a content [uri] into the virtual space as [userId].
     */
    suspend fun installApk(
        context: Context,
        uri: Uri,
        userId: Int = 0,
    ): InstallResult = withContext(Dispatchers.IO) {
        Timber.i("AppInstaller: installing APK from %s (userId=%d)", uri, userId)

        // Guard — VFS must be initialized
        if (!vfs.isInitialized) {
            return@withContext InstallResult.Error("Virtual file system not initialized — cannot install APK")
        }

        // 1. Read the APK from the Uri into a temp file so we can parse it
        val tempFile = File(context.cacheDir, "kvm_install_${System.currentTimeMillis()}.apk")
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { inp ->
                tempFile.outputStream().use { out -> inp.copyTo(out, 64 * 1024) }
            } ?: return@withContext InstallResult.Error("Cannot open URI: $uri")
        }.onFailure {
            return@withContext InstallResult.Error("Failed to read APK", it)
        }

        // 2. Parse package name from the APK
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val pi = pm.getPackageArchiveInfo(tempFile.absolutePath, 0)
            ?: return@withContext InstallResult.Error("Invalid APK: cannot parse package info").also {
                tempFile.delete()
            }

        val packageName = pi.packageName
        Timber.i("AppInstaller: parsed package=%s", packageName)

        // 3. Move the APK into VFS
        val destApk: File = runCatching {
            vfs.importApk(packageName, tempFile.inputStream())
        }.getOrElse {
            tempFile.delete()
            return@withContext InstallResult.Error("Failed to import APK", it)
        } ?: run {
            tempFile.delete()
            return@withContext InstallResult.Error("VFS not ready — importApk returned null")
        }
        tempFile.delete()

        // 4. Extract native libs
        val abi = getBestAbi()
        val nativeLibDir: File = vfs.getNativeLibDir(packageName, abi)
            ?: return@withContext InstallResult.Error("VFS not ready — cannot get native lib dir")
        extractNativeLibs(destApk.absolutePath, nativeLibDir, abi)

        // 5. Provision isolated data
        vfs.provisionInstance(packageName, userId)

        val dataDir: File = vfs.getDataDir(packageName, userId)
            ?: return@withContext InstallResult.Error("VFS not ready — cannot get data dir")

        // 6. Register
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
            return@withContext InstallResult.Error("Failed to register virtual app", it)
        }

        Timber.i("AppInstaller: installed %s (%s)", app.label, packageName)
        InstallResult.Success(app)
    }

    private fun extractNativeLibs(apkPath: String, outDir: File, abi: String) {
        outDir.mkdirs()
        runCatching {
            ZipFile(apkPath).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.name.startsWith("lib/$abi/") || !entry.name.endsWith(".so")) continue
                    val outFile = File(outDir, entry.name.substringAfterLast('/'))
                    if (outFile.exists() && outFile.length() == entry.size) continue
                    zip.getInputStream(entry).use { i -> outFile.outputStream().use { o -> i.copyTo(o) } }
                }
            }
        }.onFailure { Timber.w(it, "AppInstaller: native lib extraction failed") }
    }

    private fun getBestAbi(): String {
        return when {
            "arm64-v8a"   in Build.SUPPORTED_ABIS -> "arm64-v8a"
            "armeabi-v7a" in Build.SUPPORTED_ABIS -> "armeabi-v7a"
            "x86_64"      in Build.SUPPORTED_ABIS -> "x86_64"
            else                                  -> Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        }
    }
}
