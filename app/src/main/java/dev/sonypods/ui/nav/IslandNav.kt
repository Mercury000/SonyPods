package dev.sonypods.ui.nav

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.LayoutDirection
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import dev.sonypods.ui.components.BarBackdropContent
import dev.sonypods.ui.components.BarBlurHost
import dev.sonypods.ui.components.LocalBarBlurBackdrop
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme

// ---------------------------------------------------------------------------
// Verbatim port of the reference animation system (PredictiveBackMotion.kt).
// ---------------------------------------------------------------------------

internal fun smootherStep(progress: Float): Float {
    val value = progress.coerceIn(0f, 1f)
    return value * value * value * (value * (value * 6f - 15f) + 10f)
}

internal fun predictiveEffectIntensity(smoothProgress: Float): Float =
    1f - smoothProgress * (1f - PREDICTIVE_MIN_EFFECT_INTENSITY)

internal fun predictiveTranslationFraction(percent: Long): Float =
    percent.coerceIn(1L, 100L).toFloat() / 100f

internal fun predictiveExitProgress(maxTranslationPercent: Long): Float =
    1f / predictiveTranslationFraction(maxTranslationPercent)

internal fun predictiveSettleDuration(progress: Float, maxTranslationPercent: Long): Int {
    val translationFraction = predictiveTranslationFraction(maxTranslationPercent)
    val currentTranslation = (progress.coerceAtLeast(0f) * translationFraction).coerceIn(0f, 1f)
    return (PREDICTIVE_SETTLE_DURATION * (1f - currentTranslation))
        .roundToInt()
        .coerceIn(PREDICTIVE_MIN_SETTLE_DURATION, PREDICTIVE_SETTLE_DURATION)
}

internal const val LAYER_ENTER_DURATION = 420
internal const val LAYER_EXIT_DURATION = 380
internal const val PREDICTIVE_CANCEL_DURATION = 280
internal const val PREDICTIVE_DISMISS_DURATION = 24
internal const val BACKGROUND_SCALE_REDUCTION = 0.035f
internal const val BACKGROUND_PARALLAX = 0.025f
internal const val EFFECT_VISIBILITY_THRESHOLD = 0.001f

private const val PREDICTIVE_SETTLE_DURATION = 420
private const val PREDICTIVE_MIN_SETTLE_DURATION = 24
private const val PREDICTIVE_MIN_EFFECT_INTENSITY = 0.5f

// ---------------------------------------------------------------------------
// Verbatim port of the reference predictive backdrop (BlurBars.kt).
// ---------------------------------------------------------------------------

private const val PREDICTIVE_BACK_BLUR_RADIUS = 12f
private const val PREDICTIVE_BACK_DIM_ALPHA = 0.16f

@Composable
internal fun PredictiveBackBackdrop(
    intensity: Float,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val backdrop = LocalBarBlurBackdrop.current
    if (!visible) return
    val effectIntensity = intensity.coerceIn(0f, 1f)
    val dimColor = MiuixTheme.colorScheme.windowDimming.copy(
        alpha = PREDICTIVE_BACK_DIM_ALPHA * effectIntensity,
    )
    Box(
        modifier = if (backdrop != null) {
            modifier.textureBlur(
                backdrop = backdrop,
                shape = RectangleShape,
                blurRadius = PREDICTIVE_BACK_BLUR_RADIUS * effectIntensity,
                noiseCoefficient = 0f,
                colors = BlurDefaults.blurColors(
                    blendColors = listOf(
                        BlendColorEntry(
                            color = dimColor,
                        ),
                    ),
                ),
            )
        } else {
            modifier.background(dimColor)
        },
    )
}

// ---------------------------------------------------------------------------
// The layered navigation runtime, generalized from the reference AppShell to a
// bounded stack of layers. Every mechanism — the per-boundary animatables, the
// predictive handlers, the finish/cancel settles, the enter/exit effects — is the
// reference's, with its hardcoded two levels lifted to [maxLayers].
// ---------------------------------------------------------------------------

internal const val MAX_LAYERS = 3

/** Motion of one boundary: the layer above `coveredDepth`'s owner. */
internal class IslandLayerMotion {
    val progress = Animatable(0f)
    val backdropIntensity = Animatable(0f)
    val coveredDepth = Animatable(0f)

    var predictiveActive by mutableStateOf(false)
    var predictiveCommitting by mutableStateOf(false)
}

/**
 * The reference's detail-layer wrapper: AnimatedVisibility with the predictive
 * translation/covered-scale graphicsLayer and the reference enter/exit slides.
 * [progress] is this layer's own gesture progress; [coveredDepth] the depth this
 * layer is pushed down by the layer above it (0 when none).
 */
@Composable
internal fun IslandLayerHost(
    visible: Boolean,
    progress: Float,
    coveredDepth: Float,
    predictiveActive: Boolean,
    maxTranslationPercent: () -> Long,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val depth = coveredDepth.coerceIn(0f, 1f)
                translationX = -size.width * depth * BACKGROUND_PARALLAX +
                    size.width * progress.coerceAtLeast(0f) *
                    predictiveTranslationFraction(maxTranslationPercent())
                scaleX = 1f - depth * BACKGROUND_SCALE_REDUCTION
                scaleY = scaleX
            },
        enter = slideInHorizontally(
            tween(LAYER_ENTER_DURATION, easing = FastOutSlowInEasing),
        ) { it },
        exit = if (predictiveActive) {
            ExitTransition.None
        } else {
            slideOutHorizontally(
                tween(LAYER_EXIT_DURATION, easing = FastOutSlowInEasing),
            ) { it }
        },
    ) {
        content()
    }
}

