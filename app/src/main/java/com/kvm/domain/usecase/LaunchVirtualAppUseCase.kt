package com.kvm.domain.usecase

import android.content.Context
import android.content.Intent
import com.kvm.presentation.launcher.VirtualLauncherActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Starts the actual [VirtualLauncherActivity] Android component for a virtual
 * app. This must go through a real Activity — calling KVMEngine directly from
 * a ViewModel (as before) never resulted in anything being shown on screen,
 * which is why launching a cloned app silently did nothing.
 */
class LaunchVirtualAppUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    operator fun invoke(packageName: String, userId: Int = 0) {
        val intent = Intent(context, VirtualLauncherActivity::class.java).apply {
            putExtra(VirtualLauncherActivity.EXTRA_PACKAGE, packageName)
            putExtra(VirtualLauncherActivity.EXTRA_USER_ID, userId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
