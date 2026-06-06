package moe.chenxy.oppopods.ui.components

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import moe.chenxy.oppopods.R
import moe.chenxy.oppopods.pods.NoiseControlMode
import moe.chenxy.oppopods.pods.isNoiseCancellation
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.SinkFeedback
import top.yukonga.miuix.kmp.utils.pressable

private const val ANIM_DURATION = 300

@Composable
fun AncSwitch(
    ancStatus: NoiseControlMode,
    onAncModeChange: (NoiseControlMode) -> Unit,
    compact: Boolean = false,
    adaptiveModeEnabled: Boolean = true,
    transparencyVocalEnhancement: Boolean = false,
    onTransparencyVocalEnhancementChange: ((Boolean) -> Unit)? = null
) {
    val verticalPadding = if (compact) 8.dp else 16.dp
    val tabMinWidth = 0.dp
    val tabMaxWidth = if (compact) 72.dp else 98.dp
    val tabHeight = if (compact) 38.dp else 45.dp
    val tabSpacing = if (compact) 4.dp else 9.dp
    val tabOuterPadding = if (compact) 8.dp else 12.dp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = verticalPadding)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AncButton(
                offIconRes = R.drawable.ic_openanc_off,
                onIconRes = R.drawable.ic_openanc_on,
                label = stringResource(R.string.noise_cancellation_title),
                isSelected = ancStatus.isNoiseCancellation(),
                onClick = { onAncModeChange(NoiseControlMode.NOISE_CANCELLATION) },
                modifier = Modifier.weight(1f),
                compact = compact
            )
            // Adaptive模式按钮：仅当设置中启用Adaptive模式时显示
            if (adaptiveModeEnabled) {
                AncButton(
                    offIconRes = R.drawable.ic_adaptive_off,
                    onIconRes = R.drawable.ic_adaptive_on,
                    label = stringResource(R.string.adaptive_title),
                    isSelected = ancStatus == NoiseControlMode.ADAPTIVE,
                    onClick = { onAncModeChange(NoiseControlMode.ADAPTIVE) },
                    modifier = Modifier.weight(1f),
                    compact = compact
                )
            }
            AncButton(
                offIconRes = R.drawable.ic_transparent_off,
                onIconRes = R.drawable.ic_transparent_on,
                label = stringResource(R.string.transparency_title),
                isSelected = ancStatus == NoiseControlMode.TRANSPARENCY,
                onClick = { onAncModeChange(NoiseControlMode.TRANSPARENCY) },
                modifier = Modifier.weight(1f),
                compact = compact
            )
            AncButton(
                offIconRes = R.drawable.ic_closeanc_off,
                onIconRes = R.drawable.ic_closeanc_on,
                label = stringResource(R.string.off),
                isSelected = ancStatus == NoiseControlMode.OFF,
                onClick = { onAncModeChange(NoiseControlMode.OFF) },
                modifier = Modifier.weight(1f),
                compact = compact
            )
        }

        if (ancStatus.isNoiseCancellation()) {
            val modes = listOf(
                NoiseControlMode.NOISE_CANCELLATION_SMART,
                NoiseControlMode.NOISE_CANCELLATION_LIGHT,
                NoiseControlMode.NOISE_CANCELLATION_MEDIUM,
                NoiseControlMode.NOISE_CANCELLATION_DEEP
            )
            val tabs = listOf(
                stringResource(R.string.noise_cancellation_smart),
                stringResource(R.string.noise_cancellation_light),
                stringResource(R.string.noise_cancellation_medium),
                stringResource(R.string.noise_cancellation_deep)
            )
            ResponsiveAncTabRow(
                tabs = tabs,
                selectedTabIndex = modes.indexOf(ancStatus).takeIf { it >= 0 } ?: 0,
                onTabSelected = { onAncModeChange(modes[it]) },
                compact = compact,
                minWidth = tabMinWidth,
                tabMaxWidth = tabMaxWidth,
                height = tabHeight,
                itemSpacing = tabSpacing,
                outerPadding = tabOuterPadding
            )
        }

        if (ancStatus == NoiseControlMode.TRANSPARENCY && onTransparencyVocalEnhancementChange != null) {
            val tabs = listOf(
                stringResource(R.string.transparency_title),
                stringResource(R.string.transparency_vocal_enhancement)
            )
            ResponsiveAncTabRow(
                tabs = tabs,
                selectedTabIndex = if (transparencyVocalEnhancement) 1 else 0,
                onTabSelected = { onTransparencyVocalEnhancementChange(it == 1) },
                compact = compact,
                minWidth = tabMinWidth,
                tabMaxWidth = tabMaxWidth,
                height = tabHeight,
                itemSpacing = tabSpacing,
                outerPadding = tabOuterPadding
            )
        }
    }
}

