package com.liubai.guard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private class Week(
    val days: List<Pair<String, Int>>,      // 标签, 分钟（守护应用合计）
    val perApp: List<Pair<String, Int>>,    // 包名, 本周分钟
    val total: Int, val prev: Int, val okDays: Int, val limit: Int
)

private fun load(c: android.content.Context): Week {
    val pkgs = Store.desired(c).keys + Store.active(c).keys
    val limit = Store.desired(c).values.sum()
    val fmt = SimpleDateFormat("E", Locale.CHINA)
    val days = ArrayList<Pair<String, Int>>()
    val per = HashMap<String, Long>()
    var ok = 0
    for (i in 6 downTo 0) {
        val s = Usage.dayStart(i); val e = Usage.dayStart(i - 1)
        val m = Usage.range(c, s, e)
        var sum = 0L
        for (p in pkgs) { val v = m[p] ?: 0L; sum += v; per[p] = (per[p] ?: 0L) + v }
        val min = (sum / 60_000L).toInt()
        if (limit > 0 && min <= limit) ok++
        days += (if (i == 0) "今天" else fmt.format(Date(s)).replace("周", "")) to min
    }
    val prevMap = Usage.range(c, Usage.dayStart(13), Usage.dayStart(6))
    val prev = (pkgs.sumOf { prevMap[it] ?: 0L } / 60_000L).toInt()
    return Week(days, per.entries.sortedByDescending { it.value }.map { it.key to (it.value / 60_000L).toInt() },
        days.sumOf { it.second }, prev, ok, limit)
}

private fun hm(m: Int) = if (m >= 60) "${m / 60} 小时 ${m % 60} 分" else "$m 分钟"

@Composable
fun WeeklyScreen() {
    val c = LocalContext.current
    var w by remember { mutableStateOf<Week?>(null) }
    LaunchedEffect(Unit) { w = withContext(Dispatchers.IO) { load(c) } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
        Spacer(Modifier.height(8.dp))
        Text("每周小结", fontSize = 27.sp, fontWeight = FontWeight.Bold, color = C.Ink)
        Text("最近 7 天，守护中的应用", fontSize = 14.sp, color = C.Soft, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(18.dp))
        if (!Usage.granted(c)) { UsageGuide(); return@Column }
        val x = w
        if (x == null) { Text("正在整理这一周…", color = C.Faint, fontSize = 14.sp); return@Column }
        if (x.perApp.isEmpty()) { Text("还没有守护的应用，先在「今天」里添加吧。", color = C.Soft, fontSize = 14.sp); return@Column }

        SoftCard(Modifier.fillMaxWidth()) {
            Text("这周一共用了", fontSize = 13.sp, color = C.Soft)
            Text(hm(x.total), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = C.Ink, modifier = Modifier.padding(top = 2.dp))
            val diff = x.prev - x.total
            val line = when {
                x.prev == 0 -> "这是你和留白的第一周，慢慢来"
                diff > 0 -> "比上周少了 ${hm(diff)}，给自己留出了一大段空白"
                diff < 0 -> "比上周多了 ${hm(-diff)}，下周我们再慢一点"
                else -> "和上周差不多，稳稳的"
            }
            Text(line, fontSize = 13.sp, color = C.SageInk, modifier = Modifier.padding(top = 6.dp), lineHeight = 20.sp)
            Spacer(Modifier.height(18.dp))
            val maxV = maxOf(x.days.maxOf { it.second }, x.limit, 1)
            Row(Modifier.fillMaxWidth().height(140.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                x.days.forEach { (label, v) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                        Text("$v", fontSize = 10.sp, color = C.Faint)
                        val over = x.limit > 0 && v > x.limit
                        Box(
                            Modifier.padding(top = 4.dp).width(18.dp)
                                .height((100f * v / maxV).coerceAtLeast(4f).dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(if (over) C.Clay else C.Sage)
                        )
                        Text(label, fontSize = 11.sp, color = C.Soft, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SoftCard(Modifier.weight(1f)) {
                Text("守住的天数", fontSize = 13.sp, color = C.Soft)
                Text("${x.okDays} / 7", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = C.SageInk, modifier = Modifier.padding(top = 2.dp))
            }
            SoftCard(Modifier.weight(1f)) {
                Text("日均", fontSize = 13.sp, color = C.Soft)
                Text(hm(x.total / 7), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = C.Ink, modifier = Modifier.padding(top = 6.dp))
            }
        }

        Spacer(Modifier.height(16.dp))
        SoftCard(Modifier.fillMaxWidth()) {
            Text("各应用", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = C.Ink)
            val top = maxOf(x.perApp.first().second, 1)
            x.perApp.forEach { (pkg, m) ->
                val app = remember(pkg) { Apps.info(c, pkg) }
                Column(Modifier.padding(top = 12.dp)) {
                    Row { Text(app.label, fontSize = 14.sp, color = C.Ink, modifier = Modifier.weight(1f)); Text(hm(m), fontSize = 13.sp, color = C.Soft) }
                    Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(C.Track)) {
                        Box(Modifier.fillMaxWidth(m.toFloat() / top).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(C.Clay))
                    }
                }
            }
        }
        Text("柱子变成橘色的那天，是超过了现在的总额度。", fontSize = 12.sp, color = C.Faint, modifier = Modifier.padding(vertical = 18.dp))
    }
}
