package com.example.personal_studio.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.first
import kotlin.math.max
import kotlin.math.min

/**
 * Pace for letting a streamed answer out on screen. Text arrives from the network in uneven
 * bursts (one token, then forty at once after a pause); showing it as it lands reads as
 * "a few characters, a stall, then a whole paragraph". Instead the visible length chases the
 * received length every frame: the speed is proportional to the backlog (it drains with a
 * time constant of [CATCH_UP_S]), never slower than [MIN_CPS]. Bursts become a smooth
 * acceleration, pauses a gentle slow-down, and the screen trails the network by well under
 * a second.
 */
object StreamReveal {
    /** Slowest pace while text is still arriving, in characters per second. */
    const val MIN_CPS = 30f

    /** Slowest pace once the answer is complete, so its tail never drags. */
    const val MIN_CPS_DONE = 120f

    /** Time constant for draining the backlog while streaming, in seconds. */
    const val CATCH_UP_S = 0.45f

    /** Time constant once the answer is complete. */
    const val CATCH_UP_DONE_S = 0.2f

    /** A backlog beyond this many characters is skipped rather than typed out. */
    const val MAX_LAG_CHARS = 800

    /** Longest frame gap taken into account; a stall (GC, backgrounding) does not jump ahead. */
    const val MAX_FRAME_MS = 100f

    /**
     * Visible length after a frame of [frameMs] when [shown] characters are on screen and
     * [target] have been received. Never exceeds [target]; a shrunk [target] is followed at once.
     */
    fun advance(shown: Float, target: Int, frameMs: Float, streaming: Boolean): Float {
        if (shown >= target) return target.toFloat()
        val from = max(shown, (target - MAX_LAG_CHARS).toFloat())
        val backlog = target - from
        val tau = if (streaming) CATCH_UP_S else CATCH_UP_DONE_S
        val floor = if (streaming) MIN_CPS else MIN_CPS_DONE
        val cps = max(floor, backlog / tau)
        val dt = frameMs.coerceIn(0f, MAX_FRAME_MS) / 1000f
        return min(target.toFloat(), from + cps * dt)
    }

    /** [length] characters of [text], not cutting a surrogate pair (an emoji) in half. */
    fun prefix(text: String, length: Int): String {
        var n = length.coerceIn(0, text.length)
        if (n in 1 until text.length && Character.isHighSurrogate(text[n - 1])) n -= 1
        return text.substring(0, n)
    }
}

/**
 * How many characters of a [length]-long streamed text to show this frame (see
 * [StreamReveal]). With [animate] off the whole text shows at once. Ticks only while there is
 * something left to reveal; caught up, it waits for more text without running frames.
 */
@Composable
fun rememberRevealedLength(length: Int, streaming: Boolean, animate: Boolean): Int {
    var shown by remember { mutableFloatStateOf(if (animate) 0f else length.toFloat()) }
    val target by rememberUpdatedState(length)
    val live by rememberUpdatedState(streaming)
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        while (true) {
            snapshotFlow { target }.first { it > shown.toInt() }
            var last = withFrameNanos { it }
            while (shown < target) {
                val now = withFrameNanos { it }
                shown = StreamReveal.advance(shown, target, (now - last) / 1_000_000f, live)
                last = now
            }
        }
    }
    return if (animate) min(shown.toInt(), length) else length
}
