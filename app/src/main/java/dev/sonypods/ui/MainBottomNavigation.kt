package dev.sonypods.ui

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.zIndex
import dev.sonypods.ui.components.barBlurBackground
import dev.sonypods.ui.components.liquid.IosLiquidGlassNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.FloatingToolbarDefaults
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.NavigationRail
import top.yukonga.miuix.kmp.basic.NavigationRailItem
import top.yukonga.miuix.kmp.basic.NavigationRailValue
import top.yukonga.miuix.kmp.basic.rememberNavigationRailState
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun MainBottomNavigation(
    tabs: List<MainTab>,
    selectedTab: MainTab,
    floating: Boolean,
    blur: Boolean,
    iosStyle: Boolean,
    backdrop: LayerBackdrop?,
    onTabClick: (MainTab) -> Unit,
) {
    if (iosStyle) {
        // The iOS bar carries its own glass surface and samples the page through the
        // backdrop, so the texture-blur modifier path does not apply to it.
        val items = tabs.map { tab -> NavigationItem(label = tab.title(), icon = tab.icon) }
        IosLiquidGlassNavigationBar(
            items = items,
            selectedIndex = tabs.indexOf(selectedTab).coerceAtLeast(0),
            onItemClick = { index -> onTabClick(tabs[index]) },
            backdrop = backdrop,
            modifier = Modifier.zIndex(2f),
        )
        return
    }

    // Glass bar parameters follow the miuix example app: 16f radius with a surface
    // scrim and a faint content-color tint, falling back to the opaque surface when
    // blur is off or the RuntimeShader path is unavailable.
    val barModifier = Modifier
        .barBlurBackground(
            shape = if (floating) {
                RoundedCornerShape(FloatingToolbarDefaults.CornerRadius)
            } else {
                RectangleShape
            },
        )
        .zIndex(2f)

    if (floating) {
        FloatingNavigationBar(
            modifier = barModifier,
            color = if (blur) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer,
        ) {
            tabs.forEach { tab ->
                FloatingNavigationBarItem(
                    selected = selectedTab == tab,
                    onClick = { onTabClick(tab) },
                    icon = tab.icon,
                    label = tab.title(),
                )
            }
        }
    } else {
        // The classic full-width bar keeps miuix's divider hairline (the floating
        // pill has none by default), matching the miuix example app.
        NavigationBar(
            modifier = barModifier,
            color = if (blur) Color.Transparent else MiuixTheme.colorScheme.surface,
            showDivider = !floating,
        ) {
            tabs.forEach { tab ->
                NavigationBarItem(
                    selected = selectedTab == tab,
                    onClick = { onTabClick(tab) },
                    icon = tab.icon,
                    label = tab.title(),
                )
            }
        }
    }
}

@Composable
internal fun MainNavigationRail(
    tabs: List<MainTab>,
    selectedTab: MainTab,
    expandRail: Boolean,
    onTabClick: (MainTab) -> Unit,
) {
    val railState = rememberNavigationRailState(
        initialValue = if (expandRail) NavigationRailValue.Expanded else NavigationRailValue.Collapsed,
    )
    LaunchedEffect(expandRail) {
        if (expandRail) railState.expand() else railState.collapse()
    }
    NavigationRail(
        state = railState,
        modifier = Modifier.fillMaxHeight(),
    ) {
        tabs.forEach { tab ->
            NavigationRailItem(
                selected = selectedTab == tab,
                onClick = { onTabClick(tab) },
                icon = tab.icon,
                label = tab.title(),
            )
        }
    }
}
