package com.liubai.guard

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 课表截图识别：全程在手机上做，图片不上传。
 * 思路：先找表头「周一…周日」定出每一列，再找左边的节次数字定出每一行，
 * 然后把格子里的文字按列归到星期几、按上下位置换算成第几节到第几节。
 */
object Ocr {
    /** 一行字和它在图上的位置。 */
    class R(val left: Int, val top: Int, val right: Int, val bottom: Int)
    class L(val t: String, val r: R) { val cx get() = (r.left + r.right) / 2f; val cy get() = (r.top + r.bottom) / 2f }

    private val DAY = mapOf('一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '日' to 7, '天' to 7)
    private val EN = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

    private fun dayOf(t: String): Int? {
        val s = t.trim().replace(" ", "")
        Regex("^(?:周|星期|礼拜)([一二三四五六日天])").find(s)?.let { return DAY[it.groupValues[1][0]] }
        if (s.length == 1 && s[0] in DAY) return DAY[s[0]]
        val l = s.lowercase(); EN.forEachIndexed { i, e -> if (l.startsWith(e)) return i + 1 }
        return null
    }
    private fun secOf(t: String): Int? {
        val s = t.trim().replace(" ", "")
        Regex("^第?(\\d{1,2})节?$").find(s)?.let { return it.groupValues[1].toIntOrNull()?.takeIf { n -> n in 1..16 } }
        Regex("^第?([一二三四五六七八九十]{1,3})节$").find(s)?.let { return cn(it.groupValues[1]) }
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

    /** 识别结果回调：成功给出课的列表（时间按「每节课时间」换算），失败给出原因。 */
    fun read(c: Context, uri: Uri, done: (List<Slot>?, String?) -> Unit) {
        val img = try { InputImage.fromFilePath(c, uri) } catch (x: Exception) { done(null, "图片打不开"); return }
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()).process(img)
            .addOnSuccessListener { text ->
                val multi = Regex("(周|星期)[一二三四五六日天]")
                val lines = text.textBlocks.flatMap { b -> b.lines.flatMap { l ->
                    // 表头几个「周一 周二…」被认成一行时，拆回一个个词
                    val parts = if (multi.findAll(l.text).count() >= 2) l.elements.map { it.text to it.boundingBox } else listOf(l.text to l.boundingBox)
                    parts.mapNotNull { (t, bb) -> bb?.let { L(t, R(it.left, it.top, it.right, it.bottom)) } }
                } }
                try { val r = parse(lines, img.height) { a, b -> Scenes.secRange(c, a, b) }; if (r.isEmpty()) done(null, "没认出课程，换一张更清楚、带星期表头的截图试试") else done(r, null) }
                catch (x: Exception) { Diag.log("课表识别出错 ${x.message}"); done(null, "没认出来：${x.message ?: "格式不太一样"}") }
            }
            .addOnFailureListener { done(null, "识别失败：${it.message ?: ""}") }
    }

    /** 纯计算，方便单元测试：sec 把「第 a 到 b 节」换成分钟。 */
    fun parse(all: List<L>, imgH: Int, sec: (Int, Int) -> Pair<Int, Int>): List<Slot> {
        // 表头：每个星期几只留最上面那个
        val heads = all.mapNotNull { l -> dayOf(l.t)?.let { it to l } }.groupBy { it.first }.mapValues { e -> e.value.minBy { it.second.r.top }.second }
        if (heads.size < 3) throw IllegalStateException("没找到「周一、周二…」那一行")
        val hx = heads.mapValues { it.value.cx }
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
            val mx = xs.average().toFloat(); val my = ys.average().toFloat()
            val sxy = xs.indices.sumOf { ((xs[it] - mx) * (ys[it] - my)).toDouble() }.toFloat()
            val sxx = xs.sumOf { ((it - mx) * (it - mx)).toDouble() }.toFloat()
            b = if (sxx > 0) sxy / sxx else (imgH - headBottom) / 12f
            a = my - b * mx
        } else {
            b = (imgH - headBottom) / 12f
            a = headBottom - b / 2f
        }
        if (b <= 2f) throw IllegalStateException("行高不对")

        // 格子里的文字：按列归到星期几，同一列里上下挨着的合成一格
        val body = all.filter { it.r.top > headBottom - 2 && it.cx > leftEdge && dayOf(it.t) == null && secOf(it.t) == null && it.t.isNotBlank() &&
            !Regex("^[\\d\\s/.\\-月日:：]+$").matches(it.t.trim()) }
        val byDay = HashMap<Int, MutableList<L>>()
        for (l in body) {
            val (d, x) = hx.minBy { abs(it.value - l.cx) }
            if (abs(x - l.cx) > colW * 0.6f) continue
            byDay.getOrPut(d) { ArrayList() } += l
        }
        val out = ArrayList<Slot>()
        for ((d, ls) in byDay) {
            val cells = ArrayList<MutableList<L>>()
            for (l in ls.sortedBy { it.r.top }) {
                val last = cells.lastOrNull()
                if (last != null && l.r.top - last.maxOf { it.r.bottom } < b * 0.35f) last += l else cells += mutableListOf(l)
            }
            val maxSec = (secs.keys.maxOrNull() ?: 12).coerceAtLeast(4)
            val parsed = cells.mapNotNull { cell ->
                val name = nameOf(cell.map { it.t }) ?: return@mapNotNull null
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

    /** 从格子里的几行字里挑出课名：去掉教室、老师、周次这些。 */
    private fun nameOf(ls: List<String>): String? {
        val parts = ArrayList<String>()
        for (raw0 in ls) {
            val raw = raw0.trim().let { r -> val i = r.indexOfAny(charArrayOf('@', '＠')); if (i > 0) r.substring(0, i) else r }
            val t = raw.trim().trimStart('@', '＠')
            if (t.isEmpty()) continue
            val noise = raw.trim().startsWith("@") || raw.trim().startsWith("＠") ||
                Regex("\\d+[-~～]\\d+周|第?\\d+(-\\d+)?节|^\\d+周|[A-Za-z]?\\d{3,}|教室|楼|室$|老师|单周|双周|\\(|（").containsMatchIn(t)
            if (noise) { if (parts.isNotEmpty()) break else continue }
            parts += t
            if (parts.size == 2) break
        }
        val n = parts.joinToString("").replace(Regex("\\s+"), "").take(20)
        return n.takeIf { it.length >= 2 }
    }
}
