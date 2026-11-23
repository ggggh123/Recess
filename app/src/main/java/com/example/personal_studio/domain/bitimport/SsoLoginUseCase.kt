package com.example.personal_studio.domain.bitimport

import com.example.personal_studio.core.util.CasAesCrypto
import com.example.personal_studio.data.network.bit.BitApiClient
import com.example.personal_studio.data.network.bit.BitAuthPages
import com.example.personal_studio.data.network.bit.BitAuthResponse
import com.example.personal_studio.data.network.bit.BitAuthSession
import com.example.personal_studio.data.network.bit.BitTrace
import com.example.personal_studio.data.network.bit.CasApi
import com.example.personal_studio.data.network.bit.CasSecondFactorPage
import com.example.personal_studio.data.network.bit.NetworkMode
import com.example.personal_studio.data.network.bit.SchoolProblem
import com.example.personal_studio.data.network.bit.dto.CasInitDto
import com.example.personal_studio.data.network.bit.dto.CasLoginDto
import com.example.personal_studio.data.network.bit.origin
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import javax.inject.Inject

/** CAS and WebVPN need separate service tickets. Keep the target ticket unconsumed
 * until the gateway session is ready, then redeem it through the selected route. */
class SsoLoginUseCase @Inject constructor() {
    private enum class Target { JWAPP, JWMS, LEXUE, ECLASS }
    private class Rejected(val result: CasLoginDto, val why: String = "") : Exception()
    /** WebVPN showed its sign-in or device check instead of the site. */
    private class GatewayGate(val at: HttpUrl) : Exception()

    /**
     * The interactive sign-in (教学中心). With a [prompter], an SMS second step or picture captcha
     * is asked for in the app; without one they end as [CasLoginDto.CaptchaRequired].
     */
    suspend operator fun invoke(
        api: BitApiClient, username: String, password: String, prompter: CasPrompter? = null,
    ): CasLoginDto = login(api, username, password, Target.JWAPP, prompter)

    suspend fun loginGrades(api: BitApiClient, username: String, password: String): CasLoginDto =
        login(api, username, password, Target.JWMS)

    suspend fun loginLexue(api: BitApiClient, username: String, password: String): CasLoginDto =
        login(api, username, password, Target.LEXUE)

    /** 新乐学 (畅课): its Keycloak (zy-identity) brokers to CAS with a per-login state, so the
     * service URL is read from the redirect chain. Success when its cookies are still valid. */
    suspend fun loginEclass(api: BitApiClient, username: String, password: String): CasLoginDto =
        login(api, username, password, Target.ECLASS)

    private suspend fun login(
        api: BitApiClient, username: String, password: String, target: Target, prompter: CasPrompter? = null,
    ): CasLoginDto {
        api.prepareAccount(username)
        val session = api.auth
        val urls = session.urls
        return try {
            val service = when (target) {
                Target.JWAPP -> {
                    // This public preflight supplies a service URL with a per-login nonce.
                    val preflight = session.request(urls.jwappAuth.toHttpUrl(), publicPreflight = true)
                    val cas = preflight.redirect()
                    if (preflight.code !in 300..399 || cas == null || !urls.isCas(cas)) {
                        reject(preflight, "教学中心未返回认证入口")
                    }
                    cas.queryParameter("service")?.toHttpUrlOrNull()
                        ?.takeIf { urls.trusted(it) && !urls.isCas(it) }
                        ?: reject(preflight, "教学中心缺少服务回调")
                }
                Target.JWMS -> urls.jwms.toHttpUrl()
                Target.LEXUE -> urls.lexue.toHttpUrl()
                Target.ECLASS -> eclassService(session) ?: return CasLoginDto.Success
            }
            val callback = requestTicket(session, service, username, password, prompter)
            // 新乐学 is public: it never goes through WebVPN. 乐学 resolves only on campus.
            val mode = if (target == Target.ECLASS) NetworkMode.LOCAL else api.networkMode
            if (mode == NetworkMode.WEBVPN) signInGateway(api, session, username, password)
            if (target == Target.JWMS) {
                // jwms needs its root-session cookie before redeeming the CAS ticket.
                val root = session.request(urls.route(service, mode))
                if (root.code >= 400) reject(root, "教务系统初始化失败")
            }
            follow(session, callback, mode, if (target == Target.ECLASS) "新乐学登录" else "教务服务登录")
            if (target == Target.JWAPP) {
                follow(session, urls.jwappLanding.toHttpUrl(), mode, "教学中心初始化")
            }
            CasLoginDto.Success
        } catch (e: Rejected) {
            BitTrace.log("login $target: ${e.result.javaClass.simpleName} ${e.why}".trimEnd())
            e.result
        } catch (e: GatewayGate) {
            BitTrace.log("login $target: WebVPN 未登录 ${BitTrace.where(e.at)}")
            CasLoginDto.UnknownFailure("WebVPN 未登录：${BitTrace.where(e.at)}", SchoolProblem.WEBVPN_SIGN_IN)
        }
    }

