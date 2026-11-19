package com.liubai.guard

import android.content.Context
import java.util.Calendar

data class Row(val pkg: String, val usedMin: Int, val limit: Int, val tomorrow: Int?) {
    val left get() = (limit - usedMin).coerceAtLeast(0)
    val done get() = usedMin >= limit
    val frac get() = if (limit <= 0) 1f else usedMin.toFloat() / limit
}

object Stats {
    fun rows(c: Context): List<Row> {
        val a = Store.active(c)
        val d = Store.desired(c)
        return a.map { (pkg, lim) ->
            val used = (Store.usedMs(c, pkg) / 60_000L).toInt().coerceAtMost(lim)
            val t = d[pkg]
            Row(pkg, used, lim, if (t == lim) null else (t ?: 0))
        }
    }
    fun totals(rows: List<Row>): Pair<Int, Int> = rows.sumOf { it.usedMin } to rows.sumOf { it.limit }

    fun untilMidnight(): String {
        val now = Calendar.getInstance()
        val m = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }
        val mins = ((m.timeInMillis - now.timeInMillis) / 60_000L).toInt() + 1
        val h = mins / 60; val mm = mins % 60
        return if (h > 0) tr("{0} 小时 {1} 分", h, mm) else tr("{0} 分钟", mm)
    }

    fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..10 -> tr("早上好呀")
        in 11..13 -> tr("中午好呀")
        in 14..17 -> tr("下午好呀")
        in 18..22 -> tr("晚上好呀")
        else -> tr("夜深了")
    }
}
