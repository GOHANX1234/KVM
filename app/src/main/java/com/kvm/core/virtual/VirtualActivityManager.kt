package com.kvm.core.virtual

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.kvm.core.loader.ClassLoaderManager
import com.kvm.core.loader.VirtualApkLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.util.Stack
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * VirtualActivityManager (VAM)
 *
 * Responsible for:
 *  - Mapping guest app components to host Stub slots
 *  - Intercepting startActivity calls from virtual apps
 *  - Maintaining a virtual back-stack per instance
 *  - Forwarding real Activity creation to the Instrumentation hook
 *
 * The StubActivity trick works as follows:
 *  1. Guest calls startActivity(Intent targeting com.whatsapp/.MainActivity)
 *  2. VAM intercepts it (via our proxied IActivityManager)
 *  3. VAM selects a free StubActivity slot (e.g. StubActivity$P02)
 *  4. VAM encodes the real intent as an extra in the stub Intent
 *  5. AMS validates the stub (it's in our manifest) and launches it
 *  6. In the stub process, InstrumentationHook.newActivity() is called
 *  7. The hook decodes the real intent and instantiates the guest Activity
 */
@Singleton
class VirtualActivityManager @Inject constructor(
    private val virtualPackageManager: VirtualPackageManager,
    private val virtualProcessManager: VirtualProcessManager,
    private val virtualFileSystem: VirtualFileSystem,
    private val apkLoader: VirtualApkLoader,
    @ApplicationContext private val appContext: Context,
) {
    companion object {
        const val EXTRA_VIRTUAL_INTENT  = "kvm.virtual_intent"
        const val EXTRA_VIRTUAL_PACKAGE = "kvm.virtual_package"
        const val EXTRA_VIRTUAL_CLASS   = "kvm.virtual_class"
        const val EXTRA_USER_ID         = "kvm.user_id"

        private const val STUB_ACTIVITY_COUNT = 20

        /**
         * Stub class prefix. The stub Activities live under the Gradle `namespace`
         * (com.kvm), NOT the applicationId (com.kvm.virtualspace[.debug]) — those are
         * two different things. The namespace is fixed at build time regardless of
         * flavor/applicationIdSuffix, so this can be a plain constant.
         */
        private const val STUB_BASE_CLASS = "com.kvm.stub.StubActivity\$P"
    }

    /** Tracks which stub slot is in use: slot index → VirtualAppKey */
    private val stubSlotMap = ConcurrentHashMap<Int, VirtualAppKey>()
    private val slotCounter = AtomicInteger(0)

    /** Virtual back-stacks: one per VirtualAppKey */
    private val backStacks = ConcurrentHashMap<VirtualAppKey, Stack<VirtualActivityRecord>>()

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Entry point: launch a virtual app's main activity.
     * Finds the launch intent from the VirtualApp's PackageInfo, rewrites it into
     * a stub Intent, and actually fires it via [Context.startActivity] so AMS
     * has something real to hand off to the InstrumentationHook.
     */
    fun launchApp(packageName: String, userId: String, context: Context? = null) {
        val uid = userId.toIntOrNull() ?: 0
        val app = virtualPackageManager.getVirtualApp(packageName, uid)
            ?: run {
                Timber.e("VAM: app not installed in virtual space: %s", packageName)
                return
            }

        // Find the LAUNCHER activity from the parsed PackageInfo
        val launchClass = app.activities.firstOrNull { ai ->
            ai.name != null
        }?.name ?: run {
            Timber.e("VAM: no launchable activity in %s", packageName)
            return
        }

        val guestIntent = Intent().apply {
            setClassName(packageName, launchClass)
        }
        val stubIntent = interceptStartActivity(guestIntent, packageName, uid).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val ctx = context ?: appContext
        runCatching {
            ctx.startActivity(stubIntent)
            Timber.i("VAM: launchApp fired stub for %s (userId=%d)", packageName, uid)
        }.onFailure {
            Timber.e(it, "VAM: failed to start stub activity for %s", packageName)
        }
    }

    /**
     * Called (from whichever process the stub Activity lands in) right before
     * the InstrumentationHook resolves the guest ClassLoader. If this process
     * hasn't loaded the guest APK yet (e.g. it's a fresh :p0-:p4 process that
     * never ran [VirtualProcessManager.startVirtualApp]), load it synchronously
     * here so the ClassLoader is guaranteed ready.
     */
    fun ensureApkLoaded(packageName: String, userId: Int) {
        if (ClassLoaderManager.isLoaded(packageName)) return
        val app = virtualPackageManager.getVirtualApp(packageName, userId) ?: run {
            Timber.w("VAM: ensureApkLoaded — %s not found in registry", packageName)
            return
        }
        runCatching {
            apkLoader.loadApk(
                context      = appContext,
                apkPath      = app.apkPath,
                nativeLibDir = app.nativeLibDir,
                optimizedDir = virtualFileSystem.getOdexDir(packageName)?.absolutePath ?: "",
                packageName  = packageName,
                userId       = userId,
            )
            Timber.i("VAM: lazily loaded %s in this process", packageName)
        }.onFailure {
            Timber.e(it, "VAM: ensureApkLoaded failed for %s", packageName)
        }
    }

    /**
     * Intercept a startActivity call from a virtual app.
     * Returns the rewritten stub Intent that AMS should see.
     */
    fun interceptStartActivity(
        originalIntent: Intent,
        callingPackage: String,
        userId: Int,
    ): Intent {
        val stubClass  = acquireStubSlot(VirtualAppKey(callingPackage, userId))
        val targetComp = originalIntent.component

        val stubIntent = Intent().apply {
            // Use the *actual* installed package (includes .debug suffix in
            // debug builds) — this must match the real applicationId, not the
            // Gradle namespace, or AMS won't be able to resolve the component.
            setClassName(appContext.packageName, stubClass)
            // Encode the real destination as extras
            putExtra(EXTRA_VIRTUAL_INTENT,  originalIntent)
            putExtra(EXTRA_VIRTUAL_PACKAGE, targetComp?.packageName ?: callingPackage)
            putExtra(EXTRA_VIRTUAL_CLASS,   targetComp?.className   ?: "")
            putExtra(EXTRA_USER_ID,         userId)
            // Carry over original flags so back-stack semantics are preserved
            flags = originalIntent.flags
        }

        pushActivityRecord(
            VirtualAppKey(callingPackage, userId),
            VirtualActivityRecord(
                className    = targetComp?.className ?: "",
                packageName  = targetComp?.packageName ?: callingPackage,
                stubClassName = stubClass,
            )
        )

        Timber.d("VAM: intercepted startActivity %s → stub %s", targetComp, stubClass)
        return stubIntent
    }

    /** Called by the Instrumentation hook to decode the real Activity class to instantiate. */
    fun resolveRealActivity(stubIntent: Intent): Pair<String, String>? {
        val pkg   = stubIntent.getStringExtra(EXTRA_VIRTUAL_PACKAGE) ?: return null
        val clazz = stubIntent.getStringExtra(EXTRA_VIRTUAL_CLASS)   ?: return null
        return Pair(pkg, clazz)
    }

    // ─── Stub slot management ─────────────────────────────────────────────────

    private fun acquireStubSlot(key: VirtualAppKey): String {
        // Reuse existing slot for this key if already allocated
        val existing = stubSlotMap.entries.find { it.value == key }
        if (existing != null) {
            return formatStubClass(existing.key)
        }
        // Allocate next slot (round-robin)
        val slot = slotCounter.getAndUpdate { (it + 1) % STUB_ACTIVITY_COUNT }
        stubSlotMap[slot] = key
        return formatStubClass(slot)
    }

    fun releaseStubSlot(stubClassName: String) {
        val slot = stubClassName.removePrefix(STUB_BASE_CLASS).toIntOrNull() ?: return
        stubSlotMap.remove(slot)
    }

    private fun formatStubClass(slot: Int): String =
        STUB_BASE_CLASS + slot.toString().padStart(2, '0')

    // ─── Back-stack ───────────────────────────────────────────────────────────

    private fun pushActivityRecord(key: VirtualAppKey, record: VirtualActivityRecord) {
        backStacks.getOrPut(key) { Stack() }.push(record)
    }

    fun popActivityRecord(key: VirtualAppKey): VirtualActivityRecord? =
        backStacks[key]?.takeIf { it.isNotEmpty() }?.pop()

    fun getBackStackSize(key: VirtualAppKey): Int = backStacks[key]?.size ?: 0

    fun clearBackStack(key: VirtualAppKey) {
        backStacks.remove(key)
    }

}

/** Lightweight record of a virtual activity on the back-stack. */
data class VirtualActivityRecord(
    val className:     String,
    val packageName:   String,
    val stubClassName: String,
    val launchTime:    Long = System.currentTimeMillis(),
)
