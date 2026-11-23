package com.example.personal_studio.data.network.bit

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Where a data request really ended decides the reason a sync shows. */
class SchoolPagesTest {
    private val urls = BitAuthUrls()
    private val guard = SchoolPageGuard(urls)
    private val vpnJwapp = BitUrlsConfig.WEBVPN.jwapp
    private val vpnJwms = BitUrlsConfig.WEBVPN.jwms

    private fun req(url: String) = Request.Builder().url(url).build()
    private fun problem(request: String, final: String = request, code: Int = 200, head: String = "") =
        guard.problem(req(request), final.toHttpUrl(), code, head)

    @Test fun `a request bounced to WebVPN's device check or sign-in needs the gateway signed in`() {
        val term = vpnJwapp + "jwapp/sys/wdkbby/modules/jshkcb/dqxnxq.do"
        assertEquals(SchoolProblem.WEBVPN_SIGN_IN, problem(term, "https://webvpn.bit.edu.cn/fingerprint"))
        assertEquals(SchoolProblem.WEBVPN_SIGN_IN, problem(vpnJwms + "jsxsd/kscj/cjcx_list", "https://webvpn.bit.edu.cn/login"))
        // Any school data behind the gateway, 乐学's calendar included.
        assertEquals(
            SchoolProblem.WEBVPN_SIGN_IN,
            problem(BitUrlsConfig.WEBVPN.lexue + "calendar/export_execute.php", "https://webvpn.bit.edu.cn/fingerprint"),
        )
    }

    @Test fun `the CAS sign-in page where data was expected means the session ended`() {
        assertEquals(
            SchoolProblem.SESSION_EXPIRED,
            problem(vpnJwms + "jsxsd/kscj/cjcx_list", "https://sso.bit.edu.cn/cas/login?service=x"),
        )
        // CAS reached through the gateway counts too.
        val proxiedCas = "https://webvpn.bit.edu.cn/https/77726476706e69737468656265737421e3e44ed225397c1e7b0c9ce29b5b/cas/login"
        assertEquals(SchoolProblem.SESSION_EXPIRED, problem(vpnJwms + "jsxsd/kscj/cjcx_list", proxiedCas))
        assertEquals(
            SchoolProblem.SESSION_EXPIRED,
            problem("https://jxzxehallapp.bit.edu.cn/jwapp/sys/wdkbby/modules/jshkcb/dqxnxq.do",
                head = "<html><form id=\"normalLoginForm\"></form></html>"),
        )
    }

    @Test fun `server errors and busy pages say the school is busy`() {
        assertEquals(SchoolProblem.SERVER_BUSY, problem(vpnJwms + "jsxsd/kscj/cjcx_list", code = 502))
        assertEquals(
            SchoolProblem.SERVER_BUSY,
            problem("https://jxzxehallapp.bit.edu.cn/jwapp/sys/wdkbby/modules/jshkcb/dqxnxq.do", head = "<html>系统繁忙，请稍后再试</html>"),
        )
    }

    @Test fun `HTML where jwapp answers JSON is an unexpected page`() {
        assertEquals(
            SchoolProblem.UNEXPECTED_PAGE,
            problem("https://jxzxehallapp.bit.edu.cn/jwapp/sys/wdkbby/modules/jshkcb/dqxnxq.do", head = "<!DOCTYPE html><p>维护通知</p>"),
        )
    }

    @Test fun `data pages, HTML entry pages and other systems pass`() {
        assertNull(problem("https://jxzxehallapp.bit.edu.cn/jwapp/sys/wdkbby/modules/jshkcb/dqxnxq.do", head = """{"datas":{}}"""))
        assertNull(problem("https://jxzxehallapp.bit.edu.cn/jwapp/sys/wdksapMobile/*default/index.do", head = "<html>入口</html>"))
        // The grade list is HTML; its own checks (评教, missing table) run later.
        assertNull(problem(vpnJwms + "jsxsd/kscj/cjcx_list", head = "<table id=\"dataList\">"))
        // 新乐学 reaching CAS just means "sign in first"; its own flow handles that.
        assertNull(problem("https://zy-eclass.bit.edu.cn/api/my-semesters", "https://sso.bit.edu.cn/cas/login"))
        // A client error on a data page is left to the caller.
        assertNull(problem(vpnJwms + "jsxsd/kscj/cjcx_list", code = 404))
    }

