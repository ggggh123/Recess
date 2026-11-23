package com.example.personal_studio.feature.chat.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalDensity
import com.example.personal_studio.ui.components.MathMarkdownView
import com.example.personal_studio.ui.components.StreamReveal
import com.example.personal_studio.ui.components.rememberRevealedLength
import com.example.personal_studio.ui.theme.Tg
import com.example.personal_studio.ui.theme.TgMotion

/**
 * The body of an answer.
 *
 * A [live] answer (streamed on this page) is let out smoothly as native text while it arrives
 * ([rememberRevealedLength]); once all of it is on screen, the formula view (a WebView) renders
 * underneath, covered by the native text, and takes over once it has measured and painted: the
 * native layer fades out over 140 ms, both layers aligned to the bottom edge so the lines being
 * read stay put while the height difference goes upward. The row keeps the same composition
 * from the first token to the saved message (the list gives both the same key), so nothing is
 * torn down at the end.
 *
 * Other answers show the formula view directly at their [cachedHeightDp] (or a small
 * placeholder until it reports its height), as before.
 */
@Composable
fun AnswerContent(
    markdown: String,
    streaming: Boolean,
    live: Boolean,
    cachedHeightDp: Int?,
    onHeightChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (streaming && markdown.isEmpty()) {
        ThinkingIndicator(modifier)
        return
    }
    // An answer whose formula view has already reported a height was seen in full before
    // (scrolled away and back): no replay.
    val settled = !live || cachedHeightDp != null
    val revealed = rememberRevealedLength(markdown.length, streaming, animate = !settled)
    val allOut = !streaming && revealed >= markdown.length

    var webMeasured by remember { mutableStateOf(settled) }
    var webShown by remember { mutableStateOf(settled) }
    LaunchedEffect(webMeasured) {
        if (webMeasured && !webShown) {
            // A WebView paints a frame or two after it is resized; switch only once it has.
            repeat(3) { withFrameNanos { } }
            webShown = true
        }
    }
    val webAlpha by animateFloatAsState(if (webShown) 1f else 0f, tween(140), label = "answerWeb")
    var nativeHeightDp by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val pageColor = Tg.colors.bg

    // While streaming, the row grows from its top edge, so each new line slides in. From the
    // hand-over on, both layers sit on the bottom edge (where the reader is when an answer
    // ends) and the height change happens above it.
    val sizing = if (live) {
        Modifier.animateContentSize(TgMotion.standard(), alignment = if (allOut) Alignment.BottomStart else Alignment.TopStart)
    } else {
        Modifier
    }
    Layout(
        modifier = modifier.fillMaxWidth().then(sizing),
        content = {
            if (allOut) {
                MathMarkdownView(
                    markdown = markdown,
                    // Always fully opaque: a WebView at alpha 0 is skipped when drawing and would
                    // paint late. The native text covers it until the switch.
                    modifier = Modifier.layoutId(WEB),
                    initialHeightDp = cachedHeightDp ?: if (live) nativeHeightDp.coerceAtLeast(1) else 60,
                    onHeightChanged = { h ->
                        onHeightChanged(h)
                        webMeasured = true
                    },
                )
            }
            if (webAlpha < 1f) {
                AnswerStreamText(
                    text = StreamReveal.prefix(markdown, revealed),
                    showCursor = streaming || !allOut,
                    modifier = Modifier
                        .layoutId(NATIVE)
                        .graphicsLayer { alpha = 1f - webAlpha }
                        .background(pageColor)
                        .onSizeChanged { nativeHeightDp = with(density) { it.height.toDp().value.toInt() } },
                )
            }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        val web = measurables.firstOrNull { it.layoutId == WEB }?.measure(loose)
        val native = measurables.firstOrNull { it.layoutId == NATIVE }?.measure(loose)
        // The row is as tall as the layer being read: the formula view renders hidden at its
        // own height and only sets the size once it is shown, so no gap opens under the text.
        val height = when {
            web != null && (webShown || native == null) -> web.height
            native != null -> native.height
            else -> 0
        }
        layout(constraints.maxWidth, height) {
            web?.placeRelative(0, height - web.height)
            native?.placeRelative(0, height - native.height)
        }
    }
}

private const val WEB = "web"
private const val NATIVE = "native"
