package com.example.personal_studio.feature.chat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.personal_studio.data.deep.DeepStep
import com.example.personal_studio.domain.chat.messagesToDiscard
import com.example.personal_studio.domain.model.ChatMessage
import com.example.personal_studio.domain.model.KbDraftSource
import com.example.personal_studio.domain.model.MessageRole
import com.example.personal_studio.feature.chat.vm.ChatDetailUiState
import com.example.personal_studio.feature.chat.vm.ChatDetailViewModel
import com.example.personal_studio.feature.knowledge.ui.SavePreviewModal
import com.example.personal_studio.feature.knowledge.vm.SaveToKnowledgeUiState
import com.example.personal_studio.feature.knowledge.vm.SaveToKnowledgeViewModel
import com.example.personal_studio.feature.scanner.chat.QuickCaptureForChatScreen
import com.example.personal_studio.feature.scanner.library.ScanLibraryPickerScreen
import com.example.personal_studio.ui.components.TgConfirmDialog
import com.example.personal_studio.ui.components.TgTopBar
import com.example.personal_studio.ui.shell.LocalShellToast
import com.example.personal_studio.ui.theme.Tg
import com.example.personal_studio.ui.theme.TgMotion
import kotlinx.coroutines.launch

/**
 * A conversation: the user's turns as bubbles on the right, answers on the page with the
 * model line above and time / actions below; the composer at the bottom. Photos come from
 * the gallery, the scan camera or the scan library (the ＋ menu), then pass through the crop
 * overlay.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ChatDetailScreen(
    sessionId: Long,
    onBack: () -> Unit,
    onNavigateToKbEntry: (Long) -> Unit,
    /** Open the photo-question camera right away (＋ → 拍题提问). */
    startWithQuickCapture: Boolean = false,
    /** 打开另一段对话（验算、新开深度对话之后跳过去）。 */
    onOpenSession: (Long) -> Unit = {},
) {
    val vm: ChatDetailViewModel = hiltViewModel(
        creationCallback = { factory: ChatDetailViewModel.Factory -> factory.create(sessionId) }
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    val saveVm: SaveToKnowledgeViewModel = hiltViewModel()
    val saveState by saveVm.uiState.collectAsStateWithLifecycle()
    val c = Tg.colors
    val clipboard = LocalClipboardManager.current
    val toast = LocalShellToast.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    // Source the save modal works with, so retry from its Error state re-runs the same one.
    val activeSourceState = remember { mutableStateOf<KbDraftSource?>(null) }
    val startSave: (KbDraftSource) -> Unit = { src ->
        activeSourceState.value = src
        saveVm.startDraft(src)
    }
    val listState = rememberLazyListState()
    val composerFocus = remember { FocusRequester() }

    var attachMenuOpen by remember { mutableStateOf(false) }
    var pickedForCrop by remember { mutableStateOf<String?>(null) }
    var showQuickCapture by rememberSaveable { mutableStateOf(startWithQuickCapture) }
    var showScansPicker by remember { mutableStateOf(false) }
    var confirmRewind by remember { mutableStateOf<ChatMessage?>(null) }
    var composerHeightPx by remember { mutableIntStateOf(0) }
    val deep = state.deep
    val deepPrivacyAccepted by vm.deepPrivacyAccepted.collectAsStateWithLifecycle(initialValue = false)
    val uriHandler = LocalUriHandler.current
    // 验算和新开深度对话之前，第一次要先确认说明；确认后接着做原来要做的事。
    // 回调参数为 true 表示用户刚在说明里点了同意，ViewModel 先记下同意再新建深度对话。
    var pendingDeepAction by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    var choiceSheetOpen by remember { mutableStateOf(false) }
    val withDeepConsent: ((Boolean) -> Unit) -> Unit = { action -> if (deepPrivacyAccepted) action(false) else pendingDeepAction = action }
    LifecycleResumeEffect(deep != null) {
        vm.onResumed()
        onPauseOrDispose { }
    }

    val openGallery = rememberGalleryPicker { path -> pickedForCrop = path }
    // The camera, the scan library and the photo picker cover the page: put the keyboard away.
    val leaveComposer = {
        attachMenuOpen = false
        focusManager.clearFocus()
    }

    // Rendered heights of answers by row key (the formula view is a WebView that reports its
    // height asynchronously); re-entering rows lay out at their last size instead of popping.
    val answerHeights = remember { mutableStateMapOf<Any, Int>() }

    // The list is laid out bottom-up (see the LazyColumn), so staying with the newest turn
    // needs no scrolling while answers stream, formulas finish rendering or the keyboard
    // opens. Only sending jumps back to the bottom when the user was reading history.
    val lastUserMessageId = state.messages.lastOrNull { it.role == MessageRole.USER }?.id
    var seenUserMessageId by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(lastUserMessageId) {
        if (sentNewMessage(seen = seenUserMessageId, latest = lastUserMessageId)) listState.scrollToItem(0)
        seenUserMessageId = lastUserMessageId
    }

    // 修改 puts the message into the composer: focus it and bring the keyboard up.
    LaunchedEffect(state.editingMessageId) {
        if (state.editingMessageId != null) {
            runCatching { composerFocus.requestFocus() }
            keyboard?.show()
        }
    }

    BackHandler(enabled = attachMenuOpen) { attachMenuOpen = false }
    BackHandler(enabled = !attachMenuOpen && state.editingMessageId != null) { vm.onCancelEdit() }

    // The page lifts the composer by the keyboard itself; the window must not pan (which hid
    // the header) or shrink differently per ROM.
    com.example.personal_studio.ui.components.KeyboardHandledByInsets()

    Box(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.fillMaxSize()) {
            TgTopBar(
                route = "chat",
                subtitle = displayTitle(state.session?.title) + "\n" +
                    if (deep != null) {
                        val id = state.deepChoice?.model ?: state.deepModel ?: state.messages.lastOrNull { it.role == MessageRole.AI }?.modelUsed
                        "深度模式 · " + (modelName(state, id) ?: "Agent")
                    }
                    else state.activeModel,
                onBack = onBack,
                trailing = {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .clickable(role = Role.Button, onClickLabel = "把整段对话存入知识库") {
                                startSave(KbDraftSource.FromChatSession(sessionId = sessionId))
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.BookmarkAdd, contentDescription = "存入知识库", tint = c.text, modifier = Modifier.size(22.dp))
                    }
                },
            )

            Box(Modifier.weight(1f).fillMaxWidth()) {
                val rows = chatRows(state)
                if (rows.isEmpty()) {
                    Column(Modifier.align(Alignment.Center).padding(horizontal = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (deep != null) "深度模式" else "想问点什么？", style = MaterialTheme.typography.headlineLarge, color = c.text)
                        Text(
                            if (deep != null) {
                                "题目会发到课间服务器，由 Agent 写代码验算、需要时联网查资料，适合要计算或核对的难题。比普通问答慢一些。"
                            } else {
                                "直接输入问题，或点左下角的 ＋ 拍下题目。好的回答可以存进知识库。"
                            },
                            style = MaterialTheme.typography.bodyMedium, color = c.textSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                // Bottom-up: index 0 is the spacer at the bottom edge, then the newest row (the
                // streaming answer while there is one), then older rows. The list keeps its
                // bottom edge fixed, so a reopened long conversation stays on the newest turn
                // while the answers above it grow to their rendered height, a streaming answer
                // grows upward in place, and the keyboard shrinks the list from below without
                // hiding the newest turn. Short conversations still start at the top.
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                    state = listState,
                    reverseLayout = true,
                    verticalArrangement = Arrangement.Top,
                ) {
                    item(key = "__end__") { Box(Modifier.padding(bottom = 12.dp)) }
                    // One call site for streaming and saved answers: a streamed answer keeps its
                    // key when it is saved, so its row (and formula view) carries straight on.
                    // contentType lets the list recycle an answer row (and its WebView) only
                    // into another answer row.
                    items(rows, key = { it.key }, contentType = { it.contentType }) { row ->
                        when (row) {
                            is ChatRow.User -> {
                                val m = row.message
                                UserBubble(
                                    text = m.contentMarkdown,
                                    images = m.attachedImagePaths,
                                    editing = state.editingMessageId == m.id,
                                    canChange = !state.isSending && state.streamingText == null,
                                    onCopy = {
                                        clipboard.setText(AnnotatedString(m.contentMarkdown))
                                        toast?.show("已复制")
                                    },
                                    // 深度对话在服务器上只能往后接：不能修改或回溯。
                                    onEdit = if (deep != null) null else { { vm.onStartEdit(m.id) } },
                                    onRewind = if (deep != null) null else {
                                        {
                                            if (messagesToDiscard(state.visibleMessages, m.id, inclusive = false).isEmpty()) {
                                                vm.onRewind(m.id)
                                            } else {
                                                confirmRewind = m
                                            }
                                        }
                                    },
                                )
                            }
                            is ChatRow.Process -> DeepProcessCard(
                                steps = row.steps, live = row.live, startedAt = row.startedAt, note = row.note,
                            )
                            is ChatRow.Answer -> {
                                val m = row.message
                                AiAnswer(
                                    // The model that produced this reply, not the one active now.
                                    model = if (deep != null) {
                                        modelName(state, m?.modelUsed ?: state.deepChoice?.model ?: state.deepModel) ?: "深度模式"
                                    } else {
                                        m?.modelUsed ?: state.activeModel
                                    },
                                    footer = m?.let(::formatDoneLine),
                                    onCopy = m?.let {
                                        {
                                            clipboard.setText(AnnotatedString(it.contentMarkdown))
                                            toast?.show("已复制")
                                        }
                                    },
                                    onSave = m?.let { { startSave(KbDraftSource.FromChatMessage(sessionId = sessionId, aiMessageId = it.id)) } },
                                    // 普通回答交给深度模式验算：新开一个深度对话，带上原题和这条回答。
                                    onVerify = m?.takeIf { deep == null && state.deepAvailable && !state.isSending }?.let {
                                        { withDeepConsent { consent -> vm.onVerify(it.id, consent, onOpenSession) } }
                                    },
                                ) {
                                    AnswerContent(
                                        markdown = row.text,
                                        streaming = row.streaming,
                                        live = row.live,
                                        cachedHeightDp = answerHeights[row.key],
                                        onHeightChanged = { h -> answerHeights[row.key] = h },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            ChatErrorNote(state.errorBanner, onDismiss = vm::onDismissError)
            // 深度对话：输入框上方是当前的模型和推理等级，点开换。
            val choice = state.deepChoice
            if (deep != null && deep.expired.not() && choice != null && state.deepModels.isNotEmpty()) {
                DeepChoiceChip(
                    label = choiceLabel(state.deepModels, choice),
                    onClick = { choiceSheetOpen = true },
                )
            }
            deep?.question?.let { q ->
                DeepQuestionCard(q, onSubmit = { answer -> vm.onAnswerQuestion(q.formId, answer) })
            }
            if (deep?.expired == true) {
                DeepExpiredNote(
                    onNewConversation = { withDeepConsent { consent -> vm.onNewDeepConversation(consent, onOpenSession) } },
                    modifier = Modifier.navigationBarsPadding().onSizeChanged { composerHeightPx = it.height },
                )
            } else ChatComposer(
                input = vm.input,
                images = state.attachedImagePaths,
                sending = state.isSending,
                canAttach = state.attachedImagePaths.size <
                    if (deep != null) com.example.personal_studio.data.deep.DeepMode.MAX_IMAGES else ChatDetailViewModel.MAX_IMAGES,
                onAttach = { attachMenuOpen = !attachMenuOpen },
                onRemoveImage = vm::onRemoveAttachedImage,
                onSend = vm::onSend,
                onStop = vm::onStop,
                editing = state.editingMessageId != null,
                onCancelEdit = vm::onCancelEdit,
                attachOpen = attachMenuOpen,
                focusRequester = composerFocus,
                modifier = Modifier.onSizeChanged { composerHeightPx = it.height },
            )

            SavePreviewModal(
                state = saveState,
                onCancel = { saveVm.reset() },
                onConfirm = { draft -> saveVm.commit(draft) },
                onRetry = { activeSourceState.value?.let { saveVm.retry(it) } },
            )
            LaunchedEffect(saveState) {
                val s = saveState
                if (s is SaveToKnowledgeUiState.Saved) {
                    onNavigateToKbEntry(s.entryId)
                    saveVm.reset()
                }
            }
        }

        // The ＋ menu floats over the page above the composer's left end. It is drawn in this
        // window (not a dialog or sheet), so the text field keeps focus and an open keyboard
        // stays up. Any touch outside closes it.
        if (attachMenuOpen) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            attachMenuOpen = false
                        }
                    },
            )
        }
        val composerHeight = with(LocalDensity.current) { composerHeightPx.toDp() }
        AnimatedVisibility(
            visible = attachMenuOpen,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = (composerHeight - 4.dp).coerceAtLeast(0.dp)),
            enter = fadeIn(tween(120)) + scaleIn(TgMotion.fast(), initialScale = 0.86f, transformOrigin = TransformOrigin(0.1f, 1f)),
            exit = fadeOut(tween(110)) + scaleOut(tween(110), targetScale = 0.92f, transformOrigin = TransformOrigin(0.1f, 1f)),
        ) {
            AttachmentMenu(
                onGallery = {
                    leaveComposer()
                    openGallery()
                },
                onCamera = {
                    leaveComposer()
                    showQuickCapture = true
                },
                onScans = {
                    leaveComposer()
                    showScansPicker = true
                },
            )
        }
    }

    if (choiceSheetOpen) {
        state.deepChoice?.let { current ->
            DeepChoiceSheet(
                models = state.deepModels,
                choice = current,
                onSelect = vm::onSelectDeepChoice,
                onDismiss = { choiceSheetOpen = false },
            )
        }
    }

    pendingDeepAction?.let { action ->
        DeepPrivacyDialog(
            onAccept = {
                pendingDeepAction = null
                action(true)
            },
            onOpenPrivacy = { runCatching { uriHandler.openUri(com.example.personal_studio.data.remote.kejian.KejianApi.PRIVACY_URL) } },
            onDismiss = { pendingDeepAction = null },
        )
    }

    confirmRewind?.let { m ->
        val dropped = messagesToDiscard(state.visibleMessages, m.id, inclusive = false).size
        TgConfirmDialog(
            title = "回溯到这条消息？",
            message = "之后的 $dropped 条消息会被删除，然后重新回答这条消息。",
            confirmLabel = "回溯",
            onConfirm = {
                confirmRewind = null
                vm.onRewind(m.id)
                scope.launch { listState.scrollToItem(0) }
            },
            onDismiss = { confirmRewind = null },
        )
    }

    // Camera and scan-library takeovers end in the same crop overlay as the gallery.
    if (showQuickCapture) {
        QuickCaptureForChatScreen(
            onImagePicked = { path ->
                pickedForCrop = path
                showQuickCapture = false
            },
            onCancel = { showQuickCapture = false },
        )
    }
    if (showScansPicker) {
        ScanLibraryPickerScreen(
            onPagePicked = { path ->
                pickedForCrop = path
                showScansPicker = false
            },
            onCancel = { showScansPicker = false },
        )
    }
    val toCrop = pickedForCrop
    if (toCrop != null) {
        ImageCropOverlay(
            imagePath = toCrop,
            onDismiss = { pickedForCrop = null },
            onConfirm = { croppedPath ->
                pickedForCrop = null
                vm.onAttachImage(croppedPath)
            },
        )
    }
}

/** One row of the conversation list, newest first. */
sealed interface ChatRow {
    val key: Any
    val contentType: Any

    data class User(val message: ChatMessage) : ChatRow {
        override val key: Any get() = message.id
        override val contentType: Any get() = MessageRole.USER
    }

    /**
     * An answer: the one streaming ([message] null) or a saved one. [live] when it streamed on
     * this page (it is revealed smoothly and keeps the stream's [key] once saved).
     */
    data class Answer(
        override val key: Any,
        val text: String,
        val streaming: Boolean,
        val live: Boolean,
        val message: ChatMessage?,
    ) : ChatRow {
        override val contentType: Any get() = MessageRole.AI
    }

    /**
     * 深度对话一轮的过程卡，排在用户消息和回答之间。它的 [key] 跟着回答行走（"process-" + 回答行的键），
     * 所以进行中的卡片在回答保存后原地收成一行摘要，而不是换一张新卡。
     */
    data class Process(
        override val key: Any,
        val steps: List<DeepStep>,
        val live: Boolean,
        val startedAt: Long?,
        val note: String?,
    ) : ChatRow {
        override val contentType: Any get() = "process"
    }
}

/** The rows to show, newest first: the streaming answer (if any), then the conversation. */
internal fun chatRows(state: ChatDetailUiState): List<ChatRow> = buildList {
    val deep = state.deep
    state.streamingText?.let { text ->
        val key = state.streamRowKey(state.streamKey)
        add(ChatRow.Answer(key, text, streaming = state.isSending, live = true, message = null))
        if (deep != null) {
            val handover = deep.handover?.second
            when {
                // 进行中：有了第一步（或提示）才出现，之前回答行自己显示“思考中”。
                state.isSending && (deep.liveSteps.isNotEmpty() || deep.note != null) ->
                    add(ChatRow.Process("process-$key", deep.liveSteps, live = true, startedAt = deep.liveStartedAt, note = deep.note))
                // 刚结束、回答还在交接：先用这轮的步骤，卡片原地收起。
                !state.isSending && !handover.isNullOrEmpty() ->
                    add(ChatRow.Process("process-$key", handover, live = false, startedAt = null, note = null))
            }
        }
    }
    state.visibleMessages.asReversed().forEach { m ->
        when (m.role) {
            MessageRole.USER -> add(ChatRow.User(m))
            MessageRole.AI -> {
                val key = state.answerKey(m.id)
                add(ChatRow.Answer(key, m.contentMarkdown, streaming = false, live = m.id in state.answerKeys, message = m))
                deep?.stepsFor(m.id)?.takeIf { it.isNotEmpty() }?.let { steps ->
                    add(ChatRow.Process("process-$key", steps, live = false, startedAt = null, note = null))
                }
            }
            MessageRole.SYSTEM -> Unit
        }
    }
}

/**
 * The user's newest message changed after the conversation had loaded, i.e. they just sent
 * one. Not on first load ([seen] is still null) and not on rotation ([seen] is restored).
 */
internal fun sentNewMessage(seen: Long?, latest: Long?): Boolean =
    seen != null && latest != null && latest != seen

/** 模型 ID 换成名字：优先用服务器清单里的名字，否则按 ID 推断。 */
internal fun modelName(state: ChatDetailUiState, id: String?): String? =
    state.deepModels.firstOrNull { it.id == id }?.name ?: deepModelLabel(id)

/** Placeholder names (also the old "session #NNN") read as "新对话". */
internal fun displayTitle(title: String?): String =
    title?.takeUnless { com.example.personal_studio.domain.chat.ChatTitles.isPlaceholder(it) } ?: com.example.personal_studio.domain.chat.ChatTitles.NEW

/** "2.3 秒 · 约 410 tokens"; older rows without stats show nothing. */
internal fun formatDoneLine(message: ChatMessage): String? {
    val duration = message.generationMs ?: return null
    val tokens = message.tokenCount ?: return null
    val secs = duration / 1000.0
    val formatted = if (secs < 10) "%.1f".format(secs) else "%.0f".format(secs)
    return "$formatted 秒 · 约 $tokens tokens"
}
