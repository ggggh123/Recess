package com.example.personal_studio.data.deep

import android.database.sqlite.SQLiteConstraintException
import com.example.personal_studio.data.repository.ChatRepository
import com.example.personal_studio.domain.chat.ChatTitles
import com.example.personal_studio.domain.chat.estimateTokens
import com.example.personal_studio.domain.model.MessageRole
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class DeepApplicationScope

internal const val EXPIRED_MESSAGE = "服务器上的会话已过期，新开一个深度对话继续"

@Singleton
internal class DeepModeImpl @Inject constructor(
    private val dao: DeepDao,
    private val chat: ChatRepository,
    private val gateway: DeepGateway,
    private val prefs: DeepPrefs,
    @DeepApplicationScope private val scope: CoroutineScope,
) : DeepMode {
    private class Slot {
        val lock = Mutex()
        val live = MutableStateFlow<DeepLive?>(null)
        var reader: Job? = null
        var running: DeepSession? = null
    }

    private val slots = ConcurrentHashMap<Long, Slot>()
    private fun slot(id: Long) = slots.computeIfAbsent(id) { Slot() }
    private val catalog = Mutex()
    private val availability = MutableStateFlow<DeepStatus>(DeepStatus.Unknown)
    private val badges = MutableStateFlow<Map<Long, DeepConversation>>(emptyMap())
    override val conversations = badges.asStateFlow()
    override val status = availability.asStateFlow()
    override val privacyAccepted = prefs.privacyAccepted
    private val loaded = scope.async {
        badges.value = dao.badges().associate { it.chatId to badge(it.chatId, it.phase, it.activity) }
    }

    override suspend fun acceptPrivacy() = prefs.accept()

    override suspend fun refreshStatus() {
        availability.value = try { gateway.status() } catch (e: IOException) {
            DeepStatus.Unavailable((e as? GatewayError)?.code ?: "offline", e.description())
        }
    }

    override suspend fun createConversation(): Long {
        check(privacyAccepted.first()) { "请先确认深度模式的首次使用说明" }
        val id = chat.createSession(ChatTitles.NEW)
        slot(id).lock.withLock { write(DeepSession(id)) }
        return id
    }

    override fun live(chatSessionId: Long): StateFlow<DeepLive?> = slot(chatSessionId).live.asStateFlow()

    override fun steps(chatSessionId: Long): Flow<Map<Long, List<DeepStep>>> =
        dao.steps(chatSessionId).map { rows -> rows.groupBy({ it.answerId }, { it.step }) }

    private fun badge(id: Long, phase: Phase, activity: String?) =
        DeepConversation(id, phase == Phase.RUNNING || phase == Phase.FINISHING, phase == Phase.EXPIRED, activity)

    private fun publish(row: DeepSession) {
        val slot = slot(row.chatId)
        slot.running = row.takeIf { it.phase == Phase.RUNNING }
        val finished = row.phase == Phase.FINISHED || row.phase == Phase.EXPIRED
        badges.update { it + (row.chatId to badge(row.chatId, row.phase, row.activity)) }
        slot.live.value = when (row.phase) {
            Phase.RUNNING, Phase.FINISHING, Phase.FINISHED, Phase.EXPIRED -> row.round?.live(finished)
            else -> null
        }
    }

    private suspend fun write(row: DeepSession): DeepSession {
        loaded.await()
        dao.save(row, slot(row.chatId).running?.round?.steps)
        publish(row)
        return row
    }

    private fun IOException.description(): String = when (this) {
        is GatewayError, is ImageFailure -> message!!
        else -> "连接深度模式失败，请稍后重试"
    }

    private suspend fun expire(row: DeepSession) {
        write(row.copy(phase = Phase.EXPIRED, draft = null, activity = null,
            round = row.round?.copy(question = null, note = null, error = EXPIRED_MESSAGE,
                steps = row.round.steps.map { it.copy(running = false) })))
        val reader = slot(row.chatId).reader
        if (reader != currentCoroutineContext()[Job]) reader?.cancel()
    }

    override suspend fun send(chatSessionId: Long, text: String, imagePaths: List<String>): DeepSendResult {
        if (imagePaths.size > DeepMode.MAX_IMAGES) return DeepSendResult.Failed("每条消息最多附 4 张图片", null)
        if (text.isBlank() && imagePaths.isEmpty()) return DeepSendResult.Failed("请填写问题或选择图片", null)
        return scope.async {
            slot(chatSessionId).lock.withLock {
                val row = dao.get(chatSessionId) ?: return@withLock DeepSendResult.Failed("对话已删除", null)
                when (row.phase) {
                    Phase.EXPIRED -> return@withLock DeepSendResult.Failed(EXPIRED_MESSAGE, null)
                    Phase.DELETING -> return@withLock DeepSendResult.Failed("对话正在删除", null)
                    Phase.RUNNING, Phase.FINISHING -> {
                        if (row.phase == Phase.FINISHING || slot(chatSessionId).running == null) recover(row)
                        return@withLock DeepSendResult.Failed("请等当前回答结束后再发送", null)
                    }
                    else -> Unit
                }
                val draft = row.draft
                val same = draft != null && draft.text == text && draft.paths == imagePaths
                if (!same && row.phase == Phase.POSTING) {
                    return@withLock DeepSendResult.Failed("上一条消息的发送结果待确认，请先重发原消息", null)
                }
                if (chat.getSession(chatSessionId) == null) return@withLock DeepSendResult.Failed("对话已删除", null)
                val pending = if (same) row else {
                    val last = chat.listMessages(chatSessionId).maxOfOrNull { it.id } ?: 0
                    write(row.copy(phase = Phase.PENDING, draft = Draft(text, imagePaths, last), round = null))
                }
                submit(pending)
            }
        }.await()
    }

    /** 同一草稿既用于显式重发，也用于重启后确认已 POST 但没有收到回执的请求。 */
    private suspend fun submit(initial: DeepSession): DeepSendResult {
        var row = initial
        var draft = checkNotNull(row.draft)
        val id = row.chatId
        val userId = draft.userId ?: run {
            val previous = chat.listMessages(id).firstOrNull {
                it.id > draft.afterMessage && it.role == MessageRole.USER &&
                    it.contentMarkdown == draft.text && it.attachedImagePaths == draft.paths
            }
            val saved = previous?.id ?: try {
                chat.appendMessage(id, MessageRole.USER, draft.text, draft.paths)
            } catch (e: SQLiteConstraintException) {
                if (chat.getSession(id) == null) return DeepSendResult.Failed("对话已删除", null)
                throw e
            }
            draft = draft.copy(userId = saved)
            row = write(row.copy(draft = draft))
            saved
        }
        try {
            val images = withContext(Dispatchers.IO) { draft.paths.map(::encodeImage) }
            val remote = row.remoteId ?: catalog.withLock {
                gateway.create().also { row = write(row.copy(remoteId = it)) }
            }
            if (row.phase != Phase.POSTING) {
                if (status.value !is DeepStatus.Available) refreshStatus()
                val round = Round(userId, System.currentTimeMillis(), (status.value as? DeepStatus.Available)?.model)
                row = write(row.copy(phase = Phase.POSTING, round = round))
            }
            val turn = gateway.send(remote, userId, draft.text, images)
            attach(row, turn)
            return DeepSendResult.Sent(userId)
        } catch (e: IOException) {
            if (e is GatewayError && e.busy) {
                attach(row, null)
                return DeepSendResult.Failed("已有一轮正在进行，已接回回答。结束后可重发这条消息", userId)
            }
            if (e is GatewayError && e.expired) {
                expire(row)
                return DeepSendResult.Failed(EXPIRED_MESSAGE, userId)
            }
            if (e is GatewayError && e.status in 400..499 && row.phase == Phase.POSTING) {
                write(row.copy(phase = Phase.PENDING, round = null))
            }
            return DeepSendResult.Failed(e.description(), userId)
        }
    }

    private suspend fun attach(row: DeepSession, turn: String?) {
        val slot = slot(row.chatId)
        slot.reader?.cancelAndJoin()
        val round = checkNotNull(row.round).copy(turn = turn)
        val running = write(row.copy(phase = Phase.RUNNING, round = round,
            draft = if (turn == null) row.draft else null, activity = round.activity()))
        startReader(running)
    }

    override fun resume(chatSessionId: Long) {
        scope.launch {
            slot(chatSessionId).lock.withLock { dao.get(chatSessionId)?.let { recover(it) } }
        }
    }

    private suspend fun recover(row: DeepSession) {
        loaded.await()
        when (row.phase) {
            Phase.RUNNING -> { publish(row); startReader(row) }
            Phase.FINISHING -> finish(row)
            Phase.POSTING -> submit(row)
            else -> publish(row)
        }
    }

    private fun startReader(row: DeepSession) {
        val slot = slot(row.chatId)
        if (slot.reader?.isActive == true) return
        slot.reader = scope.launch { readRound(row.chatId, checkNotNull(row.remoteId), row.lastSeq) }
    }

    private suspend fun readRound(id: Long, remote: String, initialSeq: Long) {
        val slot = slot(id)
        var cursor = initialSeq
        var failures = 0
        while (currentCoroutineContext().isActive) {
            val before = cursor
            try {
                val ended = coroutineScope {
                    val channel = gateway.events(remote, cursor).produceIn(this)
                    try {
                        while (true) {
                            val received = channel.receiveCatching()
                            received.exceptionOrNull()?.let { throw it }
                            val first = received.getOrNull() ?: break
                            val batch = mutableListOf(first)
                            while (batch.size < 32) batch.add(channel.tryReceive().getOrNull() ?: break)
                            val done = slot.lock.withLock {
                                val row = slot.running ?: return@withLock true
                                val next = batch.fold(row) { state, event -> state.reduce(event, System.currentTimeMillis()) }
                                if (next.lastSeq != row.lastSeq) {
                                    write(next)
                                    cursor = next.lastSeq
                                }
                                if (next.phase == Phase.FINISHING) finish(next)
                                next.phase != Phase.RUNNING
                            }
                            if (done) return@coroutineScope true
                        }
                        false
                    } finally {
                        channel.cancel()
                    }
                }
                if (ended) return
            } catch (e: IOException) {
                if (e is GatewayError && e.expired) {
                    slot.lock.withLock { slot.running?.let { expire(it) } }
                    return
                }
            }
            failures = if (cursor != before) 1 else failures + 1
            val pause = failures >= 5
            slot.lock.withLock {
                val row = slot.running ?: return
                val note = if (pause) "连接已中断，重新打开对话即可继续" else "正在重新连接（第 $failures 次）"
                write(row.copy(round = row.round!!.copy(note = note)))
                if (pause) slot.reader = null
            }
            if (pause) return
            delay(1000L shl (failures - 1))
        }
    }

    /** FINISHING 已带终态游标、文本和时间。跨库提交被打断时，找到这轮已写入的 AI 消息再补步骤。 */
    private suspend fun finish(row: DeepSession) {
        val r = checkNotNull(row.round)
        val content = r.answerText()
        val answerId = content?.let {
            val duration = (checkNotNull(r.endedAt) - r.startedAt).coerceAtLeast(0)
            val saved = chat.listMessages(row.chatId).firstOrNull { m ->
                m.id > r.userId && m.role == MessageRole.AI && m.contentMarkdown == content &&
                    m.generationMs == duration && m.modelUsed == r.model
            }
            saved?.id ?: try {
                chat.appendMessage(row.chatId, MessageRole.AI, content,
                    generationMs = duration, tokenCount = estimateTokens(content), modelUsed = r.model)
            } catch (e: SQLiteConstraintException) {
                // 界面可以在最后一个事件到达时删除主库会话，对账会清理剩余数据。
                if (chat.getSession(row.chatId) == null) return
                throw e
            }
        }
        val finished = row.copy(phase = Phase.FINISHED, round = r.copy(savedAnswerId = answerId), activity = null)
        dao.finish(finished)
        publish(finished)
    }

    override fun acknowledge(chatSessionId: Long) {
        val slot = slot(chatSessionId)
        val seen = slot.live.value?.takeIf { it.finished } ?: return
        scope.launch {
            slot.lock.withLock {
                val row = dao.get(chatSessionId) ?: return@withLock
                if (slot.live.value == seen && row.phase in setOf(Phase.FINISHED, Phase.EXPIRED)) {
                    val phase = when {
                        row.phase == Phase.EXPIRED -> Phase.EXPIRED
                        row.draft != null -> Phase.PENDING
                        else -> Phase.IDLE
                    }
                    write(row.copy(round = null, phase = phase))
                }
            }
        }
    }

    override suspend fun stop(chatSessionId: Long) {
        slot(chatSessionId).lock.withLock {
            val row = dao.get(chatSessionId)?.takeIf { it.phase == Phase.RUNNING } ?: return
            try {
                gateway.stop(checkNotNull(row.remoteId))
                startReader(row)
            } catch (e: IOException) {
                if (e is GatewayError && e.expired) expire(row)
                else write(row.copy(round = row.round!!.copy(note = "停止失败：${e.description()}")))
            }
        }
    }

    override suspend fun answer(chatSessionId: Long, formId: String, answer: Map<String, List<String>>): Boolean =
        slot(chatSessionId).lock.withLock {
            val row = dao.get(chatSessionId) ?: return@withLock false
            val round = row.round ?: return@withLock false
            val q = round.question?.toQuestion()?.takeIf { it.formId == formId } ?: return@withLock false
            try {
                gateway.answer(checkNotNull(row.remoteId), q, answer)
                val next = round.copy(question = null, note = null)
                write(row.copy(round = next, activity = next.activity()))
                startReader(row)
                true
            } catch (e: IOException) {
                if (e is GatewayError && e.expired) expire(row)
                else write(row.copy(round = round.copy(note = e.description())))
                false
            }
        }

    override suspend fun reconcile() {
        loaded.await()
        for (id in dao.ids()) {
            val slot = slot(id)
            slot.lock.withLock {
                val row = dao.get(id) ?: return@withLock
                if (row.phase != Phase.DELETING && chat.getSession(id) != null) {
                    if (row.phase != Phase.RUNNING || slot.running == null) recover(row)
                    return@withLock
                }
                slot.reader?.cancelAndJoin()
                write(row.copy(phase = Phase.DELETING, activity = null))
                try {
                    row.remoteId?.let { gateway.delete(it) }
                } catch (e: IOException) {
                    if (e !is GatewayError || !e.expired) return@withLock
                }
                dao.remove(id)
                badges.update { it - id }
                slots.remove(id, slot)
            }
        }
    }

    override suspend fun deleteAllRemote(): Int {
        loaded.await()
        val (known, remoteIds) = catalog.withLock {
            dao.ids().mapNotNull { id -> dao.header(id)?.remoteId?.let { it to id } }.toMap() to gateway.sessions()
        }
        var count = 0
        var failure: IOException? = null
        for (remote in (remoteIds + known.keys).distinct()) {
            val id = known[remote]
            val slot = id?.let(::slot)
            suspend fun remove() {
                if (remote in remoteIds) {
                    try { gateway.delete(remote); count++ }
                    catch (e: IOException) { if (e !is GatewayError || !e.expired) throw e }
                }
                if (id != null) {
                    slot!!.reader?.cancelAndJoin()
                    dao.get(id)?.let { expire(it) }
                }
            }
            try {
                if (slot == null) remove() else slot.lock.withLock { remove() }
            } catch (e: IOException) {
                failure = e
            }
        }
        failure?.let { throw IOException("已删除 $count 个服务器会话，其余删除失败：${it.description()}", it) }
        return count
    }
}
