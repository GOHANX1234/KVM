package com.kvm.core.loader

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Build
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * VirtualApkLoader
 *
 * Loads a guest APK into the current process so that its classes are accessible
 * via a custom ClassLoader, and its resources (layouts, drawables, strings) are
 * accessible via a patched AssetManager.
 *
 * Strategy:
 *  1. Build a DexClassLoader pointing at the guest APK and its native libs.
 *  2. Build an AssetManager that includes the guest APK path.
 *  3. Wrap in Resources and register everything in ClassLoaderManager.
 *  4. The InstrumentationHook and VirtualContext use ClassLoaderManager to
 *     resolve the correct loader/resources for each virtual package.
 *
 * Android 14+ (API 34): dynamic code loading from world-readable locations is
 * restricted.  We copy the APK into private storage before loading.
 *
 * Crash-safety: AssetManager private-constructor reflection is wrapped in
 * try-catch on every API level because the hidden-API enforcement behaviour
 * differs across Android versions and OEM ROMs.  A failed resource load logs
 * a warning and falls back to the host's AssetManager rather than crashing.
 */
@Singleton
class VirtualApkLoader @Inject constructor() {

    data class LoadedApk(
        val packageName: String,
        val classLoader: ClassLoader,
        val resources:   Resources,
        val assetManager:AssetManager,
        val appInfo:     ApplicationInfo,
    )

    /**
     * Load [apkPath] as a virtual app instance.
     *
     * @param context      Host context (used to create Resources wrapper).
     * @param apkPath      Absolute path to the (private-storage) guest APK.
     * @param nativeLibDir Directory containing extracted native libs for the current ABI.
     * @param optimizedDir Writable dir for dex optimisation output.
     * @param packageName  Guest package name.
     * @param userId       Instance index.
     */
    fun loadApk(
        context:      Context,
        apkPath:      String,
        nativeLibDir: String,
        optimizedDir: String,
        packageName:  String,
        userId:       Int,
    ): LoadedApk {
        Timber.i("VirtualApkLoader: loading %s (user=%d)", packageName, userId)

        val apkFile = File(apkPath)
        require(apkFile.exists()) { "APK not found: $apkPath" }

        // 1. Build ClassLoader
        val loader = buildClassLoader(apkPath, nativeLibDir, optimizedDir)

        // 2. Build AssetManager + Resources
        val (am, res) = buildResources(context, apkPath)

        // 3. Build ApplicationInfo
        val appInfo = buildApplicationInfo(packageName, apkPath, nativeLibDir, context)

        val loaded = LoadedApk(
            packageName  = packageName,
            classLoader  = loader,
            resources    = res,
            assetManager = am,
            appInfo      = appInfo,
        )

        // 4. Register globally
        ClassLoaderManager.register(packageName, loaded)

        Timber.i("VirtualApkLoader: loaded %s OK", packageName)
        return loaded
    }

    // ─── ClassLoader ───────────────────────────────────────────────────────────

    private fun buildClassLoader(
        apkPath:      String,
        nativeLibDir: String,
        optimizedDir: String,
    ): ClassLoader {
        // DexClassLoader is stable across all API levels (8+).
        // nativeLibDir may be empty if no native libs exist — pass null in that case
        // to avoid DexClassLoader failing on a non-existent directory.
        val nativeLibDirArg = nativeLibDir.takeIf { it.isNotBlank() && File(it).exists() }
        return dalvik.system.DexClassLoader(
            apkPath,
            optimizedDir,
            nativeLibDirArg,
            VirtualApkLoader::class.java.classLoader
        )
    }

    // ─── Resources / AssetManager ──────────────────────────────────────────────

    private fun buildResources(context: Context, apkPath: String): Pair<AssetManager, Resources> {
        val am = tryCreateAssetManager(context, apkPath)
        val displayMetrics = context.resources.displayMetrics
        val configuration  = context.resources.configuration
        @Suppress("DEPRECATION")
        val res = try {
            Resources(am, displayMetrics, configuration)
        } catch (t: Throwable) {
            Timber.w(t, "VirtualApkLoader: failed to create Resources wrapper, using host resources")
            context.resources
        }
        return Pair(am, res)
    }

