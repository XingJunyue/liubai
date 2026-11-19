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
/**
 * 把系统使用事件累计成每个应用的前台时长。按「应用里每个界面」记：
 * 抖音这类应用内部会频繁切界面（直播间、评论、广告页），系统顺序常是
 * 新界面 RESUMED 之后旧界面才 STOPPED。以前一见到同包的 STOPPED 就停表，
 * 后面这段时间就丢了，导致检测时长比实际少。现在只有该应用的界面全部离开前台才停表。
 */
class UsageTally(var start: Long) {
    val totals = HashMap<String, Long>()
    var cur: String? = null; private set
    var curStart = start; private set
    private val shown = HashSet<String>()   // 正在前台的界面 pkg/class
    private var first = true

    fun close(at: Long) {
        val p = cur ?: return
        if (at > curStart) totals[p] = (totals[p] ?: 0L) + (at - curStart)
        cur = null
    }

    fun resumed(ts: Long, pkg: String, cls: String?) {
        shown.removeAll { !it.startsWith("$pkg/") }
        shown.add("$pkg/${cls ?: ""}")
        if (cur != pkg) { close(ts); cur = pkg; curStart = ts }
        first = false
    }

    fun left(ts: Long, pkg: String, cls: String?) {
        val had = shown.remove("$pkg/${cls ?: ""}")
        if (cur == pkg) {
            if (shown.none { it.startsWith("$pkg/") }) close(ts)
        } else if (cur == null && first && !had) {
            // 0:00 之前就开着、今天第一个事件是它离开
            totals[pkg] = (totals[pkg] ?: 0L) + (ts - start).coerceAtLeast(0)
        }
        first = false
    }

    fun screenOff(ts: Long) { close(ts); shown.clear(); first = false }

    fun live(pkg: String, now: Long) = (totals[pkg] ?: 0L) + if (cur == pkg) (now - curStart).coerceAtLeast(0) else 0L
}

object Usage {
    private var day = ""
    private var lastTs = 0L
    private var tally = UsageTally(0L)
    private var lastCall = 0L
    private var grantAt = 0L
    private var grantOk = false

    fun grantedCached(c: Context): Boolean {
        val now = System.currentTimeMillis()
        if (now - grantAt > 10_000) { grantAt = now; grantOk = granted(c) }
        return grantOk
    }

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

    /** 读取到现在为止的用量；返回当前前台应用（读不到为 null）。 */
    @Synchronized
    fun update(c: Context): String? {
        val now = System.currentTimeMillis()
        if (now - lastCall < 1000 && day == Store.today()) return tally.cur   // 一秒内只查一次系统记录，避免卡顿
        lastCall = now
        if (!grantedCached(c)) return null
        val t = Store.today()
        if (t != day) {
            day = t; lastTs = midnight(); tally = UsageTally(lastTs)
        }
        val um = c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val ev = try { um.queryEvents(lastTs, now) } catch (x: Exception) { null } ?: return tally.cur
        feed(ev, tally, lastTs)
        lastTs = now
        // 当前正在前台的应用，时长先算到现在，不提交
        return tally.cur
    }

    private fun feed(ev: UsageEvents, t: UsageTally, from: Long) {
        val e = UsageEvents.Event()
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            val ts = e.timeStamp
            if (ts < from) continue
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> t.resumed(ts, e.packageName, e.className)
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> t.left(ts, e.packageName, e.className)
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.KEYGUARD_SHOWN -> t.screenOff(ts)
            }
        }
    }

    @Synchronized
    fun usedMs(c: Context, pkg: String): Long {
        update(c)
        return tally.live(pkg, System.currentTimeMillis())
    }

    /** 一段时间内各应用的前台时长（非增量，周报用）。 */
    fun range(c: Context, start: Long, end: Long): Map<String, Long> {
        val out = HashMap<String, Long>()
        if (!granted(c)) return out
        val um = c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val ev = try { um.queryEvents(start, end) } catch (x: Exception) { null } ?: return out
        val t = UsageTally(start)
        feed(ev, t, start)
        t.close(minOf(end, System.currentTimeMillis()))
        return t.totals
    }

    fun dayStart(daysAgo: Int): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, -daysAgo)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
