package com.kvm.core.virtual

import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.drawable.Drawable

/**
 * VirtualApp — runtime representation of an installed virtual application.
 *
 * This is the domain object that lives in memory while KVM is running.
 * It is populated from the Room-persisted [com.kvm.data.db.entities.VirtualAppEntity]
 * when the engine starts, and kept in sync as state changes.
 */
data class VirtualApp(
    /** Unique virtual ID (surrogate key from Room). */
    val id: Long,
    /** Original package name of the cloned/installed app. */
    val packageName: String,
    /** User/instance index: 0 = primary clone, 1+ = additional instances. */
    val userId: Int,
    /** Absolute path to the guest APK inside host private storage. */
    val apkPath: String,
    /** Absolute path to the extracted native libs directory. */
    val nativeLibDir: String,
    /** Absolute path to the isolated data root for this instance. */
    val dataDir: String,
    /** Human-readable label as extracted from the guest APK. */
    val label: String,
    /** Version name reported by the guest APK. */
    val versionName: String,
    /** Version code. */
    val versionCode: Long,
    /** Target SDK of the guest APK. */
    val targetSdk: Int,
    /** Min SDK of the guest APK. */
    val minSdk: Int,
    /** App icon, loaded lazily — null until first access. */
    var icon: Drawable? = null,
    /** Whether this virtual instance auto-starts on device boot. */
    val autoStart: Boolean = false,
    /** Current runtime state of this virtual app instance. */
    var state: VirtualAppState = VirtualAppState.STOPPED,
    /** The PackageInfo parsed from the guest APK; populated after install. */
    var packageInfo: PackageInfo? = null,
    /** The ApplicationInfo from the guest APK. */
    var applicationInfo: ApplicationInfo? = null,
    /** All declared activities from the guest manifest. */
    val activities: List<ActivityInfo> = emptyList(),
    /** Timestamp (epoch ms) when this virtual app was installed. */
    val installedAt: Long = System.currentTimeMillis(),
)

enum class VirtualAppState {
    /** Not running; no process allocated. */
    STOPPED,
    /** Process is starting up; hooks are being installed. */
    STARTING,
    /** Main activity is visible. */
    RUNNING,
    /** Moved to background. */
    BACKGROUND,
    /** Process crashed or was killed. */
    CRASHED,
}

/** Stable key uniquely identifying a virtual app instance. */
data class VirtualAppKey(val packageName: String, val userId: Int) {
    override fun toString(): String = "$packageName:$userId"
}
