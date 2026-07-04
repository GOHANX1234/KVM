package com.kvm.core.hook

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.content.res.Resources
import com.kvm.core.loader.ClassLoaderManager
import timber.log.Timber
import java.io.File

/**
 * VirtualContext
 *
 * A ContextWrapper that overrides the identity-related methods so that a virtual
 * app sees its own package name, data directories, and ClassLoader instead of
 * the host's.
 *
 * Key overrides:
 *   • getPackageName()        → guest package name
 *   • getDataDir()            → virtual isolated data dir
 *   • getFilesDir()           → virtual files dir
 *   • getCacheDir()           → virtual cache dir
 *   • getSharedPreferences()  → reads/writes from virtual shared_prefs dir
 *   • getClassLoader()        → virtual class loader for guest classes
 *   • getResources()          → guest app's Resources (populated by VirtualApkLoader)
 *   • getAssets()             → guest app's AssetManager
 */
class VirtualContext(
    base: Context,
    private val guestPackage: String,
    private val userId: Int,
) : ContextWrapper(base) {

    private var guestResources: Resources? = null
    private var guestAssets: AssetManager? = null

    override fun getPackageName(): String = guestPackage

    override fun getOpPackageName(): String = guestPackage

    override fun getDataDir(): File {
        val vfs = VirtualFileSystem_getInstance()
        return vfs?.getDataDir(guestPackage, userId)
            ?: super.getDataDir()
    }

    override fun getFilesDir(): File {
        val vfs = VirtualFileSystem_getInstance()
        return vfs?.getFilesDir(guestPackage, userId)?.also { it.mkdirs() }
            ?: super.getFilesDir()
    }

    override fun getCacheDir(): File {
        val vfs = VirtualFileSystem_getInstance()
        return vfs?.getCacheDir(guestPackage, userId)?.also { it.mkdirs() }
            ?: super.getCacheDir()
    }

    override fun getExternalCacheDir(): File? {
        // Redirect to in-process storage — avoids scoped-storage issues
        return getCacheDir()
    }

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        val vfs = VirtualFileSystem_getInstance()
        return if (vfs != null) {
            // Use the host's context to create prefs but backed by the virtual path
            baseContext.getSharedPreferences(
                "kvm_${guestPackage}_${userId}_$name", mode
            )
        } else {
            super.getSharedPreferences(name, mode)
        }
    }

    override fun getDatabasePath(name: String): File {
        val vfs = VirtualFileSystem_getInstance()
        // getDatabasesDir() returns File? — null when VFS not initialized yet
        val dbDir = vfs?.getDatabasesDir(guestPackage, userId)
        return if (dbDir != null) File(dbDir, name) else super.getDatabasePath(name)
    }

    override fun getClassLoader(): ClassLoader {
        return ClassLoaderManager.getClassLoader(guestPackage)
            ?: super.getClassLoader()
    }

    override fun getResources(): Resources {
        return guestResources ?: super.getResources()
    }

    override fun getAssets(): AssetManager {
        return guestAssets ?: super.getAssets()
    }

    override fun getApplicationInfo(): ApplicationInfo {
        return ClassLoaderManager.getApplicationInfo(guestPackage)
            ?: super.getApplicationInfo()
    }

    fun setGuestResources(res: Resources)     { guestResources = res }
    fun setGuestAssets(assets: AssetManager)  { guestAssets = assets }
}

// Singleton accessor — avoids circular Hilt injection in ContextWrapper
fun VirtualFileSystem_getInstance(): com.kvm.core.virtual.VirtualFileSystem? {
    return com.kvm.di.ServiceLocator.virtualFileSystem
}
