package com.liubai.guard

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 按天留下来的记录（不随每天 0:00 清空）：专注分钟、拿起次数、忍住次数。只保留最近 400 天。 */
object Hist {
    private fun p(c: Context) = c.getSharedPreferences("hist", Context.MODE_PRIVATE)
    private val f = SimpleDateFormat("yyyyMMdd", Locale.US)
    fun day(ago: Int): String = f.format(Date(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -ago) }.timeInMillis))

    fun add(c: Context, key: String, v: Long) {
        val k = "${key}_${Store.today()}"
        val pr = p(c)
        pr.edit().putLong(k, pr.getLong(k, 0L) + v).putLong("tot_$key", pr.getLong("tot_$key", 0L) + v).apply()
        tidy(c)
    }
    fun get(c: Context, key: String, ago: Int) = p(c).getLong("${key}_${day(ago)}", 0L)
    fun total(c: Context, key: String) = p(c).getLong("tot_$key", 0L)

    private var tidied = ""
    private fun tidy(c: Context) {
        val t = Store.today(); if (tidied == t) return; tidied = t
        val old = day(400)
        val e = p(c).edit()
        for (k in p(c).all.keys) { val d = k.substringAfterLast('_', ""); if (d.length == 8 && d < old) e.remove(k) }
        e.apply()
    }

    /** 专注分钟（含今天正在进行的）。 */
    fun focusMin(c: Context, ago: Int): Int = if (ago == 0) Focus.todayMin(c) else (get(c, "focus", ago) / 60_000L).toInt()
    fun totalFocusMin(c: Context): Int = ((total(c, "focus")) / 60_000L).toInt() + (if (Focus.phase(c) == Focus.WORK) ((Focus.totalMs(c) - Focus.leftMs(c)) / 60_000L).toInt() else 0)

    /** 连续专注天数：每天专注满 20 分钟算一天；今天还没满不打断。 */
    fun streak(c: Context): Int {
        var n = if (focusMin(c, 0) >= 20) 1 else 0
        var i = 1
        while (i < 400 && focusMin(c, i) >= 20) { n++; i++ }
        return n
    }
    fun bestStreak(c: Context): Int = maxOf(p(c).getInt("best", 0), streak(c)).also { if (it > p(c).getInt("best", 0)) p(c).edit().putInt("best", it).apply() }
}

/** 小芽：随累计专注慢慢长大。 */
object Growth {
    val STAGES = listOf(0 to tr("一粒种子"), 60 to tr("冒出小芽"), 600 to tr("长出新叶"), 3000 to tr("一棵小树"), 12000 to tr("枝繁叶茂"))
    fun stage(min: Int) = STAGES.indexOfLast { min >= it.first }.coerceAtLeast(0)
    fun next(min: Int): Int? = STAGES.getOrNull(stage(min) + 1)?.first
}

data class Badge(val name: String, val how: String, val got: Boolean)

object Badges {
    fun all(c: Context): List<Badge> {
        val tf = Hist.totalFocusMin(c)
        val best = Hist.bestStreak(c)
        val walked = Hist.total(c, "walk")
        return listOf(
            Badge(tr("第一个番茄"), tr("完成一次专注"), tf >= 20),
            Badge(tr("十小时"), tr("累计专注 10 小时"), tf >= 600),
            Badge(tr("五十小时"), tr("累计专注 50 小时"), tf >= 3000),
            Badge(tr("两百小时"), tr("累计专注 200 小时"), tf >= 12000),
            Badge(tr("三天不断"), tr("连续 3 天专注"), best >= 3),
            Badge(tr("一周不断"), tr("连续 7 天专注"), best >= 7),
            Badge(tr("一个月"), tr("连续 30 天专注"), best >= 30),
            Badge(tr("忍住 10 次"), tr("打开前选择不刷"), walked >= 10),
            Badge(tr("忍住 100 次"), tr("打开前选择不刷"), walked >= 100)
        )
    }
}

