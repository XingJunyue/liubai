package com.liubai.guard

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import java.util.Calendar

/**
 * 用系统「使用情况访问权限」读取今天每个应用的真实前台时长（和小米「健康使用手机」同一来源），
 * 安装留白之前今天已经刷掉的时间也算进去。增量读取，开销很小。
 */
object Usage {
    private var day = ""
    private var lastTs = 0L
    private var cur: String? = null
    private var curStart = 0L
    private val totals = HashMap<String, Long>()

    @Suppress("DEPRECATION")
    fun granted(c: Context): Boolean {
        val ops = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        else ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun midnight(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun close(at: Long) {
        val p = cur ?: return
        if (at > curStart) totals[p] = (totals[p] ?: 0L) + (at - curStart)
        cur = null
    }

    /** 读取到现在为止的用量；返回当前前台应用（读不到为 null）。 */
    @Synchronized
    fun update(c: Context): String? {
        if (!granted(c)) return null
        val t = Store.today()
        val now = System.currentTimeMillis()
        if (t != day) {
            day = t; totals.clear(); cur = null; lastTs = midnight(); curStart = lastTs
        }
        val um = c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val ev = try { um.queryEvents(lastTs, now) } catch (x: Exception) { null } ?: return cur
        val e = UsageEvents.Event()
        var first = lastTs == midnight()
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            val ts = e.timeStamp
            if (ts < lastTs) continue
            val pkg = e.packageName
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    if (cur != pkg) { close(ts); cur = pkg; curStart = ts }
                }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                    if (cur == pkg) close(ts)
                    else if (cur == null && first) { // 跨过 0:00 一直开着
                        totals[pkg] = (totals[pkg] ?: 0L) + (ts - lastTs)
                    }
                }
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.KEYGUARD_SHOWN -> close(ts)
            }
            first = false
        }
        lastTs = now
        // 当前正在前台的应用，时长先算到现在，不提交
        return cur
    }

    @Synchronized
    fun usedMs(c: Context, pkg: String): Long {
        update(c)
        val base = totals[pkg] ?: 0L
        val live = if (cur == pkg) (System.currentTimeMillis() - curStart).coerceAtLeast(0) else 0L
        return base + live
    }
}
