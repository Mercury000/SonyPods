package dev.sonypods.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The About tab page: a thin pass-through to [AboutPage], which owns its full-bleed
 * layout (animated hero gradient under the status bar, no top bar) exactly like the
 * reference implementation.
 */
@Composable
internal fun AboutTabPage(
    isActive: Boolean,
    pageBottomContentPadding: Dp,
    onOpenReferences: () -> Unit,
) {
    val backgroundColor = MiuixTheme.colorScheme.background
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor),
    ) {
        AboutPage(
            padding = PaddingValues(bottom = pageBottomContentPadding),
            isActive = isActive,
            onOpenReferences = onOpenReferences,
        )
    }
}
