package com.kvm.presentation

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.kvm.core.virtual.VirtualFileSystem
import com.kvm.di.ServiceLocator
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * MainActivity — Compose host for KVM's launcher UI (Home / Apps / Settings).
 *
 * Replaces the former Navigation Component + Fragment setup
 * (activity_main.xml + nav_graph.xml + bottom_nav_menu.xml). Navigation
 * between the three top-level screens is now handled by navigation-compose
 * inside [KVMApp]; VirtualLauncherActivity (which hosts arbitrary guest app
 * windows) remains a separate plain Activity, started via Intent, and is
 * unaffected by this migration.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var virtualFileSystem: VirtualFileSystem

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* handle results in ViewModel */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        // Draw behind the status/navigation bars ourselves — required for a
        // truly transparent, edge-to-edge look on API 29+ and to avoid the
        // system dimming/scrim Android otherwise applies automatically.
        //
        // KVM is always dark (no light theme), so both bars are forced into
        // "dark" style (light icons) with a transparent scrim, regardless of
        // the device's own light/dark system setting — otherwise the system
        // would apply its own light scrim/dark-icon heuristics based on the
        // device setting and the nav/status bars would show as a grey band
        // instead of blending into the app's near-black background.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        // Populate ServiceLocator for non-injectable contexts
        ServiceLocator.virtualFileSystem = virtualFileSystem

        setContent {
            KVMApp()
        }

        requestRequiredPermissions()
    }

    private fun requestRequiredPermissions() {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }
}
