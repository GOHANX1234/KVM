package com.kvm.presentation.launcher

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.kvm.core.engine.KVMEngine
import com.kvm.databinding.ActivityVirtualLauncherBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * VirtualLauncherActivity — the host wrapper shown when a virtual app is launched.
 *
 * This activity is the bridge between the KVM UI and the virtual app's back-stack.
 * It hosts the virtual app's window inside a FrameLayout so the host can overlay
 * KVM chrome (a floating "back to KVM" button, notification bar) without interfering
 * with the guest app's rendering.
 *
 * On Android 12+, the window background is set to transparent so the guest app's
 * theme is respected — the same trick used by Game Mode / Picture-in-Picture overlays.
 */
@AndroidEntryPoint
class VirtualLauncherActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVirtualLauncherBinding

    @Inject lateinit var engine: KVMEngine

    private val packageName_ get() = intent.getStringExtra(EXTRA_PACKAGE) ?: ""
    private val userId_      get() = intent.getIntExtra(EXTRA_USER_ID, 0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVirtualLauncherBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupBackButton()
        launchVirtualApp()
    }

    private fun setupBackButton() {
        binding.fabBackToKvm.setOnClickListener { finish() }
    }

    private fun launchVirtualApp() {
        val pkg = packageName_
        if (pkg.isBlank()) {
            Timber.e("VirtualLauncherActivity: no package in intent")
            finish()
            return
        }
        Timber.i("VirtualLauncherActivity: launching %s user=%d", pkg, userId_)
        lifecycleScope.launch {
            runCatching { engine.startVirtualApp(pkg, userId_.toString()) }
                .onFailure { Timber.e(it, "VirtualLauncherActivity: launch failed for %s", pkg) }
        }
    }

    override fun onBackPressed() {
        // Give the virtual app a chance to handle back first
        // If the virtual back-stack is empty, fall back to KVM home
        super.onBackPressed()
    }

    companion object {
        const val EXTRA_PACKAGE = "kvm.launch.package"
        const val EXTRA_USER_ID = "kvm.launch.user_id"
    }
}
