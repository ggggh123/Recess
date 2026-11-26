package com.example.personal_studio.data.deep

import com.example.personal_studio.data.repository.FakeChatRepository
import com.example.personal_studio.domain.chat.estimateTokens
import com.example.personal_studio.domain.model.MessageRole
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

private class MemoryDao : DeepDao {
    private val rows = MutableStateFlow<Map<Long, DeepSession>>(emptyMap())
    private val history = MutableStateFlow<List<StoredStep>>(emptyList())
    private val converters = DeepConverters()
    override suspend fun saveHeader(row: DeepSession) {
        val header = row.copy(round = converters.round(converters.roundJson(row.round)),
            draft = converters.draft(converters.draftJson(row.draft)))
        rows.update { it + (row.chatId to header) }
    }
    override suspend fun header(id: Long) = rows.value[id]
    override suspend fun ids() = rows.value.keys.toList()
    override suspend fun badges() = rows.value.values.map { SessionBadge(it.chatId, it.phase, it.activity) }
    override fun steps(id: Long) = history.map { all -> all.filter { it.chatId == id && it.answerId > 0 } }
    override suspend fun roundSteps(id: Long, answer: Long) =
        history.value.filter { it.chatId == id && it.answerId == answer }.sortedBy { it.position }
    override suspend fun saveSteps(steps: List<StoredStep>) {
        history.update { all -> all.filterNot { old -> steps.any {
            it.chatId == old.chatId && it.position == old.position && it.answerId == old.answerId
        } } + steps }
    }
    override suspend fun trimDraftSteps(id: Long, size: Int) {
        history.update { all -> all.filterNot { it.chatId == id && it.answerId == 0L && it.position >= size } }
    }
    override suspend fun linkSteps(id: Long, answer: Long) {
        history.update { all -> all.map { if (it.chatId == id && it.answerId == 0L) it.copy(answerId = answer) else it } }
    }
    override suspend fun remove(id: Long) {
        rows.update { it - id }
        history.update { list -> list.filterNot { it.chatId == id } }
    }
}

class DeepModeClientTest {
    private val server = MockWebServer()
    private val dao = MemoryDao()
    private val chat = FakeChatRepository()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val prefs = mockk<DeepPrefs>()
    private lateinit var gateway: DeepGateway
    private lateinit var mode: DeepModeImpl
    private var scope = newScope()
    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val key = "A".repeat(43)

    @Before fun setup() {
        server.start()
        every { prefs.privacyAccepted } returns flowOf(true)
        gateway = DeepGateway(server.url("/api/v1/agent/"), { key },
            OkHttpClient.Builder().retryOnConnectionFailure(false).build())
        mode = DeepModeImpl(dao, chat, gateway, prefs, scope)
    }

    @After fun teardown() {
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        server.shutdown()
    }

