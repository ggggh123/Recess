package com.example.personal_studio.feature.chat.vm

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.personal_studio.core.llm.CuratedModels
import com.example.personal_studio.core.llm.activeEndpoint
import com.example.personal_studio.data.local.datastore.UserPreferencesRepository
import com.example.personal_studio.data.repository.ChatRepository
import com.example.personal_studio.domain.chat.GenerateTitleUseCase
import com.example.personal_studio.domain.chat.SendChunk
import com.example.personal_studio.domain.chat.ChatPrompts
import com.example.personal_studio.domain.chat.SendMessageUseCase
import com.example.personal_studio.domain.model.ChatMessage
import com.example.personal_studio.domain.model.ChatSession
import com.example.personal_studio.domain.model.MessageRole
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

data class ChatDetailUiState(
    val session: ChatSession? = null,
    /** The conversation as stored (SYSTEM turns removed). Render [visibleMessages]. */
    val messages: List<ChatMessage> = emptyList(),
    val attachedImagePaths: List<String> = emptyList(),
    /**
     * Text of the answer being streamed, or of a finished one until its saved row reaches
     * [messages]; null when there is no answer in flight.
     */
    val streamingText: String? = null,
    val errorBanner: String? = null,
    /** A request is running: the send button is a stop button and the answer is still growing. */
    val isSending: Boolean = false,
    /** Active model id from preferences (or the bundled default). Surfaced in the top bar. */
    val activeModel: String = CuratedModels.DEFAULT.code,
    /** Incremented each time a send round-trip (including title generation) fully completes. */
    val sendGeneration: Int = 0,
    /** The user message the composer is editing (修改), or null. */
    val editingMessageId: Long? = null,
    /** Number of the current (or last) streamed answer; names its row while it streams. */
    val streamKey: Int = 0,
    /**
     * Saved answers that were streamed on this page → the [streamKey] they streamed under. Their
     * rows keep that key, so the streamed row becomes the saved row without being rebuilt.
     */
    val answerKeys: Map<Long, Int> = emptyMap(),
    /** The streamed answer was saved under this id, but [messages] does not contain it yet. */
    val pendingAnswerId: Long? = null,
    /** The user message the streamed answer replies to; newer AI rows stay hidden while it streams. */
    val streamFloorId: Long? = null,
) {
    /**
     * [messages] without a just-saved answer that the streaming row still shows, so the answer
     * never appears twice whichever arrives first: the database update or the save callback.
     */
    val visibleMessages: List<ChatMessage>
        get() {
            val floor = streamFloorId
            if (streamingText == null || floor == null) return messages
            return messages.filterNot { it.role == MessageRole.AI && it.id > floor }
        }

    /** List key of an answer row: the stream it came from, else its id. */
    fun answerKey(messageId: Long): Any = answerKeys[messageId]?.let(::streamRowKey) ?: messageId

    /** List key of the row showing stream [key]. */
    fun streamRowKey(key: Int): Any = "answer-stream-$key"
}

/** A new stream starts: an empty answer row (thinking), replying to [floorId] once known. */
internal fun ChatDetailUiState.startStream(floorId: Long?): ChatDetailUiState = copy(
    streamingText = "",
    isSending = true,
    streamKey = streamKey + 1,
    pendingAnswerId = null,
    streamFloorId = floorId,
    errorBanner = null,
)

/** The streamed answer was saved as [answerId]; the stream row hands over to its saved row. */
internal fun ChatDetailUiState.answerSaved(answerId: Long): ChatDetailUiState {
    val keyed = copy(answerKeys = answerKeys + (answerId to streamKey))
    return if (messages.any { it.id == answerId }) keyed.streamDone() else keyed.copy(pendingAnswerId = answerId)
}

/** New rows from the database; completes a pending hand-over once the saved answer is among them. */
internal fun ChatDetailUiState.withMessages(rows: List<ChatMessage>): ChatDetailUiState {
    val next = copy(messages = rows)
    val pending = pendingAnswerId
    return if (pending != null && rows.any { it.id == pending }) next.streamDone() else next
}

private fun ChatDetailUiState.streamDone() = copy(streamingText = null, pendingAnswerId = null, streamFloorId = null)

