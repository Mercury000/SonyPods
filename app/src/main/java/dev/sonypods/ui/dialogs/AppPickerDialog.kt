package dev.sonypods.ui.dialogs

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mercury.sonypods.R
import dev.sonypods.utils.InstalledApp
import dev.sonypods.utils.loadLaunchableApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val GET_INSTALLED_APPS_PERMISSION = "com.android.permission.GET_INSTALLED_APPS"

@Composable
fun AppPickerDialog(
    show: Boolean,
    title: String,
    selectedPackages: Set<String>,
    onDismissRequest: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                GET_INSTALLED_APPS_PERMISSION,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var query by remember(show) { mutableStateOf("") }
    var selection by remember(show, selectedPackages) { mutableStateOf(selectedPackages) }
    var showSystemApps by remember(show) { mutableStateOf(false) }

    LaunchedEffect(show, hasPermission, showSystemApps) {
        if (show && hasPermission) {
            loading = true
            apps = withContext(Dispatchers.IO) {
                loadLaunchableApps(
                    context,
                    includeSystemApps = showSystemApps,
                    includePackages = selection,
                ).sortedWith(
                    compareByDescending<InstalledApp> { it.packageName in selection }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label },
                )
            }
            loading = false
        }
    }
    LaunchedEffect(show, hasPermission) {
        if (show && !hasPermission) permissionLauncher.launch(GET_INSTALLED_APPS_PERMISSION)
    }

    val filteredApps = remember(apps, query) {
        val needle = query.trim().lowercase()
        apps.filter { app ->
            needle.isEmpty() || app.label.lowercase().contains(needle) ||
                app.packageName.lowercase().contains(needle)
        }
    }
    val maxListHeight = (LocalConfiguration.current.screenHeightDp * 0.48f).dp

    OverlayDialog(
        show = show,
        title = title,
        onDismissRequest = onDismissRequest,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!hasPermission) {
                Text(
                    text = stringResource(R.string.popup_app_list_permission_required),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                TextButton(
                    text = stringResource(R.string.grant_permission),
                    onClick = { permissionLauncher.launch(GET_INSTALLED_APPS_PERMISSION) },
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            } else {
                SwitchPreference(
                    title = stringResource(R.string.popup_show_system_apps),
                    summary = stringResource(R.string.popup_show_system_apps_summary),
                    checked = showSystemApps,
                    onCheckedChange = { showSystemApps = it },
                )
                Text(
                    text = stringResource(R.string.popup_app_search_hint),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (filteredApps.isEmpty()) {
                    Text(
                        text = stringResource(
                            if (loading) R.string.popup_app_loading
                            else R.string.popup_app_empty,
                        ),
                        modifier = Modifier.padding(vertical = 20.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = maxListHeight),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(filteredApps, key = { it.packageName }) { app ->
                            val checked = app.packageName in selection
                            AppPickerRow(
                                app = app,
                                checked = checked,
                                onClick = {
                                    selection = if (checked) selection - app.packageName
                                    else selection + app.packageName
                                },
                            )
                        }
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
                TextButton(
                    text = stringResource(R.string.confirm),
                    onClick = { onConfirm(selection) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

@Composable
private fun AppPickerRow(
    app: InstalledApp,
    checked: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            bitmap = app.icon,
            contentDescription = null,
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = app.label, color = MiuixTheme.colorScheme.onSurface)
            Text(
                text = app.packageName,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Checkbox(state = ToggleableState(checked), onClick = onClick)
    }
}