/** 画一株随阶段变化的植物。 */
@Composable
fun Plant(stage: Int, size: Dp) {
    Canvas(Modifier.size(size)) {
        val u = this.size.width / 100f
        // 土
        drawRoundRect(Color(0xFFE9D9C6), Offset(22f * u, 82f * u), Size(56f * u, 10f * u), CornerRadius(5f * u))
        if (stage == 0) { drawOval(Color(0xFFB98B62), Offset(44f * u, 74f * u), Size(12f * u, 9f * u)); return@Canvas }
        val h = listOf(0f, 18f, 32f, 48f, 60f)[stage.coerceIn(0, 4)]
        val baseY = 82f * u
        drawLine(if (stage >= 3) Color(0xFF9A7357) else C.Sage, Offset(50f * u, baseY), Offset(50f * u, baseY - h * u), (if (stage >= 3) 5f else 3.2f) * u, StrokeCap.Round)
        fun leaf(x: Float, y: Float, dir: Float, s: Float, col: Color) {
            val p = Path().apply {
                moveTo(x, y)
                cubicTo(x + dir * 4f * s, y - 9f * s, x + dir * 12f * s, y - 12f * s, x + dir * 18f * s, y - 10f * s)
                cubicTo(x + dir * 14f * s, y - 2f * s, x + dir * 7f * s, y + 1f * s, x, y)
                close()
            }
            drawPath(p, col)
        }
        if (stage <= 2) {
            leaf(50f * u, baseY - h * u + 4f * u, -1f, u * (if (stage == 1) 0.8f else 1.1f), C.Sage)
            leaf(50f * u, baseY - h * u + 1f * u, 1f, u * (if (stage == 1) 0.8f else 1.1f), C.SageLight)
            if (stage == 2) { leaf(50f * u, baseY - h * u * 0.45f, -1f, u * 0.9f, C.SageLight); leaf(50f * u, baseY - h * u * 0.55f, 1f, u * 0.9f, C.Sage) }
        } else {
            val r = if (stage == 3) 20f else 27f
            drawCircle(C.Sage, r * u, Offset(50f * u, baseY - h * u))
            drawCircle(C.SageLight, r * 0.72f * u, Offset(41f * u, baseY - h * u - 6f * u))
            if (stage == 4) { drawCircle(C.SageLight, 14f * u, Offset(66f * u, baseY - h * u + 6f * u)); drawCircle(C.Clay.copy(alpha = 0.8f), 3f * u, Offset(58f * u, baseY - h * u - 10f * u)); drawCircle(C.Clay.copy(alpha = 0.8f), 3f * u, Offset(38f * u, baseY - h * u + 8f * u)) }
        }
    }
}

private class Trend(val labels: List<String>, val usage: List<Int>, val focus: List<Int>, val pickups: List<Int>)

private fun loadTrend(c: Context): Trend {
    val pkgs = (Store.desired(c).keys + Store.active(c).keys).filter { !Virt.isVirt(it) }
    val lf = SimpleDateFormat("d", Tr.locale())
    val labels = ArrayList<String>(); val us = ArrayList<Int>(); val fo = ArrayList<Int>(); val pk = ArrayList<Int>()
    for (i in 13 downTo 0) {
        val s = Usage.dayStart(i)
        labels += if (i == 0) tr("今") else lf.format(Date(s))
        val m = try { Usage.range(c, s, Usage.dayStart(i - 1)) } catch (x: Exception) { emptyMap() }
        us += (pkgs.sumOf { m[it] ?: 0L } / 60_000L).toInt()
        fo += Hist.focusMin(c, i)
        pk += Hist.get(c, "pick", i).toInt()
    }
    return Trend(labels, us, fo, pk)
}

