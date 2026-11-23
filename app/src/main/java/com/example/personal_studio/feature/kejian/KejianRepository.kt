package com.example.personal_studio.feature.kejian

import android.content.Context
import android.os.Build
import com.example.personal_studio.BuildConfig
import com.example.personal_studio.core.notification.FeedbackNotifier
import com.example.personal_studio.data.local.datastore.KejianPrefs
import com.example.personal_studio.data.local.datastore.LocalFeedback
import com.example.personal_studio.data.remote.kejian.Announcement
import com.example.personal_studio.data.remote.kejian.DeviceInfo
import com.example.personal_studio.data.remote.kejian.KejianApi
import com.example.personal_studio.data.remote.kejian.KejianApiException
import com.example.personal_studio.data.remote.kejian.LatestRelease
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 课间自己的服务：每天签到 + 检查更新、问题反馈、回复提醒。
 *
 * 原则：这里的每一件事都在后台进行，任何失败都只是“这次没成”，不弹错误、不阻塞界面、
 * 不影响课表、成绩等任何功能。反馈先存在本机，有网时再发；回复靠 WorkManager 定时去问。
 */
@Singleton
class KejianRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: KejianApi,
    private val prefs: KejianPrefs,
    private val notifier: FeedbackNotifier,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sendLock = Mutex()

    val latest: Flow<LatestRelease?> = prefs.latest
    val feedback: Flow<List<LocalFeedback>> = prefs.feedback
    val statsEnabled: Flow<Boolean> = prefs.statsEnabled
    val autoCheck: Flow<Boolean> = prefs.autoCheck
    val dismissedVersion: Flow<Int> = prefs.dismissedVersion
    val lastCheckAt: Flow<Long> = prefs.lastCheckAt
    val announcementsEnabled: Flow<Boolean> = prefs.announcementsEnabled
    val announcements: Flow<List<Announcement>> = prefs.announcements
    val seenAnnouncements: Flow<Set<Long>> = prefs.seenAnnouncements

    fun device() = DeviceInfo(
        brand = Build.BRAND?.take(40),
        model = Build.MODEL?.take(60),
        sdk = Build.VERSION.SDK_INT,
        vn = BuildConfig.VERSION_NAME,
        vc = BuildConfig.VERSION_CODE,
        abi = Build.SUPPORTED_ABIS.firstOrNull(),
    )

    /** App 回到前台时调用（MainActivity.onStart）。 */
    fun onForeground() {
        scope.launch {
            runCatching { dailyCheckin() }
            runCatching { if (prefs.feedbackSnapshot().any { !it.sent && it.status != REJECTED }) sendPending() }
            // App 开着的时候回复由弹窗展示，这里就不再发系统通知
            runCatching { pollReplies(minIntervalMs = 30 * 60_000L, notify = false) }
            runCatching { fetchAnnouncements(minIntervalMs = 15 * 60_000L, notify = false) }
        }
    }

    // ---------------------------------------------------------------- 公告

    /**
     * 拉一次公告（只带版本号）。新出现的：App 开着时由弹窗展示（[notify] = false），
     * 在后台时发系统通知。关掉了“接收公告”就不联网。
     */
    suspend fun fetchAnnouncements(minIntervalMs: Long = 0, notify: Boolean = true) {
        if (!prefs.announcementsEnabled.first()) return
        if (System.currentTimeMillis() - prefs.lastAnnouncementFetch() < minIntervalMs) return
        val list = api.announcements(BuildConfig.VERSION_CODE)
        val fresh = prefs.saveAnnouncements(list)
        if (notify) fresh.forEach { notifier.announcement(context, it) }
    }

    suspend fun markAnnouncementSeen(id: Long) {
        prefs.markAnnouncementSeen(id)
        notifier.cancelAnnouncement(context, id)
    }

    suspend fun setAnnouncementsEnabled(on: Boolean) {
        prefs.setAnnouncementsEnabled(on)
        if (on) {
            AnnouncementScheduler.ensure(context)
            runCatching { fetchAnnouncements(notify = false) }
        } else {
            AnnouncementScheduler.cancel(context)
        }
    }

    /** 当天第一次：签到（开着统计时带随机安装 ID）并拿到最新版本信息。两个开关都关了就完全不联网。 */
    suspend fun dailyCheckin(): Boolean {
        val stats = prefs.statsEnabled.first()
        val auto = prefs.autoCheck.first()
        if (!stats && !auto) return false
        val today = KejianLogic.bjDay(System.currentTimeMillis())
        if (prefs.lastCheckinDay() == today) return false
        val latest = api.checkin(if (stats) prefs.installId() else null, BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME, device())
        prefs.saveCheck(today, latest)
        return true
    }

    /** 设置里手动“检查更新”：不签到，只问最新版本。 */
    suspend fun checkNow(): Result<LatestRelease?> = runCatching {
        api.latest().also { prefs.saveCheck(null, it) }
    }

    suspend fun setStatsEnabled(on: Boolean) = prefs.setStatsEnabled(on)
    suspend fun setAutoCheck(on: Boolean) = prefs.setAutoCheck(on)
    suspend fun resetInstallId() = prefs.resetInstallId()
    suspend fun dismissVersion(versionCode: Int) = prefs.dismissVersion(versionCode)

    // ---------------------------------------------------------------- 反馈

    suspend fun submit(kind: String, body: String, contact: String?, includeDevice: Boolean): LocalFeedback {
        val item = LocalFeedback(
            localId = UUID.randomUUID().toString(),
            kind = kind,
            body = body.trim(),
            contact = contact?.trim()?.takeIf { it.isNotEmpty() },
            device = if (includeDevice) device() else null,
            createdAt = System.currentTimeMillis(),
        )
        prefs.updateFeedback { listOf(item) + it }
        scope.launch {
            val ok = runCatching { sendPending() }.getOrDefault(false)
            if (!ok) FeedbackScheduler.enqueueSend(context)
            FeedbackScheduler.ensureReplyPolling(context)
        }
        return item
    }

    /** 把没发出去的反馈逐条发出；全部发完返回 true。同一时间只跑一份，避免重复提交。 */
    suspend fun sendPending(): Boolean = sendLock.withLock {
        val pending = prefs.feedbackSnapshot().filter { !it.sent && it.status != REJECTED }.reversed()
        for (f in pending) {
            try {
                val (id, token) = api.submitFeedback(f.kind, f.body, f.contact, f.device)
                prefs.updateFeedback { list -> list.map { if (it.localId == f.localId) it.copy(serverId = id, token = token, status = "new") else it } }
            } catch (e: KejianApiException) {
                // 内容本身不合格（4xx，限流除外）就别再重试了；其他情况下次再发
                if (e.code in 400..499 && !e.isRateLimited) {
                    prefs.updateFeedback { list -> list.map { if (it.localId == f.localId) it.copy(status = REJECTED) else it } }
                    continue
                }
                return@withLock false
            }
        }
        true
    }

    /** 问服务器这些反馈有没有回复。[notify] 时为新回复、新修复发系统通知。 */
    suspend fun pollReplies(minIntervalMs: Long = 0, notify: Boolean = true) {
        val now = System.currentTimeMillis()
        if (now - prefs.lastReplyPoll() < minIntervalMs) return
        val open = prefs.feedbackSnapshot().filter { KejianLogic.needsPolling(it, now) }
        if (open.isEmpty()) return
        val remote = api.feedbackStatus(open.map { it.serverId!! to it.token!! })
        var merged: KejianLogic.Merge? = null
        prefs.updateFeedback { list -> KejianLogic.merge(list, remote).also { merged = it }.items }
        prefs.markReplyPoll()
        if (notify) merged?.let { m ->
            m.newReplies.forEach { notifier.reply(context, it) }
            m.newlyFixed.forEach { notifier.fixed(context, it) }
        }
    }

    suspend fun hasOpenFeedback(): Boolean = prefs.feedbackSnapshot().any { KejianLogic.needsPolling(it, System.currentTimeMillis()) || !it.sent }

    suspend fun markRepliesSeen() {
        val now = System.currentTimeMillis()
        prefs.updateFeedback { list -> list.map { if (it.hasUnseenReply) it.copy(replySeenAt = now, replyNotifiedAt = it.replyAt) else it } }
        notifier.cancelAll(context)
    }

    /** 删除：服务器上那份一起删（需要联网）；还没发出去的直接删本地。 */
    suspend fun delete(localId: String) {
        val f = prefs.feedbackSnapshot().firstOrNull { it.localId == localId } ?: return
        if (f.sent) api.deleteFeedback(f.serverId!!, f.token!!)
        prefs.updateFeedback { list -> list.filterNot { it.localId == localId } }
    }

    companion object {
        const val REJECTED = "rejected"
    }
}
