package com.example.personal_studio.domain.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPromptsTest {

    @Test fun `no custom prompt means the built-in one`() {
        assertEquals(ChatPrompts.DEFAULT, ChatPrompts.effective(null))
        assertEquals(ChatPrompts.DEFAULT, ChatPrompts.effective("   \n "))
    }

    @Test fun `a custom prompt is used trimmed`() {
        assertEquals("只用英文回答。", ChatPrompts.effective("  只用英文回答。\n"))
    }

    @Test fun `blank or unchanged text is saved as the built-in one`() {
        assertNull(ChatPrompts.normalize(""))
        assertNull(ChatPrompts.normalize(ChatPrompts.DEFAULT))
        assertNull(ChatPrompts.normalize(ChatPrompts.DEFAULT.replace("\n", "\r\n") + "\n"))
        assertEquals("自定义", ChatPrompts.normalize("自定义\r\n"))
    }

    @Test fun `a saved prompt is capped`() {
        assertEquals(ChatPrompts.MAX_LENGTH, ChatPrompts.normalize("字".repeat(ChatPrompts.MAX_LENGTH + 50))!!.length)
    }

    @Test fun `the built-in prompt keeps the formula rule and no longer mentions the terminal look`() {
        assertTrue("LaTeX" in ChatPrompts.DEFAULT)
        assertTrue("终端" !in ChatPrompts.DEFAULT && "cat/print" !in ChatPrompts.DEFAULT)
        assertEquals(ChatPrompts.DEFAULT, SendMessageUseCase.SYSTEM_PROMPT)
    }
}
