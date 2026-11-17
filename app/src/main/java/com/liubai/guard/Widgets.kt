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

object Widgets {
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

    fun updateAll(c: Context) {
        val m = AppWidgetManager.getInstance(c) ?: return
        val rows = Stats.rows(c)
        val (used, total) = Stats.totals(rows)
        val left = (total - used).coerceAtLeast(0)

        val ringIds = m.getAppWidgetIds(ComponentName(c, RingWidget::class.java))
        if (ringIds.isNotEmpty()) {
            val v = RemoteViews(c.packageName, R.layout.widget_ring)
            v.setImageViewBitmap(R.id.ring, ring(if (total == 0) 0f else used.toFloat() / total))
            v.setTextViewText(R.id.big, if (total == 0) "—" else left.toString())
            v.setTextViewText(R.id.small, if (total == 0) "还没开始守护" else if (left == 0) "今天休息啦" else "分钟可用")
            v.setOnClickPendingIntent(R.id.root, open(c))
            m.updateAppWidget(ringIds, v)
        }

        val listIds = m.getAppWidgetIds(ComponentName(c, ListWidget::class.java))
        if (listIds.isNotEmpty()) {
            val v = RemoteViews(c.packageName, R.layout.widget_list)
            v.setTextViewText(R.id.total, if (total == 0) "点我添加应用" else "还剩 $left / $total 分钟")
            val slots = listOf(
                intArrayOf(R.id.r0, R.id.i0, R.id.n0, R.id.u0, R.id.p0, R.id.q0),
                intArrayOf(R.id.r1, R.id.i1, R.id.n1, R.id.u1, R.id.p1, R.id.q1),
                intArrayOf(R.id.r2, R.id.i2, R.id.n2, R.id.u2, R.id.p2, R.id.q2)
            )
            val shown = rows.sortedByDescending { it.frac }.take(3)
            for ((i, s) in slots.withIndex()) {
                val row = shown.getOrNull(i)
                if (row == null) { v.setViewVisibility(s[0], View.GONE); continue }
                v.setViewVisibility(s[0], View.VISIBLE)
                val info = Apps.info(c, row.pkg)
                try {
                    val d = c.packageManager.getApplicationIcon(row.pkg)
                    val b = Bitmap.createBitmap(72, 72, Bitmap.Config.ARGB_8888)
                    d.setBounds(0, 0, 72, 72); d.draw(Canvas(b))
                    v.setImageViewBitmap(s[1], b)
                } catch (x: Exception) {}
                v.setTextViewText(s[2], info.label)
                v.setTextViewText(s[3], if (row.done) "明天见" else "${row.usedMin}/${row.limit}")
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
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = Widgets.updateAll(c)
}

class ListWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) = Widgets.updateAll(c)
}
