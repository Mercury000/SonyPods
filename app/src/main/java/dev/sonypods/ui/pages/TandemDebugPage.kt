package dev.sonypods.ui.pages

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.sonypods.R
import dev.sonypods.bridge.SonyBridge
import dev.sonypods.data.DebugLogEntry
import dev.sonypods.data.DebugLogKind
import dev.sonypods.data.SonyHeadphoneRepository
import kotlinx.coroutines.flow.distinctUntilChanged
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Sony Tandem debug page: shows the repository debug log (TX/RX and state lines)
 * and allows sending a raw Tandem message as HEX ([DataType][Command][Payload...]).
 */
@Composable
fun TandemDebugPage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    clearRequest: Int = 0,
    paused: Boolean = false,
) {
    val context = LocalContext.current
    val repository = remember { SonyHeadphoneRepository.getInstance(context.applicationContext) }
    val debugLogs by repository.debugLogs.collectAsState()
    val listState = rememberLazyListState()
    var hexInput by remember { mutableStateOf("") }
    // Local "clear": hide log entries produced before the last clear request.
    var hiddenCount by remember { mutableIntStateOf(0) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != SonyBridge.ACTION_DEBUG_LOG) return
                val message = intent.getStringExtra(SonyBridge.EXTRA_STRING) ?: return
                val kind = intent.getStringExtra(SonyBridge.EXTRA_LOG_KIND)
                    ?.let { name -> runCatching { DebugLogKind.valueOf(name) }.getOrNull() }
                    ?: DebugLogKind.INFO
                repository.ingestRemoteDebugLog(message, kind)
            }
        }
        context.registerReceiver(
            receiver,
            IntentFilter(SonyBridge.ACTION_DEBUG_LOG),
            Context.RECEIVER_EXPORTED,
        )
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    LaunchedEffect(clearRequest) {
        if (clearRequest > 0) {
            hiddenCount = debugLogs.size
        }
    }

    val logs = debugLogs.drop(hiddenCount.coerceAtMost(debugLogs.size))

    // Live tail: keep the newest entry in view unless the user paused the stream to
    // read history. Appends + a per-entry index scroll is stable under bursts (unlike
    // the old prepend + crude scrollBy), and following unconditionally when unpaused
    // means an IME resize or typing in the send box cannot strand the viewport.
    // Keying on [paused] re-runs on resume so the viewport catches up to the tail.
    LaunchedEffect(logs.size, paused) {
        if (logs.isNotEmpty() && !paused) {
            listState.animateScrollToItem(logs.lastIndex)
        }
    }

    // Tapping the send box raises the IME and shrinks the LazyColumn viewport, dropping
    // the tail below the fold with no new entry to trigger the size-keyed follow above.
    // Re-anchor to the newest entry when the IME inset changes (composition-level, the
    // reliable trigger) and again when the viewport actually resizes (layout-level), as
    // a jump so the resize cannot race an animation.
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(imeBottom) {
        if (!paused && logs.isNotEmpty()) {
            listState.scrollToItem(logs.lastIndex)
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.viewportSize.height }
            .distinctUntilChanged()
            .collect { _ ->
                if (!paused && logs.isNotEmpty()) {
                    listState.scrollToItem(logs.lastIndex)
                }
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
                TandemLogCard(entry)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().imePadding(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HexInputField(
                value = hexInput,
                onValueChange = { hexInput = it.uppercase() },
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = stringResource(R.string.dbg_send),
                onClick = {
                    SonyBridge.sendCommand(context, SonyBridge.CMD_DEBUG_RAW) {
                        putExtra(SonyBridge.EXTRA_STRING, hexInput)
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
            text = stringResource(R.string.dbg_waiting_logs),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun TandemLogCard(entry: DebugLogEntry) {
    val context = LocalContext.current
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    val copiedMessage = stringResource(R.string.dbg_log_copied)
    Card(
        onClick = {
            clipboard?.setPrimaryClip(ClipData.newPlainText(null, entry.text))
            Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
        }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (entry.kind != DebugLogKind.INFO) {
                Text(
                    text = if (entry.kind == DebugLogKind.TX) "TX" else "RX",
                    color = directionColor(entry.kind),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Text(
                text = entry.text,
                modifier = Modifier.weight(1f),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 4,
            )
        }
    }
}

/** Functional direction colors, readable on both light and dark surfaces. */
private fun directionColor(kind: DebugLogKind): Color = when (kind) {
    DebugLogKind.TX -> Color(0xFF42A5F5)
    DebugLogKind.RX -> Color(0xFF66BB6A)
    DebugLogKind.INFO -> Color.Unspecified
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
                        text = stringResource(R.string.dbg_hex),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 14.sp,
                    )
                }
                innerTextField()
            }
        },
    )
}