    /**
     * Signs the WebVPN gateway in with a CAS ticket of its own. A new gateway session first wants
     * a device fingerprint (its /fingerprint page runs FingerprintJS and requests /set-fingerprint);
     * a ticket redeemed before that is dropped on the way to the check, leaving the gateway signed
     * out. So the check is answered first, and once more with a fresh ticket if it still asks.
     *
     * Only the redemption itself is checked. Once signed in, the gateway sends the browser on to
     * the page it last wanted to open, which can be a stale callback ending in an error page; that
     * page says nothing about the sign-in, so it is not followed.
     */
    private suspend fun signInGateway(api: BitApiClient, session: BitAuthSession, username: String, password: String) {
        val urls = session.urls
        repeat(2) { attempt ->
            answerDeviceCheck(session, api.webVpnFingerprint())
            val ticket = requestTicket(session, urls.gatewayCallback.toHttpUrl(), username, password)
            val response = session.request(ticket)
            val next = response.redirect()
            val signedOut = when (response.code) {
                // Signed in unless sent back to the device check, its sign-in, or CAS.
                in 300..399 -> next == null || urls.isGatewayGate(next) || urls.isCas(urls.unroute(next))
                // The gateway answered with its own sign-in page.
                in 200..299 -> true
                else -> reject(response, "WebVPN 登录")
            }
            if (!signedOut) return
            BitTrace.log("login: WebVPN 第 ${attempt + 1} 次仍未登录 → ${next?.let(BitTrace::where) ?: BitTrace.where(response.url)}")
        }
        throw Rejected(
            CasLoginDto.UnknownFailure("WebVPN 登录：网关没有接受设备校验", SchoolProblem.WEBVPN_SIGN_IN),
            "WebVPN 设备校验未通过",
        )
    }

    /** Answers WebVPN's device check when its entry leads there; a signed-in gateway is left alone. */
    private suspend fun answerDeviceCheck(session: BitAuthSession, fingerprint: String) {
        val urls = session.urls
        val entry = session.request(urls.gatewayEntry())
        val check = entry.redirect()?.takeIf { entry.code in 300..399 && urls.isGatewayFingerprint(it) } ?: return
        val page = session.request(check, referer = entry.url)
        if (page.code !in 200..299) return
        session.request(urls.fingerprintSubmit(check, fingerprint), referer = check)
    }

    /** Follows 新乐学 → zy-identity until the hand-off to CAS and returns the service it asks
     * a ticket for; null when 新乐学 answers directly (still signed in). */
    private suspend fun eclassService(session: BitAuthSession): HttpUrl? {
        val urls = session.urls
        val entry = urls.eclass.toHttpUrl()
        var next = entry
        var previous: HttpUrl? = null
        repeat(10) {
            val response = session.request(next, referer = previous)
            val redirect = if (response.code in 300..399) response.redirect() else null
            if (redirect == null) {
                if (response.code in 200..299 && response.url.origin() == entry.origin()) return null
                reject(response, "新乐学未返回认证入口")
            }
            if (urls.isCas(redirect)) {
                return redirect.queryParameter("service")?.toHttpUrlOrNull()
                    ?.takeIf { urls.trusted(it) && !urls.isCas(it) }
                    ?: reject(response, "新乐学缺少服务回调")
            }
            previous = response.url
            next = redirect
        }
        throw Rejected(CasLoginDto.UnknownFailure("新乐学登录：重定向次数过多"))
    }

    private suspend fun requestTicket(
        session: BitAuthSession, service: HttpUrl, username: String, password: String, prompter: CasPrompter? = null,
    ): HttpUrl {
        val loginUrl = session.urls.casLogin.toHttpUrl().newBuilder()
            .addQueryParameter("service", service.toString()).build()
        val initResponse = session.request(loginUrl)
        ticket(initResponse, service)?.let { return it }
        if (initResponse.code !in 200..299) reject(initResponse, "统一身份认证初始化失败")
        // Modern CAS may render an SMS/risk page instead of the password form.
        val init = try { parseLoginPage(initResponse.body) } catch (_: IllegalStateException) {
            throw Rejected(BitAuthPages.loginFailure(initResponse.body) ?: CasLoginDto.CaptchaRequired, "CAS 首页不是密码表单")
        }
        val cas = CasApi(session).apply { referer = loginUrl }
        // Only an interactive sign-in asks: a captcha the person cannot see would only fail.
        val captcha = if (prompter != null) captchaAnswer(cas, username, prompter) else null
        val response = session.request(loginUrl, fields = linkedMapOf(
            "username" to username,
            "password" to CasAesCrypto.encryptPassword(password, init.salt),
            "execution" to init.execution,
            "croypto" to init.salt,
            "captcha_payload" to CasAesCrypto.encryptPassword(captcha?.second ?: "{}", init.salt),
            "captcha_code" to captcha?.first.orEmpty(),
            "type" to "UsernamePassword",
            "geolocation" to "",
            "_eventId" to "submit",
        ), referer = loginUrl)
        ticket(response, service)?.let { return it }
        BitAuthPages.secondFactorPage(response.body, response.url)?.let { page ->
            return smsSecondStep(session, cas, page, response.url, service, username, prompter)
        }
        BitAuthPages.loginFailure(response.body)?.let { throw Rejected(it, "提交密码后：" + BitAuthPages.describe(response.body)) }
        // A blank body, HTTP error, or 200 without a ticket never means success.
        reject(response, "统一身份认证未签发服务凭证")
    }

