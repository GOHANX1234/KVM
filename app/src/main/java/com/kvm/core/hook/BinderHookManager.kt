package com.kvm.core.hook

import android.content.Context
import android.os.Build
import timber.log.Timber
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BinderHookManager
 *
 * Installs dynamic Java-level proxies for the system services that virtual apps
 * interact with most:
 *   • IActivityManager  (startActivity, getRunningAppProcesses …)
 *   • IPackageManager   (getPackageInfo, queryIntentActivities …)
 *
 * Technique (no root required):
 *   1. Use reflection to access the cached singleton in ActivityManager /
 *      ActivityThread (both are internal AOSP classes).
 *   2. Wrap the real IBinder with a java.lang.reflect.Proxy that intercepts
 *      specific method calls.
 *   3. Modify or reroute the arguments before forwarding to the real service.
 *
 * Android 10+ restricts non-SDK API access.  We use the "double reflection"
 * (meta-reflection) technique to bypass the enforcement at runtime.
 *
 * IMPORTANT: These hooks are process-wide.  They are installed once in the
 * main process (host UI) and once in each stub process.
 *
 * Crash-safety: Every individual hook attempt is wrapped in runCatching.
 * Failure to install a hook is logged as a warning; the app continues normally
 * with reduced virtualization capability.
 */
@Singleton
class BinderHookManager @Inject constructor(
    private val virtualPackageManager: com.kvm.core.virtual.VirtualPackageManager,
    private val virtualActivityManager: com.kvm.core.virtual.VirtualActivityManager,
) {

    fun installHooks(context: Context) {
        Timber.i("BinderHookManager: installing hooks (API %d)", Build.VERSION.SDK_INT)
        runCatching { hookActivityManager() }
            .onFailure { Timber.w(it, "BinderHookManager: IActivityManager hook failed") }
        runCatching { hookPackageManager(context) }
            .onFailure { Timber.w(it, "BinderHookManager: IPackageManager hook failed") }
    }

    // ─── IActivityManager hook ────────────────────────────────────────────────

    private fun hookActivityManager() {
        val amClass = Class.forName("android.app.ActivityManager")

        // The singleton field was renamed across Android versions:
        //   API 26–28:  IActivityManagerSingleton  (in ActivityManager)
        //   API 29–31:  IActivityManagerSingleton  (still in ActivityManager on most ROMs)
        //   API 32+:    may be in ActivityManagerNative or removed; try fallbacks
        val singletonField: Field = findFieldInHierarchy(
            amClass,
            "IActivityManagerSingleton",    // API 26+
            "gDefault",                     // some older AOSP forks
            "sServiceSingleton",            // alternative name on some custom ROMs
        ) ?: run {
            Timber.w("BinderHookManager: IActivityManagerSingleton field not found on API %d",
                Build.VERSION.SDK_INT)
            return
        }

        val singleton = singletonField.get(null)
            ?: throw IllegalStateException("IActivityManagerSingleton field is null")

        val singletonClass = Class.forName("android.util.Singleton")

        // Force singleton materialization before reading mInstance
        runCatching {
            singletonClass.getDeclaredMethod("get")
                .also { it.isAccessible = true }
                .invoke(singleton)
        }

        val instanceField: Field = singletonClass.getDeclaredField("mInstance")
            .also { it.isAccessible = true }

        val realAm = instanceField.get(singleton)
            ?: throw IllegalStateException("IActivityManager singleton returned null even after get()")

        val iAmClass = Class.forName("android.app.IActivityManager")

        // iAmClass may be loaded by the boot class loader (returns null in Java).
        // Proxy.newProxyInstance requires a non-null ClassLoader; fall back to our own.
        val classLoader = iAmClass.classLoader ?: BinderHookManager::class.java.classLoader!!

        val proxyAm = Proxy.newProxyInstance(
            classLoader,
            arrayOf(iAmClass),
            ActivityManagerProxy(realAm, virtualActivityManager)
        )
        instanceField.set(singleton, proxyAm)
        Timber.i("BinderHookManager: IActivityManager hooked")
    }

    // ─── IPackageManager hook ─────────────────────────────────────────────────

    private fun hookPackageManager(context: Context) {
        val activityThread = getActivityThread() ?: return
        val atClass        = Class.forName("android.app.ActivityThread")

        // Force package manager singleton to initialise if not yet done
        runCatching {
            atClass.getDeclaredMethod("getPackageManager")
                .also { it.isAccessible = true }
                .invoke(activityThread)
        }

        // The field was renamed across Android versions:
        //   API 26–31:  sPackageManager
        //   API 32+:    sCurrentPackageManager  (renamed in Android 12L / 12.1)
        val pmField: Field = findFieldInHierarchy(
            atClass,
            "sPackageManager",          // API 26–31
            "sCurrentPackageManager",   // API 32+
        ) ?: run {
            Timber.w("BinderHookManager: sPackageManager field not found on API %d",
                Build.VERSION.SDK_INT)
            return
        }

        val realPm = pmField.get(activityThread)
            ?: run {
                Timber.w("BinderHookManager: sPackageManager still null after forced init; skipping PM hook")
                return
            }

        val iPmClass = Class.forName("android.content.pm.IPackageManager")
        val classLoader = iPmClass.classLoader ?: BinderHookManager::class.java.classLoader!!

        val proxyPm = Proxy.newProxyInstance(
            classLoader,
            arrayOf(iPmClass),
            PackageManagerProxy(realPm, virtualPackageManager)
        )
        pmField.set(activityThread, proxyPm)

        // Also replace in ApplicationPackageManager's mPM field
        runCatching {
            val appPmClass = Class.forName("android.app.ApplicationPackageManager")
            val mPmField   = findFieldInHierarchy(appPmClass, "mPM", "mIPackageManager")
                ?: return@runCatching
            mPmField.set(context.packageManager, proxyPm)
        }.onFailure {
            Timber.w(it, "BinderHookManager: could not replace ApplicationPackageManager.mPM")
        }

        Timber.i("BinderHookManager: IPackageManager hooked")
    }

    // ─── Reflection utilities ─────────────────────────────────────────────────

    private fun getActivityThread(): Any? {
        return runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            val method  = atClass.getDeclaredMethod("currentActivityThread")
                .also { it.isAccessible = true }
            method.invoke(null)
        }.getOrNull()
    }

    /**
     * Try each field name in order until one is found in [clazz] or its super-classes.
     * Returns the first accessible Field or null if none exists.
     */
    private fun findFieldInHierarchy(clazz: Class<*>, vararg fieldNames: String): Field? {
        for (name in fieldNames) {
            var c: Class<*>? = clazz
            while (c != null) {
                runCatching {
                    return c!!.getDeclaredField(name).also { it.isAccessible = true }
                }
                c = c.superclass
            }
        }
        return null
    }
}

