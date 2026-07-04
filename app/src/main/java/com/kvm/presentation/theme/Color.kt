package com.kvm.presentation.theme

import androidx.compose.ui.graphics.Color

/**
 * Compose color tokens for KVM's "Liquid Glass" design language — a true-dark,
 * iOS-inspired UI with frosted/translucent surfaces and a vibrant indigo/violet
 * accent family. There is intentionally only one theme (dark); mirrors
 * res/values/colors.xml + themes.xml 1:1.
 */

// Primary palette — vibrant indigo (iOS systemIndigo dark family)
val KvmPrimary = Color(0xFF7C7BFF)
val KvmOnPrimary = Color(0xFF0B0B14)
val KvmPrimaryContainer = Color(0xFF2E2C5C)
val KvmOnPrimaryContainer = Color(0xFFE4E3FF)

// Secondary — electric violet
val KvmSecondary = Color(0xFFB98CFF)
val KvmOnSecondary = Color(0xFF140B24)
val KvmSecondaryContainer = Color(0xFF3A2A5C)
val KvmOnSecondaryContainer = Color(0xFFEBDCFF)

// Tertiary — electric cyan accent
val KvmTertiary = Color(0xFF5AC8FA)
val KvmOnTertiary = Color(0xFF04222D)
val KvmTertiaryContainer = Color(0xFF0E3A47)
val KvmOnTertiaryContainer = Color(0xFFBEEBFF)

// Error
val KvmError = Color(0xFFFF6961)
val KvmOnError = Color(0xFF2A0000)
val KvmErrorContainer = Color(0xFF4A1512)
val KvmOnErrorContainer = Color(0xFFFFDAD6)

// Backgrounds / surfaces — true dark, near-black with a whisper of indigo
val KvmBackground = Color(0xFF08080D)
val KvmOnBackground = Color(0xFFF2F1F7)
val KvmSurface = Color(0xFF131218)
val KvmOnSurface = Color(0xFFF2F1F7)
val KvmSurfaceVariant = Color(0xFF1C1B24)
val KvmOnSurfaceVariant = Color(0xFFB8B6C4)

// Outline
val KvmOutline = Color(0xFF3C3A46)
val KvmOutlineVariant = Color(0xFF2A2830)

// Status colors (used directly, not part of the Material color scheme)
val StatusRunning = Color(0xFF30D158)
val StatusStopped = Color(0xFF8E8E93)
val StatusCrashed = Color(0xFFFF453A)
val StatusStarting = Color(0xFFFF9F0A)

val BadgeBackground = Color(0xFFFF375F)
val BadgeText = Color(0xFFFFFFFF)

// ─── "Liquid Glass" tokens ──────────────────────────────────────────────
// Translucent white overlays used on top of dark surfaces to create a
// frosted-glass effect (card backgrounds, dialogs, floating nav bar, sheets).
val GlassSurfaceHigh = Color(0x26FFFFFF)      // ~15% white — elevated glass (dialogs, nav bar)
val GlassSurfaceMedium = Color(0x17FFFFFF)    // ~9% white — cards, rows
val GlassSurfaceLow = Color(0x0DFFFFFF)       // ~5% white — subtle fills (text fields)
val GlassBorder = Color(0x29FFFFFF)           // ~16% white — hairline borders
val GlassHighlight = Color(0x3DFFFFFF)        // ~24% white — top-edge specular highlight
val GlassShadow = Color(0x99000000)           // ~60% black — soft ambient shadow

// Deep-space background gradient stops (behind all screens)
val SpaceGradientTop = Color(0xFF0E0D17)
val SpaceGradientBottom = Color(0xFF050507)

// Soft glow accents drifting behind glass content
val GlowIndigo = Color(0x4D7C7BFF)
val GlowViolet = Color(0x40B98CFF)
