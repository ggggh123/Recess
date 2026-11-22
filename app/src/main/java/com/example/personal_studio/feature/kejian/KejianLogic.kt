package com.example.personal_studio.feature.kejian

import com.example.personal_studio.data.local.datastore.LocalFeedback
import com.example.personal_studio.data.remote.kejian.FeedbackStatus
import com.example.personal_studio.data.remote.kejian.LatestRelease
import java.security.MessageDigest

/** 不依赖 Android 的判断逻辑，单独拿出来方便单测。 */
object KejianLogic {

    fun isNewer(latest: LatestRelease?, currentCode: Int): Boolean = latest != null && latest.versionCode > currentCode

    /** 启动时是否弹“有新版本”：开着自动检查、确实更新、这个版本没被点过“以后再说”。 */
    fun shouldPrompt(latest: LatestRelease?, currentCode: Int, autoCheck: Boolean, dismissed: Int): Boolean =
        autoCheck && isNewer(latest, currentCode) && latest!!.versionCode != dismissed

    /** 证书指纹：SHA-256，冒号分隔的大写十六进制（和 apksigner、官网下载页的写法一致）。 */
    fun certFingerprint(der: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(der).joinToString(":") { "%02X".format(it) }

    fun sameFingerprint(a: String?, b: String?): Boolean =
        a != null && b != null && a.replace(":", "").equals(b.replace(":", ""), ignoreCase = true)

    /** 还需要向服务器问回复的反馈：发出去了，没关闭，60 天之内。 */
    fun needsPolling(f: LocalFeedback, now: Long): Boolean =
        f.sent && f.status != "closed" && now - f.createdAt < 60L * 24 * 3600 * 1000

    data class Merge(val items: List<LocalFeedback>, val newReplies: List<LocalFeedback>, val newlyFixed: List<LocalFeedback>)

    /**
     * 把服务器返回的状态合并进本地列表。服务器上已经删掉的（gone）从本地移除；
     * 新的回复（之前没提醒过的 replyAt）和新变成“已修复”的问题单独列出来，用来发通知。
     */
    fun merge(local: List<LocalFeedback>, remote: List<FeedbackStatus>): Merge {
        val byId = remote.filter { it.id != null }.associateBy { it.id!! }
        val replies = mutableListOf<LocalFeedback>()
        val fixed = mutableListOf<LocalFeedback>()
        val items = local.mapNotNull { f ->
            val r = f.serverId?.let { byId[it] } ?: return@mapNotNull f
            if (r.gone) return@mapNotNull null
            var next = f.copy(status = r.status ?: f.status, reply = r.reply, replyAt = r.replyAt, issue = r.issue)
            if (next.reply != null && next.replyAt != null && next.replyAt != f.replyNotifiedAt && next.hasUnseenReply) {
                replies += next
                next = next.copy(replyNotifiedAt = next.replyAt)
            }
            if (next.issue?.status == "fixed" && f.issueNotifiedStatus != "fixed") {
                fixed += next
                next = next.copy(issueNotifiedStatus = "fixed")
            }
            next
        }
        return Merge(items, replies, fixed)
    }

    /** 北京日期，签到“每天一次”按它算。 */
    fun bjDay(nowMs: Long): String =
        java.time.Instant.ofEpochMilli(nowMs).atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate().toString()

    fun sizeText(bytes: Long): String = when {
        bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1048576.0)
        bytes >= 1024 -> "%d KB".format(bytes / 1024)
        else -> "$bytes B"
    }
}
