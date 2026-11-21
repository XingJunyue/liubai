package com.liubai.guard

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextPaint
import android.text.TextUtils
import android.widget.RemoteViews
import java.time.LocalDate

/** 课程等级的标签颜色（水课 / 普通 / 重要），课表和小部件共用。 */
object LevelTag {
    val FG = intArrayOf(0xFF4E8A62.toInt(), 0xFF4F74A6.toInt(), 0xFFC46F52.toInt())
    val BG = intArrayOf(0xFFE3F1E7.toInt(), 0xFFE1EAF6.toInt(), 0xFFF8E6D8.toInt())
}

/**
 * 课表小部件：4×2 和 2×2。上面是正在上的课（课间就是最近的下一节），下面一条是再下一节；
 * 今天没课 / 课都上完了就是白底加颜文字。整张画成一张图，排版和预览图一致。
 */
object CourseWidgets {
    /** 今天的课排好后，此刻该显示什么（纯函数，单测）。 */
    data class Now(val main: Slot?, val live: Boolean, val next: Slot?, val total: Int, val index: Int, val doneAll: Boolean)

    fun plan(today: List<Slot>, m: Int): Now {
        val l = today.sortedBy { it.from }
        if (l.isEmpty()) return Now(null, false, null, 0, 0, false)
        val cur = l.firstOrNull { m >= it.from && m < it.to }
        if (cur != null) {
            val next = l.firstOrNull { it !== cur && it.from >= cur.from && it.from > m }
            return Now(cur, true, next, l.size, l.indexOf(cur) + 1, false)
        }
        val up = l.filter { it.from > m }
        if (up.isEmpty()) return Now(null, false, null, l.size, l.size, true)
        return Now(up[0], false, up.getOrNull(1), l.size, l.indexOf(up[0]) + 1, false)
    }

    /** 今天（按周次）要上的课。 */
    fun today(c: Context): List<Slot> {
        val w = Scenes.weekNow(c)
        val d = Tasks.weekday()
        return Scenes.desired(c).filter { it.kind == "course" && d in it.days && it.from < it.to && it.inWeek(w) }.sortedBy { it.from }
    }

    private val KAO = listOf("(ﾉ≧∀≦)ﾉ ♪", "ヽ(✿ﾟ▽ﾟ)ノ", "(๑˃ᴗ˂)ﻭ ✧", "٩(ˊᗜˋ*)و ♡", "(*≧ω≦)ﾉ ☆", "ヾ(≧▽≦*)o")
    private val KAO_DONE = listOf("(๑•̀ㅂ•́)و✧", "ヽ(°▽°)ノ ✿", "(ﾉ´ヮ`)ﾉ*: ･ﾟ", "o(≧▽≦)o ♪")

    private var lastSig = ""
    private var lastMin = -1

    /** 守护服务每次检查都会调：分钟数没变就什么都不做（亮屏时每分钟最多重画一次）。 */
    fun tick(c: Context) {
        val mm = Rules.nowMin()
        if (mm == lastMin) return
        lastMin = mm
        update(c, false)
    }

