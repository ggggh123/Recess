package com.example.personal_studio.feature.settings.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.personal_studio.data.deep.DeepMode
import com.example.personal_studio.data.deep.DeepStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DeepModeSettingsUi(
    /** 一句话状态：“可以使用”“深度模式暂未开放” 等。用哪个模型在对话里选，这里不写。 */
    val status: String = "正在查询…",
    val deleting: Boolean = false,
    /** 删除完成后的一次性提示。 */
    val message: String? = null,
)

/** 设置 → AI 模型 → 深度模式：状态、隐私说明入口、删除服务器上的全部深度对话。 */
@HiltViewModel
class DeepModeSettingsViewModel @Inject constructor(
    private val deep: DeepMode,
) : ViewModel() {

    private val _ui = MutableStateFlow(DeepModeSettingsUi())
    val ui: StateFlow<DeepModeSettingsUi> = _ui.asStateFlow()

    init {
        deep.status.onEach { s -> _ui.update { it.copy(status = statusLine(s)) } }.launchIn(viewModelScope)
        viewModelScope.launch { runCatching { deep.refreshStatus() } }
    }

    fun deleteAllRemote() {
        if (_ui.value.deleting) return
        _ui.update { it.copy(deleting = true, message = null) }
        viewModelScope.launch {
            val message = runCatching { deep.deleteAllRemote() }.fold(
                onSuccess = { n -> if (n == 0) "服务器上没有你的深度对话" else "已删除 $n 个深度对话" },
                onFailure = { "没删成，请检查网络后再试" },
            )
            _ui.update { it.copy(deleting = false, message = message) }
        }
    }

    fun onMessageShown() = _ui.update { it.copy(message = null) }
}

internal fun statusLine(s: DeepStatus): String = when (s) {
    DeepStatus.Unknown -> "正在查询…"
    is DeepStatus.Available -> "可以使用"
    is DeepStatus.Unavailable -> s.message
}
