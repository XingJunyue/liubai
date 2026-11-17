package com.liubai.guard

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * 每个应用的守护方式（每日分钟数仍在 Store 的分钟表里）：
 *  mode 0 每日总量：一天累计用满就休息
 *  mode 1 限定时段：只在 from–to 之间开放，时段内仍受每日总量约束
 *  mode 2 单次限时：每次最多 session 分钟，用满歇 cool 分钟，同时受每日总量约束
 *  mode 3 完全不开
 * 叠加项：delay 打开前停几秒；warn 剩 5 分钟提醒。
 */
data class Extra(
    val mode: Int = 0,
    val from: Int = 9 * 60,
    val to: Int = 21 * 60,
    val session: Int = 15,
    val cool: Int = 30,
    val delay: Int = 0,
    val warn: Boolean = true
) {
    fun json(): JSONObject = JSONObject().put("m", mode).put("f", from).put("t", to)
        .put("s", session).put("c", cool).put("d", delay).put("w", warn)

    companion object {
        val MODES = listOf("每日总量", "限定时段", "单次限时", "完全不开")
        fun of(j: JSONObject) = Extra(
            j.optInt("m", 0), j.optInt("f", 540), j.optInt("t", 1260),
            j.optInt("s", 15), j.optInt("c", 30), j.optInt("d", 0), j.optBoolean("w", true)
        )
    }
}

/** 几个应用共用一份每日额度。 */
data class Group(val id: String, val name: String, val pkgs: List<String>, val min: Int) {
    fun json(): JSONObject = JSONObject().put("id", id).put("n", name).put("p", JSONArray(pkgs)).put("m", min)

    companion object {
        fun of(j: JSONObject): Group {
            val a = j.optJSONArray("p") ?: JSONArray()
            return Group(j.optString("id"), j.optString("n"), (0 until a.length()).map { a.getString(it) }, j.optInt("m", 60))
        }
    }
}

/** 睡前锁定：这段时间里所有守护中的应用都不开放，可以跨过午夜。 */
data class Bed(val on: Boolean = false, val from: Int = 23 * 60, val to: Int = 7 * 60) {
    fun json(): JSONObject = JSONObject().put("on", on).put("f", from).put("t", to)

    companion object {
        fun of(j: JSONObject) = Bed(j.optBoolean("on", false), j.optInt("f", 23 * 60), j.optInt("t", 7 * 60))
    }
}

object Rules {
    fun nowMin(): Int = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

    /** m 是否在 [from, to) 内；from > to 表示跨午夜。 */
    fun inWin(m: Int, from: Int, to: Int): Boolean =
        if (from == to) false else if (from < to) m in from until to else m >= from || m < to

    fun clock(min: Int): String { val x = ((min % 1440) + 1440) % 1440; return "${x / 60}:${"%02d".format(x % 60)}" }

    /** 窗口 inner 是否完全落在 outer 里。 */
    private fun within(inner: Pair<Int, Int>, outer: Pair<Int, Int>): Boolean {
        var m = 0
        while (m < 1440) {
            if (inWin(m, inner.first, inner.second) && !inWin(m, outer.first, outer.second)) return false
            m += 5
        }
        return true
    }

    /**
     * 今天已生效的 a，加上想改成的 d，今天实际执行哪一份：只采纳更严的部分。
     * 被拒绝的放宽项会在明天 0:00 随 desired 一起生效。
     */
    fun stricter(a: Extra, d: Extra): Extra {
        val delay = maxOf(a.delay, d.delay)
        val warn = a.warn || d.warn
        val base = when {
            d.mode == 3 -> d
            a.mode == 3 -> a
            a.mode == 0 -> d                       // 从只有总量 → 加时段 / 单次限制：更严
            d.mode != a.mode -> a                  // 换掉已有的限制：明天再说
            a.mode == 1 -> if (within(d.from to d.to, a.from to a.to)) d else a
            else -> a.copy(session = minOf(a.session, d.session), cool = maxOf(a.cool, d.cool))
        }
        return base.copy(delay = delay, warn = warn)
    }

    fun stricterBed(a: Bed, d: Bed): Bed = when {
        !a.on -> d
        !d.on -> a
        within(a.from to a.to, d.from to d.to) -> d
        else -> a
    }

    fun summary(x: Extra): List<String> {
        val out = ArrayList<String>()
        when (x.mode) {
            1 -> out += "${clock(x.from)}–${clock(x.to)} 开放"
            2 -> out += "单次 ${x.session} 分 · 歇 ${x.cool} 分"
            3 -> out += "完全不开"
        }
        if (x.delay > 0) out += "打开前停 ${x.delay} 秒"
        return out
    }
}
