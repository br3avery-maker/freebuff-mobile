package com.freebuff.core.model

import java.time.Instant
import java.time.ZoneOffset
import java.util.TimeZone

/**
 * 会话列表的时间分组。仓库已按 sort DESC(新会话在前)排序,
 * 这里按创建时刻切「本地日」分桶,分组顺序与列表顺序一致,不会出现组内跳序。
 */
enum class SessionBucket(val label: String) {
    TODAY("今天"),
    YESTERDAY("昨天"),
    WEEK("7 天内"),
    EARLIER("更早"),
}

/** 一个分组及其会话(组内顺序沿用输入顺序)。 */
data class SessionSection(val bucket: SessionBucket, val sessions: List<Session>) {
    val label: String get() = bucket.label
}

private const val DAY_MS = 86_400_000L

/** 当前时区偏移(毫秒)。分组与时间文案都按本地日切,跨时区/夏令时不会错位。 */
fun zoneOffsetMillis(now: Long): Int = TimeZone.getDefault().getOffset(now)

/** 本地日序号(把本地时刻按天取整;floorDiv 让 1970 之前的时刻也对)。 */
private fun dayIndex(at: Long, zoneOffset: Int): Long = Math.floorDiv(at + zoneOffset, DAY_MS)

/** from → to 相差几个本地日(同日为 0)。 */
fun localDayDiff(from: Long, to: Long, zoneOffset: Int): Long =
    dayIndex(to, zoneOffset) - dayIndex(from, zoneOffset)

/**
 * 按创建时刻分桶。createdAt <= 0(旧版落库、没有时间戳)一律算「更早」;
 * 未来时间(时钟回拨)按「今天」处理,不会掉进「更早」。
 */
fun sessionBucketOf(createdAt: Long, now: Long, zoneOffset: Int): SessionBucket {
    if (createdAt <= 0L) return SessionBucket.EARLIER
    val days = localDayDiff(createdAt, now, zoneOffset)
    return when {
        days < 1L -> SessionBucket.TODAY       // 含时钟回拨导致的「未来时间」
        days == 1L -> SessionBucket.YESTERDAY
        days <= 7L -> SessionBucket.WEEK
        else -> SessionBucket.EARLIER
    }
}

/**
 * 分组:只保留有会话的分组,顺序固定 今天 → 昨天 → 7 天内 → 更早。
 * 搜索命中集同样适用(时间分组对结果集依旧有意义)。
 */
fun groupSessions(
    sessions: List<Session>,
    now: Long,
    zoneOffset: Int = zoneOffsetMillis(now),
): List<SessionSection> = SessionBucket.entries
    .map { b -> SessionSection(b, sessions.filter { sessionBucketOf(it.createdAt, now, zoneOffset) == b }) }
    .filter { it.sessions.isNotEmpty() }

/**
 * 列表行上的时间文案:近期用相对时间(<1 分钟 / N 分钟前),当天用 时:分,
 * 昨天用「昨天」,一周内用「N 天前」,更早用日期(跨年带年份)。
 * 没有时间戳的旧会话不编造时间,统一显示「较早」。
 */
fun sessionTimeLabel(
    session: Session,
    now: Long,
    zoneOffset: Int = zoneOffsetMillis(now),
): String {
    val at = session.createdAt
    if (at <= 0L) return "较早"
    val diff = now - at
    if (diff < 60_000L) return "刚刚"
    if (diff < 3_600_000L) return (diff / 60_000L).toString() + " 分钟前"
    return when (val days = localDayDiff(at, now, zoneOffset)) {
        in Long.MIN_VALUE..0L -> clockLabel(at, zoneOffset)
        1L -> "昨天"
        in 2L..7L -> "$days 天前"
        else -> dateLabel(at, now, zoneOffset)
    }
}

/** 本地时刻 → HH:mm。 */
private fun clockLabel(at: Long, zoneOffset: Int): String {
    val local = localTime(at, zoneOffset)
    return two(local.hour) + ":" + two(local.minute)
}

/** 更早的会话显示日期:同年 MM-dd,跨年 yyyy-MM-dd。 */
private fun dateLabel(at: Long, now: Long, zoneOffset: Int): String {
    val a = localTime(at, zoneOffset)
    val n = localTime(now, zoneOffset)
    val md = two(a.monthValue) + "-" + two(a.dayOfMonth)
    return if (a.year == n.year) md else a.year.toString() + "-" + md
}

private fun localTime(at: Long, zoneOffset: Int) = Instant.ofEpochMilli(at)
    .atOffset(ZoneOffset.ofTotalSeconds(zoneOffset / 1000))
    .toLocalDateTime()

private fun two(v: Int): String = if (v < 10) "0$v" else v.toString()
