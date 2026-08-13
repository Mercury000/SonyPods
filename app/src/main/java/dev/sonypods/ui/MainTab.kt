package dev.sonypods.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.mercury.sonypods.R
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Settings
import dev.sonypods.ui.components.AppIcons

internal enum class MainTab(val icon: ImageVector) {
    Earphones(AppIcons.Headphones),
    Settings(MiuixIcons.Settings),
}

@Composable
internal fun MainTab.title(): String = when (this) {
    MainTab.Earphones -> stringResource(R.string.earphones)
    MainTab.Settings -> stringResource(R.string.settings)
}
