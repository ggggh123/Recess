package com.example.personal_studio.feature.settings.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.personal_studio.data.local.datastore.UserPreferencesRepository
import com.example.personal_studio.domain.chat.ChatPrompts
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What is saved: [custom] is null while the built-in prompt is in use; [loaded] once read. */
data class SystemPromptUi(val custom: String? = null, val loaded: Boolean = false) {
    /** The prompt 问答 sends right now. */
    val effective: String get() = ChatPrompts.effective(custom)
}

@HiltViewModel
class SystemPromptViewModel @Inject constructor(
    private val prefs: UserPreferencesRepository,
) : ViewModel() {
    val ui: StateFlow<SystemPromptUi> = prefs.chatSystemPrompt
        .map { SystemPromptUi(custom = it, loaded = true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SystemPromptUi())

    /** Saves [text]; blank or identical to the built-in prompt goes back to the built-in one. */
    fun save(text: String, done: () -> Unit = {}) = viewModelScope.launch {
        prefs.setChatSystemPrompt(ChatPrompts.normalize(text))
        done()
    }

    fun restoreDefault(done: () -> Unit = {}) = viewModelScope.launch {
        prefs.setChatSystemPrompt(null)
        done()
    }
}
