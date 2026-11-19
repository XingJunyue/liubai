package com.liubai.guard

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * 时段规则：课表里的每节课、自己定的场景（比如图书馆 Wi-Fi、工作日白天）。
 * level：0 水课（打开前拦一下）、1 普通（守护中的应用不开放）、2 重要（只开放白名单）。
 * ssid 非空时，只有连着这个 Wi-Fi 才算数。
 */
data class Slot(
    val id: String, val name: String, val days: Set<Int>, val from: Int, val to: Int,
    val level: Int = 1, val ssid: String = "", val kind: String = "course"
) {
    fun json(): JSONObject = JSONObject().put("id", id).put("n", name).put("d", JSONArray(days.toList())).put("f", from).put("t", to)
        .put("l", level).put("s", ssid).put("k", kind)

    companion object {
        fun of(j: JSONObject): Slot {
            val d = j.optJSONArray("d") ?: JSONArray()
            return Slot(j.optString("id"), j.optString("n"), (0 until d.length()).map { d.getInt(it) }.toSet(),
                j.optInt("f"), j.optInt("t"), j.optInt("l", 1), j.optString("s", ""), j.optString("k", "course"))
        }
        val LEVELS = listOf(tr("水课"), tr("普通"), tr("重要"))
        val LEVEL_NOTE = listOf(tr("打开守护中的应用前先停一下"), tr("守护中的应用不开放"), tr("只开放白名单里的应用"))
    }
}

object Scenes {
    private fun p(c: Context) = c.getSharedPreferences("scenes", Context.MODE_PRIVATE)

    private val memo = java.util.concurrent.ConcurrentHashMap<String, List<Slot>>()
    private fun list(c: Context, key: String): List<Slot> {
        val raw = p(c).getString(key, "[]") ?: "[]"
        memo[raw]?.let { return it }
        val a = try { JSONArray(raw) } catch (x: Exception) { JSONArray() }
        if (memo.size > 8) memo.clear()
        return (0 until a.length()).map { Slot.of(a.getJSONObject(it)) }.also { memo[raw] = it }
    }
    private fun put(c: Context, key: String, l: List<Slot>) {
        val a = JSONArray(); l.forEach { a.put(it.json()) }
        p(c).edit().putString(key, a.toString()).apply()
    }

    @Synchronized private fun roll(c: Context) {
        val t = Store.today()
        if (p(c).getString("day", "") == t) return
        p(c).edit().putString("active", p(c).getString("desired", "[]")).putBoolean("onA", p(c).getBoolean("onD", true)).putString("day", t).apply()
    }

    /** 你想要的（编辑的就是它）。 */
    fun desired(c: Context): List<Slot> { roll(c); return list(c, "desired") }
    /** 今天真正执行的：今天加过、改严过的都算上；删掉、放宽的明天才生效。 */
    fun active(c: Context): List<Slot> { roll(c); return if (p(c).getBoolean("onA", true)) list(c, "active") else emptyList() }

    fun setDesired(c: Context, d: List<Slot>) {
        roll(c)
        val a = list(c, "active")
        val merged = a + d.filter { n -> a.none { it == n } }
        put(c, "desired", d); put(c, "active", merged)
    }
    /** 一键暂停课表（放假）：明天起生效；重新开启马上生效。 */
    fun enabled(c: Context) = p(c).getBoolean("onD", true)
    fun setEnabled(c: Context, v: Boolean) {
        roll(c)
        p(c).edit().putBoolean("onD", v).apply { if (v) putBoolean("onA", true) }.apply()
    }
    fun enabledToday(c: Context) = p(c).getBoolean("onA", true)

    fun quiet(c: Context) = p(c).getBoolean("quiet", false)
    fun setQuiet(c: Context, v: Boolean) = p(c).edit().putBoolean("quiet", v).apply()

    // ---------- 每节课的时间 ----------
    private val DEFAULT_SEC = listOf(480, 535, 600, 655, 840, 895, 960, 1015, 1140, 1195, 1250, 1305)
    fun secStarts(c: Context): List<Int> = (p(c).getString("secs", null)?.split(',')?.mapNotNull { it.toIntOrNull() } ?: DEFAULT_SEC).ifEmpty { DEFAULT_SEC }
    fun setSecStarts(c: Context, l: List<Int>) = p(c).edit().putString("secs", l.joinToString(",")).apply()
    fun secLen(c: Context) = p(c).getInt("secLen", 45)
    fun setSecLen(c: Context, m: Int) = p(c).edit().putInt("secLen", m).apply()
    fun secRange(c: Context, a: Int, b: Int): Pair<Int, Int> {
        val s = secStarts(c)
        val i = (a - 1).coerceIn(0, s.lastIndex); val j = (b - 1).coerceIn(i, s.lastIndex)
        return s[i] to s[j] + secLen(c)
    }

