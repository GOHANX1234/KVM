package com.kvm.core.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

/**
 * Restarts the KVM engine service after device reboot so auto-start virtual
 * apps can resume (if the user has enabled that feature per-app).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            Timber.i("BootReceiver: device booted, starting KVM engine")
            KVMEngineService.start(context)
        }
    }
}
