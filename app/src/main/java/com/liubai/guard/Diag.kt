package com.liubai.guard

import android.content.Context
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 诊断记录：只存在内存里，最近 80 条，用来查「为什么没拦住」。 */
object Diag {
    private val buf = ArrayDeque<String>()
    private val f = SimpleDateFormat("HH:mm:ss", Locale.US)
    @Synchronized fun log(s: String) {
        buf.addLast("${f.format(Date())} $s")
        while (buf.size > 80) buf.removeFirst()
    }
    @Synchronized private fun lines() = buf.toList()

    fun report(c: Context): String {
        val sb = StringBuilder()
        sb.append("版本 ${BuildConfig.VERSION_NAME}\n")
        sb.append("服务在跑 ${GuardService.running}，距上次事件 ${(SystemClock.elapsedRealtime() - GuardService.lastEvent) / 1000} 秒\n")
        val s = GuardService.instance
        sb.append("前台 ${s?.fgNow()}，桌面 ${s?.homeNow()}\n")
        sb.append("守护生效 ${Store.protectActive(c)}，今日应用 ${Store.active(c).keys.joinToString(",")}\n")
        sb.append("用量权限 ${Usage.granted(c)}，放行中 ${Unlock.open(c)}，卸载锁 ${Admin.on(c)}\n")
        sb.append("系统前台 ${try { Usage.update(c) } catch (x: Exception) { "?" }}\n")
        for ((k, v) in Store.active(c)) sb.append("$k 限 $v 分，已用 ${Store.usedMs(c, k) / 60000} 分\n")
        sb.append("—— 最近事件 ——\n")
        lines().forEach { sb.append(it).append('\n') }
        return sb.toString()
    }
}
