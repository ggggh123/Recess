package com.example.personal_studio.feature.chat.vm

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.personal_studio.core.llm.CuratedModels
import com.example.personal_studio.core.llm.activeEndpoint
import com.example.personal_studio.data.deep.DeepLive
import com.example.personal_studio.data.deep.DeepMode
import com.example.personal_studio.data.deep.DeepQuestion
import com.example.personal_studio.data.deep.DeepSendResult
import com.example.personal_studio.data.deep.DeepStatus
import com.example.personal_studio.data.deep.DeepStep
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
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
    /** 深度对话的附加状态；普通对话为 null。 */
    val deep: DeepChatState? = null,
    /** 服务器上的深度模式可以用：普通对话的回答下面显示“验算”。 */
    val deepAvailable: Boolean = false,
    /** 深度模式当前的模型 ID（标题栏和新回答的模型行用）。 */
    val deepModel: String? = null,
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

/**
 * 深度对话在问答页上多出来的东西：过程步骤、提问、过期。回答文字仍走 [ChatDetailUiState.streamingText]，
 * 和普通对话共用同一套“流式行交接给保存行”的逻辑。
 */
data class DeepChatState(
    val expired: Boolean = false,
    /** 正在进行的一轮的步骤（结束后保留到这轮的步骤从数据库读到为止）。 */
    val liveSteps: List<DeepStep> = emptyList(),
    val liveStartedAt: Long? = null,
    val note: String? = null,
    val question: DeepQuestion? = null,
    /** 已完成回答的步骤，按 AI 消息 ID。 */
    val stepsByAnswer: Map<Long, List<DeepStep>> = emptyMap(),
    /** 刚交接完的回答 → 它的步骤，在 [stepsByAnswer] 读到之前先用这份，过程卡不会闪一下。 */
    val handover: Pair<Long, List<DeepStep>>? = null,
) {
    /** 这条 AI 回答的过程步骤。 */
    fun stepsFor(answerId: Long): List<DeepStep>? =
        stepsByAnswer[answerId] ?: handover?.takeIf { it.first == answerId }?.second
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
    private val deepMode: DeepMode,
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

        // 深度模式：这个会话是不是深度对话、过没过期；服务器能不能用（普通回答下面的“验算”）。
        deepMode.conversations
            .map { it[sessionId] }
            .distinctUntilChanged()
            .onEach { conv ->
                _uiState.update { st ->
                    when {
                        conv == null -> st.copy(deep = null)
                        else -> st.copy(deep = (st.deep ?: DeepChatState()).copy(expired = conv.expired))
                    }
                }
            }
            .launchIn(viewModelScope)
        deepMode.status
            .onEach { s ->
                _uiState.update {
                    it.copy(
                        deepAvailable = s is DeepStatus.Available,
                        deepModel = (s as? DeepStatus.Available)?.model ?: it.deepModel,
                    )
                }
            }
            .launchIn(viewModelScope)
        deepMode.steps(sessionId)
            .onEach { map -> _uiState.update { st -> st.copy(deep = st.deep?.copy(stepsByAnswer = map)) } }
            .launchIn(viewModelScope)
        deepMode.live(sessionId).onEach(::onDeepLive).launchIn(viewModelScope)
    }

    /** 首次使用说明是否已确认（验算、新开深度对话之前要先确认；[consent] 为 true 时先记下同意）。 */
    val deepPrivacyAccepted: Flow<Boolean> get() = deepMode.privacyAccepted

    /** 页面打开或回到前台：有正在进行的一轮就接上事件流。 */
    fun onResumed() {
        if (_uiState.value.deep != null) deepMode.resume(sessionId)
    }

    /** 这轮的 userMessageId：它的结束已经交接过，重复的状态不再处理。 */
    private var deepFinishHandled: Long? = null

    /**
     * 深度对话的一轮在服务器上进行：文字照普通回答那样流式显示，结束后保存的回答由数据层写进问答表，
     * 这里把流式行交接给保存行，再告诉数据层可以清掉这轮的状态。
     */
    private fun onDeepLive(live: DeepLive?) {
        if (live == null) {
            _uiState.update { st ->
                st.copy(deep = st.deep?.copy(liveSteps = emptyList(), liveStartedAt = null, note = null, question = null))
            }
            return
        }
        if (!live.finished) {
            deepFinishHandled = null
            _uiState.update { st ->
                val base = if (st.streamingText == null) st.startStream(floorId = live.userMessageId) else st
                base.copy(
                    streamingText = live.text,
                    isSending = true,
                    deep = (base.deep ?: DeepChatState()).copy(
                        liveSteps = live.steps, liveStartedAt = live.startedAt, note = live.note, question = live.question,
                    ),
                )
            }
            return
        }
        if (deepFinishHandled == live.userMessageId) return
        deepFinishHandled = live.userMessageId
        val saved = live.savedAnswerId
        val streamedHere = _uiState.value.streamingText != null
        _uiState.update { st ->
            val deep = st.deep?.copy(
                liveSteps = emptyList(), liveStartedAt = null, note = null, question = null,
                handover = saved?.let { it to live.steps },
            )
            when {
                // 页面打开时这轮已经结束：回答直接从数据库出现，不需要交接。
                !streamedHere -> st.copy(deep = deep, errorBanner = if (saved == null) live.error ?: st.errorBanner else st.errorBanner)
                saved != null -> st.copy(streamingText = live.text, isSending = false, deep = deep).answerSaved(saved)
                // 中止时还没生成文字：没有回答可存，提示一句，免得过程卡消失后看不出发生了什么。
                else -> st.copy(streamingText = null, isSending = false, streamFloorId = null, deep = deep,
                    errorBanner = live.error ?: "已停止，这一轮没有生成回答")
            }
        }
        viewModelScope.launch {
            if (saved != null) maybeNameDeepConversation()
            deepMode.acknowledge(sessionId)
            _uiState.update { it.copy(sendGeneration = it.sendGeneration + 1) }
        }
    }

    /** 深度对话发出去之前的检查：过期的只能看，正在进行时不能再发。 */
    private fun sendDeep(text: String, imagePaths: List<String>) {
        if (_uiState.value.deep?.expired == true) return
        input.clearText()
        _uiState.update { it.copy(attachedImagePaths = emptyList(), errorBanner = null) }
        sendJob = viewModelScope.launch {
            when (val r = deepMode.send(sessionId, text, imagePaths)) {
                is DeepSendResult.Sent -> Unit
                is DeepSendResult.Failed -> {
                    // 没发出去：原文和图片放回输入框，再按发送就是重发同一条（数据层不会重复保存）。
                    if (input.text.isEmpty()) input.setTextAndPlaceCursorAtEnd(text)
                    _uiState.update {
                        it.copy(
                            errorBanner = r.message,
                            attachedImagePaths = if (it.attachedImagePaths.isEmpty()) imagePaths else it.attachedImagePaths,
                        )
                    }
                }
            }
        }
    }

    /** 回答 Agent 的提问。 */
    fun onAnswerQuestion(formId: String, answer: Map<String, List<String>>) {
        viewModelScope.launch {
            if (!deepMode.answer(sessionId, formId, answer)) {
                _uiState.update { it.copy(errorBanner = "回答没有送达，请再试一次") }
            }
        }
    }

    /**
     * 交给深度模式验算：新开一个深度对话，把这条回答对应的题目（文字和图片）和回答一起发过去。
     * 成功建好会话后回调 [onCreated]，页面跳过去；发送结果在新对话里看。
     */
    fun onVerify(aiMessageId: Long, consent: Boolean, onCreated: (Long) -> Unit) {
        val messages = _uiState.value.messages
        val answer = messages.firstOrNull { it.id == aiMessageId && it.role == MessageRole.AI } ?: return
        val question = messages.lastOrNull { it.role == MessageRole.USER && it.id < aiMessageId }
        val images = question?.attachedImagePaths.orEmpty().take(DeepMode.MAX_IMAGES)
        val seed = verifyPrompt(question?.contentMarkdown.orEmpty(), images.isNotEmpty(), answer.contentMarkdown)
        viewModelScope.launch {
            if (consent) deepMode.acceptPrivacy()
            val id = try {
                deepMode.createConversation()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.update { it.copy(errorBanner = "没能新建深度对话，请稍后再试") }
                return@launch
            }
            repo.renameSession(id, verifyTitle(question?.contentMarkdown))
            onCreated(id)
            // 新对话页的 ViewModel 会接着显示这一轮；发送失败的提示也在那边显示。
            deepMode.send(id, seed, images)
        }
    }

    /** 过期的深度对话里点“新开深度对话”。 */
    fun onNewDeepConversation(consent: Boolean, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            if (consent) deepMode.acceptPrivacy()
            val id = runCatching { deepMode.createConversation() }.getOrNull()
            if (id == null) _uiState.update { it.copy(errorBanner = "没能新建深度对话，请稍后再试") } else onCreated(id)
        }
    }

    /**
     * 深度对话的标题直接取第一条提问的开头，不再调用模型生成：标题生成会把内容发给问答模型，
     * 而深度模式的说明里只写了发往课间服务器。
     */
    private suspend fun maybeNameDeepConversation() {
        val session = repo.getSession(sessionId) ?: return
        if (!com.example.personal_studio.domain.chat.ChatTitles.isPlaceholder(session.title)) return
        val first = repo.listMessages(sessionId).firstOrNull { it.role == MessageRole.USER } ?: return
        val title = deepTitle(first.contentMarkdown, first.attachedImagePaths.isNotEmpty())
        repo.renameSession(sessionId, title)
        _uiState.update { it.copy(session = session.copy(title = title)) }
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
        if (_uiState.value.deep != null) return sendDeep(text, imagePaths)
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
        // 服务器上的深度对话只能往后接，不能改写之前的消息。
        if (busy || _uiState.value.deep != null) return
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
        if (busy || _uiState.value.deep != null) return
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
        if (_uiState.value.deep != null) {
            // 服务器那边停下后会发回本轮结束，已生成的部分由数据层存成“（已停止）”的回答。
            viewModelScope.launch { deepMode.stop(sessionId) }
            return
        }
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
        if (_uiState.value.deep != null) return maybeNameDeepConversation()
        val session = _uiState.value.session ?: repo.getSession(sessionId) ?: return
        if (!com.example.personal_studio.domain.chat.ChatTitles.isPlaceholder(session.title)) return
        val newTitle = titleGen(sessionId) ?: return
        _uiState.update { it.copy(session = session.copy(title = newTitle)) }
    }
}

