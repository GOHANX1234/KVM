package com.kvm.presentation.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.rememberAsyncImagePainter
import com.kvm.R
import com.kvm.core.virtual.VirtualApp
import com.kvm.presentation.theme.BadgeBackground
import com.kvm.presentation.theme.BadgeText
import com.kvm.presentation.theme.BottomBarClearance
import com.kvm.presentation.theme.GlassDialogShape
import com.kvm.presentation.theme.glassSurface
import kotlinx.coroutines.launch

/**
 * HomeScreen — the main KVM launcher screen (Compose replacement for the
 * former HomeFragment + fragment_home.xml + VirtualAppGridAdapter).
 *
 * Shows all installed virtual apps in an iOS-inspired icon grid, rendered on
 * the app's "liquid glass" dark background. Long-press a tile to get options
 * (launch, add instance, info, uninstall).
 */
@Composable
fun HomeScreen(
    onNavigateToApps: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val virtualApps by viewModel.virtualApps.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var optionsApp by remember { mutableStateOf<VirtualApp?>(null) }
    var infoApp by remember { mutableStateOf<VirtualApp?>(null) }
    var uninstallApp by remember { mutableStateOf<VirtualApp?>(null) }

    LaunchedEffect(Unit) {
        viewModel.uiEvents.collect { event ->
            val message = when (event) {
                is HomeViewModel.UiEvent.AppLaunched -> null
                is HomeViewModel.UiEvent.Error -> event.message
                is HomeViewModel.UiEvent.AppUninstalled -> "App uninstalled"
            }
            if (message != null) {
                scope.launch { snackbarHostState.showSnackbar(message) }
            }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                FloatingActionButton(
                    onClick = { /* Install APK — hooked up to a file picker in a follow-up pass */ },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f),
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 0.dp),
                ) {
                    Icon(Icons.Filled.InstallMobile, contentDescription = "Install APK")
                }
                Spacer(modifier = Modifier.size(12.dp))
                ExtendedFloatingActionButton(
                    onClick = onNavigateToApps,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Clone App", fontWeight = FontWeight.SemiBold) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 0.dp),
                )
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (virtualApps.isEmpty()) {
                EmptyState()
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = 20.dp,
                        bottom = BottomBarClearance,
                    ),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(virtualApps, key = { "${it.packageName}:${it.userId}" }) { app ->
                        AppTile(
                            app = app,
                            onClick = { viewModel.launchVirtualApp(app.packageName, app.userId) },
                            onLongClick = { optionsApp = app },
                        )
                    }
                }
            }
        }
    }

    optionsApp?.let { app ->
        AppOptionsDialog(
            app = app,
            onDismiss = { optionsApp = null },
            onLaunch = { viewModel.launchVirtualApp(app.packageName, app.userId); optionsApp = null },
            onAddInstance = { viewModel.addInstance(app.packageName); optionsApp = null },
            onInfo = { infoApp = app; optionsApp = null },
            onUninstall = { uninstallApp = app; optionsApp = null },
        )
    }

    infoApp?.let { app ->
        AppInfoDialog(app = app, onDismiss = { infoApp = null })
    }

    uninstallApp?.let { app ->
        UninstallConfirmDialog(
            app = app,
            onConfirm = { viewModel.uninstall(app); uninstallApp = null },
            onDismiss = { uninstallApp = null },
        )
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .glassSurface(shape = CircleShape, elevated = false),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_kvm_logo_small),
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(modifier = Modifier.size(24.dp))
        Text(
            text = stringResource(R.string.empty_state_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.empty_state_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppTile(
    app: VirtualApp,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(8.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            val painter: Painter = if (app.icon != null) {
                rememberAsyncImagePainter(app.icon)
            } else {
                rememberAsyncImagePainter(app.apkPath)
            }
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .glassSurface(shape = RoundedCornerShape(16.dp), elevated = false),
            ) {
                Image(
                    painter = painter,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Crop,
                )
            }
            if (app.userId > 0) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .align(Alignment.TopEnd)
                        .clip(CircleShape)
                        .background(BadgeBackground),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "#${app.userId}",
                        color = BadgeText,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.size(6.dp))
        Text(
            text = app.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AppOptionsDialog(
    app: VirtualApp,
    onDismiss: () -> Unit,
    onLaunch: () -> Unit,
    onAddInstance: () -> Unit,
    onInfo: () -> Unit,
    onUninstall: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = GlassDialogShape,
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        title = { Text(app.label, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                DialogOptionRow("Launch", onLaunch)
                DialogOptionRow("Add Instance", onAddInstance)
                DialogOptionRow("App Info", onInfo)
                DialogOptionRow("Uninstall", onUninstall, danger = true)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DialogOptionRow(label: String, onClick: () -> Unit, danger: Boolean = false) {
    Text(
        text = label,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 4.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun AppInfoDialog(app: VirtualApp, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = GlassDialogShape,
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        title = { Text(app.label, fontWeight = FontWeight.SemiBold) },
        text = {
            Text(
                buildString {
                    appendLine("Package: ${app.packageName}")
                    appendLine("Version: ${app.versionName} (${app.versionCode})")
                    appendLine("Instance: #${app.userId}")
                    appendLine("Target SDK: ${app.targetSdk}")
                    appendLine("Data: ${app.dataDir}")
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

@Composable
private fun UninstallConfirmDialog(
    app: VirtualApp,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = GlassDialogShape,
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        title = { Text("Uninstall", fontWeight = FontWeight.SemiBold) },
        text = {
            Text(
                "Remove ${app.label} (instance #${app.userId}) from KVM?",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Uninstall", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
