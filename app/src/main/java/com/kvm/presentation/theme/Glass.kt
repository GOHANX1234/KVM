package com.kvm.presentation.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared "Liquid Glass" building blocks — a frosted, translucent surface
 * language used across cards, dialogs, the floating bottom nav bar and text
 * fields. Real-time backdrop blur (RenderEffect) requires API 31+ and a
 * compatible compositing surface, so instead we approximate the frosted-glass
 * look everywhere (API 26+) with layered translucent gradients, a hairline
 * top-highlight border, and soft ambient shadows — visually consistent and
 * fully compatible with minSdk 26.
 */

/** Vertical gradient used for elevated glass surfaces (dialogs, nav bar, FAB). */
private val glassSurfaceBrush = Brush.verticalGradient(
    colors = listOf(GlassSurfaceHigh, GlassSurfaceMedium),
)

/** Flatter gradient for lower-emphasis glass surfaces (cards, list rows). */
private val glassCardBrush = Brush.verticalGradient(
    colors = listOf(GlassSurfaceMedium, GlassSurfaceLow),
)

/** Applies the frosted "liquid glass" look to any composable: translucent
 * gradient fill + hairline border, clipped to [shape]. Place on top of the
 * app's dark background/gradient for the frosted effect to read correctly. */
fun Modifier.glassSurface(
    shape: Shape = RoundedCornerShape(24.dp),
    elevated: Boolean = true,
): Modifier = this
    .clip(shape)
    .background(if (elevated) glassSurfaceBrush else glassCardBrush)
    .border(width = 1.dp, color = GlassBorder, shape = shape)

/**
 * Full-bleed deep-space background: a near-black vertical gradient with soft,
 * blurred indigo/violet glow "orbs" for depth — the canvas every screen sits
 * on so glass surfaces have something atmospheric to read against.
 */
@Composable
fun AppBackground(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(SpaceGradientTop, SpaceGradientBottom),
                )
            ),
    ) {
        GlowOrb(
            color = GlowIndigo,
            size = 260.dp,
            modifier = Modifier.offset(x = (-80).dp, y = (-60).dp),
        )
        GlowOrb(
            color = GlowViolet,
            size = 220.dp,
            modifier = Modifier.offset(x = 220.dp, y = 420.dp),
        )
    }
}

@Composable
private fun GlowOrb(color: androidx.compose.ui.graphics.Color, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .blur(size / 2)
            .background(color, shape = androidx.compose.foundation.shape.CircleShape),
    )
}

/** Standard corner radius for glass cards/rows — matches iOS-style continuous corners. */
val GlassCardShape = RoundedCornerShape(20.dp)
val GlassDialogShape = RoundedCornerShape(28.dp)
val GlassPillShape = RoundedCornerShape(50)

/** Extra bottom padding every scrollable screen should reserve so content
 * never sits underneath the floating bottom nav bar. */
val BottomBarClearance = 108.dp