@HiltViewModel(assistedFactory = ChatDetailViewModel.Factory::class)
class ChatDetailViewModel @AssistedInject constructor(
    @Assisted val sessionId: Long,
    private val repo: ChatRepository,
    private val send: SendMessageUseCase,
    private val titleGen: GenerateTitleUseCase,
    private val prefs: UserPreferencesRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(sessionId: Long): ChatDetailViewModel
    }

    companion object { const val MAX_IMAGES = 6 }

    private val _uiState = MutableStateFlow(ChatDetailUiState())
    val uiState: StateFlow<ChatDetailUiState> = _uiState.asStateFlow()

    /**
     * The composer's text. The field edits it directly and synchronously; the view model only
     * reads it when sending and replaces it when an edit starts or ends.
     */
    val input = TextFieldState()

    /** What the composer held before 修改 replaced it; restored when the edit is cancelled. */
    private var draftBeforeEdit: Pair<String, List<String>>? = null

    init {
        repo.observeMessages(sessionId)
            // SYSTEM messages (e.g. the grade-context seeded by StartGradeChatUseCase)
            // are part of the LLM context but must not render as chat bubbles.
            .onEach { msgs -> _uiState.update { it.withMessages(msgs.filter { m -> m.role != MessageRole.SYSTEM }) } }
            .launchIn(viewModelScope)

        prefs.activeEndpoint()
            .onEach { endpoint -> _uiState.update { it.copy(activeModel = endpoint.model) } }
            .launchIn(viewModelScope)

        viewModelScope.launch {
            repo.getSession(sessionId)?.let { s ->
                _uiState.update { it.copy(session = s) }
            }
        }
    }

    /** Replaces the composer text (tests, and anything that fills the field programmatically). */
    fun onInputChanged(value: String) = input.setTextAndPlaceCursorAtEnd(value)

    /** 追加一张图(已满 MAX_IMAGES 则忽略)。裁剪确认后调用。 */
    fun onAttachImage(path: String) = _uiState.update {
        if (it.attachedImagePaths.size >= MAX_IMAGES) it
        else it.copy(attachedImagePaths = it.attachedImagePaths + path)
    }

    /** 从预览里移除指定图。 */
    fun onRemoveAttachedImage(path: String) = _uiState.update {
        it.copy(attachedImagePaths = it.attachedImagePaths - path)
    }

    fun onDismissError() { _uiState.update { it.copy(errorBanner = null) } }

    /**
     * Leaving a conversation in which nothing was sent removes it, so cancelling 拍题提问 or
     * backing out of 新对话 leaves no empty entry behind. onCleared runs only when the page
     * is really left (not on rotation or a theme change), on a scope of its own because
     * viewModelScope is already cancelled here.
     */
    override fun onCleared() {
        val repo = repo
        val id = sessionId
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            if (repo.listMessages(id).isEmpty()) repo.deleteSession(id)
        }
    }

    private var sendJob: Job? = null

    /** Nothing can start while an answer is generating or still being handed over. */
    private val busy: Boolean get() = _uiState.value.isSending || _uiState.value.streamingText != null

    fun onSend() {
        if (busy) return
        val text = input.text.toString().trim()
        val imagePaths = _uiState.value.attachedImagePaths
        if (text.isBlank() && imagePaths.isEmpty()) return
        val replacing = _uiState.value.editingMessageId

        input.clearText()
        draftBeforeEdit = null
        _uiState.update { it.copy(attachedImagePaths = emptyList(), editingMessageId = null).startStream(floorId = null) }

        sendJob = viewModelScope.launch {
            // 修改: the edited message and everything after it make way for the new version.
            if (replacing != null) repo.deleteMessagesFrom(sessionId, replacing, inclusive = true)
            streamAnswer(send(sessionId = sessionId, userText = text, userImagePaths = imagePaths, systemPrompt = systemPrompt()))
        }
    }

    /**
     * 修改: the composer takes the message's text and images; sending replaces the message and
     * everything after it and asks again. The current draft comes back on [onCancelEdit].
     */
    fun onStartEdit(messageId: Long) {
        if (busy) return
        val message = _uiState.value.messages.firstOrNull { it.id == messageId && it.role == MessageRole.USER } ?: return
        if (_uiState.value.editingMessageId == null) {
            draftBeforeEdit = input.text.toString() to _uiState.value.attachedImagePaths
        }
        input.setTextAndPlaceCursorAtEnd(message.contentMarkdown)
        _uiState.update { it.copy(editingMessageId = messageId, attachedImagePaths = message.attachedImagePaths.take(MAX_IMAGES)) }
    }

    fun onCancelEdit() {
        if (_uiState.value.editingMessageId == null) return
        val (text, images) = draftBeforeEdit ?: ("" to emptyList())
        draftBeforeEdit = null
        input.setTextAndPlaceCursorAtEnd(text)
        _uiState.update { it.copy(editingMessageId = null, attachedImagePaths = images) }
    }

    /** 回溯: the turns after this user message are dropped and it is answered again. */
    fun onRewind(messageId: Long) {
        if (busy) return
        if (_uiState.value.messages.none { it.id == messageId && it.role == MessageRole.USER }) return
        onCancelEdit()
        _uiState.update { it.startStream(floorId = messageId) }
        sendJob = viewModelScope.launch {
            repo.deleteMessagesFrom(sessionId, messageId, inclusive = false)
            streamAnswer(send.reply(sessionId, systemPrompt()))
        }
    }

    /** The system prompt for this request: the user's own (设置 → AI 模型) or the built-in one. */
    private suspend fun systemPrompt(): String = ChatPrompts.effective(prefs.chatSystemPrompt.first())

    private suspend fun streamAnswer(chunks: Flow<SendChunk>) {
        val buffer = StringBuilder()
        chunks.collect { chunk ->
            when (chunk) {
                is SendChunk.UserPersisted -> _uiState.update { it.copy(streamFloorId = chunk.messageId) }
                is SendChunk.Delta -> {
                    buffer.append(chunk.text)
                    _uiState.update { it.copy(streamingText = buffer.toString()) }
                }
                is SendChunk.AiPersisted -> {
                    _uiState.update { it.copy(isSending = false).answerSaved(chunk.messageId) }
                    yield() // allow collectors to observe the persisted state before continuing
                    maybeGenerateTitle()
                    // Bump generation to guarantee one stable "fully done" emission after all
                    // title work; this also ensures Turbine-based tests can call
                    // expectMostRecentItem() after the streaming loop exits.
                    _uiState.update { it.copy(sendGeneration = it.sendGeneration + 1) }
                }
                is SendChunk.Error -> {
                    _uiState.update {
                        it.copy(streamingText = null, isSending = false, streamFloorId = null, errorBanner = chunk.message)
                    }
                }
            }
        }
    }

    /**
     * 「⏹ 停止」:取消正在生成的回复,保留已流式出的半截内容(存为 AI 消息 + 「（已停止）」)。
     * The partial answer stays on screen and hands over to its saved row like a finished one.
     */
    fun onStop() {
        if (!_uiState.value.isSending) return
        sendJob?.cancel()
        sendJob = null
        val partial = _uiState.value.streamingText
        if (partial.isNullOrBlank()) {
            _uiState.update { it.copy(streamingText = null, isSending = false, streamFloorId = null) }
            return
        }
        _uiState.update { it.copy(isSending = false) }
        viewModelScope.launch {
            try {
                val id = repo.appendMessage(
                    sessionId = sessionId,
                    role = MessageRole.AI,
                    content = partial.trimEnd() + "\n\n（已停止）",
                    modelUsed = _uiState.value.activeModel,
                )
                _uiState.update { it.answerSaved(id) }
            } catch (t: Throwable) {
                _uiState.update { it.copy(streamingText = null, pendingAnswerId = null, streamFloorId = null) }
                if (t is kotlinx.coroutines.CancellationException) throw t
            }
        }
    }

    /** A placeholder name ("新对话") is replaced by a generated title after the first answer. */
    private suspend fun maybeGenerateTitle() {
        val session = _uiState.value.session ?: repo.getSession(sessionId) ?: return
        if (!com.example.personal_studio.domain.chat.ChatTitles.isPlaceholder(session.title)) return
        val newTitle = titleGen(sessionId) ?: return
        _uiState.update { it.copy(session = session.copy(title = newTitle)) }
    }
}