    fun update(c: Context, force: Boolean = true) {
        val m = AppWidgetManager.getInstance(c) ?: return
        val big = m.getAppWidgetIds(ComponentName(c, CourseWidget4::class.java))
        val small = m.getAppWidgetIds(ComponentName(c, CourseWidget2::class.java))
        if (big.isEmpty() && small.isEmpty()) return
        val sig = "${Rules.nowMin()}|${Scenes.todayEpoch()}|${Scenes.desired(c).hashCode()}|${Scenes.widgetLevel(c)}|${Tr.lang}"
        if (!force && sig == lastSig) return
        lastSig = sig
        val list = today(c)
        val now = plan(list, Rules.nowMin())
        val all = Scenes.desired(c).filter { it.kind == "course" }
        alarm(c, list)
        for ((ids, wide) in listOf(big to true, small to false)) for (id in ids) {
            try {
                val o = m.getAppWidgetOptions(id)
                val v = RemoteViews(c.packageName, R.layout.widget_course)
                v.setImageViewBitmap(R.id.canvas, draw(c, wide, o, now, all))
                v.setOnClickPendingIntent(R.id.root, PendingIntent.getActivity(c, 7,
                    Intent(c, MainActivity::class.java).putExtra("open", "table").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                m.updateAppWidget(id, v)
            } catch (x: Exception) { Diag.log("课表小部件画不出来：${x.message}") }
        }
    }

    const val REFRESH = "com.liubai.guard.COURSE_WIDGET_REFRESH"

    /** 守护服务没在跑时的兜底：在下一次上课 / 下课的那一分钟叫醒自己重画（系统省电时可能晚一点）。 */
    private fun alarm(c: Context, list: List<Slot>) {
        try {
            val m = Rules.nowMin()
            val next = list.flatMap { listOf(it.from, it.to) }.filter { it > m }.minOrNull() ?: (24 * 60)
            val cal = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 1); set(java.util.Calendar.MILLISECOND, 0)
                add(java.util.Calendar.MINUTE, next)
            }
            val pi = PendingIntent.getBroadcast(c, 71, Intent(c, CourseWidget4::class.java).setAction(REFRESH),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val am = c.getSystemService(android.app.AlarmManager::class.java) ?: return
            am.setAndAllowWhileIdle(android.app.AlarmManager.RTC, cal.timeInMillis, pi)
        } catch (x: Exception) {}
    }

    // ---------------- 画图 ----------------
    private const val INK = 0xFF3D3530.toInt()
    private const val SUB = 0xFF8C7E72.toInt()
    private const val ACC = 0xFFC46F52.toInt()
    private const val CREAM = 0xFFFBF6EF.toInt()
    private const val NEXT_BG = 0xFFEAF5EC.toInt()
    private const val NEXT_FG = 0xFF58966C.toInt()

    private fun draw(c: Context, wide: Boolean, o: Bundle?, now: Now, all: List<Slot>): Bitmap {
        val dm = c.resources.displayMetrics
        // 竖屏时桌面给的是 minWidth × maxHeight
        var wDp = (o?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0).toFloat()
        var hDp = (o?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) ?: 0).toFloat()
        if (wDp < 60f) wDp = if (wide) 320f else 156f
        if (hDp < 60f) hDp = 156f
        // 设计稿：4×2 是 320×156dp，2×2 是 156×156dp；实际尺寸不同就整体等比缩放，留白处往四周铺开
        val k = minOf(wDp / (if (wide) 320f else 156f), hDp / 156f).coerceIn(0.72f, 1.35f)
        val lw = wDp / k; val lh = hDp / k
        val px = (dm.density.coerceAtMost(2.75f)) * k
        val b = Bitmap.createBitmap((lw * px).toInt().coerceAtLeast(1), (lh * px).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val cv = Canvas(b); cv.scale(px, px)
        val d = D(cv)
        val free = now.main == null
        d.round(0f, 0f, lw, lh, 22f, if (free) 0xFFFFFFFF.toInt() else CREAM)
        when {
            free -> freeCard(d, lw, lh, wide, now.doneAll)
            wide -> wideCard(c, d, lw, lh, now, all)
            else -> smallCard(c, d, lw, lh, now, all)
        }
        return b
    }

    private class D(val cv: Canvas) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val t = TextPaint(Paint.ANTI_ALIAS_FLAG)
        fun round(l: Float, tp: Float, r: Float, bt: Float, rad: Float, col: Int) { p.style = Paint.Style.FILL; p.color = col; cv.drawRoundRect(RectF(l, tp, r, bt), rad, rad, p) }
        /** 画一行字，(x, top) 为左上角；align 0 左、1 居中、2 右；放不下就省略号。返回宽度。 */
        fun text(s: String, x: Float, top: Float, size: Float, col: Int, bold: Boolean = false, align: Int = 0, maxW: Float = 9999f): Float {
            t.textSize = size; t.color = col; t.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            val e = TextUtils.ellipsize(s, t, maxW, TextUtils.TruncateAt.END).toString()
            val w = t.measureText(e)
            val fm = t.fontMetrics
            val base = top - fm.ascent - (fm.descent - fm.ascent - size * 1.18f) / 2
            val sx = when (align) { 1 -> x - w / 2; 2 -> x - w; else -> x }
            cv.drawText(e, sx, base, t); return w
        }
        fun width(s: String, size: Float, bold: Boolean = false): Float { t.textSize = size; t.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT; return t.measureText(s) }
        fun chip(s: String, x: Float, top: Float, size: Float, fg: Int, bg: Int, bold: Boolean = false, right: Boolean = false): Float {
            val w = width(s, size, bold) + 12f; val h = size + 8f
            val l = if (right) x - w else x
            round(l, top, l + w, top + h, h / 2, bg); text(s, l + w / 2, top + 4f - size * 0.09f, size, fg, bold, 1); return w
        }
        fun bar(l: Float, cy: Float, r: Float, h: Float, frac: Float, track: Int, fill: Int) {
            round(l, cy - h / 2, r, cy + h / 2, h / 2, track)
            if (frac > 0f) round(l, cy - h / 2, l + (r - l) * frac.coerceIn(0.04f, 1f), cy + h / 2, h / 2, fill)
        }
        fun clock(x: Float, y: Float, col: Int) {
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.3f; p.color = col; p.strokeCap = Paint.Cap.ROUND
            cv.drawCircle(x + 5f, y + 5f, 4.4f, p); cv.drawLine(x + 5f, y + 2.6f, x + 5f, y + 5.2f, p); cv.drawLine(x + 5f, y + 5.2f, x + 6.9f, y + 6.4f, p)
            p.style = Paint.Style.FILL
        }
        fun pin(x: Float, y: Float, col: Int, hole: Int) {
            p.style = Paint.Style.FILL; p.color = col
            cv.drawCircle(x + 4f, y + 4f, 4f, p)
            cv.drawPath(Path().apply { moveTo(x + 0.8f, y + 5.6f); lineTo(x + 7.2f, y + 5.6f); lineTo(x + 4f, y + 10.5f); close() }, p)
            p.color = hole; cv.drawCircle(x + 4f, y + 4f, 1.5f, p)
        }
        fun person(x: Float, y: Float, col: Int) {
            p.style = Paint.Style.FILL; p.color = col
            cv.drawCircle(x + 4.5f, y + 2.6f, 2.5f, p)
            cv.drawArc(RectF(x, y + 5.6f, x + 9f, y + 15f), 180f, 180f, true, p)
        }
        fun spark(x: Float, y: Float, r: Float, col: Int) {
            p.style = Paint.Style.FILL; p.color = col
            val q = r * 0.28f
            cv.drawPath(Path().apply { moveTo(x, y - r); lineTo(x + q, y - q); lineTo(x + r, y); lineTo(x + q, y + q); lineTo(x, y + r); lineTo(x - q, y + q); lineTo(x - r, y); lineTo(x - q, y - q); close() }, p)
        }
    }

