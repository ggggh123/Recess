package com.example.personal_studio.data.network.bit

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Between syncs a carrier NAT, or a switch between Wi-Fi and mobile data, silently drops idle
 * connections; a request sent on one waits out the whole read timeout. The server here plays
 * such a path: it never answers a request that arrives on a connection used before.
 */
class BitConnectionReuseTest {
    private val server = MockWebServer()
    private val jar = BitCookieJar()
    private lateinit var client: OkHttpClient
    private lateinit var api: BitApiClient

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.sequenceNumber > 0) MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                else MockResponse().setBody("ok")
        }
        server.start()
        client = OkHttpClient.Builder().cookieJar(jar).readTimeout(500, TimeUnit.MILLISECONDS).build()
        api = BitApiClient(client, jar)
    }

    @After fun tearDown() {
        client.connectionPool.evictAll()
        server.shutdown()
    }

    private fun get(): String = client.newCall(Request.Builder().url(server.url("/cas/login")).build())
        .execute().use { it.body!!.string() }

    @Test fun `a kept idle connection stalls the next request until the read timeout`() {
        assertEquals("ok", get())
        try {
            get()
            fail("the reused connection should have stalled")
        } catch (_: SocketTimeoutException) {
        }
    }

    @Test fun `closing a session drops idle connections, so the next session starts on a new one`() {
        api.open(NetworkMode.LOCAL)
        assertEquals("ok", get())
        assertEquals(1, client.connectionPool.idleConnectionCount())
        api.close()
        assertEquals(0, client.connectionPool.idleConnectionCount())

        api.open(NetworkMode.LOCAL)
        assertEquals("ok", get())
        assertEquals(0, server.takeRequest().sequenceNumber)
        assertEquals(0, server.takeRequest().sequenceNumber)
    }
}
