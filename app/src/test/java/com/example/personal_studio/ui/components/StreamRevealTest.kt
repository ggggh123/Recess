package com.example.personal_studio.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamRevealTest {

    /** Frames of [frameMs] until [target] is fully shown, starting from [from]. */
    private fun framesToCatchUp(from: Float, target: Int, streaming: Boolean, frameMs: Float = 16.7f): Int {
        var shown = from
        var frames = 0
        while (shown < target) {
            shown = StreamReveal.advance(shown, target, frameMs, streaming)
            frames++
            check(frames < 10_000) { "never caught up" }
        }
        return frames
    }

    @Test fun `never runs ahead of the received text`() {
        var shown = 0f
        repeat(200) {
            shown = StreamReveal.advance(shown, 37, 16.7f, streaming = true)
            assertTrue(shown <= 37f)
        }
        assertEquals(37f, shown, 0f)
    }

    @Test fun `moves every frame, also while new text keeps arriving`() {
        // The old typewriter restarted its timer on every token and stood still meanwhile.
        var shown = 0f
        var target = 0
        repeat(120) { frame ->
            target += 2 // a token every frame
            val before = shown
            shown = StreamReveal.advance(shown, target, 16.7f, streaming = true)
            if (frame > 0) assertTrue("frame $frame did not advance", shown > before)
        }
    }

    @Test fun `a burst is let out over a fraction of a second, not at once`() {
        val first = StreamReveal.advance(0f, 400, 16.7f, streaming = true)
        assertTrue("first frame showed $first of 400", first < 40f)
        val seconds = framesToCatchUp(0f, 400, streaming = true) * 16.7f / 1000f
        assertTrue("took $seconds s", seconds in 0.5f..4f)
    }

    @Test fun `a small backlog still moves at a readable minimum pace`() {
        val shown = StreamReveal.advance(10f, 12, 100f, streaming = true)
        assertEquals(12f, shown, 0f) // 30 chars/s × 0.1 s = 3 ≥ 2 left
    }

    @Test fun `the tail is let out faster once the answer is complete`() {
        assertTrue(framesToCatchUp(0f, 300, streaming = false) < framesToCatchUp(0f, 300, streaming = true))
    }

    @Test fun `a huge backlog is skipped instead of typed out`() {
        val shown = StreamReveal.advance(0f, 5_000, 16.7f, streaming = true)
        assertTrue(shown >= 5_000 - StreamReveal.MAX_LAG_CHARS)
    }

    @Test fun `a long stall does not jump ahead`() {
        val afterStall = StreamReveal.advance(0f, 200, 2_000f, streaming = true)
        val afterMaxFrame = StreamReveal.advance(0f, 200, StreamReveal.MAX_FRAME_MS, streaming = true)
        assertEquals(afterMaxFrame, afterStall, 0f)
    }

    @Test fun `a shorter text is followed at once`() {
        assertEquals(5f, StreamReveal.advance(20f, 5, 16.7f, streaming = true), 0f)
    }

    @Test fun `prefix does not split an emoji`() {
        val text = "ok😀!" // "ok😀!"
        assertEquals("ok", StreamReveal.prefix(text, 3))
        assertEquals("ok😀", StreamReveal.prefix(text, 4))
        assertEquals(text, StreamReveal.prefix(text, 99))
        assertEquals("", StreamReveal.prefix(text, -1))
    }
}