    private fun hm(m: Int) = "%d:%02d".format(m / 60, m % 60)
    private fun header(): String {
        val t = LocalDate.ofEpochDay(Scenes.todayEpoch())
        return dayLabel(Tasks.weekday()) + " " + tr("{0}月{1}日", t.monthValue, t.dayOfMonth)
    }
    private fun secText(c: Context, s: Slot): String? {
        val st = Scenes.secStarts(c); val en = Scenes.secEnds(c)
        val (a, b) = Scenes.secSpan(st, en, s.from, s.to)
        if (kotlin.math.abs((st.getOrNull(a - 1) ?: -999) - s.from) > 10) return null   // 自定义时间的课不硬套节次
        return if (a == b) tr("第 {0} 节", a) else tr("{0}-{1}节", a, b)
    }
    private fun leftText(min: Int) = if (min >= 60) tr("{0} 小时 {1} 分", min / 60, min % 60) else tr("{0} 分钟", min)
    private fun untilText(min: Int) = if (min >= 60) tr("{0} 小时后", (min + 30) / 60) else tr("{0} 分钟后", min.coerceAtLeast(1))

    private fun wideCard(c: Context, d: D, w: Float, h: Float, n: Now, all: List<Slot>) {
        val s = n.main!!; val m = Rules.nowMin(); val p = 15f; val L = p; val R = w - p
        val week = Scenes.weekNow(c)
        d.text(header() + (if (week != null) " · " + tr("第 {0} 周", week) else ""), L, 11f, 11f, SUB, maxW = w * 0.55f)
        d.text(tr("今天 {0} 节 · 第 {1} 节", n.total, n.index), R, 11f, 11f, SUB, align = 2)
        val (lightC, deepC) = courseColorArgb(s, all)
        val nextH = if (n.next != null) 46f else 0f
        val top = 33f
        val bottom = h - p - (if (n.next != null) nextH + 10f else 0f)
        d.round(L, top + 2f, L + 4f, bottom - 2f, 2f, deepC)
        val cx = L + 12f
        // 右上角的标签：上课中 / 还有多久，等级
        var cr = R
        cr -= d.chip(if (n.live) tr("上课中") else untilText(s.from - m), cr, top + 1f, 9.5f, if (n.live) 0xFFFFFFFF.toInt() else ACC, if (n.live) ACC else 0xFFF8E6D8.toInt(), true, right = true) + 6f
        if (Scenes.widgetLevel(c)) cr -= d.chip(Slot.LEVELS[s.level.coerceIn(0, 2)], cr, top + 1f, 9.5f, LevelTag.FG[s.level.coerceIn(0, 2)], LevelTag.BG[s.level.coerceIn(0, 2)], right = true) + 6f
        d.text(s.name, cx, top - 2f, 17f, INK, true, maxW = cr - cx - 4f)
        // 时间 · 节次  教室  老师
        val f = 11f; var x = cx; val y = top + 25f
        d.clock(x, y + 1.5f, SUB); x += 14f
        val tt = hm(s.from) + "–" + hm(s.to) + (secText(c, s)?.let { " · $it" } ?: "")
        x += d.text(tt, x, y - 1f, f, INK) + 14f
        if (s.room.isNotBlank() && x < R - 40f) { d.pin(x + 1f, y + 1f, SUB, CREAM); x += 14f; x += d.text(s.room, x, y - 1f, f, INK, maxW = R - x - (if (s.teacher.isNotBlank()) 60f else 0f)) + 14f }
        if (s.teacher.isNotBlank() && x < R - 30f) { d.person(x, y + 1f, SUB); x += 13f; d.text(s.teacher, x, y - 1f, f, INK, maxW = R - x) }
        // 进度 / 还有多久开始
        val py = minOf(top + 49f, bottom - 3f)
        val lbl = if (n.live) tr("还剩 {0}", leftText(s.to - m)) else tr("{0} 开始", hm(s.from))
        val bl = R - d.width(lbl, 9.5f) - 8f
        val frac = if (n.live) (m - s.from).toFloat() / (s.to - s.from) else 0f
        d.bar(cx, py, bl, 5f, frac, lightC, deepC)
        d.text(lbl, R, py - 7f, 9.5f, ACC, align = 2)
        // 下一节
        n.next?.let { nx ->
            val ny = h - p - nextH
            d.round(L, ny, R, h - p, 12f, NEXT_BG)
            val q = L + 11f
            val head = tr("下一节 {0}", hm(nx.from)) + (secText(c, nx)?.let { " · $it" } ?: "")
            d.text(head, q, ny + 6f, 10f, NEXT_FG, true, maxW = w * 0.55f)
            d.text(untilText(nx.from - m), R - 11f, ny + 6f, 10f, NEXT_FG, align = 2)
            val info = listOf(nx.room, nx.teacher).filter { it.isNotBlank() }.joinToString(" · ")
            val iw = if (info.isEmpty()) 0f else minOf(d.width(info, 10.5f), (R - q) * 0.45f)
            d.text(nx.name, q, ny + 22f, 12.5f, INK, true, maxW = R - 11f - q - iw - 10f)
            if (info.isNotEmpty()) d.text(info, R - 11f, ny + 24f, 10.5f, SUB, align = 2, maxW = iw)
        }
    }

