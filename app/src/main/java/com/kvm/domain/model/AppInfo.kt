package com.kvm.domain.model

import android.graphics.drawable.Drawable

/**
 * AppInfo — lightweight domain model representing an installed device app
 * that the user can clone into the virtual space.
 *
 * This is distinct from [com.kvm.core.virtual.VirtualApp] which represents
 * an app that has already been cloned/installed inside KVM.
 */
data class AppInfo(
    val packageName:  String,
    val label:        String,
    val versionName:  String,
    val versionCode:  Long,
    val icon:         Drawable?,
    val sourceDir:    String,
    val isSystemApp:  Boolean,
    val installedAt:  Long,
    /** True if this package already has at least one virtual instance. */
    val isCloned:     Boolean = false,
    /** Number of running virtual instances. */
    val runningCount: Int = 0,
)
