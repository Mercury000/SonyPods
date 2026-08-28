package dev.sonypods.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.sonypods.bridge.SonyStateSnapshot
import dev.sonypods.config.VisibilityConfig
import dev.sonypods.ui.SonyDetailActions

/**
 * The "更多设置" sub-page of the earphone detail flow: renders every card the
 * user relocated off the detail page via the visibility settings. The card
 * rendering itself is shared with the detail page ([podControlItems] with
 * [forMorePage]); only the phone-status hero image and the fixed battery/ANC
 * cards stay behind on the detail page.
 */
@Composable
internal fun MoreSettingsPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    bottomContentPadding: Dp = 16.dp,
    uiState: SonyStateSnapshot,
    actions: SonyDetailActions,
    visibility: VisibilityConfig,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        podControlItems(
            uiState = uiState,
            actions = actions,
            visibility = visibility,
            bottomContentPadding = bottomContentPadding,
            forMorePage = true,
        )
    }
}
