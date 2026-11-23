package com.example.personal_studio.domain.chat

import com.example.personal_studio.domain.model.ChatMessage

/**
 * Editing or rewinding a conversation drops the turns after a message. [messages] is the
 * conversation in display order (oldest first). Returns the ids from [anchorId] to the end,
 * [anchorId] itself only when [inclusive]; empty when [anchorId] is not in the list, so a
 * stale tap never deletes anything.
 */
fun messagesToDiscard(messages: List<ChatMessage>, anchorId: Long, inclusive: Boolean): List<Long> {
    val index = messages.indexOfFirst { it.id == anchorId }
    if (index < 0) return emptyList()
    val from = if (inclusive) index else index + 1
    return messages.drop(from).map { it.id }
}