    // ---------- 现在是什么时段 ----------
    @Volatile private var ssid = ""
    @Volatile private var ssidAt = 0L
    fun wifi(c: Context): String {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - ssidAt < 15_000) return ssid
        ssidAt = now
        ssid = try {
            @Suppress("DEPRECATION")
            val s = (c.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager).connectionInfo?.ssid ?: ""
            s.trim('"').let { if (it == "<unknown ssid>") "" else it }
        } catch (x: Exception) { "" }
        return ssid
    }

    fun now(c: Context): Slot? {
        val l = active(c); if (l.isEmpty()) return null
        return pick(l, Tasks.weekday(), Rules.nowMin()) { wifi(c) }
    }

    /** 纯计算：星期 d、第 m 分钟时生效的最严那条。 */
    fun pick(l: List<Slot>, d: Int, m: Int, ssid: () -> String): Slot? {
        val prevDay = if (d == 1) 7 else d - 1
        var best: Slot? = null
        for (s in l) {
            val hit = if (s.from < s.to) d in s.days && m in s.from until s.to
            else (d in s.days && m >= s.from) || (prevDay in s.days && m < s.to)
            if (!hit) continue
            if (s.ssid.isNotEmpty() && s.ssid != ssid()) continue
            if (best == null || s.level > best.level) best = s
        }
        return best
    }
    fun strictNow(c: Context) = now(c)?.level == 2
    fun lockNow(c: Context) = (now(c)?.level ?: -1) >= 1
    fun lightNow(c: Context) = now(c)?.level == 0
    /** 上课（普通、重要）时收起通知，还要收多久。 */
    fun quietLeftMs(c: Context): Long {
        if (!quiet(c)) return 0
        val s = now(c) ?: return 0
        if (s.level < 1) return 0
        var left = s.to - Rules.nowMin(); if (left <= 0) left += 1440
        return left * 60_000L - Calendar.getInstance().get(Calendar.SECOND) * 1000L
    }

    // ---------- 导入 ----------
    /** 从系统日历读接下来两周的日程，按「名字 + 星期 + 时间」合成每周的课。 */
    fun fromCalendar(c: Context, calId: Long): List<Slot> {
        val out = LinkedHashMap<String, Slot>()
        val start = System.currentTimeMillis() - 86_400_000L
        val end = start + 15 * 86_400_000L
        val uri = android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, start); android.content.ContentUris.appendId(it, end)
        }.build()
        c.contentResolver.query(uri, arrayOf(android.provider.CalendarContract.Instances.TITLE, android.provider.CalendarContract.Instances.BEGIN,
            android.provider.CalendarContract.Instances.END, android.provider.CalendarContract.Instances.ALL_DAY),
            "${android.provider.CalendarContract.Instances.CALENDAR_ID} = ?", arrayOf(calId.toString()), null)?.use { cur ->
            while (cur.moveToNext()) {
                if (cur.getInt(3) == 1) continue
                addSlot(out, cur.getString(0) ?: continue, cur.getLong(1), cur.getLong(2), TimeZone.getDefault())
            }
        }
        return out.values.toList()
    }

    fun calendars(c: Context): List<Pair<Long, String>> {
        val l = ArrayList<Pair<Long, String>>()
        c.contentResolver.query(android.provider.CalendarContract.Calendars.CONTENT_URI,
            arrayOf(android.provider.CalendarContract.Calendars._ID, android.provider.CalendarContract.Calendars.CALENDAR_DISPLAY_NAME), null, null, null)?.use {
            while (it.moveToNext()) l += it.getLong(0) to (it.getString(1) ?: tr("日历"))
        }
        return l
    }

    private fun addSlot(out: MutableMap<String, Slot>, title: String, b: Long, e: Long, tz: TimeZone) {
        val k = Calendar.getInstance(tz).apply { timeInMillis = b }
        val ke = Calendar.getInstance(tz).apply { timeInMillis = e }
        val day = k.get(Calendar.DAY_OF_WEEK).let { if (it == Calendar.SUNDAY) 7 else it - 1 }
        val from = k.get(Calendar.HOUR_OF_DAY) * 60 + k.get(Calendar.MINUTE)
        val to = ke.get(Calendar.HOUR_OF_DAY) * 60 + ke.get(Calendar.MINUTE)
        if (to <= from || to - from > 6 * 60) return
        val name = title.trim().take(20)
        val key = "$name|$day|$from|$to"
        if (key !in out) out[key] = Slot("c${key.hashCode()}", name, setOf(day), from, to)
    }

    /** 读 .ics 文件（WakeUp 课程表、学校教务系统常能导出）。 */
    fun fromIcs(c: Context, uri: Uri): List<Slot> {
        val raw = c.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return emptyList()
        return parseIcs(raw)
    }

    fun parseIcs(raw: String): List<Slot> {
        val lines = ArrayList<String>()
        for (l in raw.replace("\r\n", "\n").split('\n')) { if ((l.startsWith(" ") || l.startsWith("\t")) && lines.isNotEmpty()) lines[lines.lastIndex] += l.substring(1) else lines += l }
        val out = LinkedHashMap<String, Slot>()
        var title = ""; var b = 0L; var e = 0L; val tz = TimeZone.getDefault()
        fun time(prop: String, v: String): Long {
            val zone = Regex("TZID=([^;:]+)").find(prop)?.groupValues?.get(1)?.let { TimeZone.getTimeZone(it) }
            val utc = v.endsWith("Z")
            val s = v.trimEnd('Z')
            if (s.length < 15) return 0L
            val k = Calendar.getInstance(if (utc) TimeZone.getTimeZone("UTC") else zone ?: TimeZone.getDefault())
            k.clear(); k.set(s.substring(0, 4).toInt(), s.substring(4, 6).toInt() - 1, s.substring(6, 8).toInt(), s.substring(9, 11).toInt(), s.substring(11, 13).toInt())
            return k.timeInMillis
        }
        for (l in lines) {
            val i = l.indexOf(':'); if (i < 0) continue
            val prop = l.substring(0, i); val v = l.substring(i + 1).trim()
            when {
                l.startsWith("BEGIN:VEVENT") -> { title = ""; b = 0; e = 0 }
                prop.startsWith("SUMMARY") -> title = v.replace("\\,", ",").replace("\\n", " ")
                prop.startsWith("DTSTART") -> b = try { time(prop, v) } catch (x: Exception) { 0L }
                prop.startsWith("DTEND") -> e = try { time(prop, v) } catch (x: Exception) { 0L }
                l.startsWith("END:VEVENT") -> if (title.isNotBlank() && b > 0 && e > b) addSlot(out, title, b, e, tz)
            }
        }
        return out.values.toList()
    }
}