/** “验算”发给深度模式的第一条消息：原题、原回答，以及要它做什么。 */
internal fun verifyPrompt(question: String, hasImages: Boolean, answer: String): String = buildString {
    append("请验算下面这道题的解答：能用代码计算的就在沙箱里写 Python 算一遍，指出解答里错误或不严谨的地方，最后给出你确认的答案。")
    append("\n\n【题目】\n")
    append(question.trim().ifEmpty { if (hasImages) "（见图片）" else "（原对话里没有文字题目）" })
    if (hasImages && question.isNotBlank()) append("\n（另见图片）")
    append("\n\n【待验算的解答】\n")
    append(answer.trim())
}

/** 验算对话的标题：“验算：”加题目开头。 */
internal fun verifyTitle(question: String?): String =
    "验算：" + (question?.let(::firstLineOf)?.takeIf { it.isNotEmpty() } ?: "拍的题")

/** 深度对话的标题：第一条提问的第一行，最多 18 个字；只有图片时叫“拍题 · 深度”。 */
internal fun deepTitle(text: String, hasImages: Boolean): String =
    firstLineOf(text).takeIf { it.isNotEmpty() } ?: if (hasImages) "拍题 · 深度" else com.example.personal_studio.domain.chat.ChatTitles.NEW

private fun firstLineOf(text: String): String {
    val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        .replace(Regex("""\$+"""), "")
        .trim()
    return if (line.length > 18) line.take(18) + "…" else line
}