    private fun smallCard(c: Context, d: D, w: Float, h: Float, n: Now, all: List<Slot>) {
        val s = n.main!!; val m = Rules.nowMin(); val p = 13f; val L = p; val R = w - p
        val (lightC, deepC) = courseColorArgb(s, all)
        d.chip(if (n.live) tr("上课中") else untilText(s.from - m), L, p - 2f, 9f, if (n.live) 0xFFFFFFFF.toInt() else ACC, if (n.live) ACC else 0xFFF8E6D8.toInt(), true)
        if (Scenes.widgetLevel(c)) d.chip(Slot.LEVELS[s.level.coerceIn(0, 2)], R, p - 2f, 9f, LevelTag.FG[s.level.coerceIn(0, 2)], LevelTag.BG[s.level.coerceIn(0, 2)], right = true)
        val top = 37f
        d.round(L, top + 2f, L + 4f, top + 46f, 2f, deepC)
        val cx = L + 10f
        d.text(s.name, cx, top - 3f, 15f, INK, true, maxW = R - cx)
        d.text(hm(s.from) + "–" + hm(s.to), cx, top + 17f, 10.5f, INK, maxW = R - cx)
        val info = listOf(s.room, s.teacher).filter { it.isNotBlank() }.joinToString(" · ")
        if (info.isNotEmpty()) d.text(info, cx, top + 31f, 10.5f, SUB, maxW = R - cx)
        val py = top + 56f
        if (n.live) {
            val lbl = tr("还剩 {0}", leftText(s.to - m))
            val bl = R - d.width(lbl, 9f) - 6f
            d.bar(L, py, bl, 4f, (m - s.from).toFloat() / (s.to - s.from), lightC, deepC)
            d.text(lbl, R, py - 6.5f, 9f, ACC, align = 2)
        } else d.text(tr("{0} 开始", hm(s.from)), L, py - 6.5f, 9.5f, ACC)
        val ny = py + 9f
        val nb = h - p
        if (nb - ny < 26f) return
        d.round(L, ny, R, nb, 11f, NEXT_BG)
        val nx = n.next
        if (nx == null) { d.text(tr("这是今天最后一节"), (L + R) / 2, ny + (nb - ny) / 2 - 7f, 10f, NEXT_FG, true, 1); return }
        d.text(tr("下一节 {0}", hm(nx.from)) + (if (nx.room.isNotBlank()) " · " + nx.room else ""), L + 9f, ny + 5f, 9f, NEXT_FG, true, maxW = R - L - 18f)
        d.text(nx.name, L + 9f, ny + 19f, 11.5f, INK, true, maxW = R - L - 18f)
    }

