package com.freebuff.core.model

import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 会话时间分组与列表行时间文案。全部按「本地日」切分,时区偏移显式传入保证可测。 */
class SessionGroupingTest {

    /** 指定时区下的本地时刻 → epoch ms。 */
    private fun ts(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0, tzHours: Int = 0): Long =
        LocalDateTime.of(y, mo, d, h, mi).toInstant(ZoneOffset.ofHours(tzHours)).toEpochMilli()

    private fun session(id: String, createdAt: Long) =
        Session(id, "会话 $id", "Just now", "", emptyList(), createdAt)

    private val now = ts(2026, 9, 26, 10, 0)

    @Test
    fun `今天 昨天 一周内 更早 各归其桶`() {
        assertEquals(SessionBucket.TODAY, sessionBucketOf(ts(2026, 9, 26, 0, 5), now, 0))
        assertEquals(SessionBucket.YESTERDAY, sessionBucketOf(ts(2026, 9, 25, 23, 59), now, 0))
        assertEquals(SessionBucket.WEEK, sessionBucketOf(ts(2026, 9, 20, 10, 0), now, 0))     // 6 天前
        assertEquals(SessionBucket.WEEK, sessionBucketOf(ts(2026, 9, 19, 10, 0), now, 0))     // 边界:第 7 天仍算一周内
        assertEquals(SessionBucket.EARLIER, sessionBucketOf(ts(2026, 9, 18, 10, 0), now, 0))  // 第 8 天落到更早
        assertEquals(SessionBucket.EARLIER, sessionBucketOf(ts(2025, 9, 19, 10, 0), now, 0))
    }

    @Test
    fun `没有时间戳的旧会话归入更早`() {
        assertEquals(SessionBucket.EARLIER, sessionBucketOf(0L, now, 0))
        assertEquals(SessionBucket.EARLIER, sessionBucketOf(-1L, now, 0))
    }

    @Test
    fun `时钟回拨导致的未来时间算今天`() {
        assertEquals(SessionBucket.TODAY, sessionBucketOf(now + 3_600_000L, now, 0))
    }

    @Test
    fun `按本地日切桶,时区偏移会改变归属`() {
        val tz8 = 8 * 3600 * 1000
        // 同一对时刻:按 +08:00 的本地日是 09-25 20:00 → 09-26 01:00(跨天)
        val created = ts(2026, 9, 25, 20, 0, 8)
        val at = ts(2026, 9, 26, 1, 0, 8)
        assertEquals(SessionBucket.YESTERDAY, sessionBucketOf(created, at, tz8))
        // 忽略时区(按 UTC 切日)则落在同一天 —— 证明分桶吃的是本地日,不是原始时长
        assertEquals(SessionBucket.TODAY, sessionBucketOf(created, at, 0))
    }

    @Test
    fun `分组只保留非空桶且组内保持输入顺序`() {
        val a = session("a", ts(2026, 9, 26, 9, 0))
        val b = session("b", ts(2026, 9, 10, 9, 0))
        val c = session("c", ts(2026, 9, 26, 8, 0))
        val d = session("d", ts(2026, 9, 25, 9, 0))
        val sections = groupSessions(listOf(a, b, c, d), now, 0)
        assertEquals(
            listOf(SessionBucket.TODAY, SessionBucket.YESTERDAY, SessionBucket.EARLIER),
            sections.map { it.bucket },
        )
        assertEquals(listOf("a", "c"), sections[0].sessions.map { it.id })
        assertEquals(listOf("d"), sections[1].sessions.map { it.id })
        assertEquals(listOf("b"), sections[2].sessions.map { it.id })
        assertEquals("Today", sections[0].label)
        assertEquals("Earlier", sections[2].label)
    }

    @Test
    fun `空列表分组为空`() {
        assertTrue(groupSessions(emptyList(), now, 0).isEmpty())
    }

    @Test
    fun `时间文案覆盖刚刚 分钟前 今天时刻 昨天 天数与日期`() {
        assertEquals("Just now", sessionTimeLabel(session("a", now - 30_000L), now, 0))
        assertEquals("10 min ago", sessionTimeLabel(session("a", now - 600_000L), now, 0))
        assertEquals("01:05", sessionTimeLabel(session("a", ts(2026, 9, 26, 1, 5)), now, 0))
        assertEquals("Yesterday", sessionTimeLabel(session("a", ts(2026, 9, 25, 9, 0)), now, 0))
        assertEquals("3 days ago", sessionTimeLabel(session("a", ts(2026, 9, 23, 9, 0)), now, 0))
        assertEquals("09-10", sessionTimeLabel(session("a", ts(2026, 9, 10, 9, 0)), now, 0))
        assertEquals("2025-12-31", sessionTimeLabel(session("a", ts(2025, 12, 31, 9, 0)), now, 0))
    }

    @Test
    fun `旧会话不编造时间,统一显示较早`() {
        assertEquals("Earlier", sessionTimeLabel(session("a", 0L), now, 0))
    }
}
