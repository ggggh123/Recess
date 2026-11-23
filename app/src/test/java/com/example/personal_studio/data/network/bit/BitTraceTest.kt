package com.example.personal_studio.data.network.bit

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The trace may be read off a phone with adb, so it must never carry account data or tokens. */
class BitTraceTest {
    @Test fun `only host, shortened path and query names are written`() {
        val line = BitTrace.where(
            "https://webvpn.bit.edu.cn/https/77726476706e69737468656265737421fcf25989227e6a596a468ca88d1b203b/calendar/export_execute.php?userid=12345&authtoken=SECRET".toHttpUrl(),
        )
        assertEquals("webvpn.bit.edu.cn/https/…/calendar/export_execute.php?authtoken,userid", line)
        assertFalse(line.contains("SECRET"))
    }

    @Test fun `a student number in a CAS path is hidden`() {
        val line = BitTrace.where("https://sso.bit.edu.cn/cas/api/protected/user/findCaptchaCount/1120230001".toHttpUrl())
        assertEquals("sso.bit.edu.cn/cas/api/protected/user/findCaptchaCount/#", line)
    }
}