    private fun route(block: (String, RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.requestUrl!!.encodedPath.removePrefix("/api/v1/agent/")
                return when {
                    path == "status" -> reply("""{"available":true,"model":"server-model"}""")
                    path == "sessions" && request.method == "POST" -> reply("""{"id":"remote"}""", 201)
                    else -> block(path, request)
                }
            }
        }
    }

    private fun reply(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body)
    private fun sse(vararg events: AgentEvent) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody(events.joinToString("") { "id: ${it.seq}\nevent: ${it.type}\ndata: ${it.data}\n\n" })
    private fun end(seq: Long) = event(seq, "turn.end", """{"status":"completed"}""")
    private fun missing() = reply("""{"code":"session_not_found","message":"会话不存在"}""", 404)
    private suspend fun finished(id: Long) = withTimeout(10_000) { mode.live(id).filterNotNull().first { it.finished } }
    private suspend fun restart() {
        scope.coroutineContext.job.cancelAndJoin()
        scope = newScope()
        mode = DeepModeImpl(dao, chat, gateway, prefs, scope)
    }

    @Test fun requestHeadersImagePayloadAnswersAndDelete202() = runBlocking {
        route { path, _ -> when (path) {
            "sessions/remote/messages" -> reply("""{"turn":"t","replay":true}""", 202)
            "sessions" -> reply("""{"sessions":[{"id":"remote"}]}""")
            "sessions/remote" -> reply("""{"ok":true,"pending":true}""", 202)
            else -> reply("{}")
        } }
        assertEquals(DeepStatus.Available("server-model"), gateway.status())
        assertEquals("remote", gateway.create())
        assertEquals("t", gateway.send("remote", 42, "题", listOf("aW1n")))
        val q = Json.parseToJsonElement("""{"formID":"f","fields":[{"type":"string","key":"a"},
            {"type":"multiselect","key":"b","options":[]}]}""").jsonObject.toQuestion()
        gateway.answer("remote", q, mapOf("a" to listOf("一"), "b" to listOf("二")))
        gateway.stop("remote")
        assertEquals(listOf("remote"), gateway.sessions())
        gateway.delete("remote")
        requests.drop(1).forEach {
            assertEquals("Bearer $key", it.getHeader("Authorization"))
            assertEquals(if (it.method == "GET") null else "1", it.getHeader("X-Kejian"))
        }
        assertEquals(Json.parseToJsonElement("""{"clientMessageId":"chat-42","text":"题","images":[{"mime":"image/jpeg","data":"aW1n"}]}"""),
            Json.parseToJsonElement(requests[2].body.readUtf8()))
        assertEquals(Json.parseToJsonElement("""{"formID":"f","answer":{"a":"一","b":["二"]}}"""),
            Json.parseToJsonElement(requests[3].body.readUtf8()))
    }

    @Test fun disconnectResumesAfterCommittedEventAndSavesOneAnswer() = runBlocking {
        val streams = AtomicInteger()
        val delta = event(2, "text.delta", """{"messageID":"m","ordinal":0,"text":"好"}""")
        route { path, _ -> when {
            path.endsWith("messages") -> reply("""{"turn":"t","replay":false}""", 202)
            streams.incrementAndGet() == 1 -> sse(event(1,"turn.start"), delta)
            else -> sse(delta, event(3,"tool.start","""{"name":"shell","call":"c"}"""),
                event(4,"tool.end","""{"call":"c","success":true}"""), end(5))
        } }
        val id = mode.createConversation()
        assertEquals(0, requests.size)
        val result = mode.send(id, "问题", emptyList()) as DeepSendResult.Sent
        repeat(5) { mode.resume(id) }
        val done = finished(id)
        assertEquals(result.userMessageId, done.userMessageId)
        val pair = chat.listMessages(id)
        assertEquals(2, pair.size)
        val answer = pair.last()
        assertEquals("好", answer.contentMarkdown)
        assertEquals("server-model", answer.modelUsed)
        assertEquals(estimateTokens("好"), answer.tokenCount)
        assertTrue(answer.generationMs!! >= 0)
        assertEquals(answer.id, done.savedAnswerId)
        assertEquals(true, mode.steps(id).first()[answer.id]!!.single().success)
        val connects = requests.filter { it.path!!.contains("/events") }
        assertEquals(listOf("0","2"), connects.map { it.getHeader("Last-Event-ID") })
        assertEquals(listOf("0","2"), connects.map { it.requestUrl!!.queryParameter("after") })
        mode.acknowledge(id)
        withTimeout(2500) { mode.live(id).first { it == null } }
        assertEquals(5L, dao.get(id)!!.lastSeq)
    }

    @Test fun uncertainPostReusesUserMessageAndRemoteSessionAfterRestart() = runBlocking {
        val posts = AtomicInteger()
        route { path, _ -> when {
            path.endsWith("events") -> sse(end(1))
            posts.incrementAndGet() == 1 -> MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
            else -> reply("""{"turn":"t","replay":true}""", 202)
        } }
        val id = mode.createConversation()
        val failed = mode.send(id, "原消息", emptyList()) as DeepSendResult.Failed
        assertEquals("remote", dao.get(id)!!.remoteId)
        assertEquals(Phase.POSTING, dao.get(id)!!.phase)
        restart()
        assertEquals(DeepSendResult.Sent(failed.userMessageId!!), mode.send(id, "原消息", emptyList()))
        finished(id)
        assertEquals(1, requests.count { it.path!!.endsWith("/sessions") && it.method == "POST" })
        val bodies = requests.filter { it.path!!.endsWith("/messages") }.map { it.body.readUtf8() }
        assertEquals(2, bodies.size)
        assertEquals(bodies.first(), bodies.last())
        assertEquals(1, chat.listMessages(id).count { it.role == MessageRole.USER })
    }

    @Test fun turnRunningAttachesToExistingStream() = runBlocking {
        route { path, _ ->
            if (path.endsWith("messages")) reply("""{"code":"turn_running","message":"正在运行"}""", 409)
            else sse(event(1,"turn.start"), end(2))
        }
        val id = mode.createConversation()
        val result = mode.send(id, "问题", emptyList()) as DeepSendResult.Failed
        assertNotNull(result.userMessageId)
        assertTrue(finished(id).finished)
        assertEquals(1, requests.count { it.path!!.contains("/events") })
    }

    @Test fun repeatedDisconnectsPauseUntilResumeWithoutLosingTheCursor() = runBlocking {
        val streams = AtomicInteger()
        route { path, _ -> when {
            path.endsWith("messages") -> reply("""{"turn":"t"}""", 202)
            streams.incrementAndGet() == 1 -> sse(event(1, "turn.start"))
            streams.get() <= 5 -> sse()
            else -> sse(end(2))
        } }
        val id = mode.createConversation()
        mode.send(id, "题", emptyList())
        withTimeout(25_000) { mode.live(id).first { it?.note?.contains("重新打开") == true } }
        assertEquals(5, streams.get())
        assertTrue(mode.conversations.value.getValue(id).running)
        mode.resume(id)
        finished(id)
        assertEquals(6, streams.get())
        assertEquals("1", requests.last().getHeader("Last-Event-ID"))
    }

    @Test fun abortWaitsForTurnEndAndSavesPartialText() = runBlocking {
        val stopped = java.util.concurrent.atomic.AtomicBoolean()
        route { path, _ -> when {
            path.endsWith("messages") -> reply("""{"turn":"t"}""", 202)
            path.endsWith("abort") -> { stopped.set(true); reply("{}") }
            stopped.get() -> sse(event(2, "turn.end", """{"status":"aborted"}"""))
            else -> sse(event(1, "text.delta", """{"messageID":"m","ordinal":0,"text":"部分"}"""))
        } }
        val id = mode.createConversation()
        mode.send(id, "请回答", emptyList())
        withTimeout(5000) { mode.live(id).first { it?.text == "部分" } }
        mode.stop(id)
        assertNotNull(finished(id).savedAnswerId)
        assertEquals("部分\n\n（已停止）", chat.listMessages(id).last().contentMarkdown)
    }

    @Test fun failedTurnWithoutTextFinishesWithoutInsertingAnAnswer() = runBlocking {
        route { path, _ -> if (path.endsWith("messages")) reply("""{"turn":"t"}""",202)
            else sse(event(1,"error","""{"message":"模型响应中断"}"""), event(2,"turn.end","""{"status":"failed"}""")) }
        val id = mode.createConversation()
        mode.send(id, "题", emptyList())
        val done = finished(id)
        assertNull(done.savedAnswerId)
        assertEquals("模型响应中断", done.error)
        assertEquals(listOf(MessageRole.USER), chat.listMessages(id).map { it.role })
    }

    @Test fun missingSessionDuringSendAndStreamBecomesReadOnly() = runBlocking {
        for (onSend in listOf(true, false)) {
            route { path, _ ->
                if (path.endsWith("messages") && !onSend) reply("""{"turn":"t"}""", 202) else missing()
            }
            val id = mode.createConversation()
            mode.send(id, "问题", emptyList())
            assertEquals(EXPIRED_MESSAGE, finished(id).error)
            assertEquals(Phase.EXPIRED, dao.get(id)!!.phase)
            assertTrue(mode.conversations.value.getValue(id).expired)
            assertEquals(DeepSendResult.Failed(EXPIRED_MESSAGE, null), mode.send(id, "重发", emptyList()))
        }
    }

    @Test fun resumeRestoresRunningSnapshotAndAnswersOnlyCurrentQuestion() = runBlocking {
        val id = chat.createSession("题")
        val user = chat.appendMessage(id, MessageRole.USER, "题")
        val question = """{"formID":"f","fields":[{"key":"a","type":"string"}]}"""
        var row = running(id, user).reduce(event(1,"text.delta","""{"messageID":"m","ordinal":0,"text":"已收到"}"""),2000)
        row = row.reduce(event(2,"question",question),2001)
        dao.save(row)
        val answers = AtomicInteger()
        route { path, _ -> when {
            path.endsWith("answer") && answers.incrementAndGet() == 1 -> reply("""{"code":"invalid_answer","message":"答案无效"}""",404)
            path.endsWith("answer") -> reply("{}")
            else -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
        } }
        restart()
        mode.reconcile()
        assertEquals("已收到", mode.live(id).value!!.text)
        mode.resume(id)
        assertFalse(mode.answer(id, "old", emptyMap()))
        assertFalse(mode.answer(id, "f", mapOf("a" to listOf("一"))))
        assertEquals("f", mode.live(id).value!!.question!!.formId)
        assertTrue(mode.answer(id, "f", mapOf("a" to listOf("二"))))
        assertNull(mode.live(id).value!!.question)
        assertNull(dao.get(id)!!.round!!.question)
        withTimeout(2500) { while (requests.none { it.path!!.contains("/events") }) delay(10) }
        assertEquals("2", requests.single { it.path!!.contains("/events") }.getHeader("Last-Event-ID"))
    }

    @Test fun finishingCheckpointRepairsCrossDatabaseCommitWithoutDuplicateAnswer() = runBlocking {
        val id = chat.createSession("题")
        val user = chat.appendMessage(id, MessageRole.USER, "问")
        val row = running(id,user)
            .reduce(event(1,"tool.start","""{"call":"c","name":"read"}"""),1200)
            .reduce(event(2,"text.end","""{"messageID":"m","ordinal":0,"text":"结果"}"""),1400)
            .reduce(end(3),1500)
        dao.save(row)
        val answer = chat.appendMessage(id, MessageRole.AI, "结果", generationMs = 500, modelUsed = "model")
        restart()
        mode.reconcile()
        assertEquals(answer, finished(id).savedAnswerId)
        assertEquals(2, chat.listMessages(id).size)
        assertEquals("读取文件", mode.steps(id).first().getValue(answer).single().title)
        assertEquals(0, requests.size)
    }

    @Test fun reconcileRetainsFailedDeletesAndCascadesOnRetry() = runBlocking {
        val local = mode.createConversation()
        val remote = mode.createConversation()
        dao.save(dao.get(remote)!!.copy(remoteId = "remote"))
        chat.deleteSession(local)
        chat.deleteSession(remote)
        val attempts = AtomicInteger()
        route { _, _ -> if (attempts.incrementAndGet() == 1) reply("{}", 503) else reply("""{"ok":true,"pending":true}""",202) }
        mode.reconcile()
        assertNull(dao.get(local))
        assertEquals(Phase.DELETING, dao.get(remote)!!.phase)
        mode.reconcile()
        assertNull(dao.get(remote))
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.method == "DELETE" })
    }

    @Test fun deleteAllIncludesUnmappedRemoteAndExpiresMissingMappings() = runBlocking {
        val a = mode.createConversation()
        val b = mode.createConversation()
        val empty = mode.createConversation()
        dao.save(dao.get(a)!!.copy(remoteId = "a"))
        dao.save(dao.get(b)!!.copy(remoteId = "missing"))
        route { path, _ -> if (path == "sessions") reply("""{"sessions":[{"id":"a"},{"id":"unmapped"}]}""")
            else reply("""{"ok":true,"pending":true}""",202) }
        assertEquals(2, mode.deleteAllRemote())
        assertTrue(mode.conversations.value.getValue(a).expired)
        assertTrue(mode.conversations.value.getValue(b).expired)
        assertFalse(mode.conversations.value.getValue(empty).expired)
        assertEquals(2, requests.count { it.method == "DELETE" })
        assertNotNull(chat.getSession(a))
    }
}