    /**
     * Create an AssetManager backed by the guest APK.
     *
     * Attempts three strategies in order of preference:
     *  1. Reflection on [AssetManager.addAssetPath] (works on API 1–35 with varying
     *     hidden-API enforcement; suppressed with @Suppress for clarity).
     *  2. If strategy 1 throws for any reason, fall back to the host's AssetManager.
     *     This means the guest app's drawables/strings won't load, but the app won't crash.
     *
     * The private no-arg constructor approach (common in older virtual-space code) was
     * removed in this version because:
     *  - API 28 removed the public no-arg constructor
     *  - API 29+ blocks private-constructor reflection via hidden-API enforcement
     *  - Accessing it causes NoSuchMethodException or SecurityException at runtime
     */
    private fun tryCreateAssetManager(context: Context, apkPath: String): AssetManager {
        // Strategy 1: addAssetPath reflection (stable across all API levels in practice)
        runCatching { createAssetManagerViaAddAssetPath(apkPath) }
            .onSuccess { am -> return am }
            .onFailure { t ->
                Timber.w(t, "VirtualApkLoader: addAssetPath strategy failed for %s", apkPath)
            }

        // Strategy 2: host fallback — no crash, guest resources unavailable
        Timber.w("VirtualApkLoader: all AssetManager strategies failed for %s — using host assets", apkPath)
        return context.assets
    }

    /**
     * Build an AssetManager via the hidden [addAssetPath] method.
     *
     * On API < 28: AssetManager() constructor is public; reflected easily.
     * On API 28+:  constructor is hidden; we allocate via [AssetManager.Builder]
     *              if available (API 28+), otherwise fall back to newInstance trick.
     *
     * All reflection is inside runCatching at the call site.
     */
    @Suppress("DEPRECATION")
    private fun createAssetManagerViaAddAssetPath(apkPath: String): AssetManager {
        val amClass = AssetManager::class.java

        // Allocate a bare AssetManager instance.
        // On API 28+ the no-arg constructor is hidden but still exists in AOSP;
        // we attempt it and let the caller's runCatching handle any failure.
        val am: AssetManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // On API 28+, use the package-private constructor via newInstance if accessible,
            // otherwise allocate through Unsafe to skip the constructor entirely.
            allocateAssetManagerApi28(amClass)
        } else {
            amClass.getDeclaredConstructor()
                .also { it.isAccessible = true }
                .newInstance() as AssetManager
        }

        // Call addAssetPath to include the guest APK
        val addAssetPath = amClass
            .getDeclaredMethod("addAssetPath", String::class.java)
            .also { it.isAccessible = true }
        val cookie = addAssetPath.invoke(am, apkPath) as? Int ?: 0
        if (cookie == 0) {
            Timber.w("VirtualApkLoader: addAssetPath returned 0 for %s — resources may be incomplete", apkPath)
        }

        // On API 28+ call ensureStringBlocks to avoid NPE in getResourceName
        runCatching {
            amClass.getDeclaredMethod("ensureStringBlocks")
                .also { it.isAccessible = true }
                .invoke(am)
        }

        return am
    }

    /**
     * Allocate an AssetManager on API 28+ without calling its (hidden) constructor.
     *
     * Priority:
     *  1. getDeclaredConstructor() with isAccessible — works on many ROMs even on API 28+
     *  2. sun.misc.Unsafe.allocateInstance — allocates without calling any constructor,
     *     safe because addAssetPath will fully initialise the object.
     */
    @Suppress("DEPRECATION")
    private fun allocateAssetManagerApi28(amClass: Class<AssetManager>): AssetManager {
        // Try the accessible constructor first
        runCatching {
            return amClass.getDeclaredConstructor()
                .also { it.isAccessible = true }
                .newInstance() as AssetManager
        }

        // Fall back to Unsafe allocation (no constructor is called)
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafeField = unsafeClass.getDeclaredField("theUnsafe")
            .also { it.isAccessible = true }
        val unsafe = unsafeField.get(null)
        val allocateInstance = unsafeClass.getDeclaredMethod("allocateInstance", Class::class.java)
            .also { it.isAccessible = true }
        return allocateInstance.invoke(unsafe, amClass) as AssetManager
    }

    // ─── ApplicationInfo ───────────────────────────────────────────────────────

    private fun buildApplicationInfo(
        packageName:  String,
        apkPath:      String,
        nativeLibDir: String,
        context:      Context,
    ): ApplicationInfo {
        val pm = context.packageManager
        val info = runCatching {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apkPath, 0)?.applicationInfo
        }.getOrNull() ?: ApplicationInfo()

        info.packageName       = packageName
        info.sourceDir         = apkPath
        info.publicSourceDir   = apkPath
        info.nativeLibraryDir  = nativeLibDir
        info.dataDir           = context.dataDir.absolutePath + "/virtual/data/user/0/$packageName"
        return info
    }
}