/** 「数据」页最上面：小芽、连续天数、两周趋势、成就、分享。 */
@Composable
fun InsightsTop() {
    val c = LocalContext.current
    val tf = remember { Hist.totalFocusMin(c) }
    val st = remember { Hist.streak(c) }
    val stage = Growth.stage(tf)
    var tr by remember { mutableStateOf<Trend?>(null) }
    var mode by remember { mutableStateOf(0) }
    var showBadges by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { tr = withContext(Dispatchers.IO) { try { loadTrend(c) } catch (x: Throwable) { null } } }

    SoftCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Plant(stage, 86.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(Growth.STAGES[stage].second, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = C.Ink)
                Text(tr("累计专注 {0}", hmShort(tf)), fontSize = 13.sp, color = C.Soft, modifier = Modifier.padding(top = 3.dp))
                Growth.next(tf)?.let { Text(tr("再专注 {0} 长大一点", hmShort(it - tf)), fontSize = 12.sp, color = C.Faint, modifier = Modifier.padding(top = 2.dp)) }
                Text(if (st > 0) tr("已连续专注 {0} 天", st) else tr("今天专注满 20 分钟就开始计天数"), fontSize = 13.sp, color = C.SageInk, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    SoftCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("两周趋势"), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = C.Ink, modifier = Modifier.weight(1f))
            Box(Modifier.width(170.dp)) { Segments(listOf(tr("刷"), tr("专注"), tr("拿起")), mode) { mode = it } }
        }
        val t = tr
        if (t == null) Text(tr("正在整理…"), fontSize = 13.sp, color = C.Faint, modifier = Modifier.padding(top = 12.dp))
        else {
            val v = when (mode) { 0 -> t.usage; 1 -> t.focus; else -> t.pickups }
            val col = when (mode) { 0 -> C.Clay; 1 -> C.Sage; else -> C.ClayDeep.copy(alpha = 0.7f) }
            val avg = v.take(13).filter { it > 0 }.let { if (it.isEmpty()) 0 else it.sum() / it.size }
            Text(
                when (mode) { 0 -> tr("今天刷了 {0} 分钟 · 平时约 {1} 分钟", v.last(), avg); 1 -> tr("今天专注 {0} 分钟 · 平时约 {1} 分钟", v.last(), avg); else -> tr("今天拿起 {0} 次 · 平时约 {1} 次", v.last(), avg) },
                fontSize = 13.sp, color = C.Soft, modifier = Modifier.padding(top = 10.dp)
            )
            Bars(t.labels, v, col)
        }
    }

    Spacer(Modifier.height(12.dp))
    val badges = remember { Badges.all(c) }
    SoftCard(Modifier.fillMaxWidth().clickable { showBadges = !showBadges }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("成就"), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = C.Ink, modifier = Modifier.weight(1f))
            Text("${badges.count { it.got }} / ${badges.size}  ${if (showBadges) tr("收起") else "›"}", fontSize = 13.sp, color = C.Soft)
        }
        if (showBadges) for (b in badges) Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(if (b.got) C.Clay else C.Track))
            Text(b.name, fontSize = 14.sp, color = if (b.got) C.Ink else C.Faint, fontWeight = if (b.got) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(start = 10.dp).weight(1f))
            Text(b.how, fontSize = 12.sp, color = C.Faint)
        }
    }

    Text(
        tr("生成今天的分享卡片"), fontSize = 14.sp, color = C.ClayDeep, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(12.dp)).clickable { ShareCard.share(c) }.padding(vertical = 8.dp)
    )
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun Bars(labels: List<String>, v: List<Int>, col: Color) {
    val maxV = maxOf(v.maxOrNull() ?: 0, 1)
    Row(Modifier.fillMaxWidth().padding(top = 12.dp).height(110.dp), verticalAlignment = Alignment.Bottom) {
        v.forEachIndexed { i, x ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.width(10.dp).height((80f * x / maxV).coerceAtLeast(3f).dp).clip(RoundedCornerShape(5.dp)).background(if (i == v.lastIndex) col else col.copy(alpha = 0.45f)))
                Text(labels[i], fontSize = 9.sp, color = C.Faint, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

fun hmShort(m: Int) = if (m >= 60) tr("{0} 小时{1}", m / 60, if (m % 60 > 0) tr(" {0} 分", m % 60) else "") else tr("{0} 分钟", m)

/** 分享卡片：直接画成图片，交给系统分享。 */
object ShareCard {
    fun share(c: Context) {
        try {
            val f = render(c)
            val uri = FileProvider.getUriForFile(c, "${c.packageName}.files", f)
            c.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), tr("分享")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (x: Exception) { Diag.log("分享卡片失败 ${x.message}") }
    }

    private fun render(c: Context): File {
        val w = 1080; val h = 1350
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(b)
        cv.drawColor(C.Cream.toArgb())
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = C.Card.toArgb(); cv.drawRoundRect(RectF(70f, 70f, w - 70f, h - 70f), 60f, 60f, p)
        fun text(s: String, x: Float, y: Float, size: Float, col: Color, bold: Boolean = false) {
            p.color = col.toArgb(); p.textSize = size; p.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT; cv.drawText(s, x, y, p)
        }
        val date = SimpleDateFormat(tr("yyyy 年 M 月 d 日 E"), Tr.locale()).format(Date())
        text(tr("留白"), 150f, 230f, 76f, C.Ink, true)
        text(date, 150f, 300f, 36f, C.Soft)
        val fm = Focus.todayMin(c)
        val rows = Stats.rows(c)
        val used = rows.sumOf { it.usedMin }
        val walked = Store.walkedAway(c)
        val st = Hist.streak(c)
        var y = 470f
        fun stat(label: String, v: String, col: Color) { text(label, 150f, y, 40f, C.Soft); text(v, 150f, y + 96f, 88f, col, true); y += 210f }
        stat(tr("今天专注"), hmShort(fm), C.SageInk)
        stat(tr("守护的应用今天用了"), hmShort(used), C.Clay)
        stat(tr("忍住没刷"), tr("{0} 次", walked), C.Ink)
        text(if (st > 0) tr("已连续专注 {0} 天", st) else tr("给生活留一点空白"), 150f, h - 170f, 40f, C.SageInk)
        val dir = File(c.filesDir, "update").apply { mkdirs() }
        val f = File(dir, "share.png")
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }
}
