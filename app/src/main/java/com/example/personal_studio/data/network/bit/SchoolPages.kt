package com.example.personal_studio.data.network.bit

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import java.io.IOException

/** Why the school answered a data request with something other than the data. */
enum class SchoolProblem(val text: String) {
    /** WebVPN's own sign-in or device-check page: the off-campus channel is not signed in. */
    WEBVPN_SIGN_IN("校外访问需要先验证"),
    /** The CAS sign-in page where data was expected: the school session has ended. */
    SESSION_EXPIRED("登录已过期，请重新登录"),
    /** A 5xx, a gateway error page, "系统繁忙", or rate limiting. */
    SERVER_BUSY("学校系统繁忙或正在维护，请稍后再试"),
    /** Some other page where data was expected. */
    UNEXPECTED_PAGE("学校返回了意外的页面，请稍后再试"),
}

/** Thrown by [SchoolPageGuard]. An IOException only because OkHttp interceptors may throw no
 * other kind; callers catch it before their network-failure branch, so it never triggers a
 * switch of network route (that would hide the real reason behind "unreachable"). */
class SchoolPageException(val problem: SchoolProblem, val where: String) : IOException("$problem at $where")

/** Marks requests of the sign-in flow, which inspects every page itself. */
internal object BitAuthFlow

/**
 * Checks where each data request really ended, after redirects. A request bounced to WebVPN's
 * sign-in or device check, to the CAS sign-in page, or answered with a server error used to
 * reach the parsers as "unexpected content"; now it fails with the actual reason.
 */
class SchoolPageGuard internal constructor(private val urls: BitAuthUrls) : Interceptor {
    constructor() : this(BitAuthUrls())

    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (request.tag(BitAuthFlow::class.java) != null) return response
        val head = if (response.code in 200..299 && expectsJson(request)) {
            try { response.peekBody(PEEK).string() } catch (_: IOException) { "" }
        } else ""
        val problem = problem(request, response.request.url, response.code, head) ?: return response
        response.close()
        throw SchoolPageException(problem, BitTrace.where(response.request.url))
    }

    /** The reason a data request failed, or null when its answer may be what was asked for. */
    fun problem(request: Request, finalUrl: HttpUrl, code: Int, head: String): SchoolProblem? {
        if (urls.isGatewayGate(finalUrl)) return SchoolProblem.WEBVPN_SIGN_IN
        if (!isAcademic(request.url)) return null
        return when {
            code >= 500 -> SchoolProblem.SERVER_BUSY
            urls.isCas(urls.unroute(finalUrl)) -> SchoolProblem.SESSION_EXPIRED
            code !in 200..299 -> null
            expectsJson(request) && head.isNotBlank() && !head.trimStart().let { it.startsWith("{") || it.startsWith("[") } -> when {
                BitAuthPages.isLoginPage(head) -> SchoolProblem.SESSION_EXPIRED
                BitPageKind.isErrorPage(head) -> SchoolProblem.SERVER_BUSY
                else -> SchoolProblem.UNEXPECTED_PAGE
            }
            else -> null
        }
    }

    /** 教学中心 (jwapp), 正方教务 (jsxsd) and 乐学, directly or through WebVPN. 新乐学 handles
     * its own answers (a sign-in page there just means "sign in first"). */
    private fun isAcademic(url: HttpUrl): Boolean = "/jwapp/" in url.encodedPath || "/jsxsd/" in url.encodedPath ||
        urls.unroute(url).host == LEXUE_HOST

    /** jwapp's module calls answer JSON; its index.do pages are HTML. */
    private fun expectsJson(request: Request): Boolean =
        "/jwapp/" in request.url.encodedPath && "index.do" !in request.url.encodedPath

    private companion object {
        const val PEEK = 4_096L
        const val LEXUE_HOST = "lexue.bit.edu.cn"
    }
}
