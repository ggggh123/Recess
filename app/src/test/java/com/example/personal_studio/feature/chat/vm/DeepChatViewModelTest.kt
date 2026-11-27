package com.example.personal_studio.feature.chat.vm

import com.example.personal_studio.data.deep.DeepChoice
import com.example.personal_studio.data.deep.DeepConversation
import com.example.personal_studio.data.deep.DeepModelOption
import com.example.personal_studio.data.deep.DeepLive
import com.example.personal_studio.data.deep.DeepQuestion
import com.example.personal_studio.data.deep.DeepQuestionField
import com.example.personal_studio.data.deep.DeepQuestionOption
import com.example.personal_studio.data.deep.DeepSendResult
import com.example.personal_studio.data.deep.DeepStatus
import com.example.personal_studio.data.deep.effectiveChoice
import com.example.personal_studio.data.deep.DeepStep
import com.example.personal_studio.data.deep.DeepStepKind
import com.example.personal_studio.data.local.datastore.FakeUserPreferencesRepository
import com.example.personal_studio.data.remote.llm.FakeLLMProvider
import com.example.personal_studio.data.repository.FakeChatRepository
import com.example.personal_studio.domain.chat.GenerateTitleUseCase
import com.example.personal_studio.domain.chat.SendMessageUseCase
import com.example.personal_studio.domain.model.MessageRole
import com.example.personal_studio.feature.chat.ui.ChatRow
import com.example.personal_studio.feature.chat.ui.chatRows
import com.example.personal_studio.feature.chat.ui.deepModelLabel
import com.example.personal_studio.feature.chat.ui.deepSubtitle
import com.example.personal_studio.feature.chat.ui.formatElapsed
import com.example.personal_studio.feature.chat.ui.processSummary
import com.example.personal_studio.feature.chat.ui.questionAnswer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeepChatViewModelTest {

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val step = DeepStep("c1", DeepStepKind.RUN, "运行 Python", "python3 a.py", "115", running = false, success = true)

    private fun vm(repo: FakeChatRepository, sid: Long, deep: FakeDeepMode): ChatDetailViewModel {
        val llm = FakeLLMProvider(textChunks = listOf("不应调用"))
        return ChatDetailViewModel(
            sessionId = sid, repo = repo, send = SendMessageUseCase(repo, llm),
            titleGen = GenerateTitleUseCase(repo, llm), prefs = FakeUserPreferencesRepository(), deepMode = deep,
        )
    }

    private fun deepFor(sid: Long, status: DeepStatus = DeepStatus.Available("gpt-6.2-sol")) = FakeDeepMode(status).apply {
        conversations.value = mapOf(sid to DeepConversation(sid, running = false, expired = false, activity = null))
    }

    private fun live(userId: Long, text: String, finished: Boolean = false, saved: Long? = null, error: String? = null, steps: List<DeepStep> = listOf(step)) =
        DeepLive(userId, startedAt = 1000, text = text, steps = steps, note = null, question = null,
            finished = finished, savedAnswerId = saved, error = error)

    @Test fun `a running round streams like a normal answer and hands over to the saved answer`() = runTest {
        val repo = FakeChatRepository()
        val sid = repo.createSession("新对话")
        val deep = deepFor(sid)
        val vm = vm(repo, sid, deep)
        val userId = repo.appendMessage(sid, MessageRole.USER, "求 2 的 100 次方各位数字之和", emptyList())

        deep.liveFlow(sid).value = live(userId, "正在算")
        var st = vm.uiState.value
        assertEquals("正在算", st.streamingText)
        assertTrue(st.isSending)
        assertEquals(listOf(step), st.deep?.liveSteps)
        assertEquals(1000L, st.deep?.liveStartedAt)
        // 进行中：回答行之后紧跟过程卡（列表自下而上，过程卡显示在回答上方）。
        val rows = chatRows(st)
        assertTrue(rows[0] is ChatRow.Answer)
        assertTrue((rows[1] as ChatRow.Process).live)

        val answerId = repo.appendMessage(sid, MessageRole.AI, "115", emptyList(), modelUsed = "gpt-6.2-sol")
        deep.liveFlow(sid).value = live(userId, "115", finished = true, saved = answerId)
        advanceUntilIdle()
        st = vm.uiState.value
        assertFalse(st.isSending)
        assertTrue(answerId in st.answerKeys)
        assertEquals(listOf(sid), deep.acknowledged)
        // 步骤先用交接时留下的那份，过程卡收成摘要，不会闪一下。
        val process = chatRows(st).filterIsInstance<ChatRow.Process>().single()
        assertFalse(process.live)
        assertEquals(listOf(step), process.steps)
        // 标题取第一条提问，不调用问答模型。
        assertEquals("求 2 的 100 次方各位数字之和", repo.getSession(sid)?.title)
    }

    @Test fun `a failed round without an answer shows the reason and clears the stream`() = runTest {
        val repo = FakeChatRepository()
        val sid = repo.createSession("新对话")
        val deep = deepFor(sid)
        val vm = vm(repo, sid, deep)
        val userId = repo.appendMessage(sid, MessageRole.USER, "题目", emptyList())
        deep.liveFlow(sid).value = live(userId, "")
        deep.liveFlow(sid).value = live(userId, "", finished = true, error = "模型额度已用完")
        advanceUntilIdle()
        val st = vm.uiState.value
        assertNull(st.streamingText)
        assertFalse(st.isSending)
        assertEquals("模型额度已用完", st.errorBanner)
        assertEquals(listOf(sid), deep.acknowledged)
    }

    @Test fun `stopping before any text says so instead of leaving nothing`() = runTest {
        val repo = FakeChatRepository()
        val sid = repo.createSession("新对话")
        val deep = deepFor(sid)
        val vm = vm(repo, sid, deep)
        val userId = repo.appendMessage(sid, MessageRole.USER, "题目", emptyList())
        deep.liveFlow(sid).value = live(userId, "")
        deep.liveFlow(sid).value = live(userId, "", finished = true)
        advanceUntilIdle()
        assertEquals("已停止，这一轮没有生成回答", vm.uiState.value.errorBanner)
        assertNull(vm.uiState.value.streamingText)
    }

    @Test fun `sending goes to deep mode, and a failure puts the text back`() = runTest {
        val repo = FakeChatRepository()
        val sid = repo.createSession("新对话")
        val deep = deepFor(sid)
        deep.sendResult = { _, _, _ -> DeepSendResult.Failed("连接深度模式失败，请稍后重试", null) }
        val vm = vm(repo, sid, deep)
        vm.onInputChanged("用 Python 验算")
        vm.onAttachImage("/p/a.jpg")
        vm.onSend()
        advanceUntilIdle()
        assertEquals(listOf(Triple(sid, "用 Python 验算", listOf("/p/a.jpg"))), deep.sent)
        val st = vm.uiState.value
        assertEquals("连接深度模式失败，请稍后重试", st.errorBanner)
        assertEquals("用 Python 验算", vm.input.text.toString())
        assertEquals(listOf("/p/a.jpg"), st.attachedImagePaths)
        // 没有走普通问答的流式。
        assertNull(st.streamingText)
    }

    @Test fun `deep conversations cannot be edited or rewound, and stop goes to the server`() = runTest {
        val repo = FakeChatRepository()
        val sid = repo.createSession("新对话")
        val deep = deepFor(sid)
        val vm = vm(repo, sid, deep)
        val userId = repo.appendMessage(sid, MessageRole.USER, "题目", emptyList())
        vm.onStartEdit(userId)
        assertNull(vm.uiState.value.editingMessageId)
        vm.onRewind(userId)
        assertNull(vm.uiState.value.streamingText)
        deep.liveFlow(sid).value = live(userId, "算到一半")
        vm.onStop()
        advanceUntilIdle()
        assertEquals(listOf(sid), deep.stopped)
    }

    @Test fun `expired conversations do not send`() = runTest {
        val repo = FakeChatRepository()
        val sid = repo.createSession("新对话")
        val deep = deepFor(sid)
        deep.conversations.value = mapOf(sid to DeepConversation(sid, running = false, expired = true, activity = null))
        val vm = vm(repo, sid, deep)
        assertTrue(vm.uiState.value.deep?.expired == true)
        vm.onInputChanged("再问一句")
        vm.onSend()
        advanceUntilIdle()
        assertTrue(deep.sent.isEmpty())
    }

    @Test fun `verify starts a deep conversation with the question, its images and the answer`() = runTest {
        val repo = FakeChatRepository()
        val sid = repo.createSession("线代")
        val deep = FakeDeepMode(DeepStatus.Available("gpt-6.2-sol")).apply { createLocal = { repo.createSession("新对话") } }
        val vm = vm(repo, sid, deep)
        assertTrue(vm.uiState.value.deepAvailable)
        repo.appendMessage(sid, MessageRole.USER, "求矩阵的特征值", listOf("/p/q.jpg"))
        val aiId = repo.appendMessage(sid, MessageRole.AI, "特征值是 1 和 3", emptyList())
        advanceUntilIdle()
        var opened: Long? = null
        vm.onVerify(aiId, consent = true) { opened = it }
        advanceUntilIdle()
        assertTrue(deep.accepted.first())
        val (target, text, images) = deep.sent.single()
        assertEquals(opened, target)
        assertTrue(text.contains("【题目】\n求矩阵的特征值"))
        assertTrue(text.contains("【待验算的解答】\n特征值是 1 和 3"))
        assertEquals(listOf("/p/q.jpg"), images)
        assertEquals("验算：求矩阵的特征值", repo.getSession(target!!)?.title)
    }

    @Test fun `saved answers show their steps after the answer row`() {
        val st = ChatDetailUiState(
            messages = listOf(
                com.example.personal_studio.domain.model.ChatMessage(id = 1, sessionId = 9, role = MessageRole.USER, contentMarkdown = "q", createdAt = 0),
                com.example.personal_studio.domain.model.ChatMessage(
                    id = 2, sessionId = 9, role = MessageRole.AI, contentMarkdown = "a", createdAt = 1,
                    generationMs = 10, tokenCount = 1, modelUsed = "gpt-6.2-sol",
                ),
            ),
            deep = DeepChatState(stepsByAnswer = mapOf(2L to listOf(step))),
        )
        val rows = chatRows(st)
        assertTrue(rows[0] is ChatRow.Answer)
        assertEquals("process-2", (rows[1] as ChatRow.Process).key)
        assertTrue(rows[2] is ChatRow.User)
    }

    @Test fun `the model choice follows the server list and goes out with the message`() = runTest {
        val repo = FakeChatRepository()
        val sid = repo.createSession("新对话")
        val models = listOf(
            DeepModelOption("gpt-6.2-sol", "GPT-6.2 Sol", "均衡", listOf("low", "medium", "high"), isDefault = true),
            DeepModelOption("gpt-6-luna", "GPT-6 Luna", "最快", listOf("low", "medium", "high"), isDefault = false),
        )
        val deep = deepFor(sid, DeepStatus.Available("gpt-6.2-sol", models, "medium"))
        val vm = vm(repo, sid, deep)
        // 没选过：默认模型、默认推理等级。
        assertEquals(DeepChoice("gpt-6.2-sol", "medium"), vm.uiState.value.deepChoice)
        vm.onSelectDeepChoice(DeepChoice("gpt-6-luna", "high"))
        advanceUntilIdle()
        assertEquals(DeepChoice("gpt-6-luna", "high"), deep.savedChoice.value)
        vm.onInputChanged("算一下")
        vm.onSend()
        advanceUntilIdle()
        assertEquals(DeepChoice("gpt-6-luna", "high"), deep.sentChoices.single())
        // 选过的模型不再开放时退回默认。
        deep.status.value = DeepStatus.Available("gpt-6.2-sol", models.take(1), "medium")
        assertEquals(DeepChoice("gpt-6.2-sol", "high"), vm.uiState.value.deepChoice)
        assertEquals("GPT-6 Luna · 推理 高", com.example.personal_studio.feature.chat.ui.choiceLabel(models, DeepChoice("gpt-6-luna", "high")))
    }

    @Test fun `provider ids, per-model default effort and choices saved by older versions`() {
        val models = listOf(
            DeepModelOption("anthropic/claude-opus-5-5", "Claude Opus 5.5", "", listOf("high", "max"), isDefault = true, defaultEffort = "max"),
            DeepModelOption("openai/gpt-6.2-sol", "GPT-6.2 Sol", "均衡", listOf("low", "medium", "high"), isDefault = false, defaultEffort = "medium"),
            DeepModelOption("anthropic/claude-plain", "Claude", "", emptyList(), isDefault = false),
        )
        val status = DeepStatus.Available("anthropic/claude-opus-5-5", models, "max")
        assertEquals(DeepChoice("anthropic/claude-opus-5-5", "max"), status.effectiveChoice(null))
        // 旧版本存的是不带提供方的 ID；选过的档位这个模型不开放时用它自己的默认档
        assertEquals(DeepChoice("openai/gpt-6.2-sol", "medium"), status.effectiveChoice(DeepChoice("gpt-6.2-sol", "max")))
        assertEquals(DeepChoice("openai/gpt-6.2-sol", "low"), status.effectiveChoice(DeepChoice("openai/gpt-6.2-sol", "low")))
        assertEquals(DeepChoice("anthropic/claude-plain", null), status.effectiveChoice(DeepChoice("anthropic/claude-plain", "high")))
        assertEquals("GPT-6.2 Sol · 推理 中", com.example.personal_studio.feature.chat.ui.choiceLabel(models, DeepChoice("gpt-6.2-sol", "medium")))
        assertEquals("Claude", com.example.personal_studio.feature.chat.ui.choiceLabel(models, DeepChoice("anthropic/claude-plain", null)))
    }

    @Test fun `the picker groups models by series in server order, status says only that it works`() {
        val opt = { id: String -> DeepModelOption(id, id, "", emptyList(), isDefault = false) }
        val groups = com.example.personal_studio.feature.chat.ui.deepModelGroups(
            listOf(opt("anthropic/claude-opus-5-5"), opt("anthropic/claude-haiku-5-5"), opt("openai/gpt-6.2-sol"), opt("openai/gpt-6-luna")),
        )
        assertEquals(listOf("Claude 系列", "GPT 系列"), groups.map { it.first })
        assertEquals(listOf("anthropic/claude-opus-5-5", "anthropic/claude-haiku-5-5"), groups[0].second.map { it.id })
        // 只有一个系列、或是旧服务器不带提供方的 ID：不分组
        assertEquals(listOf<String?>(null), com.example.personal_studio.feature.chat.ui.deepModelGroups(listOf(opt("openai/gpt-6.2-sol"))).map { it.first })
        assertEquals(listOf<String?>(null), com.example.personal_studio.feature.chat.ui.deepModelGroups(listOf(opt("gpt-6.2-sol"), opt("gpt-6-luna"))).map { it.first })
        assertEquals("deepseek", com.example.personal_studio.feature.chat.ui.deepSeriesLabel("deepseek/v4"))
        val status = DeepStatus.Available("openai/gpt-6.2-sol", listOf(opt("openai/gpt-6.2-sol").copy(isDefault = true)), "medium")
        assertEquals("可以使用", com.example.personal_studio.feature.settings.vm.statusLine(status))
    }

    @Test fun `labels, summaries and answers`() {
        assertEquals("GPT-6.2 Sol", deepModelLabel("openai/gpt-6.2-sol"))
        assertEquals("Claude Opus 5.5", deepModelLabel("anthropic/claude-opus-5-5"))
        assertEquals("Claude Opus 5.5 Fast", deepModelLabel("anthropic/claude-opus-5-5-fast"))
        assertEquals("Claude Haiku 4.5", deepModelLabel("anthropic/claude-haiku-4-5-20251001"))
        assertEquals("some-model", deepModelLabel("other/some-model"))
        assertEquals("GPT-6.2 Sol", deepModelLabel("gpt-6.2-sol"))
        assertEquals("GPT-6.2 Astra Fast", deepModelLabel("gpt-6.2-astra-fast"))
        assertEquals("deepseek-flash", deepModelLabel("deepseek-flash"))
        assertNull(deepModelLabel(null))
        assertEquals("深度模式", deepSubtitle(null))
        assertEquals("深度模式 · GPT-6.2 Sol", deepSubtitle("gpt-6.2-sol"))
        assertEquals("0:07", formatElapsed(7_400))
        assertEquals("12:05", formatElapsed(725_000))

        val search = DeepStep("s", DeepStepKind.SEARCH, "搜索“洛必达”", null, null, false, true)
        val cmd = DeepStep("r2", DeepStepKind.RUN, "运行命令", "ls", null, false, true)
        assertEquals("过程", processSummary(emptyList()))
        assertEquals("过程 · 运行 Python 2 次 · 搜索 1 次 · 运行命令 1 次", processSummary(listOf(step, search, step, cmd)))

        val q = DeepQuestion("frm_1", listOf(
            DeepQuestionField("unit", "用哪种单位？", null, listOf(DeepQuestionOption("si", "国际单位", null)), multiple = false, allowCustom = true, required = true),
            DeepQuestionField("tags", null, null, listOf(DeepQuestionOption("a", "A", null), DeepQuestionOption("b", "B", null)), multiple = true, allowCustom = false, required = false),
        ))
        assertEquals(mapOf("unit" to listOf("si")), questionAnswer(q, mapOf("unit" to listOf("si")), emptyMap()))
        assertEquals(mapOf("unit" to listOf("厘米")), questionAnswer(q, mapOf("unit" to listOf("si")), mapOf("unit" to " 厘米 ")))
        assertEquals(mapOf("tags" to listOf("a", "b")), questionAnswer(q, mapOf("tags" to listOf("a", "b")), emptyMap()))

        assertEquals("验算：拍的题", verifyTitle(""))
        assertEquals("拍题 · 深度", deepTitle("", hasImages = true))
        // 最多 18 个字，超出的加省略号。
        val long = deepTitle("求极限 lim x→0 sin(x)/x 的值，并写出过程", false)
        assertEquals(19, long.length)
        assertTrue(long.endsWith("…"))
        assertEquals("短题", deepTitle("短题\n第二行", false))
        assertTrue(verifyPrompt("", hasImages = true, answer = "答").contains("（见图片）"))
    }
}
