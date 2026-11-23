package com.example.personal_studio.domain.chat

import com.example.personal_studio.domain.model.ChatMessage
import com.example.personal_studio.domain.model.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatRewindTest {

    private fun msg(id: Long, role: MessageRole) =
        ChatMessage(id = id, sessionId = 1, role = role, contentMarkdown = "m$id", createdAt = id)

    private val conversation = listOf(
        msg(1, MessageRole.USER), msg(2, MessageRole.AI),
        msg(3, MessageRole.USER), msg(4, MessageRole.AI),
        msg(5, MessageRole.USER), msg(6, MessageRole.AI),
    )

    @Test fun `rewind keeps the message and drops what follows`() {
        assertEquals(listOf(4L, 5L, 6L), messagesToDiscard(conversation, 3, inclusive = false))
    }

    @Test fun `edit drops the message itself too`() {
        assertEquals(listOf(3L, 4L, 5L, 6L), messagesToDiscard(conversation, 3, inclusive = true))
    }

    @Test fun `rewinding the last message drops nothing`() {
        assertEquals(emptyList<Long>(), messagesToDiscard(conversation.take(5), 5, inclusive = false))
    }

    @Test fun `an unknown message drops nothing`() {
        assertEquals(emptyList<Long>(), messagesToDiscard(conversation, 42, inclusive = true))
    }
}
