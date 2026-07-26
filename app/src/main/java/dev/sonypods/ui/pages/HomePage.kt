package dev.sonypods.ui.pages

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.pm.PackageInfoCompat
import io.github.libxposed.service.XposedService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import dev.sonypods.BuildConfig
import dev.sonypods.R
import dev.sonypods.ui.components.AppIcons
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
fun HomePage(
    modifier: Modifier = Modifier,
    xposedService: XposedService?,
    bluetoothServiceResponsive: Boolean,
    bluetoothEnabled: Boolean,
    bondedDeviceCount: Int,
    onBluetoothStatusClick: () -> Unit,
    onPairedBluetoothClick: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    bottomContentPadding: Dp = 16.dp,
) {
    val context = LocalContext.current
    val systemInfo = remember { homeSystemInfo(context) }
    val active = remember(xposedService) { hasRequiredBluetoothScopes(xposedService) }
    val inactiveSummary = if (xposedService == null) {
        "等待 LSPosed 服务连接"
    } else {
        "请在LSPosed中激活作用域"
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 12.dp,
            top = contentPadding.calculateTopPadding() + 12.dp,
            end = 12.dp,
            bottom = bottomContentPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            StatusGrid(
                active = active,
                inactiveSummary = inactiveSummary,
                bluetoothServiceResponsive = bluetoothServiceResponsive,
                bluetoothEnabled = bluetoothEnabled,
                bondedDeviceCount = bondedDeviceCount,
                onBluetoothStatusClick = onBluetoothStatusClick,
                onPairedBluetoothClick = onPairedBluetoothClick,
            )
        }
        item {
            InfoCard(systemInfo = systemInfo, xposedService = xposedService)
        }
    }
}

@Composable
private fun StatusGrid(
    active: Boolean,
    inactiveSummary: String,
    bluetoothServiceResponsive: Boolean,
    bluetoothEnabled: Boolean,
    bondedDeviceCount: Int,
    onBluetoothStatusClick: () -> Unit,
    onPairedBluetoothClick: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (maxWidth >= 600.dp) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusCard(active = active, inactiveSummary = inactiveSummary, bluetoothServiceResponsive = bluetoothServiceResponsive, modifier = Modifier.weight(1f).height(112.dp))
                StatCard(title = "蓝牙状态", value = if (bluetoothEnabled) "已开启" else "未开启", modifier = Modifier.weight(1f).height(112.dp), onClick = onBluetoothStatusClick)
                StatCard(title = "配对蓝牙", value = bondedDeviceCount.toString(), modifier = Modifier.weight(1f).height(112.dp), onClick = onPairedBluetoothClick)
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusCard(active = active, inactiveSummary = inactiveSummary, bluetoothServiceResponsive = bluetoothServiceResponsive, modifier = Modifier.weight(1f).aspectRatio(1f))
                Column(
                    modifier = Modifier.weight(1f).aspectRatio(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatCard(title = "蓝牙状态", value = if (bluetoothEnabled) "已开启" else "未开启", modifier = Modifier.weight(1f), onClick = onBluetoothStatusClick)
                    StatCard(title = "配对蓝牙", value = bondedDeviceCount.toString(), modifier = Modifier.weight(1f), onClick = onPairedBluetoothClick)
                }
            }
        }
    }
}

@Composable
private fun StatusCard(active: Boolean, inactiveSummary: String, bluetoothServiceResponsive: Boolean, modifier: Modifier = Modifier) {
    val serviceTimeout = active && !bluetoothServiceResponsive
    val statusColor = when {
        !active -> Color(0xFFFF5A52)
        serviceTimeout -> Color(0xFFFF9F0A)
        else -> Color(0xFF36D167)
    }
    val statusBackground = when {
        !active -> Color(0xFFFFE5E3)
        serviceTimeout -> Color(0xFFFFF0D7)
        else -> Color(0xFFDFFAE4)
    }
    Card(
        modifier = modifier,
        colors = CardDefaults.defaultColors(color = statusBackground),
        pressFeedbackType = PressFeedbackType.Tilt,
        showIndication = true,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.fillMaxSize().offset(34.dp, 38.dp),
                contentAlignment = Alignment.BottomEnd,
            ) {
                Icon(
                    modifier = Modifier.size(136.dp),
                    imageVector = AppIcons.Headphones,
                    contentDescription = null,
                    tint = statusColor.copy(alpha = 0.78f),
                )
            }
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text(
                    text = when {
                        !active -> "LSPosed 未激活"
                        serviceTimeout -> "模块服务超时"
                        else -> "模块已激活"
                    },
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF101010),
                )
                Text(
                    text = when {
                        !active -> inactiveSummary
                        serviceTimeout -> "模块服务未响应"
                        else -> "模块服务已连接"
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = when {
                        !active -> Color(0xFFFF5A52)
                        serviceTimeout -> Color(0xFFFF9F0A)
                        else -> Color(0xFF2F3A32).copy(alpha = 0.78f)
                    },
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

private val requiredBluetoothScopes = setOf(
    "com.android.bluetooth",
    "com.xiaomi.bluetooth",
)

private fun hasRequiredBluetoothScopes(service: XposedService?): Boolean {
    if (service == null) return false
    return runCatching {
        service.scope.containsAll(requiredBluetoothScopes)
    }.getOrDefault(false)
}

@Composable
private fun StatCard(title: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        pressFeedbackType = PressFeedbackType.Tilt,
        showIndication = true,
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                text = value,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun InfoCard(systemInfo: HomeSystemInfo, xposedService: XposedService?) {
    Card {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            InfoText(title = "系统版本", content = systemInfo.systemVersion)
            InfoText(title = "应用版本", content = systemInfo.appVersion)
            InfoText(title = "Android 版本", content = systemInfo.androidVersion)
            InfoText(title = "LSPosed 版本", content = lsposedVersion(xposedService).ifBlank { "未知" })
            InfoText(title = "构建时间", content = systemInfo.buildDate.ifBlank { "未知" })
            InfoText(title = "设备型号", content = systemInfo.deviceModel.ifBlank { "未知" }, bottomPadding = 0.dp)
        }
    }
}

@Composable
private fun InfoText(title: String, content: String, bottomPadding: Dp = 24.dp) {
    Text(
        text = title,
        fontSize = MiuixTheme.textStyles.headline1.fontSize,
        fontWeight = FontWeight.Medium,
        color = MiuixTheme.colorScheme.onSurface,
    )
    Text(
        text = content,
        fontSize = MiuixTheme.textStyles.body2.fontSize,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(top = 2.dp, bottom = bottomPadding),
    )
}

private data class HomeSystemInfo(
    val systemVersion: String,
    val appVersion: String,
    val androidVersion: String,
    val buildDate: String,
    val deviceModel: String,
)

private fun homeSystemInfo(context: Context): HomeSystemInfo {
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    val versionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
    val versionName = packageInfo.versionName ?: "unknown"
    return HomeSystemInfo(
        systemVersion = Build.DISPLAY,
        appVersion = "$versionName ($versionCode)",
        androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        buildDate = buildDate(BuildConfig.BUILD_TIMESTAMP),
        deviceModel = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" "),
    )
}

private fun lsposedVersion(service: XposedService?): String {
    if (service == null) return ""
    return runCatching {
        "${service.frameworkName} ${service.frameworkVersion} (${service.frameworkVersionCode}), API ${service.apiVersion}"
    }.getOrDefault("")
}

private fun buildDate(timeMillis: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timeMillis))
}
