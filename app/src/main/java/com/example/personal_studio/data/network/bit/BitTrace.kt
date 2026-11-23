package com.example.personal_studio.data.network.bit

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Sanitized trace of school requests, for telling why a sync failed. Records only the method,
 * host and path (never the query: tickets and calendar tokens live there), status, redirect
 * target, content type and what kind of page came back. Never headers, cookies or bodies.
 * The sink is a no-op until the app installs one, so unit tests stay silent.
 */
object BitTrace {
    @Volatile var sink: (String) -> Unit = {}

    fun log(line: String) = sink(line)

    /** host + path, with WebVPN's long encoded-host segment shortened and long numbers (an
     * account appears in some CAS paths) hidden. Only the names of query parameters, never values. */
    fun where(url: HttpUrl): String =
        url.host + url.encodedPath.replace(Regex("/[0-9a-f]{32,}/"), "/…/").replace(Regex("""\d{6,}"""), "#") +
            url.queryParameterNames.takeIf { it.isNotEmpty() }?.sorted()?.joinToString(",", prefix = "?").orEmpty()

    /** When each connection was last used, to tell a request sent on a long-idle one. */
    private val lastUse = java.util.WeakHashMap<Any, Long>()

    /** "复用空闲 Ns 的连接" when this hop went out on a connection idle for a while. */
    private fun reuse(connection: Any?, now: Long): String {
        if (connection == null) return ""
        val idle = synchronized(lastUse) { lastUse.put(connection, now) }?.let { (now - it) / 1000 } ?: return ""
        return if (idle >= IDLE_NOTE_SECONDS) "（复用空闲 ${idle}s 的连接）" else ""
    }

    /** One line per redirect hop (network level, before transparent gzip is undone). */
    val hops = Interceptor { chain ->
        val request = chain.request()
        val reused = reuse(chain.connection(), System.currentTimeMillis())
        if (reused.isNotEmpty()) log("  ⟲ ${request.method} ${where(request.url)} $reused")
        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            log("  ✗ ${request.method} ${where(request.url)} ${e.javaClass.simpleName}$reused")
            throw e
        }
        chain.connection()?.let { synchronized(lastUse) { lastUse[it] = System.currentTimeMillis() } }
        if (response.code in 300..399) {
            val to = response.header("Location")?.let { request.url.resolve(it) }?.let(::where) ?: "?"
            log("  ↪ ${request.method} ${where(request.url)} → ${response.code} → $to")
        }
        response
    }

    /** The final answer of each call, classified from the start of its (decoded) body. */
    val finals = Interceptor { chain ->
        val request = chain.request()
        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            log("${request.method} ${where(request.url)} ✗ ${(e as? SchoolPageException)?.problem ?: e.javaClass.simpleName}")
            throw e
        }
        if (response.code !in 300..399) {
            val type = response.header("Content-Type")?.substringBefore(';')?.trim().orEmpty()
            val head = try { response.peekBody(PEEK).string() } catch (_: IOException) { "" }
            val kind = BitPageKind.of(response.request.url, type, head)
            log("${request.method} ${where(request.url)} → ${response.code} ${where(response.request.url)} [$type] $kind")
        }
        response
    }

    private const val PEEK = 16_384L
    private const val IDLE_NOTE_SECONDS = 10L
}

/** What a school response turned out to be. */
enum class BitPageKind {
    JSON, CALENDAR, EMPTY,
    /** The CAS password / SMS form: the session behind this request is gone. */
    CAS_LOGIN,
    /** The WebVPN gateway's own sign-in page: the off-campus session is gone. */
    WEBVPN_LOGIN,
    /** The academic system asks for course evaluation first. */
    REVIEW,
    /** An error page from a proxy or server (502, "系统繁忙" and the like). */
    ERROR_PAGE,
    HTML, OTHER;

    companion object {
        fun of(url: HttpUrl, contentType: String, head: String): BitPageKind {
            val text = head.trimStart()
            return when {
                text.isEmpty() -> EMPTY
                text.startsWith("{") || text.startsWith("[") -> JSON
                text.startsWith("BEGIN:VCALENDAR") -> CALENDAR
                isWebVpnLogin(url, text) -> WEBVPN_LOGIN
                BitAuthPages.isLoginPage(text) || "/cas/login" in url.encodedPath -> CAS_LOGIN
                "评教" in text && "dataList" !in text -> REVIEW
                isErrorPage(text) -> ERROR_PAGE
                "html" in contentType || text.startsWith("<") -> HTML
                else -> OTHER
            }
        }

        /** The gateway's sign-in or device check: its `/login` or `/fingerprint`, or their markup. */
        fun isWebVpnLogin(url: HttpUrl, text: String): Boolean {
            val gateway = url.host.startsWith("webvpn.")
            val path = url.encodedPath.trimEnd('/')
            val gatePath = path == "/login" || path == "/fingerprint"
            return gateway && (gatePath || "FingerprintJS" in text ||
                ("wengine" in text && Regex("(?i)<form[^>]*login").containsMatchIn(text)))
        }

        fun isErrorPage(text: String): Boolean = listOf(
            "502 Bad Gateway", "503 Service", "504 Gateway", "Internal Server Error",
            "系统繁忙", "服务器繁忙", "访问过于频繁", "请求过于频繁", "访问受限", "Forbidden",
        ).any { text.contains(it, ignoreCase = true) }
    }
}
