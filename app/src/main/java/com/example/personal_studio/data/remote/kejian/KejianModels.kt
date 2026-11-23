package com.example.personal_studio.data.remote.kejian

import kotlinx.serialization.Serializable

/**
 * 课间自己的服务（bit-recess.tech）的接口数据。服务端代码在官网仓库的 server/ 里。
 * 所有字段都允许缺省：服务端以后加字段、少字段都不会让 App 崩。
 */
@Serializable
data class LatestRelease(
    val versionCode: Int,
    val versionName: String,
    val releaseDate: String? = null,
    val notes: List<String> = emptyList(),
    val page: String? = null,
    val apk: ApkInfo,
)

@Serializable
data class ApkInfo(
    val name: String,
    val size: Long,
    val sha256: String,
    /** 签名证书的 SHA-256，冒号分隔的大写十六进制。 */
    val cert: String? = null,
    /** 按优先级排好：国内镜像在前，GitHub 在后。 */
    val urls: List<String> = emptyList(),
)

@Serializable
internal data class LatestResponse(val latest: LatestRelease? = null)

/** 每天第一次打开时发一次。[iid] 为空表示用户关掉了匿名统计，服务器只按“日期 + 版本”计数。 */
@Serializable
internal data class CheckinBody(
    val iid: String? = null,
    val vc: Int,
    val vn: String? = null,
    val sdk: Int? = null,
    val brand: String? = null,
    val model: String? = null,
    val abi: String? = null,
)

@Serializable
data class DeviceInfo(
    val brand: String? = null,
    val model: String? = null,
    val sdk: Int? = null,
    val vn: String? = null,
    val vc: Int? = null,
    val abi: String? = null,
)

@Serializable
internal data class FeedbackBody(
    val kind: String,
    val body: String,
    val contact: String? = null,
    val source: String = "app",
    val device: DeviceInfo? = null,
)

@Serializable
internal data class FeedbackCreated(val id: Long, val token: String)

@Serializable
internal data class IdToken(val id: Long, val token: String)

@Serializable
internal data class StatusRequest(val items: List<IdToken>)

@Serializable
internal data class StatusResponse(val items: List<FeedbackStatus> = emptyList())

@Serializable
data class FeedbackStatus(
    val id: Long? = null,
    val gone: Boolean = false,
    val status: String? = null,
    val reply: String? = null,
    val replyAt: Long? = null,
    val issue: IssueRef? = null,
)

/** 开发者在后台发布的公告。[level] 是 "info" 或 "important"。 */
@Serializable
data class Announcement(
    val id: Long,
    val title: String,
    val body: String = "",
    val link: String? = null,
    val level: String = "info",
    val publishedAt: Long = 0,
) {
    val important get() = level == "important"
}

@Serializable
internal data class AnnouncementsResponse(val announcements: List<Announcement> = emptyList())

@Serializable
data class IssueRef(val id: Long, val title: String, val status: String, val fixedIn: String? = null)
