package com.example.personal_studio.data.local.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.personal_studio.data.remote.kejian.Announcement
import com.example.personal_studio.data.remote.kejian.DeviceInfo
import com.example.personal_studio.data.remote.kejian.IssueRef
import com.example.personal_studio.data.remote.kejian.LatestRelease
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// 单独的文件，不进主数据库：主库用的是破坏性迁移，加表会清空用户数据
private val Context.kejianDataStore: DataStore<Preferences> by preferencesDataStore("kejian-service")

/** 本机保存的一条反馈。serverId / token 为空表示还没发出去。 */
@Serializable
data class LocalFeedback(
    val localId: String,
    val kind: String,
    val body: String,
    val contact: String? = null,
    val device: DeviceInfo? = null,
    val createdAt: Long,
    val serverId: Long? = null,
    val token: String? = null,
    val status: String? = null,
    val reply: String? = null,
    val replyAt: Long? = null,
    /** 用户在 App 里看过这条回复的时间（弹窗点过“知道了”或打开过反馈页）。 */
    val replySeenAt: Long? = null,
    /** 已经为哪一次回复发过系统通知，避免重复提醒。 */
    val replyNotifiedAt: Long? = null,
    val issue: IssueRef? = null,
    /** 已经为问题的哪个状态发过通知（目前只提醒“已修复”）。 */
    val issueNotifiedStatus: String? = null,
) {
    val sent get() = serverId != null && token != null
    val hasUnseenReply get() = reply != null && (replyAt ?: 0) > (replySeenAt ?: 0)
}

@Singleton
class KejianPrefs @Inject constructor(@ApplicationContext private val context: Context) {
    private val store get() = context.kejianDataStore
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val listSer = ListSerializer(LocalFeedback.serializer())

    val statsEnabled: Flow<Boolean> = store.data.map { it[STATS] ?: true }
    val autoCheck: Flow<Boolean> = store.data.map { it[AUTO_CHECK] ?: true }
    val latest: Flow<LatestRelease?> = store.data.map { p -> p[LATEST]?.let { runCatching { json.decodeFromString(LatestRelease.serializer(), it) }.getOrNull() } }
    val dismissedVersion: Flow<Int> = store.data.map { it[DISMISSED] ?: 0 }
    val feedback: Flow<List<LocalFeedback>> = store.data.map { p -> decode(p[FEEDBACK]) }
    val lastCheckAt: Flow<Long> = store.data.map { it[LAST_CHECK_AT] ?: 0L }

    // ---- 公告 ----
    private val annSer = ListSerializer(Announcement.serializer())
    val announcementsEnabled: Flow<Boolean> = store.data.map { it[ANN_ENABLED] ?: true }
    val announcements: Flow<List<Announcement>> = store.data.map { p ->
        p[ANN_CACHE]?.let { runCatching { json.decodeFromString(annSer, it) }.getOrNull() } ?: emptyList()
    }
    /** 在 App 里看过（弹窗点过“知道了”）的公告 id。 */
    val seenAnnouncements: Flow<Set<Long>> = store.data.map { p -> p[ANN_SEEN].orEmpty().mapNotNull { it.toLongOrNull() }.toSet() }

    suspend fun setAnnouncementsEnabled(on: Boolean) = store.edit { it[ANN_ENABLED] = on }
    suspend fun lastAnnouncementFetch(): Long = store.data.first()[ANN_FETCHED] ?: 0L

    /**
     * 存下最新的公告列表，返回其中第一次见到、还没发过通知的（之后不会再算“新”）。
     * “看过 / 通知过”的记录只保留仍在列表里的，免得越积越多。
     */
    suspend fun saveAnnouncements(list: List<Announcement>): List<Announcement> {
        var fresh = emptyList<Announcement>()
        store.edit { p ->
            val book = com.example.personal_studio.feature.kejian.KejianLogic.announcementBook(
                list, notified = p[ANN_NOTIFIED].orEmpty(), seen = p[ANN_SEEN].orEmpty(),
            )
            fresh = book.fresh
            p[ANN_CACHE] = json.encodeToString(annSer, list)
            p[ANN_NOTIFIED] = book.notified
            p[ANN_SEEN] = book.seen
            p[ANN_FETCHED] = System.currentTimeMillis()
        }
        return fresh
    }

    suspend fun markAnnouncementSeen(id: Long) = store.edit { it[ANN_SEEN] = it[ANN_SEEN].orEmpty() + id.toString() }

    /** 随机安装 ID：第一次需要时生成；关闭统计时删除，再打开会是一个新的。 */
    suspend fun installId(): String {
        store.data.first()[IID]?.let { return it }
        val id = UUID.randomUUID().toString()
        store.edit { if (it[IID] == null) it[IID] = id }
        return store.data.first()[IID]!!
    }

    suspend fun setStatsEnabled(on: Boolean) = store.edit {
        it[STATS] = on
        if (!on) it.remove(IID)
    }

    suspend fun resetInstallId() = store.edit { it.remove(IID) }

    suspend fun setAutoCheck(on: Boolean) = store.edit { it[AUTO_CHECK] = on }

    suspend fun lastCheckinDay(): String? = store.data.first()[CHECKIN_DAY]

    suspend fun saveCheck(day: String?, latest: LatestRelease?) = store.edit {
        if (day != null) it[CHECKIN_DAY] = day
        it[LAST_CHECK_AT] = System.currentTimeMillis()
        if (latest != null) it[LATEST] = json.encodeToString(LatestRelease.serializer(), latest) else it.remove(LATEST)
    }

    suspend fun dismissVersion(versionCode: Int) = store.edit { it[DISMISSED] = versionCode }

    suspend fun lastReplyPoll(): Long = store.data.first()[LAST_POLL] ?: 0L
    suspend fun markReplyPoll() = store.edit { it[LAST_POLL] = System.currentTimeMillis() }

    suspend fun feedbackSnapshot(): List<LocalFeedback> = decode(store.data.first()[FEEDBACK])

    /** 读-改-写放在一次 edit 里，前台和后台同时改也不会互相覆盖。 */
    suspend fun updateFeedback(transform: (List<LocalFeedback>) -> List<LocalFeedback>) = store.edit {
        it[FEEDBACK] = json.encodeToString(listSer, transform(decode(it[FEEDBACK])).take(MAX_FEEDBACK))
    }

    private fun decode(s: String?): List<LocalFeedback> =
        s?.let { runCatching { json.decodeFromString(listSer, it) }.getOrNull() } ?: emptyList()

    private companion object {
        const val MAX_FEEDBACK = 50
        val STATS = booleanPreferencesKey("stats_enabled")
        val AUTO_CHECK = booleanPreferencesKey("auto_update_check")
        val IID = stringPreferencesKey("install_id")
        val CHECKIN_DAY = stringPreferencesKey("checkin_day")
        val LAST_CHECK_AT = longPreferencesKey("last_check_at")
        val LATEST = stringPreferencesKey("latest_release")
        val DISMISSED = intPreferencesKey("dismissed_version")
        val FEEDBACK = stringPreferencesKey("feedback")
        val LAST_POLL = longPreferencesKey("last_reply_poll")
        val ANN_ENABLED = booleanPreferencesKey("announcements_enabled")
        val ANN_CACHE = stringPreferencesKey("announcements")
        val ANN_SEEN = stringSetPreferencesKey("announcements_seen")
        val ANN_NOTIFIED = stringSetPreferencesKey("announcements_notified")
        val ANN_FETCHED = longPreferencesKey("announcements_fetched_at")
    }
}
