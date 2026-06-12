package moe.chenxy.oppopods.ui.dialogs

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import moe.chenxy.oppopods.R
import moe.chenxy.oppopods.config.EarphonePref
import moe.chenxy.oppopods.config.PodImageResource
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun PodImageConfigDialog(
    show: Boolean,
    earphones: List<EarphonePref>,
    currentAddress: String,
    currentName: String,
    onDismissRequest: () -> Unit,
    onSave: (String, String, Map<PodImageResource, Uri?>, Set<PodImageResource>) -> Unit,
) {
    val target = earphones.firstOrNull { it.address.equals(currentAddress, ignoreCase = true) }
        ?: EarphonePref(address = currentAddress, name = currentName)
    var selectedResource by remember(show) { mutableStateOf(PodImageResource.BOX) }
    var selectedImages by remember(show, target.address) { mutableStateOf<Map<PodImageResource, Uri?>>(emptyMap()) }
    var clearedImages by remember(show, target.address) { mutableStateOf<Set<PodImageResource>>(emptySet()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedImages = selectedImages + (selectedResource to uri)
            clearedImages = clearedImages - selectedResource
        }
    }

    OverlayDialog(
        title = stringResource(R.string.custom_pod_images),
        summary = target.name.ifBlank { target.address },
        show = show,
        onDismissRequest = onDismissRequest,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PodImageResource.entries.forEach { resource ->
                PodImageResourceRow(
                    resource = resource,
                    selectedUri = selectedImages[resource],
                    savedPath = target.imagePath(resource).takeUnless { resource in clearedImages },
                    title = stringResource(resource.titleRes()),
                    summary = if (resource !in clearedImages && (selectedImages[resource] != null || target.imagePath(resource) != null)) {
                        stringResource(R.string.custom_image_selected)
                    } else {
                        stringResource(R.string.custom_image_default)
                    },
                    clearable = resource !in clearedImages && (selectedImages[resource] != null || target.imagePath(resource) != null),
                    onClick = {
                        selectedResource = resource
                        launcher.launch("image/*")
                    },
                    onClear = {
                        selectedImages = selectedImages - resource
                        clearedImages = clearedImages + resource
                    },
                )
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
                text = stringResource(R.string.save),
                onClick = { onSave(target.address, target.name, selectedImages, clearedImages) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

@Composable
private fun PodImageResourceRow(
    resource: PodImageResource,
    selectedUri: Uri?,
    savedPath: String?,
    title: String,
    summary: String,
    clearable: Boolean,
    onClick: () -> Unit,
    onClear: () -> Unit,
) {
    val previewPainter = rememberPodImagePreviewPainter(resource, selectedUri, savedPath)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = previewPainter,
            contentDescription = title,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp)),
            contentScale = ContentScale.Fit,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = MiuixTheme.colorScheme.onSurface,
                style = MiuixTheme.textStyles.headline1,
            )
            Text(
                text = summary,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.body2,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (clearable) {
            Spacer(Modifier.width(12.dp))
            IconButton(
                modifier = Modifier.size(32.dp),
                onClick = onClear,
            ) {
                Icon(
                    modifier = Modifier.size(18.dp),
                    imageVector = MiuixIcons.Close,
                    contentDescription = stringResource(R.string.custom_image_restore_default),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
private fun rememberPodImagePreviewPainter(
    resource: PodImageResource,
    selectedUri: Uri?,
    savedPath: String?,
): Painter {
    val context = LocalContext.current
    return remember(context, selectedUri, savedPath, resource) {
        selectedUri?.let { uri ->
            runCatching {
                context.contentResolver.openInputStream(uri).use { input ->
                    input?.let { BitmapFactory.decodeStream(it) }
                }
            }.getOrNull()
        } ?: savedPath?.let { path ->
            runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
        }
    }?.let { bitmap -> BitmapPainter(bitmap.asImageBitmap()) }
        ?: painterResource(resource.defaultImageRes())
}

private fun PodImageResource.titleRes(): Int = when (this) {
    PodImageResource.BOX -> R.string.custom_image_box
    PodImageResource.LEFT -> R.string.custom_image_left
    PodImageResource.RIGHT -> R.string.custom_image_right
}

private fun PodImageResource.defaultImageRes(): Int = when (this) {
    PodImageResource.BOX -> R.drawable.img_box
    PodImageResource.LEFT -> R.drawable.img_left
    PodImageResource.RIGHT -> R.drawable.img_right
}
