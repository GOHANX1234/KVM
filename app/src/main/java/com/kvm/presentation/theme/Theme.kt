package com.kvm.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val KvmDarkColorScheme = darkColorScheme(
    primary = KvmPrimary,
    onPrimary = KvmOnPrimary,
    primaryContainer = KvmPrimaryContainer,
    onPrimaryContainer = KvmOnPrimaryContainer,
    secondary = KvmSecondary,
    onSecondary = KvmOnSecondary,
    secondaryContainer = KvmSecondaryContainer,
    onSecondaryContainer = KvmOnSecondaryContainer,
    tertiary = KvmTertiary,
    onTertiary = KvmOnTertiary,
    tertiaryContainer = KvmTertiaryContainer,
    onTertiaryContainer = KvmOnTertiaryContainer,
    error = KvmError,
    onError = KvmOnError,
    errorContainer = KvmErrorContainer,
    onErrorContainer = KvmOnErrorContainer,
    background = KvmBackground,
    onBackground = KvmOnBackground,
    surface = KvmSurface,
    onSurface = KvmOnSurface,
    surfaceVariant = KvmSurfaceVariant,
    onSurfaceVariant = KvmOnSurfaceVariant,
    outline = KvmOutline,
    outlineVariant = KvmOutlineVariant,
)

/**
 * KVM's Compose theme wrapper — "Liquid Glass": a single, always-dark,
 * iOS-inspired theme with frosted/translucent surfaces (see [GlassSurfaceHigh]
 * and friends in Color.kt, and the reusable glass components in Glass.kt).
 */
@Composable
fun KVMTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = KvmDarkColorScheme,
        typography = KvmTypography,
        content = content,
    )
}
