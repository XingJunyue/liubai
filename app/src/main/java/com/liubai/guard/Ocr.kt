package com.liubai.guard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 课表截图识别：全程在本机做，图片不上传。
 * 思路：先找表头「周一…周日 / Mon…Sun / 月…日」定出每一列，再找左边的节次数字定出每一行，
 * 然后把格子里的文字按列归到星期几、按上下位置换算成第几节到第几节。
 * 中文识别器同时认拉丁字母（英法德西）；日文、韩文各用自己的识别器，三个都试，取认出最多的那个。
 */
object Ocr {
    /** 一行字和它在图上的位置。 */
    class R(val left: Int, val top: Int, val right: Int, val bottom: Int)
    /** 最近一次认出的原始文字（调试用）。 */
    @Volatile var last: List<L> = emptyList()
    class L(val t: String, val r: R, val parts: List<L> = emptyList()) {
        val cx get() = (r.left + r.right) / 2f; val cy get() = (r.top + r.bottom) / 2f
        val w get() = r.right - r.left
    }

    private val DAY_CN = mapOf('一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '日' to 7, '天' to 7)
    private val DAY_JA = mapOf('月' to 1, '火' to 2, '水' to 3, '木' to 4, '金' to 5, '土' to 6, '日' to 7)
    private val DAY_KO = mapOf('월' to 1, '화' to 2, '수' to 3, '목' to 4, '금' to 5, '토' to 6, '일' to 7)
    /** 拉丁语系星期名：完整词和常见缩写（英、法、德、西）。 */
    private val DAY_LAT: List<Pair<String, Int>> = listOf(
        "monday" to 1, "tuesday" to 2, "wednesday" to 3, "thursday" to 4, "friday" to 5, "saturday" to 6, "sunday" to 7,
        "lundi" to 1, "mardi" to 2, "mercredi" to 3, "jeudi" to 4, "vendredi" to 5, "samedi" to 6, "dimanche" to 7,
        "montag" to 1, "dienstag" to 2, "mittwoch" to 3, "donnerstag" to 4, "freitag" to 5, "samstag" to 6, "sonntag" to 7, "sonnabend" to 6,
        "lunes" to 1, "martes" to 2, "miércoles" to 3, "miercoles" to 3, "jueves" to 4, "viernes" to 5, "sábado" to 6, "sabado" to 6, "domingo" to 7,
        "mon" to 1, "tue" to 2, "tues" to 2, "wed" to 3, "thu" to 4, "thur" to 4, "thurs" to 4, "fri" to 5, "sat" to 6, "sun" to 7,
        "lun" to 1, "mar" to 2, "mer" to 3, "jeu" to 4, "ven" to 5, "sam" to 6, "dim" to 7,
        "mo" to 1, "di" to 2, "mi" to 3, "do" to 4, "fr" to 5, "sa" to 6, "so" to 7,
        "mié" to 3, "mie" to 3, "jue" to 4, "vie" to 5, "sáb" to 6, "sab" to 6, "dom" to 7,
        "lu" to 1, "ma" to 2, "me" to 3, "je" to 4, "ve" to 5, "ju" to 4, "vi" to 5, "di" to 7
    )
    private val LAT_MAP: Map<String, Int> = LinkedHashMap<String, Int>().apply {
        // 西语 di 不是星期；德语 di = 周二，先放的优先
        for ((k, v) in DAY_LAT) if (k !in this) put(k, v)
    }

    fun dayOf(t0: String): Int? {
        val s = t0.trim().replace(" ", "").trim('(', ')', '（', '）', '[', ']')
        if (s.isEmpty()) return null
        Regex("^(?:周|星期|礼拜|週)([一二三四五六日天])").find(s)?.let { return DAY_CN[it.groupValues[1][0]] }
        // 「一」很细，常被认成横杠
        if (Regex("^(?:周|星期|礼拜)[-—–_~]$").matches(s)) return 1
        if (s.length == 1 && s[0] in DAY_CN && s[0] != '日') return DAY_CN[s[0]]
        // 日文：月曜日 / 月曜 / (月) / 月
        Regex("^([月火水木金土日])(?:曜日?|曜)?$").find(s)?.let { return DAY_JA[it.groupValues[1][0]] }
        // 韩文：월요일 / 월
        Regex("^([월화수목금토일])(?:요일)?$").find(s)?.let { return DAY_KO[it.groupValues[1][0]] }
        // 拉丁字母：取开头的字母部分，整词或缩写，后面可以跟点和日期
        val m = Regex("^([A-Za-zÀ-ÿ]+)\\.?(?:[\\d/.\\-]*)$").find(s) ?: return null
        return LAT_MAP[m.groupValues[1].lowercase()]
    }

    fun secOf(t0: String): Int? {
        val s = t0.trim().replace(" ", "").lowercase()
        if (':' in s || '：' in s || s.length > 12) return null
        Regex("^第?(\\d{1,2})(?:节|節|限|時限|時間目|교시|\\.|st|nd|rd|th|e|ème|ª|º|\\.?stunde|\\.?std\\.?)?$").find(s)?.let {
            return it.groupValues[1].toIntOrNull()?.takeIf { n -> n in 1..16 }
        }
        Regex("^(?:period|p|lesson|cours|clase|stunde|std\\.?|h)(\\d{1,2})$").find(s)?.let { return it.groupValues[1].toIntOrNull()?.takeIf { n -> n in 1..16 } }
        Regex("^第?([一二三四五六七八九十]{1,3})(?:节|節|限)$").find(s)?.let { return cn(it.groupValues[1]) }
        return null
    }
    private fun cn(s: String): Int? {
        val d = "一二三四五六七八九"
        return when {
            s == "十" -> 10
            s.startsWith("十") -> 10 + d.indexOf(s[1]) + 1
            s.length == 1 -> d.indexOf(s[0]) + 1
            else -> null
        }
    }

    /** 读图：太小的截图放大一点再认，字越大越准。 */
    private fun load(c: Context, uri: Uri): Bitmap? = try {
        val b = c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            ?: if (uri.scheme == "file") BitmapFactory.decodeFile(uri.path) else null
        if (b == null) null else {
            val k = when { b.width < 900 -> 2f; b.width < 1300 -> 1.5f; else -> 1f }
            if (k > 1f) Bitmap.createScaledBitmap(b, (b.width * k).toInt(), (b.height * k).toInt(), true) else b
        }
    } catch (x: Exception) { null }

    private fun lines(text: Text): List<L> = text.textBlocks.flatMap { b -> b.lines.mapNotNull { l ->
        val bb = l.boundingBox ?: return@mapNotNull null
        val parts = l.elements.mapNotNull { e -> e.boundingBox?.let { L(e.text, R(it.left, it.top, it.right, it.bottom)) } }
        L(l.text, R(bb.left, bb.top, bb.right, bb.bottom), parts)
    } }

    /** 识别结果回调：成功给出课的列表（时间按「每节课时间」换算），失败给出原因。 */
    fun read(c: Context, uri: Uri, done: (List<Slot>?, String?) -> Unit) {
        val bmp = load(c, uri) ?: run { done(null, tr("图片打不开")); return }
        val img = InputImage.fromBitmap(bmp, 0)
        val sec: (Int, Int) -> Pair<Int, Int> = { a, b -> Scenes.secRange(c, a, b) }
        // 先用最可能的识别器，认不出再换
        val order = when (Tr.lang) { "ja" -> listOf("ja", "zh", "ko"); "ko" -> listOf("ko", "zh", "ja"); else -> listOf("zh", "ja", "ko") }
        var best: List<Slot>? = null; var bestLines: List<L> = emptyList(); var err: String? = null
        fun next(i: Int) {
            if (i >= order.size || (best?.size ?: 0) >= 3) {
                last = bestLines
                if (best.isNullOrEmpty()) done(null, err ?: tr("没认出课程，换一张更清楚、带星期表头的截图试试")) else done(best, null)
                return
            }
            val client = when (order[i]) {
                "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                else -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            }
            client.process(img).addOnSuccessListener { text ->
                val ls = lines(text)
                if (bestLines.isEmpty()) bestLines = ls
                try {
                    val r = parse(ls, bmp.height, sec)
                    if (r.size > (best?.size ?: 0)) { best = r; bestLines = ls }
                } catch (x: Exception) { Diag.log("课表识别 ${order[i]}：${x.message}"); if (err == null) err = tr("没找到「周一、周二…」那一行，换一张带星期表头的截图试试") }
                client.close(); next(i + 1)
            }.addOnFailureListener { client.close(); if (err == null) err = tr("识别失败：{0}", it.message ?: ""); next(i + 1) }
        }
        next(0)
    }

    /** 纯计算，方便单元测试：sec 把「第 a 到 b 节」换成分钟。 */
    fun parse(all0: List<L>, imgH: Int, sec: (Int, Int) -> Pair<Int, Int>): List<Slot> {
        // 表头被认成一整行（「周一 周二 周三」）时拆回一个个词
        val all = all0.flatMap { l -> if (l.parts.size >= 2 && l.parts.count { dayOf(it.t) != null } >= 2) l.parts else listOf(l) }
        // 表头：每个星期几只留最上面那个；要求它们大致在同一高度
        val cand = all.mapNotNull { l -> dayOf(l.t)?.let { it to l } }
        if (cand.isEmpty()) throw IllegalStateException("no header")
        val rowY = cand.groupBy { (it.second.cy / 40).toInt() }.maxBy { e -> e.value.map { it.first }.toSet().size }.value.first().second.cy
        val heads = cand.filter { abs(it.second.cy - rowY) < 60 }.groupBy { it.first }.mapValues { e -> e.value.minBy { it.second.r.top }.second }
        if (heads.size < 3) throw IllegalStateException("no header")
        // 有的表头字没认出来：按已认出的几列等距补齐
        val found = heads.mapValues { it.value.cx }
        val n = found.size.toFloat()
        val md = found.keys.sum() / n; val mx = found.values.sum() / n
        val slope = found.entries.sumOf { ((it.key - md) * (it.value - mx)).toDouble() } / found.keys.sumOf { ((it - md) * (it - md)).toDouble() }
        val lastDay = if ((found.keys.maxOrNull() ?: 5) >= 6) 7 else 5
        val hx = (1..lastDay).associateWith { d -> found[d] ?: (mx + slope * (d - md)).toFloat() }
        val sortedX = hx.values.sorted()
        val colW = (sortedX.last() - sortedX.first()) / (sortedX.size - 1).coerceAtLeast(1)
        val headBottom = heads.values.maxOf { it.r.bottom }
        val leftEdge = sortedX.first() - colW * 0.6f

        // 节次：表格左侧的 1、2、3…
        val secs = all.filter { it.cx < leftEdge && it.r.top > headBottom - 4 }.mapNotNull { l -> secOf(l.t)?.let { it to l.cy } }
            .groupBy { it.first }.mapValues { e -> e.value.minBy { it.second }.second }
        // 拟合 y = a + b * 节次
        val a: Float; val b: Float
        if (secs.size >= 3) {
            val xs = secs.keys.map { it.toFloat() }; val ys = secs.values.toList()
            val mxs = xs.average().toFloat(); val my = ys.average().toFloat()
            val sxy = xs.indices.sumOf { ((xs[it] - mxs) * (ys[it] - my)).toDouble() }.toFloat()
            val sxx = xs.sumOf { ((it - mxs) * (it - mxs)).toDouble() }.toFloat()
            b = if (sxx > 0) sxy / sxx else (imgH - headBottom) / 12f
            a = my - b * mxs
        } else {
            b = (imgH - headBottom) / 12f
            a = headBottom - b / 2f
        }
        if (b <= 2f) throw IllegalStateException("bad row height")

        // 一行字横跨两列（相邻两格的字连在一起被认成一行）：拆成单词再分
        val pieces = all.flatMap { l -> if (l.w > colW * 1.15f && l.parts.size >= 2) l.parts else listOf(l) }
        val timeLike = Regex("^[\\d\\s/.\\-~–—:：月日周週주()（）]+$")
        val body = pieces.filter { it.r.top > headBottom - 2 && it.cx > leftEdge && dayOf(it.t) == null && secOf(it.t) == null && it.t.isNotBlank() &&
            !timeLike.matches(it.t.trim()) }
        val byDay = HashMap<Int, MutableList<L>>()
        for (l in body) {
            val (d, x) = hx.minBy { abs(it.value - l.cx) }
            if (abs(x - l.cx) > colW * 0.6f) continue
            byDay.getOrPut(d) { ArrayList() } += l
        }
        val maxSec = (secs.keys.maxOrNull() ?: 12).coerceAtLeast(4)
        val out = ArrayList<Slot>()
        for ((d, ls) in byDay) {
            // 同一格里的字：上下挨着的合成一格；同一高度左右并排的也算一格
            val cells = ArrayList<MutableList<L>>()
            for (l in ls.sortedWith(compareBy({ it.r.top }, { it.r.left }))) {
                val last = cells.lastOrNull()
                if (last != null && l.r.top - last.maxOf { it.r.bottom } < b * 0.35f) last += l else cells += mutableListOf(l)
            }
            val parsed = cells.mapNotNull { cell ->
                val rows = cell.groupBy { (it.cy / (b * 0.3f).coerceAtLeast(8f)).toInt() }.toSortedMap().values
                    .map { r -> r.sortedBy { it.r.left }.joinToString(" ") { it.t } }
                val name = nameOf(rows) ?: return@mapNotNull null
                val top = cell.minOf { it.r.top }.toFloat(); val bot = cell.maxOf { it.r.bottom }.toFloat()
                val s1 = ((top - a) / b + 0.5f).roundToInt().coerceIn(1, maxSec)
                val s2 = ((bot - a) / b - 0.5f).roundToInt().coerceIn(s1, maxSec)
                Triple(name, s1, s2)
            }
            parsed.forEachIndexed { i, (name, s1, s2raw) ->
                // 文字通常只占格子上半截：没有别的课挡着时，按最常见的两节连上
                val next = parsed.getOrNull(i + 1)?.second ?: (maxSec + 1)
                val s2 = maxOf(s2raw, minOf(s1 + 1, next - 1, maxSec)).coerceAtLeast(s1)
                val (f, t) = sec(s1, s2)
                out += Slot("o$d-$s1-${name.hashCode()}", name, setOf(d), f, t)
            }
        }
        // 同一天同名、时间挨着的并成一节
        return out.groupBy { it.name to it.days }.flatMap { (_, l) ->
            val s = l.sortedBy { it.from }; val m = ArrayList<Slot>()
            for (x in s) { val y = m.lastOrNull(); if (y != null && x.from <= y.to + 15) m[m.lastIndex] = y.copy(to = maxOf(y.to, x.to)) else m += x }
            m
        }.sortedWith(compareBy({ it.days.first() }, { it.from }))
    }

    /** 教室、老师、周次这些不是课名。 */
    private val NOISE = Regex(
        "\\d+[-~～]\\d+(周|週|주)|第?\\d+(-\\d+)?(节|節|限)|^\\d+(周|週|주)|[A-Za-z]?\\d{3,}|教室|楼|室$|老师|教师|单周|双周|\\(|（|" +
            "(?i)\\b(room|rm\\.?|hall|bldg|building|prof\\.?|dr\\.|teacher|weeks?|wk|salle|bât|amphi|raum|hörsaal|gebäude|aula|sala|edificio|semanas?|semaines?|wochen?)\\b|" +
            "講義室|号館|号室|先生|강의실|교실|호관|교수"
    )

    /** 从格子里的几行字里挑出课名：去掉教室、老师、周次这些。 */
    fun nameOf(ls: List<String>): String? {
        val parts = ArrayList<String>()
        for (raw0 in ls) {
            val raw = raw0.trim().let { r -> val i = r.indexOfAny(charArrayOf('@', '＠')); if (i > 0) r.substring(0, i) else r }
            val t = raw.trim().trimStart('@', '＠')
            if (t.isEmpty()) continue
            val noise = raw0.trim().startsWith("@") || raw0.trim().startsWith("＠") || NOISE.containsMatchIn(t)
            if (noise) { if (parts.isNotEmpty()) break else continue }
            parts += t
            if (parts.size == 5) break
        }
        // 中日韩字直接连上，拉丁字母之间留空格
        val n = parts.fold("") { acc, p ->
            if (acc.isEmpty()) p
            else if (acc.last().isLetter() && acc.last().code < 0x2E80 && p.first().isLetter() && p.first().code < 0x2E80) "$acc $p" else acc + p
        }.replace(Regex("\\s+"), " ").replace(Regex("(?<=[\\u3040-\\u30FF\\u4E00-\\u9FFF]) (?=[\\u3040-\\u30FF\\u4E00-\\u9FFF])"), "").trim().take(30)
        return n.takeIf { it.length >= 2 }
    }
}
