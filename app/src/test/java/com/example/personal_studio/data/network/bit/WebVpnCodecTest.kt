package com.example.personal_studio.data.network.bit

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebVpnCodecTest {
    private fun segment(base: String) = base.toHttpUrl().pathSegments[1]

    @Test fun `encoding reproduces every gateway prefix in use`() {
        assertEquals(segment(BitUrlsConfig.WEBVPN.jwapp), WebVpnCodec.encodeHost("jxzxehallapp.bit.edu.cn"))
        assertEquals(segment(BitUrlsConfig.WEBVPN.jwms), WebVpnCodec.encodeHost("jwms.bit.edu.cn"))
        assertEquals(segment(BitUrlsConfig.WEBVPN.lexue), WebVpnCodec.encodeHost("lexue.bit.edu.cn"))
        assertEquals(segment(BitAuthUrls().proxyBases.getValue("https://jxzxehall.bit.edu.cn")), WebVpnCodec.encodeHost("jxzxehall.bit.edu.cn"))
        // The gateway's own redirect to CAS, seen on a real device.
        assertEquals("77726476706e69737468656265737421e3e44ed225397c1e7b0c9ce29b5b", WebVpnCodec.encodeHost("sso.bit.edu.cn"))
    }

    @Test fun `decoding names the host, and refuses what is not an encoded host`() {
        assertEquals("lexue.bit.edu.cn", WebVpnCodec.decodeHost(segment(BitUrlsConfig.WEBVPN.lexue)))
        assertEquals("jxzxehallapp.bit.edu.cn", WebVpnCodec.decodeHost(segment(BitUrlsConfig.WEBVPN.jwapp)))
        assertNull(WebVpnCodec.decodeHost("campus"))
        assertNull(WebVpnCodec.decodeHost(WebVpnCodec.IV_HEX))
        assertNull(WebVpnCodec.decodeHost(WebVpnCodec.IV_HEX + "zz"))
    }

    @Test fun `original keeps scheme, port, path, query and fragment`() {
        val host = WebVpnCodec.encodeHost("jwms.bit.edu.cn")
        assertEquals(
            "http://jwms.bit.edu.cn:8080/jsxsd/kscj/cjfx?a=1&b=%2B#f",
            WebVpnCodec.original("https://webvpn.bit.edu.cn/http-8080/$host/jsxsd/kscj/cjfx?a=1&b=%2B#f".toHttpUrl(), "webvpn.bit.edu.cn").toString(),
        )
        assertNull(WebVpnCodec.original("https://webvpn.bit.edu.cn/login".toHttpUrl(), "webvpn.bit.edu.cn"))
        assertNull(WebVpnCodec.original("https://other.test/https/$host/".toHttpUrl(), "webvpn.bit.edu.cn"))
    }
}
