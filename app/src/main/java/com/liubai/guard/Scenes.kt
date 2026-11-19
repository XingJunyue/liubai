package com.liubai.guard

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs

/**
 * 时段规则：课表里的每节课、自己定的场景（比如图书馆 Wi-Fi、工作日白天）。
 * level：0 水课（打开前拦一下）、1 普通（守护中的应用不开放）、2 重要（只开放白名单）。
 * ssid 非空时，只有连着这个 Wi-Fi 才算数。
 */
data class Slot(
    val id: String, val name: String, val days: Set<Int>, val from: Int, val to: Int,
    val level: Int = 1, val ssid: String = "", val kind: String = "course",
    /** 教室、老师；weeks 为空 = 每周都上；color -1 = 按课名自动配色。 */
    val room: String = "", val teacher: String = "", val weeks: Set<Int> = emptySet(), val color: Int = -1,
    val note: String = "", val credit: String = ""
) {
    fun json(): JSONObject = JSONObject().put("id", id).put("n", name).put("d", JSONArray(days.toList())).put("f", from).put("t", to)
        .put("l", level).put("s", ssid).put("k", kind).put("r", room).put("te", teacher).put("w", JSONArray(weeks.sorted())).put("co", color)
        .put("no", note).put("cr", credit)

    /** 这周上不上（week 为 null 表示没设开学日期，按每周都上算）。 */
    fun inWeek(week: Int?) = week == null || weeks.isEmpty() || week in weeks

    companion object {
        fun of(j: JSONObject): Slot {
            val d = j.optJSONArray("d") ?: JSONArray()
            val w = j.optJSONArray("w") ?: JSONArray()
            return Slot(j.optString("id"), j.optString("n"), (0 until d.length()).map { d.getInt(it) }.toSet(),
                j.optInt("f"), j.optInt("t"), j.optInt("l", 1), j.optString("s", ""), j.optString("k", "course"),
                j.optString("r", ""), j.optString("te", ""), (0 until w.length()).map { w.getInt(it) }.toSet(), j.optInt("co", -1),
                j.optString("no", ""), j.optString("cr", ""))
        }
        val LEVELS = listOf(tr("水课"), tr("普通"), tr("重要"))
        val LEVEL_NOTE = listOf(tr("打开守护中的应用前先停一下"), tr("守护中的应用不开放"), tr("只开放白名单里的应用"))

        /** 「1-16周」「3,5,7」「1-8,10-16 单」这类写法 → 周次集合；空串 = 每周。 */
        fun parseWeeks(t0: String, max: Int = 30): Set<Int> {
            val t = t0.replace('，', ',').replace('、', ',').replace('～', '-').replace('~', '-').replace('—', '-').replace('–', '-')
            val odd = Regex("单|單|odd|impair|ungerade|impar|奇").containsMatchIn(t)
            val even = Regex("双|雙|even|pair(?!e)|gerade(?!r)|par\\b|偶").containsMatchIn(t) && !Regex("ungerade|impair|impar").containsMatchIn(t)
            val out = sortedSetOf<Int>()
            for (m in Regex("(\\d+)\\s*(?:-\\s*(\\d+))?").findAll(t)) {
                val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toIntOrNull() ?: a
                for (w in minOf(a, b)..maxOf(a, b)) if (w in 1..max) out += w
            }
            val r = out.filter { (!odd || it % 2 == 1) && (!even || it % 2 == 0) }.toSet()
            if (r.isEmpty() && (odd || even)) return (1..max).filter { if (odd) it % 2 == 1 else it % 2 == 0 }.toSet()
            return r
        }
        /** 周次集合 → 简短文字：每周 / 1-16周 / 1-15周(单) / 3,5,9周 */
        fun weeksText(w: Set<Int>): String {
            if (w.isEmpty()) return tr("每周")
            val l = w.sorted()
            val step = if (l.size >= 2 && l.zipWithNext().all { (a, b) -> b - a == 2 }) 2 else if (l.zipWithNext().all { (a, b) -> b - a == 1 }) 1 else 0
            val body = when {
                l.size == 1 -> "${l[0]}"
                step == 1 -> "${l.first()}-${l.last()}"
                step == 2 -> "${l.first()}-${l.last()}"
                else -> {
                    val parts = ArrayList<String>(); var i = 0
                    while (i < l.size) { var j = i; while (j + 1 < l.size && l[j + 1] == l[j] + 1) j++; parts += if (j > i) "${l[i]}-${l[j]}" else "${l[i]}"; i = j + 1 }
                    parts.joinToString(",")
                }
            }
            return tr("{0}周", body) + if (step == 2 && l.size >= 2) (if (l.first() % 2 == 1) tr("(单)") else tr("(双)")) else ""
        }
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

    // ---------- 周次 ----------
    /** 第 1 周周一（epoch 天）；-1 = 没设，所有课按每周都上。 */
    fun termStart(c: Context) = p(c).getLong("term", -1L)
    fun setTermStart(c: Context, day: Long) = p(c).edit().putLong("term", day).apply()
    fun totalWeeks(c: Context) = p(c).getInt("weeks", 20)
    fun setTotalWeeks(c: Context, n: Int) = p(c).edit().putInt("weeks", n.coerceIn(1, 30)).apply()
    fun showOff(c: Context) = p(c).getBoolean("showOff", true)
    fun setShowOff(c: Context, v: Boolean) = p(c).edit().putBoolean("showOff", v).apply()
    fun showWeekend(c: Context) = p(c).getBoolean("weekend", true)
    fun setShowWeekend(c: Context, v: Boolean) = p(c).edit().putBoolean("weekend", v).apply()
    fun gridView(c: Context) = p(c).getBoolean("grid", true)
    fun setGridView(c: Context, v: Boolean) = p(c).edit().putBoolean("grid", v).apply()

    /** 今天所在周一的 epoch 天。 */
    fun mondayOf(epochDay: Long): Long { val dow = Math.floorMod(epochDay + 3, 7L); return epochDay - dow }  // 1970-01-01 是周四
    fun todayEpoch(): Long { val k = Calendar.getInstance(); return Math.floorDiv(k.timeInMillis + k.get(Calendar.ZONE_OFFSET) + k.get(Calendar.DST_OFFSET), 86_400_000L) }
    /** 现在是第几周；没设开学日期返回 null。 */
    fun weekNow(c: Context): Int? { val t = termStart(c); return if (t < 0) null else weekOf(t, todayEpoch()) }
    fun weekOf(term: Long, day: Long) = Math.floorDiv(mondayOf(day) - term, 7L).toInt() + 1
    /** 「现在是第 n 周」：倒推出第 1 周的周一。 */
    fun setWeekNow(c: Context, n: Int) = setTermStart(c, mondayOf(todayEpoch()) - (n - 1) * 7L)

    // ---------- 每节课的时间 ----------
    private val DEFAULT_SEC = listOf(480, 535, 600, 655, 840, 895, 960, 1015, 1140, 1195, 1250, 1305)
    fun secStarts(c: Context): List<Int> = (p(c).getString("secs", null)?.split(',')?.mapNotNull { it.toIntOrNull() } ?: DEFAULT_SEC).ifEmpty { DEFAULT_SEC }
    fun setSecStarts(c: Context, l: List<Int>) = p(c).edit().putString("secs", l.joinToString(",")).apply()
    fun secLen(c: Context) = p(c).getInt("secLen", 45)
    fun setSecLen(c: Context, m: Int) = p(c).edit().putInt("secLen", m).apply()
    /** 每节下课时间：单独改过的用改过的，没改过的 = 上课时间 + 每节时长。 */
    fun secEnds(c: Context): List<Int> {
        val st = secStarts(c); val len = secLen(c)
        val saved = p(c).getString("secE", null)?.split(',')?.map { it.toIntOrNull() } ?: emptyList()
        return st.indices.map { i -> saved.getOrNull(i)?.takeIf { it > st[i] } ?: (st[i] + len) }
    }
    fun setSecEnds(c: Context, l: List<Int>) = p(c).edit().putString("secE", l.joinToString(",")).apply()
    /** 统一时长：清掉单独改过的下课时间。 */
    fun resetSecEnds(c: Context) = p(c).edit().remove("secE").apply()
    fun secRange(c: Context, a: Int, b: Int): Pair<Int, Int> {
        val s = secStarts(c); val e = secEnds(c)
        val i = (a - 1).coerceIn(0, s.lastIndex); val j = (b - 1).coerceIn(i, s.lastIndex)
        return s[i] to e[j]
    }
    /** 一段时间落在第几节到第几节（就近算）。 */
    fun secSpan(starts: List<Int>, ends: List<Int>, from: Int, to: Int): Pair<Int, Int> {
        if (starts.isEmpty()) return 1 to 1
        val a = starts.indices.minByOrNull { abs(starts[it] - from) } ?: 0
        val b = (ends.indices.minByOrNull { abs(ends[it] - to) } ?: a).coerceAtLeast(a)
        return (a + 1) to (b + 1)
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
        val w = weekNow(c)
        return pick(l.filter { it.kind != "course" || it.inWeek(w) }, Tasks.weekday(), Rules.nowMin()) { wifi(c) }
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

    // ---------- 备份 / 导出 ----------
    class Backup(val courses: List<Slot>, val starts: List<Int>, val ends: List<Int>, val len: Int, val term: Long, val weeks: Int)

    /** 课表备份：课程 + 每节课时间 + 开学日期 + 总周数。 */
    fun backupJson(courses: List<Slot>, starts: List<Int>, ends: List<Int>, len: Int, term: Long, weeks: Int): String {
        val a = JSONArray(); courses.forEach { a.put(it.json()) }
        return JSONObject().put("app", "liubai-timetable").put("v", 1).put("courses", a)
            .put("secs", JSONArray(starts)).put("secE", JSONArray(ends)).put("secLen", len).put("term", term).put("weeks", weeks).toString(2)
    }
    fun parseBackup(s: String): Backup? = try {
        val j = JSONObject(s)
        val a = j.optJSONArray("courses") ?: JSONArray()
        fun ints(k: String) = j.optJSONArray(k)?.let { x -> (0 until x.length()).map { x.getInt(it) } } ?: emptyList()
        Backup((0 until a.length()).map { Slot.of(a.getJSONObject(it)).copy(kind = "course") }, ints("secs"), ints("secE"),
            j.optInt("secLen", 45), j.optLong("term", -1L), j.optInt("weeks", 20))
    } catch (x: Exception) { null }
    fun exportBackup(c: Context) = backupJson(desired(c).filter { it.kind == "course" }, secStarts(c), secEnds(c), secLen(c), termStart(c), totalWeeks(c))
    /** 用备份替换课表（场景不动）。 */
    fun applyBackup(c: Context, b: Backup) {
        setDesired(c, desired(c).filter { it.kind != "course" } + b.courses)
        if (b.starts.isNotEmpty()) { setSecStarts(c, b.starts); setSecLen(c, b.len); if (b.ends.size == b.starts.size) setSecEnds(c, b.ends) else resetSecEnds(c) }
        setTermStart(c, b.term); setTotalWeeks(c, b.weeks)
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

    /** 读 .ics 文件（课表 App、学校教务系统常能导出）。 */
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
