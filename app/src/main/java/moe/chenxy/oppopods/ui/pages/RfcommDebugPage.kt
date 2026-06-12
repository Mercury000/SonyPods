package moe.chenxy.oppopods.ui.pages

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.widget.Toast
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsAction
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val MAX_LOGS = 300

private data class RfcommDebugLogEntry(
    val level: String,
    val tag: String,
    val message: String,
    val time: String,
)

@Composable
fun RfcommDebugPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    clearRequest: Int = 0,
) {
    val context = LocalContext.current
    val logs = remember { mutableStateListOf<RfcommDebugLogEntry>() }
    val listState = rememberLazyListState()
    var hexInput by remember { mutableStateOf("") }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != OppoPodsAction.ACTION_RFCOMM_LOG) return
                logs.add(
                    RfcommDebugLogEntry(
                        level = intent.getStringExtra("level").orEmpty().ifBlank { "D" },
                        tag = intent.getStringExtra("tag").orEmpty().ifBlank { "RFCOMM" },
                        message = intent.getStringExtra("message").orEmpty(),
                        time = intent.getStringExtra("time").orEmpty(),
                    )
                )
                while (logs.size > MAX_LOGS) logs.removeAt(0)
            }
        }
        context.registerReceiver(receiver, IntentFilter(OppoPodsAction.ACTION_RFCOMM_LOG), Context.RECEIVER_EXPORTED)
        context.sendRfcommDebugBroadcast(OppoPodsAction.ACTION_RFCOMM_LOG_CONNECT)
        onDispose {
            context.sendRfcommDebugBroadcast(OppoPodsAction.ACTION_RFCOMM_LOG_DISCONNECT)
            context.unregisterReceiver(receiver)
        }
    }

    LaunchedEffect(clearRequest) {
        if (clearRequest > 0) {
            logs.clear()
            context.sendRfcommDebugBroadcast(OppoPodsAction.ACTION_RFCOMM_LOG_CLEAR)
        }
    }

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollBy(
                value = 50_000f,
                animationSpec = tween(durationMillis = 280),
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(
                start = 12.dp,
                top = contentPadding.calculateTopPadding() + 12.dp,
                end = 12.dp,
                bottom = contentPadding.calculateBottomPadding() + 12.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (logs.isEmpty()) {
                item {
                    EmptyLogCard()
                }
            }
            items(logs) { entry ->
                RfcommLogCard(entry)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HexInputField(
                value = hexInput,
                onValueChange = { hexInput = it.uppercase() },
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = "发送",
                onClick = {
                    context.sendRfcommDebugBroadcast(OppoPodsAction.ACTION_RFCOMM_DEBUG_SEND) {
                        putExtra("hex", hexInput)
                    }
                    hexInput = ""
                },
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

@Composable
private fun EmptyLogCard() {
    Card {
        Text(
            text = "等待 RFCOMM 日志...",
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun RfcommLogCard(entry: RfcommDebugLogEntry) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val direction = entry.directionLabel()
    Card(
        onClick = {
            val text = "${entry.time} ${entry.level}/${entry.tag} ${entry.message}"
            clipboard.setText(AnnotatedString(text))
            Toast.makeText(context, "已复制日志", Toast.LENGTH_SHORT).show()
        }
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .background(levelColor(entry.level), RoundedCornerShape(5.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = entry.level.take(1),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
                Text(
                    text = direction,
                    modifier = Modifier.padding(start = 8.dp),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = directionColor(entry),
                )
                Text(
                    text = entry.tag,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    text = entry.time,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Text(
                text = entry.message,
                modifier = Modifier.padding(top = 6.dp),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 3,
            )
        }
    }
}

@Composable
private fun HexInputField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .heightIn(min = 44.dp)
            .background(MiuixTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        textStyle = androidx.compose.ui.text.TextStyle(
            color = MiuixTheme.colorScheme.onSurface,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
        ),
        singleLine = true,
        decorationBox = { innerTextField ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(
                        text = "HEX",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 14.sp,
                    )
                }
                innerTextField()
            }
        },
    )
}

private fun RfcommDebugLogEntry.directionLabel(): String = when (tag.uppercase()) {
    "RFCOMM/TX" -> "SEND"
    "RFCOMM/RX" -> "RECV"
    "RFCOMM/DEBUG" -> "DEBUG"
    else -> "STATE"
}

private fun directionColor(entry: RfcommDebugLogEntry): Color = when (entry.tag.uppercase()) {
    "RFCOMM/TX" -> Color(0xFF00A86B)
    "RFCOMM/RX" -> Color(0xFF1677FF)
    "RFCOMM/DEBUG" -> Color(0xFF7C4DFF)
    else -> Color(0xFF6B7280)
}

private fun levelColor(level: String): Color = when (level.uppercase()) {
    "E", "C" -> Color(0xFFFF4D4F)
    "W" -> Color(0xFFFF9F0A)
    "I" -> Color(0xFF1677FF)
    "D" -> Color(0xFF7C4DFF)
    else -> Color(0xFF6B7280)
}

private fun Context.sendRfcommDebugBroadcast(action: String, fill: Intent.() -> Unit = {}) {
    Intent(action).apply {
        setPackage("com.android.bluetooth")
        addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        fill()
        sendBroadcast(this)
    }
}
