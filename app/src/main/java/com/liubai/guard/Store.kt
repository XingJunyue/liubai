package com.liubai.guard

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 规则：
 *  - desired（你想要的设置）随时可改，但今天真正生效的 active 只能「更严」：
 *    新加应用、调低时长、打开保护 → 立即生效；
 *    调高时长、移除应用、关闭保护 → 明天 0:00 才生效。
 *  - 每天 0:00 后第一次读取时，active = desired，用量清零。
 */
object Store {
    private fun p(c: Context): SharedPreferences = c.getSharedPreferences("liubai", Context.MODE_PRIVATE)

    fun today(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    private fun parse(s: String?): Map<String, Int> {
        if (s.isNullOrBlank()) return emptyMap()
        val m = LinkedHashMap<String, Int>()
        for (part in s.split(";")) {
            val i = part.lastIndexOf(':')
            if (i <= 0) continue
            val v = part.substring(i + 1).toIntOrNull() ?: continue
            m[part.substring(0, i)] = v
        }
        return m
    }

    private fun fmt(m: Map<String, Int>) = m.entries.joinToString(";") { "${it.key}:${it.value}" }

    @Synchronized
    private fun roll(c: Context) {
        val pr = p(c)
        val t = today()
        if (pr.getString("day", null) == t) return
        val e = pr.edit()
        e.putString("active", pr.getString("desired", ""))
        e.putString("day", t)
        for (k in pr.all.keys) if (k.startsWith("u_") && !k.startsWith("u_${t}_")) e.remove(k)
        e.apply()
    }

    fun active(c: Context): Map<String, Int> { roll(c); return parse(p(c).getString("active", "")) }
    fun desired(c: Context): Map<String, Int> { roll(c); return parse(p(c).getString("desired", "")) }

    @Synchronized
    fun setDesired(c: Context, d: Map<String, Int>) {
        val a = active(c)
        val m = LinkedHashMap<String, Int>()
        for (k in (a.keys + d.keys)) {
            val x = a[k]; val y = d[k]
            m[k] = when {
                x != null && y != null -> minOf(x, y)
                x != null -> x
                else -> y!!
            }
        }
        p(c).edit().putString("active", fmt(m)).putString("desired", fmt(d)).apply()
    }

    /** 防反悔内置、不外显：只要今天还有应用在守护，就挡住关闭守护和卸载。想彻底停用，移除所有应用，明天 0:00 起自动解除。 */
    fun protectActive(c: Context): Boolean = active(c).isNotEmpty()

    fun usedMs(c: Context, pkg: String): Long { roll(c); return p(c).getLong("u_${today()}_$pkg", 0L) }
    fun addUsage(c: Context, pkg: String, ms: Long) {
        roll(c)
        val k = "u_${today()}_$pkg"
        val pr = p(c)
        pr.edit().putLong(k, pr.getLong(k, 0L) + ms).apply()
    }

    fun introDone(c: Context) = p(c).getBoolean("intro", false)
    fun setIntroDone(c: Context) = p(c).edit().putBoolean("intro", true).apply()
}