    private fun freeCard(d: D, w: Float, h: Float, wide: Boolean, done: Boolean) {
        val day = Scenes.todayEpoch().toInt()
        val kao = if (done) KAO_DONE[Math.floorMod(day, KAO_DONE.size)] else KAO[Math.floorMod(day, KAO.size)]
        val title = if (done) tr("今天的课上完啦") else tr("今天没课啦")
        val pink = 0xFFFF8AAA.toInt(); val yel = 0xFFFFCE5C.toInt(); val mint = 0xFF84D6BE.toInt(); val lav = 0xFFB2A0F0.toInt()
        val cx = w / 2
        if (wide) {
            d.text(header(), 15f, 12f, 11f, SUB)
            d.text(kao, cx, h * 0.40f - 14f, 22f, pink, true, 1, w - 40f)
            d.text(title, cx, h * 0.65f - 12f, 18f, INK, true, 1)
            d.text(if (done) tr("辛苦啦，好好放松一下 ♡") else tr("去做点喜欢的事吧 ♡"), cx, h * 0.82f - 7f, 11f, SUB, align = 1)
            for (s in listOf(floatArrayOf(.14f, .52f, 8f), floatArrayOf(.20f, .78f, 5f), floatArrayOf(.84f, .28f, 7f), floatArrayOf(.88f, .64f, 9f), floatArrayOf(.78f, .86f, 5f), floatArrayOf(.27f, .27f, 5f)).withIndex())
                d.spark(w * s.value[0], h * s.value[1], s.value[2], listOf(yel, mint, lav, pink, yel, pink)[s.index])
        } else {
            d.text(kao, cx, h * 0.36f - 11f, 17f, pink, true, 1, w - 24f)
            d.text(title, cx, h * 0.60f - 10f, 15f, INK, true, 1, w - 20f)
            d.text(if (done) tr("辛苦啦 ♡") else tr("休息一下吧 ♡"), cx, h * 0.77f - 6f, 10f, SUB, align = 1)
            for (s in listOf(floatArrayOf(.15f, .16f, 6f), floatArrayOf(.85f, .19f, 5f), floatArrayOf(.13f, .87f, 4f), floatArrayOf(.87f, .85f, 7f)).withIndex())
                d.spark(w * s.value[0], h * s.value[1], s.value[2], listOf(yel, lav, mint, pink)[s.index])
        }
    }
}

class CourseWidget4 : AppWidgetProvider() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == CourseWidgets.REFRESH) { CourseWidgets.update(c); return }
        super.onReceive(c, i)
    }
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = run { Keep.heal(c); CourseWidgets.update(c) }
    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, o: Bundle) = CourseWidgets.update(c)
}

class CourseWidget2 : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = run { Keep.heal(c); CourseWidgets.update(c) }
    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, o: Bundle) = CourseWidgets.update(c)
}
