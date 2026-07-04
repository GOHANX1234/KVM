package com.kvm.core.virtual

import android.content.Context
import com.kvm.core.engine.KVMNative
import timber.log.Timber
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * VirtualFileSystem (VFS)
 *
 * Manages the isolated storage tree for all virtual app instances inside the
 * host's private directory.  No root is required.
 *
 * Directory layout:
 * ```
 * /data/data/com.kvm.virtualspace/virtual/
 * ├── apks/
 * │   └── com.whatsapp/          ← copied guest APK + native libs
 * │       ├── base.apk
 * │       └── lib/arm64-v8a/
 * └── data/user/
 *     ├── 0/com.whatsapp/        ← primary clone
 *     └── 1/com.whatsapp/        ← second instance
 * ```
 *
 * Crash-safety: [virtualRoot] is a nullable [File] rather than `lateinit var`
 * so that any premature call to a directory getter (before [initRoot]) does NOT
 * throw [UninitializedPropertyAccessException].
 *
 * All directory getters that need the root return null when the VFS has not been
 * initialized yet and log a warning.  Callers that receive null must handle it
 * gracefully (skip the operation, show a user-visible error, etc.).
 *
 * The only code path that throws is an explicit [requireInitialized] call which
 * callers opt into knowingly (e.g., inside a coroutine scope where a crash would
 * be caught by the supervisor).
 */
@Singleton
class VirtualFileSystem @Inject constructor() {

    @Volatile
    private var virtualRoot: File? = null

    val isInitialized: Boolean get() = virtualRoot != null

    /**
     * One-time root directory setup.  Idempotent — safe to call more than once
     * (subsequent calls update the root path and recreate missing directories).
     */
    fun initRoot(rootPath: String) {
        val root = File(rootPath)
        virtualRoot = root
        listOf(root, apksRootOrNull(), dataRootOrNull())
            .filterNotNull()
            .forEach { it.mkdirs() }
        Timber.i("VFS: root initialized at %s", root.absolutePath)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Returns the root, or null (with a warning) if not yet initialized. */
    private fun rootOrNull(): File? = virtualRoot.also {
        if (it == null) Timber.w("VFS: directory getter called before initRoot()")
    }

    /** Throws only in contexts where the caller has opted in (use sparingly). */
    fun requireInitialized(): File = virtualRoot
        ?: error("VirtualFileSystem.initRoot() has not been called yet")

    // ── Directory getters (null-safe) ─────────────────────────────────────────

    private fun apksRootOrNull(): File? = rootOrNull()?.let { File(it, "apks") }
    private fun dataRootOrNull(): File? = rootOrNull()?.let { File(it, "data/user") }

    fun apksRoot(): File = apksRootOrNull()
        ?: File(requireInitialized(), "apks")          // will throw clearly if not init'd

    fun dataRoot(): File = dataRootOrNull()
        ?: File(requireInitialized(), "data/user")

    /** Directory where the guest APK and its native libs are stored, or null. */
    fun getApkDirectory(packageName: String): File? =
        apksRootOrNull()?.let { File(it, packageName).also { d -> d.mkdirs() } }

    /** Path to the stored guest APK, or null. */
    fun getApkFile(packageName: String): File? =
        getApkDirectory(packageName)?.let { File(it, "base.apk") }

    /** Native lib extraction directory, or null. */
    fun getNativeLibDir(packageName: String, abi: String = "arm64-v8a"): File? =
        getApkDirectory(packageName)?.let { File(it, "lib/$abi").also { d -> d.mkdirs() } }

    /** Root data directory for a virtual app instance, or null. */
    fun getDataDir(packageName: String, userId: Int): File? =
        dataRootOrNull()?.let { File(it, "$userId/$packageName").also { d -> d.mkdirs() } }

    fun getFilesDir(packageName: String, userId: Int): File? =
        getDataDir(packageName, userId)?.let { File(it, "files").also { d -> d.mkdirs() } }

    fun getCacheDir(packageName: String, userId: Int): File? =
        getDataDir(packageName, userId)?.let { File(it, "cache").also { d -> d.mkdirs() } }

    fun getDatabasesDir(packageName: String, userId: Int): File? =
        getDataDir(packageName, userId)?.let { File(it, "databases").also { d -> d.mkdirs() } }

    fun getSharedPrefsDir(packageName: String, userId: Int): File? =
        getDataDir(packageName, userId)?.let { File(it, "shared_prefs").also { d -> d.mkdirs() } }

    fun getCodeCacheDir(packageName: String, userId: Int): File? =
        getDataDir(packageName, userId)?.let { File(it, "code_cache").also { d -> d.mkdirs() } }

    /** Dex optimisation output directory, or null. */
    fun getOdexDir(packageName: String): File? =
        getApkDirectory(packageName)?.let { File(it, "odex").also { d -> d.mkdirs() } }

    // ── Operations ────────────────────────────────────────────────────────────

    /**
     * Copy a source APK into the virtual APK store.
     * Returns the destination [File], or null if the VFS is not initialized.
     */
    fun importApk(packageName: String, sourceStream: InputStream): File? {
        val dest = getApkFile(packageName) ?: return null
        dest.outputStream().use { out -> sourceStream.copyTo(out, bufferSize = 64 * 1024) }
        Timber.i("VFS: APK imported for %s → %s (%d bytes)",
            packageName, dest.absolutePath, dest.length())
        return dest
    }

    fun importApk(packageName: String, sourcePath: String): File? {
        val src = File(sourcePath)
        if (!src.exists()) {
            Timber.e("VFS: importApk — source APK not found: %s", sourcePath)
            return null
        }
        return importApk(packageName, src.inputStream())
    }

    /** Wipe all isolated storage for a virtual app instance (uninstall single clone). */
    fun deleteInstanceData(packageName: String, userId: Int) {
        getDataDir(packageName, userId)?.deleteRecursively()
        Timber.i("VFS: deleted data for %s::%d", packageName, userId)
    }

    /** Wipe the APK and all instances of a virtual app. */
    fun deleteAppCompletely(packageName: String) {
        getApkDirectory(packageName)?.deleteRecursively()
        dataRootOrNull()?.listFiles()?.forEach { userDir ->
            File(userDir, packageName).deleteRecursively()
        }
        Timber.i("VFS: fully deleted %s", packageName)
    }

    /** Total storage used by a virtual app instance in bytes. */
    fun getInstanceSize(packageName: String, userId: Int): Long =
        getDataDir(packageName, userId)?.walkTopDown()?.sumOf { it.length() } ?: 0L

    /**
     * Translate a guest path to its isolated host equivalent.
     * Falls back to the original path when the native library is not loaded.
     */
    fun translatePath(guestPath: String): String =
        KVMNative.safeTranslatePath(guestPath)

    /**
     * Ensure all required sub-directories exist for an app instance.
     * Returns false and logs if the VFS root has not been initialized.
     */
    fun provisionInstance(packageName: String, userId: Int): Boolean {
        if (!isInitialized) {
            Timber.e("VFS: provisionInstance called before initRoot for %s::%d", packageName, userId)
            return false
        }
        getFilesDir(packageName, userId)
        getCacheDir(packageName, userId)
        getDatabasesDir(packageName, userId)
        getSharedPrefsDir(packageName, userId)
        getCodeCacheDir(packageName, userId)
        Timber.d("VFS: provisioned %s::%d", packageName, userId)
        return true
    }

    fun copyFile(src: File, dst: File) {
        dst.parentFile?.mkdirs()
        src.inputStream().use { inp -> dst.outputStream().use { out -> inp.copyTo(out) } }
    }
}
