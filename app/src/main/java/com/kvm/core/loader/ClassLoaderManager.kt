package com.kvm.core.loader

import android.content.pm.ApplicationInfo
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * ClassLoaderManager
 *
 * Global registry of ClassLoaders and associated metadata for every loaded
 * virtual APK.  Accessed by InstrumentationHook and VirtualContext without
 * needing dependency injection (they live inside Android framework callbacks
 * where Hilt cannot inject).
 *
 * Thread-safe: all mutations use a ConcurrentHashMap.
 */
object ClassLoaderManager {

    private val registry = ConcurrentHashMap<String, VirtualApkLoader.LoadedApk>()

    /**
     * Register a loaded APK.  Overwrites any previous entry for the same package.
     */
    fun register(packageName: String, loaded: VirtualApkLoader.LoadedApk) {
        registry[packageName] = loaded
        Timber.d("ClassLoaderManager: registered %s", packageName)
    }

    fun unregister(packageName: String) {
        registry.remove(packageName)
        Timber.d("ClassLoaderManager: unregistered %s", packageName)
    }

    fun getClassLoader(packageName: String): ClassLoader? =
        registry[packageName]?.classLoader

    fun getApplicationInfo(packageName: String): ApplicationInfo? =
        registry[packageName]?.appInfo

    fun getResources(packageName: String): android.content.res.Resources? =
        registry[packageName]?.resources

    fun isLoaded(packageName: String): Boolean = registry.containsKey(packageName)

    fun getAllLoaded(): Set<String> = registry.keys.toSet()

    fun clear() {
        registry.clear()
        Timber.i("ClassLoaderManager: cleared all entries")
    }
}
