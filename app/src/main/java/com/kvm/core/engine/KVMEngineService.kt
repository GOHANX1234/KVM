package com.kvm.core.engine

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.kvm.KVMApplication
import com.kvm.R
import com.kvm.presentation.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

/**
 * Foreground service that keeps the KVM engine alive while virtual apps are running.
 * Without a foreground service, Android may kill the host process and destroy all
 * virtual app instances.
 */
@AndroidEntryPoint
class KVMEngineService : Service() {

    @Inject lateinit var kvmEngine: KVMEngine

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                Timber.i("KVMEngineService: START")
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            ACTION_STOP -> {
                Timber.i("KVMEngineService: STOP")
                kvmEngine.stopAllApps()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Timber.i("KVMEngineService: destroyed")
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, KVMEngineService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, KVMApplication.CHANNEL_ENGINE)
            .setContentTitle("KVM Virtual Space")
            .setContentText("Virtual environment is running")
            .setSmallIcon(R.drawable.ic_kvm_notification)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_stop, "Stop", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val ACTION_START = "com.kvm.engine.START"
        const val ACTION_STOP  = "com.kvm.engine.STOP"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, KVMEngineService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, KVMEngineService::class.java).apply { action = ACTION_STOP }
            )
        }
    }
}
