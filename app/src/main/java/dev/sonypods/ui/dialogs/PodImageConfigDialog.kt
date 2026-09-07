package dev.sonypods.ui.dialogs

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mercury.sonypods.R
import dev.sonypods.SonyPodsApp
import dev.sonypods.bridge.BoxSyncResult
import dev.sonypods.bridge.ModelImageSync
import dev.sonypods.bridge.SonyBridge
import dev.sonypods.config.EarphonePref
import dev.sonypods.config.PodImagePrefs
import dev.sonypods.utils.PodImageLoader
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * BOX-only custom headphone image picker. Shown from the connected headset page's
 * top-bar menu. Two immediate actions:
 *  - pick a photo from the album → applies a manual override (overwrites the shared
 *    `<address>_box.img` slot, marks [EarphonePref.boxManual]);
 *  - sync from cloud → re-downloads the catalog image and clears the override.
 * Restoring from cloud is download-only: on any failure the manual image is kept and
 * the caller is told via a toast.
 */
@Composable
internal fun PodImageConfigDialog(
    show: Boolean,
    targetAddress: String,
    displayName: String,
    pref: EarphonePref?,
    urlHint: String? = null,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val boxPath = pref?.boxImagePath
    val title = displayName.ifBlank { pref?.name.orEmpty() }

    fun toast(resId: Int) {
        runCatching {
            Toast.makeText(context.applicationContext, resId, Toast.LENGTH_SHORT).show()
        }
    }

    fun systemServiceAvailable(): Boolean = SonyPodsApp.xposedService != null

    fun refreshSystemSurfaces() {
        runCatching { SonyBridge.imageReady(context, targetAddress) }
    }

    val pickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            // Without the framework service nothing can be applied or refreshed; do
            // nothing and tell the user rather than half-saving.
            if (!systemServiceAvailable()) {
                toast(R.string.custom_image_service_unready)
                return@launch
            }
            val appContext = context.applicationContext ?: context
            val bytes = withContext(Dispatchers.IO) { decodePickedImage(appContext, uri) }
            if (bytes == null) {
                toast(R.string.custom_image_pick_failed)
                return@launch
            }
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    PodImagePrefs.saveBoxOverride(
                        context = appContext,
                        service = SonyPodsApp.xposedService,
                        address = targetAddress,
                        name = title,
                        bytes = bytes,
                    ) != null
                }.getOrDefault(false)
            }
            if (!saved) {
                toast(R.string.custom_image_apply_failed)
                return@launch
            }
            refreshSystemSurfaces()
        }
    }

    fun syncCloud() {
        if (!systemServiceAvailable()) {
            toast(R.string.custom_image_service_unready)
            return
        }
        scope.launch {
            val appContext = context.applicationContext ?: context
            val result = withContext(Dispatchers.IO) {
                ModelImageSync.syncBoxImageBlocking(appContext, targetAddress, urlHint)
            }
            when (result) {
                BoxSyncResult.Ok -> {
                    refreshSystemSurfaces()
                    toast(R.string.custom_image_cloud_synced)
                }
                BoxSyncResult.NoUrl -> toast(R.string.custom_image_no_cloud)
                BoxSyncResult.Failed -> toast(R.string.custom_image_cloud_failed)
                BoxSyncResult.Unavailable -> toast(R.string.custom_image_service_unready)
            }
        }
    }

    // Mirrors the surfaces: the effective image is the manual/catalog box if one is
    // cached, otherwise the default SONY logotype — never a blank area.
    val defaultBitmap = remember {
        runCatching { PodImageLoader.defaultLogoBitmap(context) }.getOrNull()
    }
    val previewBitmap = remember(boxPath, pref?.imageRevision, show) {
        boxPath?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
            ?: defaultBitmap
    }

    OverlayDialog(
        title = stringResource(R.string.custom_pod_images),
        show = show,
        onDismissRequest = onDismissRequest,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The preview itself is the album button.
            Box(
                modifier = Modifier
                    .size(128.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainer)
                    .clickable { pickerLauncher.launch("image/*") },
            ) {
                if (previewBitmap != null) {
                    Image(
                        painter = BitmapPainter(previewBitmap.asImageBitmap()),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
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
                text = stringResource(R.string.custom_image_sync_cloud),
                onClick = { syncCloud() },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

/**
 * Decode a picked [Uri] into PNG bytes capped at [MAX_EDGE] px so a huge gallery photo
 * cannot blow up memory in the hooked renderers that decode it back. Returns null when
 * the content cannot be read or decoded.
 */
private fun decodePickedImage(context: android.content.Context, uri: Uri): ByteArray? {
    return try {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        if (maxDim <= 0) return null
        var sample = 1
        while (maxDim / sample > MAX_EDGE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return null
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        if (!bitmap.isRecycled) bitmap.recycle()
        out.toByteArray()
    } catch (t: Throwable) {
        null
    }
}

private const val MAX_EDGE = 1024
