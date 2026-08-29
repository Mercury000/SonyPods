package dev.sonypods.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val LocalBottomBarBlurEnabled = staticCompositionLocalOf { false }
internal val LocalTopBarBlurEnabled = staticCompositionLocalOf { false }

/** The glass sampling layer shared by every blurred bar inside the host. */
internal val LocalBarBlurBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/**
 * Hosts the [LayerBackdrop] the blurred bars sample from. The record layer is whatever
 * [BarBackdropContent] subtree sits inside [content]; bars themselves must stay outside
 * it (Scaffold bar slots are siblings of the content slot, which is why this works).
 *
 * The backdrop only exists when a bar blur is enabled AND the platform RuntimeShader
 * path is available; bars fall back to an opaque surface via [barBlurBackground].
 */
@Composable
internal fun BarBlurHost(
    bottomBarBlurEnabled: Boolean,
    topBarBlurEnabled: Boolean,
    captureForEffects: Boolean = false,
    content: @Composable () -> Unit,
) {
    val surfaceColor = MiuixTheme.colorScheme.surface
    val backdrop = if ((bottomBarBlurEnabled || topBarBlurEnabled || captureForEffects) && isRuntimeShaderSupported()) {
        rememberLayerBackdrop {
            drawRect(surfaceColor)
            drawContent()
        }
    } else {
        null
    }
    CompositionLocalProvider(
        LocalBottomBarBlurEnabled provides bottomBarBlurEnabled,
        LocalTopBarBlurEnabled provides topBarBlurEnabled,
        LocalBarBlurBackdrop provides backdrop,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            content()
        }
    }
}

/**
 * Marks the layer whose content is recorded into the host backdrop, so the blurred
 * bars sample the page scrolling beneath them instead of their own pixels.
 */
@Composable
internal fun BarBackdropContent(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val backdrop = LocalBarBlurBackdrop.current
    Box(
        modifier = modifier.then(
            if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier,
        ),
    ) {
        content()
    }
}

@Composable
internal fun BlurredBar(
    topGradient: Boolean = false,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier.barBlurBackground(
            shape = RectangleShape,
            topGradient = topGradient,
        ),
    ) {
        content()
    }
}

/**
 * Blur background for a bar. When the corresponding blur switch is off or the
 * RuntimeShader path is unavailable, degrades to an opaque surface background.
 */
@Composable
internal fun Modifier.barBlurBackground(
    shape: Shape,
    topGradient: Boolean = false,
): Modifier {
    val backdrop = LocalBarBlurBackdrop.current
    val blurEnabled = if (topGradient) LocalTopBarBlurEnabled.current else LocalBottomBarBlurEnabled.current
    val surfaceColor = MiuixTheme.colorScheme.surface
    val contentColor = MiuixTheme.colorScheme.onSurface
    return if (blurEnabled && backdrop != null) {
        if (topGradient) {
            progressiveTextureBlur(
                backdrop = backdrop,
                shape = shape,
                blurRadius = BAR_BLUR_RADIUS,
                gradient = TOP_BAR_PROGRESSIVE_BLUR,
                colors = BlurDefaults.blurColors(
                    blendColors = listOf(
                        BlendColorEntry(color = surfaceColor.copy(alpha = TOP_BAR_SURFACE_ALPHA)),
                    ),
                ),
            )
        } else {
            textureBlur(
                backdrop = backdrop,
                shape = shape,
                blurRadius = BAR_BLUR_RADIUS,
                colors = BlurDefaults.blurColors(
                    blendColors = listOf(
                        BlendColorEntry(color = surfaceColor.copy(alpha = BOTTOM_BAR_SURFACE_ALPHA)),
                        BlendColorEntry(color = contentColor.copy(alpha = BAR_GLASS_TINT_ALPHA)),
                    ),
                ),
            )
        }
    } else {
        background(color = surfaceColor, shape = shape)
    }
}

private val TOP_BAR_PROGRESSIVE_BLUR = ProgressiveBlur.Top.copy(
    startFraction = 0.12f,
    endFraction = 1f,
    curve = 1.25f,
)
private const val BAR_BLUR_RADIUS = 16f
private const val TOP_BAR_SURFACE_ALPHA = 0.66f
private const val BOTTOM_BAR_SURFACE_ALPHA = 0.58f
private const val BAR_GLASS_TINT_ALPHA = 0.025f
