package dev.sonypods.ui.pages

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Dims the headset page and swallows input while its control channel is not usable.
 *
 * The values already on screen are the last thing the headset actually said, so they stay: the
 * snapshot deliberately retains them across a sub-second drop, and blanking the page here would undo
 * that and flicker. What must not happen is a write against a session that cannot carry it, hence
 * consuming pointer events at the [PointerEventPass.Initial] pass — that reaches every child,
 * including the scrollable list, without needing each control to grow an `enabled` flag.
 */
fun Modifier.earphonePageReadiness(ready: Boolean): Modifier =
    if (ready) {
        this
    } else {
        this
            .alpha(0.45f)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            }
    }
