package com.example.personal_studio.domain.bitddl

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.example.personal_studio.data.network.bit.SchoolPageException
import com.example.personal_studio.data.network.bit.BitApiClient
import com.example.personal_studio.data.network.bit.BitAuthUrls
import com.example.personal_studio.data.network.bit.dto.CasLoginDto
import com.example.personal_studio.domain.bitddl.model.DdlSyncError
import com.example.personal_studio.domain.bitddl.model.DdlSyncRequest
import com.example.personal_studio.domain.bitddl.model.LexueUrlResult
import com.example.personal_studio.domain.bitimport.SsoLoginUseCase
import java.io.IOException
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/**
 * 一次性派生乐学 iCal 订阅 URL。假设 apiClient 已 open(由调用方管 open/close)。
 * 流程:CAS 登录 → 激活乐学服务会话 → 抠 sesskey → POST 导出 → 抠 .ics URL。
 */
class GenerateLexueIcalUrlUseCase @Inject constructor(
    private val apiClient: BitApiClient,
    private val ssoLogin: SsoLoginUseCase,
) {
    suspend fun invoke(req: DdlSyncRequest): LexueUrlResult {
        return try {
            val login = ssoLogin.loginLexue(apiClient, req.username, req.password)
            login.toDdlError()?.let { return LexueUrlResult.Failed(it) }
            val indexHtml = (apiClient.lexue.getIndexHtml().let { it.body() ?: it.errorBody() })?.string().orEmpty()
            val sesskey = extractSesskey(indexHtml)
                ?: return LexueUrlResult.Failed(DdlSyncError.ParseFail("no sesskey"))
            val export = apiClient.lexue.exportCalendar(sesskey)
            val exportHtml = (export.body() ?: export.errorBody())?.string().orEmpty()
            val url = extractIcalUrl(exportHtml, export.raw().request.url)
                ?: return LexueUrlResult.Failed(DdlSyncError.ParseFail("no calendar url"))
            // Through WebVPN the page may show the gateway's address; keep 乐学's own.
            LexueUrlResult.Ok(url.toHttpUrlOrNull()?.let { SCHOOL_URLS.unroute(it).toString() } ?: url)
        } catch (e: SchoolPageException) {
            LexueUrlResult.Failed(DdlSyncError.School(e.problem))
        } catch (io: IOException) {
            LexueUrlResult.Failed(DdlSyncError.NetworkFail(io.message ?: "io"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            LexueUrlResult.Failed(DdlSyncError.Unexpected(e.message ?: e.javaClass.simpleName))
        }
    }

    private fun CasLoginDto.toDdlError(): DdlSyncError? = when (this) {
        CasLoginDto.Success -> null
        CasLoginDto.WrongCredentials -> DdlSyncError.WrongCredentials
        CasLoginDto.AccountLocked -> DdlSyncError.AccountLocked
        CasLoginDto.CaptchaRequired -> DdlSyncError.CaptchaRequired
        is CasLoginDto.UnknownFailure -> problem?.let { DdlSyncError.School(it) } ?: DdlSyncError.ParseFail("CAS: $body")
    }

    companion object {
        private val SCHOOL_URLS = BitAuthUrls()
        private val SESSKEY = Regex("""["']sesskey["']\s*:\s*["']([^"']+)["']""")
        private val ICAL_URL = Regex("""(?:https?://[^\s"'<>]*?)?/[^\s"'<>]*export_execute\.php[^\s"'<>]*""")

        fun extractSesskey(html: String): String? = SESSKEY.find(html)?.groupValues?.get(1)

        /** The subscription address on the export page; a path (as WebVPN may rewrite it) is resolved against [page]. */
        fun extractIcalUrl(html: String, page: HttpUrl? = null): String? {
            val found = ICAL_URL.find(html)?.value?.replace("&amp;", "&") ?: return null
            return if (found.startsWith("http://") || found.startsWith("https://")) found else page?.resolve(found)?.toString()
        }
    }
}