    @Test fun `gateway helpers know its entry, device check and the address it submits`() {
        assertEquals("https://webvpn.bit.edu.cn/login", urls.gatewayEntry().toString())
        assertTrue(urls.isGatewayFingerprint("https://webvpn.bit.edu.cn/fingerprint".toHttpUrl()))
        assertTrue(urls.isGatewayGate("https://webvpn.bit.edu.cn/login?cas_login=true".toHttpUrl()))
        assertFalse(urls.isGatewayGate("https://webvpn.bit.edu.cn/".toHttpUrl()))
        assertFalse(urls.isGatewayGate("https://sso.bit.edu.cn/fingerprint".toHttpUrl()))
        assertFalse(urls.isGatewayGate((vpnJwms + "login").toHttpUrl()))
        // Like the page's script: its own query, plus the fingerprint.
        val submit = urls.fingerprintSubmit("https://webvpn.bit.edu.cn/fingerprint?from=x".toHttpUrl(), "0123456789abcdef0123456789abcdef")
        assertEquals("/set-fingerprint", submit.encodedPath)
        assertEquals("x", submit.queryParameter("from"))
        assertEquals("0123456789abcdef0123456789abcdef", submit.queryParameter("fingerprint"))
    }

    @Test fun `gateway addresses map back to the school system they stand for`() {
        assertEquals(
            "https://lexue.bit.edu.cn/calendar/export_execute.php?authtoken=t&userid=1",
            urls.unroute((BitUrlsConfig.WEBVPN.lexue + "calendar/export_execute.php?authtoken=t&userid=1").toHttpUrl()).toString(),
        )
        assertEquals("http://jwms.bit.edu.cn/jsxsd/", urls.unroute((vpnJwms + "jsxsd/").toHttpUrl()).toString())
        val direct = "https://lexue.bit.edu.cn/".toHttpUrl()
        assertEquals(direct, urls.unroute(direct))
        // 乐学 is routed through the gateway off campus, and only then.
        val ics = "https://lexue.bit.edu.cn/calendar/export_execute.php?authtoken=t".toHttpUrl()
        assertEquals(BitUrlsConfig.WEBVPN.lexue + "calendar/export_execute.php?authtoken=t", urls.route(ics, NetworkMode.WEBVPN).toString())
        assertEquals(ics, urls.route(ics, NetworkMode.LOCAL))
    }

    @Test fun `the guard turns a bounced data request into its reason and leaves sign-in requests alone`() {
        val server = MockWebServer().apply { start() }
        try {
            val local = BitAuthUrls(gatewayCallback = server.url("/login?cas_login=true").toString())
            val client = OkHttpClient.Builder().addInterceptor(SchoolPageGuard(local))
                .callTimeout(5, TimeUnit.SECONDS).build()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/fingerprint"))
            server.enqueue(MockResponse().setBody("<script>FingerprintJS.load()</script>"))
            try {
                client.newCall(req(server.url("/https/x/jwapp/sys/wdkbby/modules/jshkcb/dqxnxq.do").toString())).execute().close()
                fail("expected the reason")
            } catch (e: SchoolPageException) {
                assertEquals(SchoolProblem.WEBVPN_SIGN_IN, e.problem)
            }
            server.enqueue(MockResponse().setBody("<script>FingerprintJS.load()</script>"))
            val signIn = Request.Builder().url(server.url("/fingerprint")).tag(BitAuthFlow::class.java, BitAuthFlow).build()
            client.newCall(signIn).execute().use { assertEquals(200, it.code) }
        } finally {
            server.shutdown()
        }
    }

    @Test fun `diagnostic page kinds`() {
        val gate = "https://webvpn.bit.edu.cn/fingerprint".toHttpUrl()
        assertEquals(BitPageKind.WEBVPN_LOGIN, BitPageKind.of(gate, "text/html", "<html><script>FingerprintJS</script>"))
        val data = "https://jxzxehallapp.bit.edu.cn/jwapp/x.do".toHttpUrl()
        assertEquals(BitPageKind.JSON, BitPageKind.of(data, "application/json", """ {"a":1}"""))
        assertEquals(BitPageKind.CALENDAR, BitPageKind.of(data, "text/calendar", "BEGIN:VCALENDAR"))
        assertEquals(BitPageKind.ERROR_PAGE, BitPageKind.of(data, "text/html", "<h1>502 Bad Gateway</h1>"))
        assertEquals(BitPageKind.EMPTY, BitPageKind.of(data, "", "  "))
    }
}
