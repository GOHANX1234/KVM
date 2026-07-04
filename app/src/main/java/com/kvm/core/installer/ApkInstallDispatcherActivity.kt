package com.kvm.core.installer

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.kvm.presentation.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Transparent dispatcher activity that intercepts APK opens from external apps
 * (Files, Downloads, Chrome …) and routes them through [AppInstaller].
 *
 * The activity finishes immediately; the actual install happens in the background
 * via a coroutine.  The user sees a notification or toast confirming the result.
 */
@AndroidEntryPoint
class ApkInstallDispatcherActivity : AppCompatActivity() {

    @Inject lateinit var appInstaller: AppInstaller

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent?.data ?: run {
            Timber.w("ApkInstallDispatcher: no URI in intent")
            finish()
            return
        }

        Timber.i("ApkInstallDispatcher: received APK URI: %s", uri)

        lifecycleScope.launch {
            val result = appInstaller.installApk(this@ApkInstallDispatcherActivity, uri)
            when (result) {
                is AppInstaller.InstallResult.Success -> {
                    Timber.i("ApkInstallDispatcher: installed %s", result.app.packageName)
                    // Navigate to KVM main screen
                    startActivity(
                        Intent(this@ApkInstallDispatcherActivity, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    )
                }
                is AppInstaller.InstallResult.Error -> {
                    Timber.e(result.cause, "ApkInstallDispatcher: install failed: %s", result.message)
                }
            }
            finish()
        }
    }
}
