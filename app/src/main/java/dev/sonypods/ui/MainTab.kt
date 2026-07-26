package dev.sonypods.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import dev.sonypods.R
import dev.sonypods.ui.components.AppIcons
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Settings

internal enum class MainTab(val icon: ImageVector) {
    Module(AppIcons.Home),
    Earphones(AppIcons.Headphones),
    Settings(MiuixIcons.Settings),
}

@Composable
internal fun MainTab.title(): String = when (this) {
    MainTab.Module -> stringResource(R.string.module)
    MainTab.Earphones -> stringResource(R.string.earphones)
    MainTab.Settings -> stringResource(R.string.settings)
}
