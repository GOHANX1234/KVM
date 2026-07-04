package com.kvm.core.hook

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import com.kvm.core.virtual.VirtualActivityManager
import timber.log.Timber
import java.lang.reflect.Field
import javax.inject.Inject
import javax.inject.Singleton

/**
 * InstrumentationHook
 *
 * Replaces the system Instrumentation instance in ActivityThread with our own
 * subclass.  This is the final interception point in the StubActivity trick:
 *
 *   AMS → ... → Instrumentation.newActivity(classLoader, stubClass, intent)
 *
 * Our override:
 *   1. Checks if the intent carries EXTRA_VIRTUAL_CLASS
 *   2. If yes, loads the real guest Activity class from the virtual ClassLoader
 *   3. Returns an instance of the real guest Activity instead of the stub
 *   4. The stub Activity's window, lifecycle callbacks etc. all work normally
 *      because the guest Activity extends Activity just like any other app.
 *
 * This requires no root — Instrumentation is a regular public class and the
 * ActivityThread field is accessible via reflection.
 */
@Singleton
class InstrumentationHook @Inject constructor(
    private val vam: VirtualActivityManager,
) {
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            val currentAt = atClass.getDeclaredMethod("currentActivityThread")
                .also { it.isAccessible = true }
                .invoke(null) ?: return

            val instrField: Field = atClass.getDeclaredField("mInstrumentation")
                .also { it.isAccessible = true }

            val originalInstr = instrField.get(currentAt) as? Instrumentation ?: return
            val hookedInstr   = VirtualInstrumentation(originalInstr, vam)
            instrField.set(currentAt, hookedInstr)
            installed = true
            Timber.i("InstrumentationHook: installed")
        }.onFailure {
            Timber.e(it, "InstrumentationHook: failed to install")
        }
    }
}

/**
 * The hooked Instrumentation — delegates everything to the original except
 * [newActivity], where we swap the stub class for the real guest class.
 */
private class VirtualInstrumentation(
    private val base: Instrumentation,
    private val vam: VirtualActivityManager,
) : Instrumentation() {

    override fun newActivity(
        cl: ClassLoader?,
        className: String,
        intent: Intent?,
    ): Activity {
        // Is this a stub being resolved?
        val resolved = intent?.let { vam.resolveRealActivity(it) }

        if (resolved != null) {
            val (guestPkg, guestClass) = resolved
            val userId = intent?.getIntExtra(VirtualActivityManager.EXTRA_USER_ID, 0) ?: 0
            Timber.d("VirtualInstrumentation: newActivity stub %s → real %s/%s",
                className, guestPkg, guestClass)
            runCatching {
                // This process may never have run VirtualProcessManager.startVirtualApp
                // (e.g. a fresh :p0-:p4 stub process) — load the guest APK here,
                // synchronously, so the ClassLoader below is guaranteed to exist.
                vam.ensureApkLoaded(guestPkg, userId)
                // Look up the ClassLoader registered for this virtual package
                val loader = com.kvm.core.loader.ClassLoaderManager
                    .getClassLoader(guestPkg) ?: cl
                return super.newActivity(loader, guestClass, intent)
            }.onFailure {
                Timber.e(it, "VirtualInstrumentation: failed to instantiate %s, falling back to stub", guestClass)
            }
        }
        return super.newActivity(cl, className, intent)
    }

    override fun callActivityOnCreate(activity: Activity, icicle: Bundle?) {
        patchActivityContext(activity)
        super.callActivityOnCreate(activity, icicle)
    }

    override fun callActivityOnCreate(activity: Activity, icicle: Bundle?, persistentState: android.os.PersistableBundle?) {
        patchActivityContext(activity)
        super.callActivityOnCreate(activity, icicle, persistentState)
    }

    /**
     * Replace the Activity's base context with a virtual context that returns
     * the correct package name, data dirs, etc. for the guest app.
     */
    private fun patchActivityContext(activity: Activity) {
        runCatching {
            val intentPkg = activity.intent
                ?.getStringExtra(VirtualActivityManager.EXTRA_VIRTUAL_PACKAGE)
                ?: return
            val userId = activity.intent
                ?.getIntExtra(VirtualActivityManager.EXTRA_USER_ID, 0)
                ?: 0

            // Wrap context to return guest identity
            val virtualCtx = VirtualContext(activity.baseContext, intentPkg, userId)
            val activityClass = Activity::class.java
            val baseField = activityClass.getDeclaredField("mBase")
                .also { it.isAccessible = true }
            baseField.set(activity, virtualCtx)
        }.onFailure {
            Timber.w(it, "patchActivityContext: failed for %s", activity.javaClass.name)
        }
    }
}
