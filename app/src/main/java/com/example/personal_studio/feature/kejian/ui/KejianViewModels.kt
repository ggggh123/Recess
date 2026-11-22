package com.example.personal_studio.feature.kejian.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.personal_studio.BuildConfig
import com.example.personal_studio.data.local.datastore.LocalFeedback
import com.example.personal_studio.data.remote.kejian.KejianApiException
import com.example.personal_studio.data.remote.kejian.LatestRelease
import com.example.personal_studio.feature.kejian.KejianLogic
import com.example.personal_studio.feature.kejian.KejianRepository
import com.example.personal_studio.feature.kejian.UpdateInstaller
import com.example.personal_studio.feature.kejian.UpdateState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private fun <T> kotlinx.coroutines.flow.Flow<T>.state(vm: ViewModel, initial: T): StateFlow<T> =
    stateIn(vm.viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

// ================================================================ 检查更新

data class UpdateUi(
    val latest: LatestRelease? = null,
    val newer: Boolean = false,
    val autoCheck: Boolean = true,
    val lastCheckAt: Long = 0,
    val checking: Boolean = false,
    val checkError: String? = null,
)

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val repo: KejianRepository,
    private val installer: UpdateInstaller,
) : ViewModel() {
    private val checking = MutableStateFlow(false)
    private val checkError = MutableStateFlow<String?>(null)

    val ui: StateFlow<UpdateUi> = combine(repo.latest, repo.autoCheck, repo.lastCheckAt, checking, checkError) { latest, auto, at, busy, err ->
        UpdateUi(latest, KejianLogic.isNewer(latest, BuildConfig.VERSION_CODE), auto, at, busy, err)
    }.state(this, UpdateUi())

    val download: StateFlow<UpdateState> = installer.state

    fun checkNow() {
        if (checking.value) return
        viewModelScope.launch {
            checking.value = true
            checkError.value = null
            repo.checkNow().onFailure {
                checkError.value = if ((it as? KejianApiException)?.isOffline != false) "暂时连不上更新服务器，稍后再试。" else "检查失败（${(it as KejianApiException).code}）。"
            }
            checking.value = false
        }
    }

    fun start() = ui.value.latest?.let { installer.start(it) }
    fun cancel() = installer.cancel()
    fun canInstall() = installer.canInstall()
    fun permissionIntent() = installer.permissionIntent()
    fun install() = installer.install()
    fun setAutoCheck(on: Boolean) = viewModelScope.launch { repo.setAutoCheck(on) }
}

// ================================================================ 问题反馈

@HiltViewModel
class FeedbackViewModel @Inject constructor(private val repo: KejianRepository) : ViewModel() {
    val items: StateFlow<List<LocalFeedback>> = repo.feedback.state(this, emptyList())
    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()

    /** 打开页面：把未读回复标成已读，再向服务器要一次最新状态。 */
    fun onOpen() = viewModelScope.launch {
        repo.markRepliesSeen()
        _refreshing.value = true
        runCatching { repo.sendPending() }
        runCatching { repo.pollReplies(notify = false) }
        repo.markRepliesSeen()
        _refreshing.value = false
    }

    fun submit(kind: String, body: String, contact: String, includeDevice: Boolean, done: () -> Unit) = viewModelScope.launch {
        repo.submit(kind, body, contact, includeDevice)
        done()
    }

    fun delete(localId: String, onError: (String) -> Unit) = viewModelScope.launch {
        runCatching { repo.delete(localId) }.onFailure { onError("要联网才能删除服务器上的那一份，稍后再试。") }
    }
}

// ================================================================ 设置页的“关于”与统计开关

data class KejianSettingsUi(val newerVersion: String? = null, val unseenReplies: Int = 0, val statsEnabled: Boolean = true)

@HiltViewModel
class KejianSettingsViewModel @Inject constructor(private val repo: KejianRepository) : ViewModel() {
    val ui: StateFlow<KejianSettingsUi> = combine(repo.latest, repo.feedback, repo.statsEnabled) { latest, fb, stats ->
        KejianSettingsUi(
            newerVersion = latest?.takeIf { KejianLogic.isNewer(it, BuildConfig.VERSION_CODE) }?.versionName,
            unseenReplies = fb.count { it.hasUnseenReply },
            statsEnabled = stats,
        )
    }.state(this, KejianSettingsUi())

    fun setStats(on: Boolean) = viewModelScope.launch { repo.setStatsEnabled(on) }
}

// ================================================================ 全局弹窗：新回复、新版本

@HiltViewModel
class KejianPromptsViewModel @Inject constructor(
    private val repo: KejianRepository,
    private val installer: UpdateInstaller,
) : ViewModel() {
    val reply: StateFlow<LocalFeedback?> = repo.feedback.map { list -> list.firstOrNull { it.hasUnseenReply } }.state(this, null)

    val update: StateFlow<LatestRelease?> = combine(repo.latest, repo.autoCheck, repo.dismissedVersion) { latest, auto, dismissed ->
        latest?.takeIf { KejianLogic.shouldPrompt(it, BuildConfig.VERSION_CODE, auto, dismissed) }
    }.state(this, null)

    init {
        viewModelScope.launch { installer.cleanup(repo.latest.first()) }
    }

    fun seen() = viewModelScope.launch { repo.markRepliesSeen() }
    fun later(versionCode: Int) = viewModelScope.launch { repo.dismissVersion(versionCode) }
    fun update(release: LatestRelease) = viewModelScope.launch {
        repo.dismissVersion(release.versionCode)
        installer.start(release)
    }
}
