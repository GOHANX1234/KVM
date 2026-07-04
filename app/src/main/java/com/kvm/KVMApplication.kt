package com.kvm

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.kvm.core.engine.KVMEngine
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class KVMApplication : Application() {

    @Inject lateinit var kvmEngine: KVMEngine
    @Inject lateinit var virtualFileSystem: com.kvm.core.virtual.VirtualFileSystem

    override fun onCreate() {
        super.onCreate()
        initLogging()
        // Populate ServiceLocator FIRST — before anything that runs in
        // background processes or without an Activity (hooks, VirtualContext, etc.)
        com.kvm.di.ServiceLocator.virtualFileSystem = virtualFileSystem
        createNotificationChannels()
        kvmEngine.initialize(this)
    }

    private fun initLogging() {
        if (BuildConfig.ENABLE_VERBOSE_LOG) {
            Timber.plant(Timber.DebugTree())
        } else {
            // Release: only log warnings and above
            Timber.plant(object : Timber.Tree() {
                override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                    if (priority >= android.util.Log.WARN) {
                        android.util.Log.println(priority, tag ?: "KVM", message)
                    }
                }
            })
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val engineChannel = NotificationChannel(
            CHANNEL_ENGINE,
            "KVM Engine",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Virtual space engine status"
            setShowBadge(false)
        }

        val appsChannel = NotificationChannel(
            CHANNEL_APPS,
            "Running Virtual Apps",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifications from cloned apps"
        }

        nm.createNotificationChannels(listOf(engineChannel, appsChannel))
    }

    companion object {
        const val CHANNEL_ENGINE = "kvm_engine"
        const val CHANNEL_APPS   = "kvm_apps"
    }
}