@Composable
private fun ResponsiveAncTabRow(
    tabs: List<String>,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    compact: Boolean,
    minWidth: Dp,
    tabMaxWidth: Dp,
    height: Dp,
    itemSpacing: Dp,
    outerPadding: Dp
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (compact) 8.dp else 16.dp),
        contentAlignment = Alignment.Center
    ) {
        TabRowWithContour(
            tabs = tabs,
            selectedTabIndex = selectedTabIndex,
            onTabSelected = onTabSelected,
            modifier = Modifier
                .padding(horizontal = outerPadding)
                .fillMaxWidth(if (maxWidth >= 480.dp) 0.8f else 1f),
            minWidth = minWidth,
            maxWidth = tabMaxWidth,
            height = height,
            itemSpacing = itemSpacing
        )
    }
}

@Composable
private fun AncButton(
    offIconRes: Int,
    onIconRes: Int,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }
    val boxSize = if (compact) 40.dp else 60.dp
    val iconSize = if (compact) (if (isSelected) 40.dp else 32.dp) else (if (isSelected) 60.dp else 48.dp)

    val textColor by animateColorAsState(
        targetValue = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onBackground,
        animationSpec = tween(ANIM_DURATION),
        label = "anc_text_color"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .pressable(interactionSource = interactionSource, indication = SinkFeedback())
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
    ) {
        Box(
            modifier = Modifier.size(boxSize),
            contentAlignment = Alignment.Center
        ) {
            Crossfade(
                targetState = isSelected,
                animationSpec = tween(ANIM_DURATION),
                label = "anc_icon"
            ) { selected ->
                Box(
                    modifier = Modifier.size(boxSize),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = themedPainterResource(if (selected) onIconRes else offIconRes),
                        contentDescription = label,
                        modifier = Modifier.size(if (selected) boxSize else (if (compact) 32.dp else 48.dp))
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            label,
            fontSize = if (compact) 12.sp else 14.sp,
            fontWeight = FontWeight.Medium,
            color = textColor
        )
    }
}

/**
 * Loads a drawable resource respecting the app's theme override.
 * Handles both bitmap and vector drawables by rendering via Canvas when needed.
 */
@Composable
private fun themedPainterResource(@androidx.annotation.DrawableRes id: Int): Painter {
    val context = LocalContext.current
    val themeConfig = LocalConfiguration.current
    val sysNightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    val themeNightMode = themeConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK

    return if (sysNightMode == themeNightMode) {
        painterResource(id)
    } else {
        val themedResources = remember(context, themeNightMode) {
            context.createConfigurationContext(
                Configuration(context.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or themeNightMode
                }
            ).resources
        }
        remember(id, themeNightMode) {
            val drawable = themedResources.getDrawable(id, null)
            if (drawable is BitmapDrawable) {
                BitmapPainter(drawable.bitmap.asImageBitmap())
            } else {
                val bitmap = Bitmap.createBitmap(
                    drawable.intrinsicWidth.coerceAtLeast(1),
                    drawable.intrinsicHeight.coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                BitmapPainter(bitmap.asImageBitmap())
            }
        }
    }
}
