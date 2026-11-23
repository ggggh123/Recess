package com.example.personal_studio.feature.kejian

import com.example.personal_studio.data.local.datastore.LocalFeedback
import com.example.personal_studio.data.remote.kejian.ApkInfo
import com.example.personal_studio.data.remote.kejian.FeedbackStatus
import com.example.personal_studio.data.remote.kejian.IssueRef
import com.example.personal_studio.data.remote.kejian.LatestRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KejianLogicTest {

    private fun rel(code: Int) = LatestRelease(code, "1.$code", apk = ApkInfo("k.apk", 1, "a".repeat(64)))
    private fun fb(id: Long?, reply: String? = null, replyAt: Long? = null, seen: Long? = null, notified: Long? = null, created: Long = 1_000) =
        LocalFeedback(
            localId = "l$id", kind = "bug", body = "x", createdAt = created,
            serverId = id, token = id?.let { "t$it" }, reply = reply, replyAt = replyAt, replySeenAt = seen, replyNotifiedAt = notified,
        )

    @Test fun `update prompt only for a newer, not-dismissed version with auto check on`() {
        assertTrue(KejianLogic.shouldPrompt(rel(110), 100, autoCheck = true, dismissed = 0))
        assertFalse(KejianLogic.shouldPrompt(rel(100), 100, true, 0))
        assertFalse(KejianLogic.shouldPrompt(rel(110), 100, false, 0))
        assertFalse(KejianLogic.shouldPrompt(rel(110), 100, true, 110))
        assertTrue(KejianLogic.shouldPrompt(rel(120), 100, true, 110)) // 跳过的是旧的那个版本
        assertFalse(KejianLogic.shouldPrompt(null, 100, true, 0))
    }

    @Test fun `fingerprints compare without colons and case`() {
        assertTrue(KejianLogic.sameFingerprint("EC:74:66", "ec7466"))
        assertFalse(KejianLogic.sameFingerprint("EC:74:66", "EC:74:67"))
        assertFalse(KejianLogic.sameFingerprint(null, "EC"))
        assertEquals(95, KejianLogic.certFingerprint(byteArrayOf(1, 2, 3)).length) // 32 字节 → 64 位十六进制 + 31 个冒号
    }

    @Test fun `new reply is reported once, then not again`() {
        val local = listOf(fb(1))
        val first = KejianLogic.merge(local, listOf(FeedbackStatus(id = 1, status = "replied", reply = "好的", replyAt = 5_000)))
        assertEquals(1, first.newReplies.size)
        assertEquals(5_000L, first.items[0].replyNotifiedAt)
        val again = KejianLogic.merge(first.items, listOf(FeedbackStatus(id = 1, status = "replied", reply = "好的", replyAt = 5_000)))
        assertTrue(again.newReplies.isEmpty())
        // 开发者改了回复（新的时间）→ 再提醒一次
        val edited = KejianLogic.merge(again.items, listOf(FeedbackStatus(id = 1, status = "replied", reply = "更新：已修好", replyAt = 9_000)))
        assertEquals(1, edited.newReplies.size)
    }

    @Test fun `reply already seen in app is not notified`() {
        val m = KejianLogic.merge(listOf(fb(1, seen = 6_000)), listOf(FeedbackStatus(id = 1, reply = "好的", replyAt = 5_000)))
        assertTrue(m.newReplies.isEmpty())
    }

    @Test fun `deleted on server disappears locally, unsent stays`() {
        val m = KejianLogic.merge(listOf(fb(1), fb(null)), listOf(FeedbackStatus(id = 1, gone = true)))
        assertEquals(listOf("lnull"), m.items.map { it.localId })
    }

    @Test fun `fixed issue notifies once`() {
        val fixed = FeedbackStatus(id = 1, status = "read", issue = IssueRef(3, "刷新空白", "fixed", "1.1.0"))
        val a = KejianLogic.merge(listOf(fb(1)), listOf(fixed))
        assertEquals(1, a.newlyFixed.size)
        assertTrue(KejianLogic.merge(a.items, listOf(fixed)).newlyFixed.isEmpty())
    }

    @Test fun `polling stops for closed, unsent or old feedback`() {
        val now = 100L * 24 * 3600 * 1000
        assertTrue(KejianLogic.needsPolling(fb(1, created = now - 1000), now))
        assertFalse(KejianLogic.needsPolling(fb(null, created = now - 1000), now))
        assertFalse(KejianLogic.needsPolling(fb(1, created = 0), now))
        assertFalse(KejianLogic.needsPolling(fb(1, created = now - 1000).copy(status = "closed"), now))
    }

    @Test fun `an announcement is new once, records keep only live ids`() {
        fun a(id: Long) = com.example.personal_studio.data.remote.kejian.Announcement(id, "公告$id")
        val first = KejianLogic.announcementBook(listOf(a(1), a(2)), notified = emptySet(), seen = emptySet())
        assertEquals(listOf(1L, 2L), first.fresh.map { it.id })
        // 同一批再来一次：不再算新
        val again = KejianLogic.announcementBook(listOf(a(1), a(2)), first.notified, first.seen)
        assertTrue(again.fresh.isEmpty())
        // 看过的不提醒；下线的 2 从记录里去掉，新上线的 3 算新
        val next = KejianLogic.announcementBook(listOf(a(1), a(3)), notified = setOf("1", "2"), seen = setOf("1", "2", "9"))
        assertEquals(listOf(3L), next.fresh.map { it.id })
        assertEquals(setOf("1", "3"), next.notified)
        assertEquals(setOf("1"), next.seen)
    }

    @Test fun `beijing day rolls over at 16 00 UTC`() {
        assertEquals("2026-11-21", KejianLogic.bjDay(java.time.Instant.parse("2026-11-21T15:59:00Z").toEpochMilli()))
        assertEquals("2026-11-22", KejianLogic.bjDay(java.time.Instant.parse("2026-11-21T16:00:00Z").toEpochMilli()))
    }
}
