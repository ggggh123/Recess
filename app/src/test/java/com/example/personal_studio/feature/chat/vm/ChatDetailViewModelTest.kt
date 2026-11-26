package com.example.personal_studio.feature.chat.vm

import app.cash.turbine.test
import com.example.personal_studio.data.local.datastore.FakeUserPreferencesRepository
import com.example.personal_studio.data.remote.llm.FakeLLMProvider
import com.example.personal_studio.data.repository.FakeChatRepository
import com.example.personal_studio.domain.chat.GenerateTitleUseCase
import com.example.personal_studio.domain.chat.SendMessageUseCase
import com.example.personal_studio.domain.model.MessageRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatDetailViewModelTest {

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `sending a message produces streaming then persisted messages`() = runTest {
        val repo = FakeChatRepository()
        val sid = repo.createSession("test")
        val llm = FakeLLMProvider(textChunks = listOf("hel", "lo world"))
        val vm = ChatDetailViewModel(
            sessionId = sid,
            repo = repo,
            send = SendMessageUseCase(repo, llm),
            titleGen = GenerateTitleUseCase(repo, llm),
            prefs = FakeUserPreferencesRepository(),
            deepMode = FakeDeepMode(),
        )
        vm.uiState.test {
            awaitItem()  // initial empty

            vm.onInputChanged("hi")
            vm.onSend()

            var sawStreaming = false
            var sawPersisted = false
            while (!sawPersisted) {
                val s = awaitItem()
                if (s.streamingText != null) sawStreaming = true
                if (s.streamingText == null && s.messages.any {
                    it.role == com.example.personal_studio.domain.model.MessageRole.AI
                }) {
                    sawPersisted = true
                }
            }
            assertTrue(sawStreaming)

            val finalState = expectMostRecentItem()
            assertEquals(2, finalState.messages.size)
            assertEquals("hello world", finalState.messages.last().contentMarkdown)
            assertNull(finalState.streamingText)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `a new conversation is titled after the first answer`() = runTest {
        val repo = FakeChatRepository()
        val sid = repo.createSession(com.example.personal_studio.domain.chat.ChatTitles.NEW)
        val llm = FakeLLMProvider(textChunks = listOf("极限的定义"))
        val vm = ChatDetailViewModel(
            sessionId = sid,
            repo = repo,
            send = SendMessageUseCase(repo, llm),
            titleGen = GenerateTitleUseCase(repo, llm),
            prefs = FakeUserPreferencesRepository(),
            deepMode = FakeDeepMode(),
        )
        vm.uiState.test {
            awaitItem()
            vm.onInputChanged("什么是极限")
            vm.onSend()
            var s = awaitItem()
            while (s.sendGeneration == 0) s = awaitItem()
            assertEquals("极限的定义", s.session?.title)
            assertEquals("极限的定义", repo.getSession(sid)?.title)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun vmWith(
        repo: FakeChatRepository,
        sid: Long,
        llm: com.example.personal_studio.data.remote.llm.LLMProvider,
        prefs: FakeUserPreferencesRepository = FakeUserPreferencesRepository(),
    ) =
        ChatDetailViewModel(
            sessionId = sid,
            repo = repo,
            send = SendMessageUseCase(repo, llm),
            titleGen = GenerateTitleUseCase(repo, llm),
            prefs = prefs,
            deepMode = FakeDeepMode(),
        )

    @Test fun `the user's own system prompt is sent, the built-in one otherwise`() = runTest {
        val repo = FakeChatRepository()
        val sid = repo.createSession("t")
        val llm = com.example.personal_studio.data.remote.llm.FakeLLMProvider(listOf("答"))
        val prefs = FakeUserPreferencesRepository()
        val vm = vmWith(repo, sid, llm, prefs)

        vm.onInputChanged("第一问")
        vm.onSend()
        vm.uiState.first { !it.isSending && it.streamingText == null }
        assertEquals(com.example.personal_studio.domain.chat.ChatPrompts.DEFAULT, llm.lastMessages.first().text)

        prefs.setChatSystemPrompt("只用英文回答。")
        vm.onInputChanged("第二问")
        vm.onSend()
        vm.uiState.first { !it.isSending && it.streamingText == null && it.messages.size == 4 }
        assertEquals(com.example.personal_studio.data.remote.llm.LlmRole.SYSTEM, llm.lastMessages.first().role)
        assertEquals("只用英文回答。", llm.lastMessages.first().text)

        // 回溯 uses it too
        vm.onRewind(repo.listMessages(sid).first().id)
        vm.uiState.first { !it.isSending && it.streamingText == null && it.messages.size == 2 }
        assertEquals("只用英文回答。", llm.lastMessages.first().text)
    }

    private suspend fun FakeChatRepository.seedTwoTurns(sid: Long) {
        appendMessage(sid, MessageRole.USER, "第一问", attachedImagePaths = listOf("/tmp/a.jpg"))
        appendMessage(sid, MessageRole.AI, "第一答")
        appendMessage(sid, MessageRole.USER, "第二问")
        appendMessage(sid, MessageRole.AI, "第二答")
    }

    @Test fun `editing a message replaces it and everything after, then answers again`() = runTest {
        val repo = FakeChatRepository()
        val sid = repo.createSession("x")
        repo.seedTwoTurns(sid)
        val vm = vmWith(repo, sid, FakeLLMProvider(textChunks = listOf("新答")))
        val first = repo.listMessages(sid).first()

        vm.onInputChanged("草稿")
        vm.onStartEdit(first.id)
        assertEquals("第一问", vm.input.text.toString())
        assertEquals(first.id, vm.uiState.value.editingMessageId)
        assertEquals(listOf("/tmp/a.jpg"), vm.uiState.value.attachedImagePaths)

        vm.onInputChanged("第一问（改）")
        vm.onSend()

        val messages = repo.listMessages(sid)
        assertEquals(listOf("第一问（改）", "新答"), messages.map { it.contentMarkdown })
        assertEquals(listOf("/tmp/a.jpg"), messages[0].attachedImagePaths)
        val s = vm.uiState.value
        assertNull(s.editingMessageId)
        assertNull(s.streamingText)
        assertEquals("", vm.input.text.toString()) // the draft is not restored after sending
    }

    @Test fun `cancelling an edit brings the draft back`() = runTest {
        val repo = FakeChatRepository()
        val sid = repo.createSession("x")
        repo.seedTwoTurns(sid)
        val vm = vmWith(repo, sid, FakeLLMProvider())
        vm.onInputChanged("还没发的草稿")

        vm.onStartEdit(repo.listMessages(sid)[2].id)
        assertEquals("第二问", vm.input.text.toString())
        vm.onCancelEdit()

        assertEquals("还没发的草稿", vm.input.text.toString())
        assertNull(vm.uiState.value.editingMessageId)
        assertTrue(vm.uiState.value.attachedImagePaths.isEmpty())
        assertEquals(4, repo.listMessages(sid).size) // nothing deleted
    }

    @Test fun `rewinding drops the later turns and answers the message again`() = runTest {
        val repo = FakeChatRepository()
        val sid = repo.createSession("x")
        repo.seedTwoTurns(sid)
        val llm = FakeLLMProvider(textChunks = listOf("重新", "回答"))
        val vm = vmWith(repo, sid, llm)
        val firstQuestion = repo.listMessages(sid).first()

        vm.onRewind(firstQuestion.id)

        val messages = repo.listMessages(sid)
        assertEquals(listOf("第一问", "重新回答"), messages.map { it.contentMarkdown })
        assertEquals(firstQuestion.id, messages[0].id) // the message itself is kept
        // The model was asked with the history up to that message only.
        assertEquals(
            listOf("第一问"),
            llm.lastMessages.filter { it.role == com.example.personal_studio.data.remote.llm.LlmRole.USER }.map { it.text },
        )
        val s = vm.uiState.value
        assertNull(s.streamingText)
        assertTrue(messages[1].id in s.answerKeys) // the new answer keeps its stream row
    }

    @Test fun `stopping keeps the partial answer on screen until it is saved`() = runTest {
        val repo = FakeChatRepository()
        val sid = repo.createSession("x")
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val llm = object : com.example.personal_studio.data.remote.llm.LLMProvider {
            override val name = "slow"
            override fun generate(
                messages: List<com.example.personal_studio.data.remote.llm.LlmMessage>,
                temperature: Float,
            ) = kotlinx.coroutines.flow.flow {
                emit(com.example.personal_studio.data.remote.llm.LlmChunk.Text("一半"))
                gate.await()
            }
            override suspend fun generateStructured(
                messages: List<com.example.personal_studio.data.remote.llm.LlmMessage>,
                jsonSchema: String,
                temperature: Float,
            ): String = error("not used")
        }
        val vm = vmWith(repo, sid, llm)
        vm.onInputChanged("问")
        vm.onSend()
        assertEquals("一半", vm.uiState.value.streamingText)

        vm.onStop()

        val saved = repo.listMessages(sid).last()
        assertEquals("一半\n\n（已停止）", saved.contentMarkdown)
        val s = vm.uiState.value
        assertTrue(!s.isSending)
        assertNull(s.streamingText)
        assertTrue(saved.id in s.answerKeys)
    }

    @Test fun `error during streaming surfaces as errorBanner`() = runTest {
        val repo = FakeChatRepository()
        val sid = repo.createSession("x")
        val llm = object : com.example.personal_studio.data.remote.llm.LLMProvider {
            override val name = "fake-err"
            override fun generate(
                messages: List<com.example.personal_studio.data.remote.llm.LlmMessage>,
                temperature: Float,
            ) = kotlinx.coroutines.flow.flow {
                emit(com.example.personal_studio.data.remote.llm.LlmChunk.Error("boom", true))
            }
            override suspend fun generateStructured(
                messages: List<com.example.personal_studio.data.remote.llm.LlmMessage>,
                jsonSchema: String,
                temperature: Float,
            ): String = error("test fake — not used")
        }
        val vm = ChatDetailViewModel(
            sessionId = sid,
            repo = repo,
            send = SendMessageUseCase(repo, llm),
            titleGen = GenerateTitleUseCase(repo, llm),
            prefs = FakeUserPreferencesRepository(),
            deepMode = FakeDeepMode(),
        )
        vm.uiState.test {
            awaitItem()
            vm.onInputChanged("hi")
            vm.onSend()
            var banner: String? = null
            while (banner == null) {
                banner = awaitItem().errorBanner
            }
            assertEquals("boom", banner)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
