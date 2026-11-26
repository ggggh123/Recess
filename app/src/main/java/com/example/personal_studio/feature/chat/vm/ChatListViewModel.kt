package com.example.personal_studio.feature.chat.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.personal_studio.data.deep.DeepConversation
import com.example.personal_studio.data.deep.DeepMode
import com.example.personal_studio.data.deep.DeepStatus
import com.example.personal_studio.data.repository.ChatRepository
import com.example.personal_studio.domain.model.ChatSessionSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatListUiState(
    val sessions: List<ChatSessionSummary> = emptyList(),
    /** 深度对话（按会话 ID），列表里打“深度”标签、显示“进行中”。 */
    val deep: Map<Long, DeepConversation> = emptyMap(),
    /** 服务器上的深度模式可以用：显示“深度模式”入口。 */
    val deepAvailable: Boolean = false,
)

@HiltViewModel
class ChatListViewModel @Inject constructor(
    private val repo: ChatRepository,
    private val deepMode: DeepMode,
) : ViewModel() {

    val uiState: StateFlow<ChatListUiState> = combine(
        repo.observeSessionSummaries(), deepMode.conversations, deepMode.status,
    ) { sessions, deep, status ->
        ChatListUiState(sessions = sessions, deep = deep, deepAvailable = status is DeepStatus.Available)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ChatListUiState())

    val deepPrivacyAccepted: Flow<Boolean> get() = deepMode.privacyAccepted

    /** 进入问答页时查一下深度模式能不能用（连不上就不显示入口）。 */
    fun refreshDeep() {
        viewModelScope.launch { runCatching { deepMode.refreshStatus() } }
    }

    /** 新开深度对话；[consent] 为 true 表示用户刚在首次说明里点了同意。 */
    fun createDeepSession(consent: Boolean, onCreated: (Long) -> Unit) {
        if (creating) return
        creating = true
        viewModelScope.launch {
            try {
                if (consent) deepMode.acceptPrivacy()
                onCreated(deepMode.createConversation())
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            } finally {
                creating = false
            }
        }
    }

    init {
        // Conversations that never got a message (a cancelled 拍题提问, an abandoned 新对话).
        viewModelScope.launch { repo.deleteEmptySessions() }
    }

    private var creating = false

    fun createNewSession(onCreated: (Long) -> Unit) {
        if (creating) return
        creating = true
        viewModelScope.launch {
            try {
                val id = repo.createSession(initialTitle = com.example.personal_studio.domain.chat.ChatTitles.NEW)
                onCreated(id)
            } finally {
                creating = false
            }
        }
    }

    fun onRename(id: Long, title: String) {
        val t = title.trim()
        if (t.isEmpty()) return
        viewModelScope.launch { repo.renameSession(id, t) }
    }

    /** 删除对话；深度对话随后对账，服务器上的会话一并删除（没网时下次再删）。 */
    fun onDelete(id: Long) = viewModelScope.launch {
        repo.deleteSession(id)
        if (uiState.value.deep.containsKey(id)) runCatching { deepMode.reconcile() }
    }
}
