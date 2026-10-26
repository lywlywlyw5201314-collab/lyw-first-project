package com.example.notifyledger.util

import java.time.LocalDate
import java.time.ZoneId

/** 记账日期口径: 一律 Asia/Shanghai(与 web 端一致) */
object TimeUtil {
    val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 今天的日期串 yyyy-MM-dd(用于 LedgerEntry.day / 统计查询) */
    fun todayDay(): String = LocalDate.now(ZONE).toString()

    /** 最近 n 天(含今天)的日期串, 升序 */
    fun lastNDays(n: Int): List<String> {
        val today = LocalDate.now(ZONE)
        return (n - 1 downTo 0).map { today.minusDays(it.toLong()).toString() }
    }

    /** 本周一(周一为一周起点, 与 web 端统计口径一致) */
    fun mondayOfToday(): String {
        val today = LocalDate.now(ZONE)
        val dow = today.dayOfWeek.value // Mon=1
        return today.minusDays((dow - 1).toLong()).toString()
    }

    /** 本月 1 日 */
    fun firstOfMonth(): String = LocalDate.now(ZONE).withDayOfMonth(1).toString()
}
