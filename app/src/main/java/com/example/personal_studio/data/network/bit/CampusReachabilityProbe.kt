package com.example.personal_studio.data.network.bit

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Anonymous direct-access probe. The teaching portal is publicly reachable now,
 * so reaching it (or following its redirect to public SSO) cannot identify campus
 * access. Probe jwms without redirects/cookies and reject HTTP error pages (a campus
 * Wi-Fi that is not signed in yet redirects to its portal).
 *
 * It asks with GET: jwms answers HEAD with 403 (seen on campus Wi-Fi 2026-11), which
 * made every campus probe fail and sent 成绩 and 乐学 to WebVPN, whose name campus DNS
 * does not resolve. Only the status line is read; the body is dropped unread.
 */
@Singleton
class CampusReachabilityProbe internal constructor(
    private val client: OkHttpClient,
    private val url: String,
) {
    @Inject constructor() : this(OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(), LOCAL_PROBE_URL)

    /** Direct-route availability only; cancel the network request when the caller leaves. */
    suspend fun localReachable(): Boolean = suspendCancellableCoroutine { continuation ->
        // No kept connection: one gone stale since the last probe would eat its 3 s budget
        // and send a campus sync to WebVPN.
        val call = client.newCall(Request.Builder().url(url).get().header("Connection", "close").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resume(false)
            }

            override fun onResponse(call: Call, response: Response) {
                val reachable = response.use { it.isSuccessful }
                if (continuation.isActive) continuation.resume(reachable)
            }
        })
    }

    companion object {
        const val LOCAL_PROBE_URL = "http://jwms.bit.edu.cn/"
    }
}
