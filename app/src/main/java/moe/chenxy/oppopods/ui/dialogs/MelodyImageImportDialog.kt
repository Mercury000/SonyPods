package moe.chenxy.oppopods.ui.dialogs

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.chenxy.oppopods.R
import moe.chenxy.oppopods.config.PodImageResource
import moe.chenxy.oppopods.utils.MelodyImageCandidate
import moe.chenxy.oppopods.utils.RootManager
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun MelodyImageImportDialog(
    show: Boolean,
    currentAddress: String,
    currentName: String,
    onDismissRequest: () -> Unit,
    onImport: (String, String, Map<PodImageResource, ByteArray>) -> Unit,
) {
    var candidates by remember(show) { mutableStateOf<List<MelodyImageCandidate>>(emptyList()) }
    var selectedCandidate by remember(show) { mutableStateOf<MelodyImageCandidate?>(null) }
    var hasRootAccess by remember(show) { mutableStateOf(true) }
    var loading by remember(show) { mutableStateOf(false) }
    var importing by remember(show) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(show) {
        if (!show) return@LaunchedEffect
        loading = true
        hasRootAccess = withContext(Dispatchers.IO) { RootManager.hasRootAccess() }
        candidates = if (hasRootAccess) {
            withContext(Dispatchers.IO) { RootManager.scanMelodyImageCandidates() }
        } else {
            emptyList()
        }
        selectedCandidate = candidates.firstOrNull()
        loading = false
    }

    OverlayDialog(
        title = stringResource(R.string.import_melody_images),
        summary = stringResource(R.string.import_melody_images_summary),
        show = show,
        onDismissRequest = onDismissRequest,
    ) {
        Text(
            text = stringResource(R.string.import_melody_images_hint),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.body2,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )
        if (loading) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InfiniteProgressIndicator()
            }
        } else if (!hasRootAccess) {
            Text(
                text = stringResource(R.string.import_melody_images_root_required),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.body2,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            )
        } else if (candidates.isEmpty()) {
            Text(
                text = stringResource(R.string.import_melody_images_empty),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.body2,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(candidates, key = { it.imageDir }) { candidate ->
                    MelodyImageCandidateRow(
                        candidate = candidate,
                        selected = candidate.imageDir == selectedCandidate?.imageDir,
                        onClick = { selectedCandidate = candidate },
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                text = stringResource(R.string.cancel),
                onClick = onDismissRequest,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(4.dp))
            TextButton(
                text = stringResource(R.string.import_melody_images_action),
                onClick = {
                    val candidate = selectedCandidate ?: return@TextButton
                    if (importing) return@TextButton
                    importing = true
                    scope.launch {
                        val images: Map<PodImageResource, ByteArray> = withContext(Dispatchers.IO) {
                            val paths: Map<PodImageResource, String> = mapOf(
                                PodImageResource.BOX to candidate.boxPath,
                                PodImageResource.LEFT to candidate.rightPath,
                                PodImageResource.RIGHT to candidate.leftPath,
                            )
                            paths.mapNotNull { (resource, path) ->
                                RootManager.readMelodyImage(path)?.takeIf { it.isNotEmpty() }?.let { bytes ->
                                    resource to bytes
                                }
                            }.toMap()
                        }
                        importing = false
                        if (images.size == PodImageResource.entries.size) {
                            onImport(currentAddress, currentName, images)
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

@Composable
private fun MelodyImageCandidateRow(
    candidate: MelodyImageCandidate,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val previewPainter = remember(candidate.imageDir) {
        BitmapFactory.decodeByteArray(candidate.boxBytes, 0, candidate.boxBytes.size)
    }?.let { bitmap -> BitmapPainter(bitmap.asImageBitmap()) }
        ?: painterResource(R.drawable.img_box)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = previewPainter,
            contentDescription = candidate.label,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp)),
            contentScale = ContentScale.Fit,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = candidate.label,
                color = MiuixTheme.colorScheme.onSurface,
                style = MiuixTheme.textStyles.headline1,
            )
            Text(
                text = candidate.imageDir,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.body2,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
