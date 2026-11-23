package com.example.personal_studio.feature.chat.vm

import com.example.personal_studio.domain.model.ChatMessage
import com.example.personal_studio.domain.model.MessageRole
import com.example.personal_studio.feature.chat.ui.ChatRow
import com.example.personal_studio.feature.chat.ui.chatRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A streamed answer becomes its saved row in one step, whichever comes first: the save
 * callback or the database update; it is never shown twice and never missing for a frame.
 */
class StreamHandOverTest {

    private fun msg(id: Long, role: MessageRole, text: String = "m$id") =
        ChatMessage(id = id, sessionId = 1, role = role, contentMarkdown = text, createdAt = id)

    private val question = msg(1, MessageRole.USER, "问题")
    private val answer = msg(2, MessageRole.AI, "回答")

    private fun streaming(): ChatDetailUiState =
        ChatDetailUiState(messages = listOf(question))
            .startStream(floorId = 1)
            .copy(streamingText = "回答")

    private fun answerRows(state: ChatDetailUiState) = chatRows(state).filterIsInstance<ChatRow.Answer>()

    @Test fun `save callback first, then the database row`() {
        val saved = streaming().copy(isSending = false).answerSaved(2)
        assertEquals(2L, saved.pendingAnswerId)
        assertEquals("回答", saved.streamingText) // still shown by the stream row
        assertEquals(1, answerRows(saved).size)

        val landed = saved.withMessages(listOf(question, answer))
        assertNull(landed.streamingText)
        assertNull(landed.pendingAnswerId)
        val rows = answerRows(landed)
        assertEquals(1, rows.size)
        assertEquals(answerRows(saved).single().key, rows.single().key) // same row, carried on
        assertEquals(answer, rows.single().message)
        assertTrue(rows.single().live)
    }

    @Test fun `database row first, then the save callback`() {
        val early = streaming().withMessages(listOf(question, answer))
        assertEquals(listOf(question), early.visibleMessages) // hidden behind the stream row
        assertEquals(1, answerRows(early).size)

        val saved = early.copy(isSending = false).answerSaved(2)
        assertNull(saved.streamingText)
        val rows = answerRows(saved)
        assertEquals(1, rows.size)
        assertEquals(answerRows(early).single().key, rows.single().key)
    }

    @Test fun `older answers keep their own keys and stay settled`() {
        val older = msg(0, MessageRole.AI, "旧回答")
        val state = ChatDetailUiState(messages = listOf(older, question)).startStream(floorId = 1)
        val rows = answerRows(state)
        assertEquals(listOf<Any>("answer-stream-1", 0L), rows.map { it.key })
        assertEquals(listOf(true, false), rows.map { it.live })
        assertEquals("", rows.first().text) // thinking until the first token
    }

    @Test fun `each stream gets a new key`() {
        val first = streaming().answerSaved(2).withMessages(listOf(question, answer))
        val second = first.startStream(floorId = 3)
        assertEquals("answer-stream-1", first.answerKey(2))
        assertEquals("answer-stream-2", second.streamRowKey(second.streamKey))
    }
}
