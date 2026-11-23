package com.example.personal_studio.data.remote.kejian

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 课间服务的客户端。和学校、AI 用的连接完全分开：不带 Cookie、超时很短，
 * 失败只抛 [KejianApiException]，调用方一律吞掉——连不上服务器不能影响任何正常功能。
 */
@Singleton
class KejianApi @Inject constructor() {

    internal val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    suspend fun latest(): LatestRelease? = get("/api/v1/latest", LatestResponse.serializer()).latest

    /** 当前对这个版本显示的公告（只带版本号）。 */
    suspend fun announcements(versionCode: Int): List<Announcement> =
        get("/api/v1/announcements?vc=$versionCode", AnnouncementsResponse.serializer()).announcements

    suspend fun checkin(iid: String?, versionCode: Int, versionName: String, device: DeviceInfo): LatestRelease? =
        post(
            "/api/v1/checkin",
            CheckinBody(iid, versionCode, versionName, device.sdk, device.brand, device.model, device.abi),
            CheckinBody.serializer(),
            LatestResponse.serializer(),
        ).latest

    suspend fun submitFeedback(kind: String, body: String, contact: String?, device: DeviceInfo?): Pair<Long, String> =
        post("/api/v1/feedback", FeedbackBody(kind, body, contact, "app", device), FeedbackBody.serializer(), FeedbackCreated.serializer())
            .let { it.id to it.token }

    suspend fun feedbackStatus(items: List<Pair<Long, String>>): List<FeedbackStatus> =
        post(
            "/api/v1/feedback/status",
            StatusRequest(items.map { IdToken(it.first, it.second) }),
            StatusRequest.serializer(),
            StatusResponse.serializer(),
        ).items

    suspend fun deleteFeedback(id: Long, token: String) {
        post("/api/v1/feedback/delete", IdToken(id, token), IdToken.serializer(), StatusResponse.serializer())
    }

    private suspend fun <T> get(path: String, out: KSerializer<T>): T = call(Request.Builder().url(BASE + path).get().build(), out)

    private suspend fun <B, T> post(path: String, body: B, inSer: KSerializer<B>, out: KSerializer<T>): T =
        call(
            Request.Builder()
                .url(BASE + path)
                .header("X-Kejian", "1")
                .post(json.encodeToString(inSer, body).toRequestBody(JSON))
                .build(),
            out,
        )

    private suspend fun <T> call(req: Request, out: KSerializer<T>): T = withContext(Dispatchers.IO) {
        try {
            client.newCall(req).execute().use { res ->
                val text = res.body?.string().orEmpty()
                if (!res.isSuccessful) throw KejianApiException(res.code, text.take(200))
                json.decodeFromString(out, text)
            }
        } catch (e: KejianApiException) {
            throw e
        } catch (e: IOException) {
            throw KejianApiException(0, e.message ?: "network")
        } catch (e: Exception) {
            throw KejianApiException(-1, e.message ?: "bad response")
        }
    }

    companion object {
        const val BASE = "https://bit-recess.tech"
        const val PRIVACY_URL = "$BASE/privacy/#service"
        const val FEEDBACK_URL = "$BASE/feedback/"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/** [code] 为 0 表示网络不通，-1 表示响应解析失败，其余是 HTTP 状态码。 */
class KejianApiException(val code: Int, message: String) : IOException(message) {
    val isRateLimited get() = code == 429
    val isOffline get() = code == 0 || code == 502 || code == 503 || code == 504
}
