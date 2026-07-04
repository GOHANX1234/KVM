package com.kvm.presentation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kvm.R
import com.kvm.presentation.apps.AppsScreen
import com.kvm.presentation.home.HomeScreen
import com.kvm.presentation.settings.SettingsScreen
import com.kvm.presentation.theme.AppBackground
import com.kvm.presentation.theme.GlassPillShape
import com.kvm.presentation.theme.KVMTheme
import com.kvm.presentation.theme.glassSurface

/** Top-level Compose routes — mirrors the previous nav_graph.xml destinations
 * (minus the launcher, which is a plain Activity started via Intent). */
sealed class KvmRoute(val route: String, val label: String) {
    data object Home : KvmRoute("home", "Home")
    data object Apps : KvmRoute("apps", "Apps")
    data object Settings : KvmRoute("settings", "Settings")

    companion object {
        val bottomBarRoutes = listOf(Home, Apps, Settings)
    }
}

@Composable
fun KVMApp() {
    KVMTheme {
        val navController = rememberNavController()

        Box(modifier = Modifier.fillMaxSize()) {
            AppBackground()

            NavHost(
                navController = navController,
                startDestination = KvmRoute.Home.route,
                modifier = Modifier.fillMaxSize(),
            ) {
                composable(KvmRoute.Home.route) {
                    HomeScreen(onNavigateToApps = {
                        navController.navigate(KvmRoute.Apps.route)
                    })
                }
                composable(KvmRoute.Apps.route) {
                    AppsScreen()
                }
                composable(KvmRoute.Settings.route) {
                    SettingsScreen()
                }
            }

            FloatingBottomBar(
                navController = navController,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/**
 * Small, floating "liquid glass" pill nav bar — iOS-style, hovering above the
 * transparent system navigation bar rather than stretching edge-to-edge.
 */
@Composable
private fun FloatingBottomBar(navController: NavHostController, modifier: Modifier = Modifier) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Box(
        modifier = modifier
            .navigationBarsPadding()
            .padding(bottom = 16.dp, start = 32.dp, end = 32.dp)
            .height(64.dp)
            .glassSurface(shape = GlassPillShape, elevated = true),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 6.dp),
        ) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KvmRoute.bottomBarRoutes.forEach { destination ->
                    val selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true
                    val iconRes = when (destination) {
                        KvmRoute.Home -> R.drawable.ic_nav_home
                        KvmRoute.Apps -> R.drawable.ic_nav_apps
                        else -> R.drawable.ic_nav_settings
                    }
                    NavPillItem(
                        selected = selected,
                        iconRes = iconRes,
                        label = destination.label,
                        onClick = {
                            if (!selected) {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun NavPillItem(
    selected: Boolean,
    iconRes: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val indicatorColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else androidx.compose.ui.graphics.Color.Transparent,
        animationSpec = tween(220),
        label = "navIndicatorColor",
    )
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .clip(GlassPillShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .background(indicatorColor, shape = GlassPillShape)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.layout.Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = label,
                    tint = contentColor,
                    modifier = Modifier.size(22.dp),
                )
                if (selected) {
                    Text(
                        text = label,
                        color = contentColor,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}
