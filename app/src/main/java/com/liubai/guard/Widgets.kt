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
import android.graphics.RectF
import android.view.View
import android.widget.RemoteViews

/** 小部件里展示哪几个应用。空 = 自动（全部）。按勾选顺序排。 */
object WidgetSel {
    private fun p(c: Context) = c.getSharedPreferences("liubai_ui", Context.MODE_PRIVATE)
    fun get(c: Context): List<String> = p(c).getString("widgetPkgs", "")!!.split(",").filter { it.isNotBlank() }
    fun set(c: Context, l: List<String>) { p(c).edit().putString("widgetPkgs", l.joinToString(",")).apply() }
    fun pick(c: Context, all: List<Row>): List<Row> {
        val sel = get(c)
        if (sel.isEmpty()) return all
        val m = all.associateBy { it.pkg }
        val r = sel.mapNotNull { m[it] }
        return if (r.isEmpty()) all else r
    }
}

object Widgets {
    private val iconCache = HashMap<String, Bitmap>()
    private fun ring(frac: Float): Bitmap {
        val s = 300
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        val sw = 28f
        val r = RectF(sw / 2, sw / 2, s - sw / 2, s - sw / 2)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = sw; strokeCap = Paint.Cap.ROUND }
        p.color = 0xFFF3DCCB.toInt(); cv.drawArc(r, 0f, 360f, false, p)
        if (frac > 0f) { p.color = 0xFFE38B6D.toInt(); cv.drawArc(r, -90f, 360f * frac.coerceIn(0f, 1f), false, p) }
        return b
    }

    private fun open(c: Context): PendingIntent = PendingIntent.getActivity(
        c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** 一排排圆形图标，外圈画今天用掉的比例；用满变成暖橘色。 */
    private fun icons(c: Context, rows: List<Row>, lines: Int): Bitmap {
        val cols = 4
        val cell = 240
        val w = cell * cols; val h = cell * lines
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        val sw = 16f
        val ringR = cell * 0.40f
        val iconR = ringR - sw - 8f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = sw; strokeCap = Paint.Cap.ROUND }
        for (i in 0 until cols * lines) {
            val cx = cell * (i % cols) + cell / 2f
            val cy = cell * (i / cols) + cell / 2f
            val row = rows.getOrNull(i)
            val arc = RectF(cx - ringR, cy - ringR, cx + ringR, cy + ringR)
            if (row == null) {
                p.color = 0x22A39589; p.strokeWidth = 4f
                cv.drawCircle(cx, cy, iconR, p)
                p.strokeWidth = sw
                continue
            }
            p.color = 0xFFEDE6DC.toInt(); cv.drawArc(arc, 0f, 360f, false, p)
            val f = row.frac.coerceIn(0f, 1f)
            if (f > 0f) {
                p.color = if (row.done) 0xFFE38B6D.toInt() else 0xFF7FAE8C.toInt()
                cv.drawArc(arc, -90f, 360f * f, false, p)
            }
            try {
                val s = (iconR * 2).toInt()
                val ib = iconCache.getOrPut(row.pkg) {
                val d = c.packageManager.getApplicationIcon(Virt.real(row.pkg))
                val ib = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
                val ic = Canvas(ib)
                val clip = android.graphics.Path().apply { addCircle(s / 2f, s / 2f, s / 2f, android.graphics.Path.Direction.CW) }
                ic.clipPath(clip)
                d.setBounds(-s / 10, -s / 10, s + s / 10, s + s / 10)   // 稍放大，去掉自带的留边
                d.draw(ic)
                ib
                }
                val ip = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                if (row.done) ip.alpha = 110
                cv.drawBitmap(ib, cx - iconR, cy - iconR, ip)
            } catch (x: Exception) {}
        }
        return b
    }

    private var lastSig = ""
    private var lastSigAt = 0L

    fun updateAll(c: Context, force: Boolean = false) {
        val m = AppWidgetManager.getInstance(c) ?: return
        val rows = WidgetSel.pick(c, Stats.rows(c))
        val custom = WidgetSel.get(c).isNotEmpty()
        // 内容没变（分钟数一样）就不重画、不推给桌面，省电省发热；每 30 分钟兜底强刷一次
        val sig = (if (custom) "c" else "a") + rows.joinToString("|") { "${it.pkg}:${it.usedMin}:${it.limit}:${it.done}" }
        val now = System.currentTimeMillis()
        if (!force && sig == lastSig && now - lastSigAt < 30 * 60_000L) return
        lastSig = sig; lastSigAt = now
        for ((cls, lines) in listOf(IconsWidget1::class.java to 1, IconsWidget2::class.java to 2)) {
            val ids = m.getAppWidgetIds(ComponentName(c, cls))
            if (ids.isEmpty()) continue
            val v = RemoteViews(c.packageName, R.layout.widget_icons)
            v.setImageViewBitmap(R.id.canvas, icons(c, rows, lines))
            v.setOnClickPendingIntent(R.id.root, open(c))
            m.updateAppWidget(ids, v)
        }
        val (used, total) = Stats.totals(rows)
        val left = (total - used).coerceAtLeast(0)

        val ringIds = m.getAppWidgetIds(ComponentName(c, RingWidget::class.java))
        if (ringIds.isNotEmpty()) {
            val v = RemoteViews(c.packageName, R.layout.widget_ring)
            v.setImageViewBitmap(R.id.ring, ring(if (total == 0) 0f else used.toFloat() / total))
            v.setTextViewText(R.id.big, if (total == 0) "—" else left.toString())
            v.setTextViewText(R.id.small, if (total == 0) tr("还没开始守护") else if (left == 0) tr("今天休息啦") else tr("分钟可用"))
            v.setOnClickPendingIntent(R.id.root, open(c))
            m.updateAppWidget(ringIds, v)
        }

        val listIds = m.getAppWidgetIds(ComponentName(c, ListWidget::class.java))
        if (listIds.isNotEmpty()) {
            val v = RemoteViews(c.packageName, R.layout.widget_list)
            v.setTextViewText(R.id.total, if (total == 0) tr("点我添加应用") else tr("还剩 {0} / {1} 分钟", left, total))
            val slots = listOf(
                intArrayOf(R.id.r0, R.id.i0, R.id.n0, R.id.u0, R.id.p0, R.id.q0),
                intArrayOf(R.id.r1, R.id.i1, R.id.n1, R.id.u1, R.id.p1, R.id.q1),
                intArrayOf(R.id.r2, R.id.i2, R.id.n2, R.id.u2, R.id.p2, R.id.q2)
            )
            val shown = (if (custom) rows else rows.sortedByDescending { it.frac }).take(3)
            for ((i, s) in slots.withIndex()) {
                val row = shown.getOrNull(i)
                if (row == null) { v.setViewVisibility(s[0], View.GONE); continue }
                v.setViewVisibility(s[0], View.VISIBLE)
                val info = Apps.info(c, row.pkg)
                try {
                    val d = c.packageManager.getApplicationIcon(Virt.real(row.pkg))
                    val b = Bitmap.createBitmap(72, 72, Bitmap.Config.ARGB_8888)
                    d.setBounds(0, 0, 72, 72); d.draw(Canvas(b))
                    v.setImageViewBitmap(s[1], b)
                } catch (x: Exception) {}
                v.setTextViewText(s[2], info.label)
                v.setTextViewText(s[3], if (row.done) tr("明天见") else "${row.usedMin}/${row.limit}")
                val pct = (row.frac * 100).toInt().coerceIn(0, 100)
                v.setViewVisibility(s[4], if (row.done) View.GONE else View.VISIBLE)
                v.setViewVisibility(s[5], if (row.done) View.VISIBLE else View.GONE)
                v.setProgressBar(s[4], 100, pct, false)
            }
            v.setOnClickPendingIntent(R.id.root, open(c))
            m.updateAppWidget(listIds, v)
        }
    }
}

class RingWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = run { Keep.heal(c); Widgets.updateAll(c, force = true) }
}

class ListWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = run { Keep.heal(c); Widgets.updateAll(c, force = true) }
}

class IconsWidget1 : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = run { Keep.heal(c); Widgets.updateAll(c, force = true) }
}

class IconsWidget2 : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = run { Keep.heal(c); Widgets.updateAll(c, force = true) }
}
