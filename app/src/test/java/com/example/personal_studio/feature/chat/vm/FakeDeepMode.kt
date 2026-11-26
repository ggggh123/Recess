package com.example.personal_studio.feature.chat.vm

import com.example.personal_studio.data.deep.DeepChoice
import com.example.personal_studio.data.deep.DeepConversation
import com.example.personal_studio.data.deep.DeepLive
import com.example.personal_studio.data.deep.DeepMode
import com.example.personal_studio.data.deep.DeepSendResult
import com.example.personal_studio.data.deep.DeepStatus
import com.example.personal_studio.data.deep.DeepStep
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** 测试用的深度模式：不联网，记录调用，状态由测试直接设置。 */
class FakeDeepMode(
    initialStatus: DeepStatus = DeepStatus.Unavailable("agent_disabled", "深度模式已关闭"),
) : DeepMode {
    override val status = MutableStateFlow(initialStatus)
    override suspend fun refreshStatus() { refreshCount++ }
    var refreshCount = 0

    val accepted = MutableStateFlow(false)
    override val privacyAccepted: Flow<Boolean> = accepted
    override suspend fun acceptPrivacy() { accepted.value = true }

    val savedChoice = MutableStateFlow<DeepChoice?>(null)
    override val choice: Flow<DeepChoice?> = savedChoice
    override suspend fun setChoice(choice: DeepChoice) { savedChoice.value = choice }

    override val conversations = MutableStateFlow<Map<Long, DeepConversation>>(emptyMap())

    /** createConversation 用它建本地会话（测试里通常是 FakeChatRepository.createSession）。 */
    var createLocal: suspend () -> Long = { error("createLocal not set") }
    override suspend fun createConversation(): Long {
        val id = createLocal()
        conversations.update { it + (id to DeepConversation(id, running = false, expired = false, activity = null)) }
        return id
    }

    private val lives = mutableMapOf<Long, MutableStateFlow<DeepLive?>>()
    fun liveFlow(id: Long) = lives.getOrPut(id) { MutableStateFlow(null) }
    override fun live(chatSessionId: Long): StateFlow<DeepLive?> = liveFlow(chatSessionId)

    val stepFlows = mutableMapOf<Long, MutableStateFlow<Map<Long, List<DeepStep>>>>()
    override fun steps(chatSessionId: Long): Flow<Map<Long, List<DeepStep>>> =
        stepFlows.getOrPut(chatSessionId) { MutableStateFlow(emptyMap()) }

    val sent = mutableListOf<Triple<Long, String, List<String>>>()
    var sendResult: suspend (Long, String, List<String>) -> DeepSendResult = { _, _, _ -> DeepSendResult.Sent(0) }
    val sentChoices = mutableListOf<DeepChoice?>()
    override suspend fun send(chatSessionId: Long, text: String, imagePaths: List<String>, choice: DeepChoice?): DeepSendResult {
        sent += Triple(chatSessionId, text, imagePaths)
        sentChoices += choice
        return sendResult(chatSessionId, text, imagePaths)
    }

    val stopped = mutableListOf<Long>()
    override suspend fun stop(chatSessionId: Long) { stopped += chatSessionId }

    val answers = mutableListOf<Pair<String, Map<String, List<String>>>>()
    override suspend fun answer(chatSessionId: Long, formId: String, answer: Map<String, List<String>>): Boolean {
        answers += formId to answer
        return true
    }

    val resumed = mutableListOf<Long>()
    override fun resume(chatSessionId: Long) { resumed += chatSessionId }

    val acknowledged = mutableListOf<Long>()
    override fun acknowledge(chatSessionId: Long) {
        acknowledged += chatSessionId
        liveFlow(chatSessionId).value = null
    }

    var reconcileCount = 0
    override suspend fun reconcile() { reconcileCount++ }

    override suspend fun deleteAllRemote(): Int = 0
}