/**
 * One boundary level, in the reference's exact scoping:
 *
 * ```
 * BarBlurHost(H_k) {                      // provides the backdrop this level records into
 *     BarBackdropContent { layer k }      // the record layer — THIS LAYER ONLY
 *     PredictiveBackBackdrop(k + 1)       // consumer of H_k, a SIBLING of the record
 *     BarBlurHost(H_k+1) { level k + 1 }  // also a sibling of the record
 * }
 * ```
 *
 * The scoping is load-bearing, not stylistic: putting the consumer or the level above
 * *inside* the record layer makes the recorded layer contain a node that draws that same
 * layer, and the platform's background-blur blend (HyperOS `MiBackgroundBlurBlend`)
 * segfaults in the render thread on the frame the second level engages.
 */
@Composable
internal fun <T> IslandLevelHost(
    level: Int,
    layerCount: Int,
    renderScreens: List<T?>,
    motion: List<IslandLayerMotion>,
    maxTranslationPercent: () -> Long,
    screenContent: @Composable (T) -> Unit,
) {
    if (level >= MAX_LAYERS) return
    val nextMotion = motion.getOrNull(level + 1)
    // The host stays composed even before this level is ever used: an AnimatedVisibility
    // entering composition already visible skips its enter animation, which would drop
    // the very first push into each level.
    BarBackdropContent(modifier = Modifier.fillMaxSize()) {
        IslandLayerHost(
            visible = layerCount > level,
            progress = motion[level].progress.value,
            coveredDepth = nextMotion?.coveredDepth?.value ?: 0f,
            predictiveActive = motion[level].predictiveActive,
            maxTranslationPercent = maxTranslationPercent,
        ) {
            renderScreens.getOrNull(level)?.let { screenContent(it) }
        }
    }
    if (nextMotion != null && level + 1 < MAX_LAYERS) {
        PredictiveBackBackdrop(
            intensity = nextMotion.backdropIntensity.value,
            visible = nextMotion.backdropIntensity.value > EFFECT_VISIBILITY_THRESHOLD,
            modifier = Modifier.fillMaxSize(),
        )
        BarBlurHost(
            bottomBarBlurEnabled = false,
            topBarBlurEnabled = false,
            captureForEffects = motion.isBoundaryEngaged(level + 2, layerCount),
        ) {
            IslandLevelHost(
                level = level + 1,
                layerCount = layerCount,
                renderScreens = renderScreens,
                motion = motion,
                maxTranslationPercent = maxTranslationPercent,
                screenContent = screenContent,
            )
        }
    }
}

/**
 * Whether the boundary at [index] is in play — the layer is pushed, its gesture owns the
 * float, or its backdrop has not faded out yet. A host only needs to record while the
 * boundary that consumes its backdrop is engaged.
 */
internal fun List<IslandLayerMotion>.isBoundaryEngaged(index: Int, layerCount: Int): Boolean {
    val m = getOrNull(index) ?: return false
    return layerCount > index || m.predictiveActive ||
        m.backdropIntensity.value > EFFECT_VISIBILITY_THRESHOLD
}

/**
 * The reference's predictive back wiring for one boundary, verbatim: progress snaps
 * to the system events with the eased backdrop/depth pair, commit runs the linear
 * distance-scaled settle and then dismisses the layer, cancel springs everything
 * back over the reference's 280ms. A plain BackHandler covers the reference's
 * commit-in-progress fallback.
 */
@Composable
internal fun IslandLayerBackHandlers(
    enabled: Boolean,
    motion: IslandLayerMotion,
    maxTranslationPercent: () -> Long,
    onDismissed: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    suspend fun finishPredictiveBack() {
        motion.predictiveCommitting = true
        val duration = predictiveSettleDuration(
            progress = motion.progress.value,
            maxTranslationPercent = maxTranslationPercent(),
        )
        coroutineScope {
            launch {
                motion.progress.animateTo(
                    predictiveExitProgress(maxTranslationPercent()),
                    tween(duration, easing = LinearEasing),
                )
            }
            launch {
                motion.backdropIntensity.animateTo(0f, tween(duration, easing = LinearEasing))
            }
            launch {
                motion.coveredDepth.animateTo(0f, tween(duration, easing = LinearEasing))
            }
        }
        onDismissed()
        delay(PREDICTIVE_DISMISS_DURATION.toLong())
        motion.progress.snapTo(0f)
        motion.predictiveActive = false
        motion.predictiveCommitting = false
    }

    PredictiveBackHandler(enabled = enabled) { events ->
        try {
            events.collect { event ->
                motion.predictiveActive = true
                motion.progress.snapTo(event.progress)
                val smoothProgress = smootherStep(event.progress)
                motion.backdropIntensity.snapTo(predictiveEffectIntensity(smoothProgress))
                motion.coveredDepth.snapTo(1f - smoothProgress)
            }
            finishPredictiveBack()
        } catch (_: CancellationException) {
            if (!motion.predictiveCommitting) {
                coroutineScope {
                    launch {
                        motion.progress.animateTo(
                            0f,
                            tween(PREDICTIVE_CANCEL_DURATION, easing = FastOutSlowInEasing),
                        )
                    }
                    launch {
                        motion.backdropIntensity.animateTo(
                            1f,
                            tween(PREDICTIVE_CANCEL_DURATION, easing = FastOutSlowInEasing),
                        )
                    }
                    launch {
                        motion.coveredDepth.animateTo(
                            1f,
                            tween(PREDICTIVE_CANCEL_DURATION, easing = FastOutSlowInEasing),
                        )
                    }
                }
                motion.predictiveActive = false
            }
        }
    }

    BackHandler(enabled = motion.predictiveActive && motion.predictiveCommitting) {
        scope.launch { finishPredictiveBack() }
    }
}