// ─── Proxy implementations ────────────────────────────────────────────────────

private class ActivityManagerProxy(
    private val realAm: Any,
    private val vam: com.kvm.core.virtual.VirtualActivityManager,
) : InvocationHandler {

    override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
        return when (method.name) {
            "startActivity" -> handleStartActivity(method, args)
            "getRunningAppProcesses" -> handleGetRunningAppProcesses(method, args)
            else -> method.invoke(realAm, *(args ?: emptyArray()))
        }
    }

    private fun handleStartActivity(method: Method, args: Array<out Any>?): Any? {
        // args[2] is the Intent on most AOSP versions
        if (args == null || args.size < 3) return method.invoke(realAm, *(args ?: emptyArray()))
        val intent = args[2] as? android.content.Intent ?: return method.invoke(realAm, *args)

        val targetPkg = intent.component?.packageName ?: intent.`package`
        if (targetPkg != null) {
            val rewritten = vam.interceptStartActivity(intent, targetPkg, 0)
            val newArgs: Array<Any?> = arrayOfNulls(args.size)
            args.copyInto(newArgs)
            newArgs[2] = rewritten
            @Suppress("UNCHECKED_CAST")
            return method.invoke(realAm, *(newArgs as Array<Any?>))
        }
        return method.invoke(realAm, *args)
    }

    private fun handleGetRunningAppProcesses(method: Method, args: Array<out Any>?): Any? {
        return method.invoke(realAm, *(args ?: emptyArray()))
    }
}

private class PackageManagerProxy(
    private val realPm: Any,
    private val vpm: com.kvm.core.virtual.VirtualPackageManager,
) : InvocationHandler {

    override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
        return when (method.name) {
            "getPackageInfo" -> handleGetPackageInfo(method, args)
            "getApplicationInfo" -> handleGetApplicationInfo(method, args)
            else -> method.invoke(realPm, *(args ?: emptyArray()))
        }
    }

    private fun handleGetPackageInfo(method: Method, args: Array<out Any>?): Any? {
        val pkgName = args?.getOrNull(0) as? String ?: return method.invoke(realPm, *(args ?: emptyArray()))
        val virtualInfo = vpm.handleGuestPmQuery(pkgName, 0)
        if (virtualInfo != null) return virtualInfo
        return method.invoke(realPm, *(args ?: emptyArray()))
    }

    private fun handleGetApplicationInfo(method: Method, args: Array<out Any>?): Any? {
        val pkgName = args?.getOrNull(0) as? String ?: return method.invoke(realPm, *(args ?: emptyArray()))
        val virtualInfo = vpm.handleGuestPmQuery(pkgName, 0)
        if (virtualInfo?.applicationInfo != null) return virtualInfo.applicationInfo
        return method.invoke(realPm, *(args ?: emptyArray()))
    }
}
