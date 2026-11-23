package com.example.personal_studio.domain.bitddl

import com.example.personal_studio.data.network.bit.BitApiClient
import com.example.personal_studio.data.network.bit.NetworkMode
import com.example.personal_studio.data.network.bit.dto.CasLoginDto
import com.example.personal_studio.data.network.bit.service.BitLexueService
import com.example.personal_studio.domain.bitddl.model.DdlSyncError
import com.example.personal_studio.domain.bitddl.model.DdlSyncRequest
import com.example.personal_studio.domain.bitddl.model.LexueUrlResult
import com.example.personal_studio.domain.bitimport.SsoLoginUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.personal_studio.data.network.bit.SchoolPageException
import com.example.personal_studio.data.network.bit.SchoolProblem
import okhttp3.HttpUrl.Companion.toHttpUrl
import retrofit2.Response

class GenerateLexueIcalUrlUseCaseTest {

    private fun ok(body: String) = Response.success(body.toResponseBody("text/html".toMediaType()))
    private fun req() = DdlSyncRequest("u", "p", NetworkMode.LOCAL, true)

    /** SsoLoginUseCase.invoke is an `operator fun`; stub it via an explicit
     *  receiver so the call doesn't collide with MockK's matcher-scope DSL. */
    private fun ssoReturning(result: CasLoginDto): SsoLoginUseCase {
        val sso = mockk<SsoLoginUseCase>()
        coEvery { sso.loginLexue(any(), any(), any()) } returns result
        return sso
    }

    @Test fun `extractSesskey pulls value from moodle config json`() {
        val html = """...,"sesskey":"AbC123xyz",..."""
        assertEquals("AbC123xyz", GenerateLexueIcalUrlUseCase.extractSesskey(html))
    }

    @Test fun `extractIcalUrl pulls export_execute url and unescapes amp`() {
        val html = """<div class="calendarurl">https://lexue.bit.edu.cn/calendar/export_execute.php?userid=1&amp;authtoken=t&amp;preset_what=all</div>"""
        assertEquals(
            "https://lexue.bit.edu.cn/calendar/export_execute.php?userid=1&authtoken=t&preset_what=all",
            GenerateLexueIcalUrlUseCase.extractIcalUrl(html),
        )
    }

    @Test fun `a path-only address, as WebVPN may rewrite it, is resolved against the page`() {
        val page = "https://webvpn.bit.edu.cn/https/77726476706e69737468656265737421fcf25989227e6a596a468ca88d1b203b/calendar/export.php"
            .toHttpUrl()
        val html = """<a href="/https/77726476706e69737468656265737421fcf25989227e6a596a468ca88d1b203b/calendar/export_execute.php?authtoken=t&amp;userid=1">"""
        assertEquals(
            "https://webvpn.bit.edu.cn/https/77726476706e69737468656265737421fcf25989227e6a596a468ca88d1b203b/calendar/export_execute.php?authtoken=t&userid=1",
            GenerateLexueIcalUrlUseCase.extractIcalUrl(html, page),
        )
        assertEquals(null, GenerateLexueIcalUrlUseCase.extractIcalUrl(html))
    }

    @Test fun `an address shown through WebVPN is kept as lexue's own`() = runTest {
        val lexue = mockk<BitLexueService> {
            coEvery { getIndexHtml() } returns ok("""x"sesskey":"KEY1"x""")
            coEvery { exportCalendar(any(), any(), any(), any(), any()) } returns ok(
                """<div>https://webvpn.bit.edu.cn/https/77726476706e69737468656265737421fcf25989227e6a596a468ca88d1b203b/calendar/export_execute.php?authtoken=T</div>""",
            )
        }
        val api = mockk<BitApiClient>(relaxed = true) { coEvery { this@mockk.lexue } returns lexue }
        val r = GenerateLexueIcalUrlUseCase(api, ssoReturning(CasLoginDto.Success)).invoke(req())
        assertEquals("https://lexue.bit.edu.cn/calendar/export_execute.php?authtoken=T", (r as LexueUrlResult.Ok).url)
    }

    @Test fun `a bounced export page fails with its reason`() = runTest {
        val lexue = mockk<BitLexueService> {
            coEvery { getIndexHtml() } throws SchoolPageException(SchoolProblem.WEBVPN_SIGN_IN, "webvpn.bit.edu.cn/fingerprint")
        }
        val api = mockk<BitApiClient>(relaxed = true) { coEvery { this@mockk.lexue } returns lexue }
        val r = GenerateLexueIcalUrlUseCase(api, ssoReturning(CasLoginDto.Success)).invoke(req())
        assertEquals(DdlSyncError.School(SchoolProblem.WEBVPN_SIGN_IN), (r as LexueUrlResult.Failed).error)
    }

    @Test fun `wrong password returns Failed WrongCredentials`() = runTest {
        val sso = ssoReturning(CasLoginDto.WrongCredentials)
        val uc = GenerateLexueIcalUrlUseCase(mockk(relaxed = true), sso)
        val r = uc.invoke(req())
        assertTrue(r is LexueUrlResult.Failed)
        assertEquals(DdlSyncError.WrongCredentials, (r as LexueUrlResult.Failed).error)
    }

    @Test fun `happy path returns Ok url`() = runTest {
        val lexue = mockk<BitLexueService> {
            coEvery { getIndexHtml() } returns ok("""x"sesskey":"KEY1"x""")
            coEvery { exportCalendar(any(), any(), any(), any(), any()) } returns
                ok("""<div class="calendarurl">https://lexue.bit.edu.cn/calendar/export_execute.php?authtoken=T</div>""")
        }
        val api = mockk<BitApiClient>(relaxed = true) {
            coEvery { this@mockk.lexue } returns lexue
        }
        val sso = ssoReturning(CasLoginDto.Success)
        val r = GenerateLexueIcalUrlUseCase(api, sso).invoke(req())
        assertTrue(r is LexueUrlResult.Ok)
        assertEquals("https://lexue.bit.edu.cn/calendar/export_execute.php?authtoken=T", (r as LexueUrlResult.Ok).url)
    }

    @Test fun `uses independent lexue login without activating an unchecked CAS endpoint`() = runTest {
        val lexue = mockk<BitLexueService> {
            coEvery { getIndexHtml() } returns ok("""x"sesskey":"K"x""")
            coEvery { exportCalendar(any(), any(), any(), any(), any()) } returns
                ok("""<div class="calendarurl">https://lexue.bit.edu.cn/calendar/export_execute.php?authtoken=T</div>""")
        }
        val api = mockk<BitApiClient>(relaxed = true) {
            coEvery { this@mockk.lexue } returns lexue
        }
        val sso = ssoReturning(CasLoginDto.Success)
        GenerateLexueIcalUrlUseCase(api, sso).invoke(req())
        coVerify { sso.loginLexue(api, "u", "p") }
    }

    @Test fun `missing sesskey returns Failed ParseFail`() = runTest {
        val lexue = mockk<BitLexueService> { coEvery { getIndexHtml() } returns ok("<html>no key</html>") }
        val api = mockk<BitApiClient>(relaxed = true) {
            coEvery { this@mockk.lexue } returns lexue
        }
        val sso = ssoReturning(CasLoginDto.Success)
        val r = GenerateLexueIcalUrlUseCase(api, sso).invoke(req())
        assertTrue(r is LexueUrlResult.Failed)
        assertTrue((r as LexueUrlResult.Failed).error is DdlSyncError.ParseFail)
    }
}
