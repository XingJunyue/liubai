package com.liubai.guard

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
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

    @Volatile private var tDay = ""
    @Volatile private var tUntil = 0L
    /** 今天的日期串；每天只算一次，不再每次新建格式化器。 */
    fun today(): String {
        val now = System.currentTimeMillis()
        if (now < tUntil && now > tUntil - 86_400_000L - 3_600_000L) return tDay
        val cal = java.util.Calendar.getInstance()
        tDay = SimpleDateFormat("yyyyMMdd", Locale.US).format(cal.time)
        cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
        tUntil = cal.timeInMillis
        return tDay
    }

    private val memo = java.util.concurrent.ConcurrentHashMap<String, Map<String, Int>>()
    private fun parse(s: String?): Map<String, Int> {
        if (s.isNullOrBlank()) return emptyMap()
        memo[s]?.let { return it }
        if (memo.size > 8) memo.clear()
        return parseRaw(s).also { memo[s] = it }
    }
    private fun parseRaw(s: String): Map<String, Int> {
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

    @Volatile private var rolled = ""
    @Synchronized
    private fun roll(c: Context) {
        val t = today()
        if (rolled == t) return
        val pr = p(c)
        if (pr.getString("day", null) == t) { rolled = t; return }
        val e = pr.edit()
        e.putString("active", pr.getString("desired", ""))
        e.putString("xa", pr.getString("xd", "{}"))
        e.putString("ga", pr.getString("gd", "[]"))
        e.putString("ba", pr.getString("bd", "{}"))
        e.putString("day", t)
        rolled = t
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
    fun protectActive(c: Context): Boolean = active(c).isNotEmpty() || groupsActive(c).isNotEmpty() || bedsActive(c).any { it.on }

    // ---------- 守护方式（每个应用） ----------
    private val xmemo = java.util.concurrent.ConcurrentHashMap<String, Map<String, Extra>>()
    private fun extras(c: Context, key: String): Map<String, Extra> {
        roll(c)
        val raw = p(c).getString(key, "{}") ?: "{}"
        xmemo[raw]?.let { return it }
        val j = try { JSONObject(raw) } catch (x: Exception) { JSONObject() }
        val m = LinkedHashMap<String, Extra>()
        for (k in j.keys()) m[k] = Extra.of(j.getJSONObject(k))
        if (xmemo.size > 8) xmemo.clear()
        xmemo[raw] = m
        return m
    }
    private fun putExtras(c: Context, key: String, m: Map<String, Extra>) {
        val j = JSONObject(); for ((k, v) in m) j.put(k, v.json())
        p(c).edit().putString(key, j.toString()).apply()
    }
    fun extraActive(c: Context, pkg: String): Extra = extras(c, "xa")[pkg] ?: Extra()
    fun extraDesired(c: Context, pkg: String): Extra = extras(c, "xd")[pkg] ?: extraActive(c, pkg)

    /** 保存想要的守护方式；今天只采纳其中更严的部分，返回今天实际生效的。 */
    @Synchronized
    fun setExtra(c: Context, pkg: String, want: Extra?): Extra? {
        val d = extras(c, "xd").toMutableMap()
        val a = extras(c, "xa").toMutableMap()
        if (want == null) { d.remove(pkg); putExtras(c, "xd", d); return a[pkg] }
        d[pkg] = want
        val cur = a[pkg]
        val eff = if (cur == null) want else Rules.stricter(cur, want)
        a[pkg] = eff
        putExtras(c, "xd", d); putExtras(c, "xa", a)
        return eff
    }

    // ---------- 分组共享额度 ----------
    private val gmemo = java.util.concurrent.ConcurrentHashMap<String, List<Group>>()
    private fun groups(c: Context, key: String): List<Group> {
        roll(c)
        val raw = p(c).getString(key, "[]") ?: "[]"
        gmemo[raw]?.let { return it }
        val arr = try { JSONArray(raw) } catch (x: Exception) { JSONArray() }
        val g = (0 until arr.length()).map { Group.of(arr.getJSONObject(it)) }
        if (gmemo.size > 8) gmemo.clear()
        gmemo[raw] = g
        return g
    }
    private fun putGroups(c: Context, key: String, g: List<Group>) {
        val arr = JSONArray(); g.forEach { arr.put(it.json()) }
        p(c).edit().putString(key, arr.toString()).apply()
    }
    fun groupsActive(c: Context) = groups(c, "ga")
    fun groupsDesired(c: Context) = groups(c, "gd")

    @Synchronized
    fun setGroupsDesired(c: Context, d: List<Group>) {
        val a = groupsActive(c).associateBy { it.id }
        val dm = d.associateBy { it.id }
        val out = ArrayList<Group>()
        for (id in (a.keys + dm.keys)) {
            val x = a[id]; val y = dm[id]
            out += when {
                x != null && y != null -> Group(id, y.name, (x.pkgs + y.pkgs).distinct(), minOf(x.min, y.min))
                x != null -> x
                else -> y!!
            }
        }
        putGroups(c, "gd", d); putGroups(c, "ga", out)
    }

    // ---------- 睡前锁定 ----------
    private val bmemo = java.util.concurrent.ConcurrentHashMap<String, List<Bed>>()
    private fun beds(c: Context, key: String): List<Bed> {
        roll(c)
        val raw = p(c).getString(key, "{}") ?: "{}"
        bmemo[raw]?.let { return it }
        val b = Bed.list(raw)
        if (bmemo.size > 8) bmemo.clear()
        bmemo[raw] = b
        return b
    }
    /** 今天在执行的各段睡眠守护（睡前、午睡……）。 */
    fun bedsActive(c: Context) = beds(c, "ba")
    fun bedsDesired(c: Context): List<Bed> = if (p(c).contains("bd")) beds(c, "bd") else bedsActive(c)
    /** 现在落在哪一段里；不在任何一段就是 null。 */
    fun bedNow(c: Context): Bed? { val d = Tasks.weekday(); val m = Rules.nowMin(); return bedsActive(c).firstOrNull { it.hit(d, m) } }

    @Synchronized
    fun setBeds(c: Context, want: List<Bed>): List<Bed> {
        val eff = Rules.stricterBeds(bedsActive(c), want)
        p(c).edit().putString("bd", Bed.listJson(want)).putString("ba", Bed.listJson(eff)).apply()
        return eff
    }

    // ---------- 单次限时的冷却、提醒、忍住次数 ----------
    fun coolUntil(c: Context, pkg: String) = p(c).getLong("cu_$pkg", 0L)
    fun setCoolUntil(c: Context, pkg: String, t: Long) = p(c).edit().putLong("cu_$pkg", t).apply()
    fun warned(c: Context, pkg: String) = p(c).getBoolean("u_${today()}_w_$pkg", false)
    fun setWarned(c: Context, pkg: String) = p(c).edit().putBoolean("u_${today()}_w_$pkg", true).apply()
    fun walkedAway(c: Context) = p(c).getInt("u_${today()}_walk", 0)
    fun addWalkedAway(c: Context) { val k = "u_${today()}_walk"; p(c).edit().putInt(k, p(c).getInt(k, 0) + 1).apply(); Hist.add(c, "walk", 1) }

    // ---------- 打开次数、紧急解锁 ----------
    fun opens(c: Context, pkg: String) = p(c).getInt("u_${today()}_o_$pkg", 0)
    fun addOpen(c: Context, pkg: String) { roll(c); val k = "u_${today()}_o_$pkg"; p(c).edit().putInt(k, p(c).getInt(k, 0) + 1).apply() }

    private fun week(): String { val k = java.util.Calendar.getInstance(); k.firstDayOfWeek = java.util.Calendar.MONDAY; return "${k.get(java.util.Calendar.YEAR)}w${k.get(java.util.Calendar.WEEK_OF_YEAR)}" }
    /** 每周能紧急用几次（0 = 关掉这个口子）。改少马上生效，改多下周生效。 */
    fun sosMax(c: Context) = p(c).getInt("sosMax", 2)
    fun setSosMax(c: Context, n: Int) {
        val cur = sosMax(c)
        if (n <= cur) p(c).edit().putInt("sosMax", n).remove("sosNext").apply()
        else p(c).edit().putInt("sosNext", n).putString("sosNextW", week()).apply()
    }
    fun sosPending(c: Context): Int? = if (p(c).contains("sosNext")) p(c).getInt("sosNext", 0) else null
    fun sosUsed(c: Context): Int {
        val pr = p(c)
        if (pr.contains("sosNext") && pr.getString("sosNextW", "") != week()) pr.edit().putInt("sosMax", pr.getInt("sosNext", 2)).remove("sosNext").apply()
        return if (pr.getString("sosW", "") == week()) pr.getInt("sosN", 0) else 0
    }
    fun sosLeft(c: Context) = (sosMax(c) - sosUsed(c)).coerceAtLeast(0)
    fun useSos(c: Context, pkg: String, min: Int) {
        val n = sosUsed(c) + 1
        p(c).edit().putString("sosW", week()).putInt("sosN", n).putLong("sos_$pkg", System.currentTimeMillis() + min * 60_000L).apply()
    }
    fun sosUntil(c: Context, pkg: String) = p(c).getLong("sos_$pkg", 0L)

    /** 打开时选的「想做什么」，今天每种几次。 */
    fun addIntent(c: Context, what: String) { roll(c); val k = "u_${today()}_i_$what"; p(c).edit().putInt(k, p(c).getInt(k, 0) + 1).apply() }

    fun usedMs(c: Context, pkg: String): Long {
        roll(c)
        val own = p(c).getLong("u_${today()}_$pkg", 0L)
        return if (Usage.grantedCached(c)) maxOf(own, Usage.usedMs(c, pkg)) else own
    }
    fun addUsage(c: Context, pkg: String, ms: Long) {
        roll(c)
        val k = "u_${today()}_$pkg"
        val pr = p(c)
        pr.edit().putLong(k, pr.getLong(k, 0L) + ms).apply()
    }

    fun introDone(c: Context) = p(c).getBoolean("intro", false)
    fun setIntroDone(c: Context) = p(c).edit().putBoolean("intro", true).apply()
}