    /** The code and payload for the picture captcha the CAS wants, asked of the person; null when there is none. */
    private suspend fun captchaAnswer(cas: CasApi, username: String, prompter: CasPrompter): Pair<String, String>? {
        val captcha = try { cas.captchaFor(username) } catch (_: IOException) { null } ?: return null
        val image = captcha.image ?: throw Rejected(CasLoginDto.CaptchaRequired, "图形验证码无法显示")
        return when (val answer = prompter.captcha(image)) {
            is CaptchaDecision.Answer -> answer.code.trim() to captcha.payload.toString()
            CaptchaDecision.UseWebPage -> throw Rejected(CasLoginDto.CaptchaRequired, "改用网页验证")
        }
    }

    /**
     * The password was right and CAS wants an SMS code too. The person gets the code in the app
     * and it is checked there; the final `smsLogin` form then issues the ticket. Anything this
     * cannot do (no prompter, no bound phone, an unusual form) ends as "verification needed",
     * which sends an interactive sign-in to the school's own page.
     */
    private suspend fun smsSecondStep(
        session: BitAuthSession, cas: CasApi, page: CasSecondFactorPage, pageUrl: HttpUrl,
        service: HttpUrl, username: String, prompter: CasPrompter?,
    ): HttpUrl {
        if (prompter == null || !session.urls.isCas(page.formAction)) throw Rejected(CasLoginDto.CaptchaRequired, "需要短信二次验证")
        cas.referer = pageUrl
        val challenge = CasSmsChallenge(cas, page)
        if (!challenge.prepare()) throw Rejected(CasLoginDto.CaptchaRequired, "短信验证准备失败")
        val verified = prompter.sms(challenge) as? SmsDecision.Verified ?: throw Rejected(CasLoginDto.CaptchaRequired, "短信验证未完成")
        val response = session.request(page.formAction, fields = linkedMapOf(
            "username" to username,
            "password" to verified.code,
            "type" to "smsLogin",
            "_eventId" to "submit",
            "geolocation" to "",
            "execution" to page.execution,
            "captcha_code" to "",
            "trustDevice" to verified.trustDevice.toString(),
        ), referer = pageUrl)
        ticket(response, service)?.let { return it }
        BitAuthPages.loginFailure(response.body)?.let { throw Rejected(it, "短信验证后：" + BitAuthPages.describe(response.body)) }
        reject(response, "短信验证后未签发服务凭证")
    }

    private fun ticket(response: BitAuthResponse, service: HttpUrl): HttpUrl? {
        if (response.code !in 300..399) return null
        val callback = response.redirect() ?: return null
        if (callback.queryParameter("ticket").isNullOrBlank()) return null
        if (callback.origin() != service.origin() ||
            callback.encodedPath.trimEnd('/') != service.encodedPath.trimEnd('/')) {
            reject(response, "统一身份认证返回了不匹配的服务回调")
        }
        return callback
    }

    private suspend fun follow(session: BitAuthSession, start: HttpUrl, mode: NetworkMode, stage: String) {
        var next = session.urls.route(start, mode)
        var previous: HttpUrl? = null
        repeat(10) {
            val response = session.request(next, referer = previous)
            val redirect = if (response.code in 300..399) response.redirect() else null
            if (redirect != null) {
                previous = response.url
                next = session.urls.route(redirect, mode)
            } else {
                if (session.urls.isGatewayGate(response.url)) throw GatewayGate(response.url)
                if (response.code !in 200..299) reject(response, stage)
                if (session.urls.isCas(response.url) || BitAuthPages.isSessionFailure(response.body)) {
                    throw Rejected(
                        BitAuthPages.loginFailure(response.body) ?: CasLoginDto.CaptchaRequired,
                        "$stage 落到登录页 ${BitTrace.where(response.url)}",
                    )
                }
                val script = BitAuthPages.scriptRedirect(response.body)?.let { response.url.resolve(it) }
                if (script == null) return
                previous = response.url
                next = session.urls.route(script, mode)
            }
        }
        throw Rejected(CasLoginDto.UnknownFailure("$stage：重定向次数过多，请重新完成网页验证"))
    }

    private fun reject(response: BitAuthResponse, stage: String): Nothing = throw Rejected(
        CasLoginDto.UnknownFailure(response.diagnostic(stage), SchoolProblem.SERVER_BUSY.takeIf { response.code >= 500 }),
    )

    fun parseLoginPage(html: String): CasInitDto = CasInitDto(
        execution = BitAuthPages.idText(html, "login-page-flowkey") ?: error("统一身份认证页面缺少 flowkey"),
        salt = BitAuthPages.idText(html, "login-croypto") ?: error("统一身份认证页面缺少加密参数"),
    )
}
