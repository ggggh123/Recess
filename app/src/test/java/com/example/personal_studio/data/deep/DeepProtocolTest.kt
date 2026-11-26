package com.example.personal_studio.data.deep

import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class DeepProtocolTest {
    @Test fun splitUtf8CrLfHeartbeatsMultilineAndIds() {
        val bytes = Buffer().writeUtf8(": heartbeat\r\n\r\nid: 17\r\nevent: text.delta\r\ndata: {\r\ndata: \"text\":\"中文\"}\r\n\r\n" +
            ": heartbeat\n\nid:18\nevent:notice\ndata:{}\n\n")
        val source = object : ForwardingSource(bytes) {
            override fun read(sink: Buffer, byteCount: Long) = super.read(sink, minOf(1L, byteCount))
        }.buffer()
        val event = readEvent(source)!!
        assertEquals(17L, event.seq)
        assertEquals("text.delta", event.type)
        assertEquals("中文", event.data.field("text"))
        assertEquals(18L, readEvent(source)!!.seq)
        assertNull(readEvent(source))
    }

    @Test fun incompleteFrameIsNotDelivered() {
        assertNull(readEvent(Buffer().writeUtf8("id: 3\nevent: error\ndata: {}\n")))
    }

    @Test fun fitsLandscapePortraitPanoramaAndSmallImages() {
        assertEquals(ImageSize(2048, 1536, 4), jpegSize(8192, 6144))
        assertEquals(ImageSize(1024, 2048, 2), jpegSize(3000, 6000))
        assertEquals(ImageSize(2048, 1, 32), jpegSize(100000, 1))
        assertEquals(ImageSize(320, 240, 1), jpegSize(320, 240))
        assertEquals(ImageSize(2048, 2048, 1), jpegSize(2048, 2048))
    }
}
