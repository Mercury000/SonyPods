package dev.sonypods.ui.pages

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sonypods.data.SonyHeadphoneRepository
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
) {
    val context = LocalContext.current
    val repository = remember { SonyHeadphoneRepository.getInstance(context.applicationContext) }
    val state by repository.state.collectAsState()
    val listState = rememberLazyListState()
    var hexInput by remember { mutableStateOf("") }
    // Local "clear": hide log entries produced before the last clear request.
    var hiddenCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(clearRequest) {
        if (clearRequest > 0) {
            hiddenCount = state.debugLogs.size
        }
    }

    val logs = state.debugLogs.drop(hiddenCount.coerceAtMost(state.debugLogs.size))

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
                TandemLogCard(entry)
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
                    repository.runDebugAction("raw", hexInput)
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
            text = "等待 Tandem 日志...",
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun TandemLogCard(entry: String) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Card(
        onClick = {
            clipboard.setText(AnnotatedString(entry))
            Toast.makeText(context, "已复制日志", Toast.LENGTH_SHORT).show()
        }
    ) {
        Text(
            text = entry,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 4,
        )
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
