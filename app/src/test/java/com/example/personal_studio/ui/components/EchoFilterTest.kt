package com.example.personal_studio.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoFilterTest {

    @Test fun `typing is sent out once per change`() {
        val f = EchoFilter("")
        assertFalse(f.edited("")) // the initial text is not an edit
        assertTrue(f.edited("他"))
        assertFalse(f.edited("他")) // same text again
        assertTrue(f.edited("他说"))
    }

    @Test fun `echoes of the field's own edits are ignored, also when they come late`() {
        val f = EchoFilter("")
        f.edited("a"); f.edited("ab"); f.edited("abc")
        // The owner's value catches up a frame behind the typing.
        assertFalse(f.external("a"))
        assertFalse(f.external("ab"))
        assertFalse(f.external("abc"))
    }

    @Test fun `a skipped echo is acknowledged by a newer one`() {
        val f = EchoFilter("")
        f.edited("a"); f.edited("ab"); f.edited("abc")
        assertFalse(f.external("abc")) // "a" and "ab" were conflated away
        // Now that everything is acknowledged, an outside change to an old text applies.
        assertTrue(f.external("a"))
    }

    @Test fun `a change made outside the field replaces its text`() {
        val f = EchoFilter("")
        f.edited("搜索词")
        assertFalse(f.external("搜索词"))
        assertTrue(f.external("")) // the clear button
        assertFalse(f.edited("")) // setting the text is not reported back as an edit
    }

    @Test fun `a prefill after loading replaces the empty field`() {
        val f = EchoFilter("")
        assertFalse(f.external(""))
        assertTrue(f.external("高等数学"))
        assertTrue(f.edited("高等数学（下）"))
        assertFalse(f.external("高等数学（下）"))
    }

    @Test fun `an outside change while edits are pending wins and clears them`() {
        val f = EchoFilter("")
        f.edited("草")
        assertTrue(f.external("")) // reset before the echo arrived
        assertTrue(f.external("草")) // so "草" later is an outside change, not an echo
    }
}
