package com.liubai.guard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

/** 课块配色：柔和、白字看得清。 */
val COURSE_COLORS = listOf(
    Color(0xFFE07D92), Color(0xFFE3A0B0), Color(0xFFB7A4EE), Color(0xFF86A6EA), Color(0xFF7898C6),
    Color(0xFF6FC1B4), Color(0xFFE48E72), Color(0xFFE5AE5C), Color(0xFF8DB78F), Color(0xFF9CA6B8)
)

fun courseColor(s: Slot): Color =
    COURSE_COLORS[if (s.color in COURSE_COLORS.indices) s.color else Math.floorMod(s.name.trim().hashCode(), COURSE_COLORS.size)]

private class B(val s: Slot, val d: Int, val a: Int, val b: Int, val on: Boolean)

private fun md(epochDay: Long): Pair<Int, Int> { val d = LocalDate.ofEpochDay(epochDay); return d.monthValue to d.dayOfMonth }

/**
 * 整周课表：左边节次和时间，上面星期和日期，课块按节次跨格。
 * 点课块改，点空格在那一节加课；非本周的课变淡。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun CourseGrid(courses: List<Slot>, ver: Int, onOpen: (Slot) -> Unit, onAdd: (Int, Int) -> Unit, onSettings: () -> Unit,
               onCopy: (Slot) -> Unit = {}, onDelete: (Slot, Int) -> Unit = { _, _ -> }) {
    val c = LocalContext.current
    val starts = remember(ver) { Scenes.secStarts(c) }
    val ends = remember(ver) { Scenes.secEnds(c) }
    val term = remember(ver) { Scenes.termStart(c) }
    val total = remember(ver) { Scenes.totalWeeks(c) }
    val showOff = remember(ver) { Scenes.showOff(c) }
    val nowWeek = remember(ver) { Scenes.weekNow(c) }
    var week by remember(ver) { mutableStateOf(nowWeek) }
    val today = Scenes.todayEpoch()
    val monday = if (term >= 0 && week != null) term + (week!! - 1) * 7L else Scenes.mondayOf(today)
    val weekend = Scenes.showWeekend(c) || courses.any { s -> s.days.any { it >= 6 } }
    val days = if (weekend) (1..7).toList() else (1..5).toList()

    // 周次切换条
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        val w = week
        Text("‹", fontSize = 24.sp, color = if (w != null && w > 1) C.Clay else C.Faint,
            modifier = Modifier.clip(CircleShape).clickable(enabled = w != null && w > 1) { week = w!! - 1 }.padding(horizontal = 14.dp, vertical = 2.dp))
        Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { if (w != nowWeek) week = nowWeek else onSettings() }, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (w == null) tr("设置当前周") else tr("第 {0} 周", w), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = if (w == null) C.ClayDeep else C.Ink)
            Text(when {
                w == null -> tr("设好后单双周、非本周的课会自动区分")
                w != nowWeek -> tr("不是本周 · 点这里回到本周")
                w > total -> tr("学期已结束")
                else -> tr("本周")
            }, fontSize = 11.sp, color = C.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("›", fontSize = 24.sp, color = if (w != null && w < total) C.Clay else C.Faint,
            modifier = Modifier.clip(CircleShape).clickable(enabled = w != null && w < total) { week = w!! + 1 }.padding(horizontal = 14.dp, vertical = 2.dp))
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val leftW = 36.dp
        val colW: Dp = (maxWidth - leftW) / days.size
        val rowH = 60.dp
        val n = starts.size
        // 表头
        Column {
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (Tr.cjk) tr("{0}月", md(monday).first) else LocalDate.ofEpochDay(monday).month.getDisplayName(java.time.format.TextStyle.SHORT, Tr.locale()), fontSize = 11.sp, color = C.Soft, textAlign = TextAlign.Center, modifier = Modifier.width(leftW))
                days.forEachIndexed { i, d ->
                    val day = monday + (d - 1)
                    val isToday = day == today
                    Column(Modifier.width(colW), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(dayShort(d), fontSize = 12.sp, color = if (isToday) C.Ink else C.Faint, fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal)
                        Box(Modifier.padding(top = 2.dp).clip(RoundedCornerShape(7.dp)).background(if (isToday) C.Ink else Color.Transparent).padding(horizontal = 5.dp, vertical = 1.dp)) {
                            Text("${md(day).second}", fontSize = 12.sp, color = if (isToday) Color.White else C.Faint)
                        }
                    }
                }
            }
            val density = LocalDensity.current
            Box(Modifier.fillMaxWidth().height(rowH * n.coerceAtLeast(1)).pointerInput(days, n, colW) {
                detectTapGestures { o ->
                    val x = with(density) { o.x.toDp() } - leftW
                    if (x < 0.dp) return@detectTapGestures
                    val di = (x / colW).toInt().coerceIn(0, days.lastIndex)
                    val si = (with(density) { o.y.toDp() } / rowH).toInt().coerceIn(0, n - 1)
                    onAdd(days[di], si + 1)
                }
            }) {
                // 节次与时间
                for (i in 0 until n) {
                    Box(Modifier.offset(x = leftW, y = rowH * i).width(colW * days.size).height(1.dp).background(C.Track.copy(alpha = 0.6f)))
                    Column(Modifier.offset(y = rowH * i).width(leftW).height(rowH), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text("${i + 1}", fontSize = 14.sp, color = C.Ink, fontWeight = FontWeight.SemiBold)
                        Text(Rules.clock(starts[i]), fontSize = 9.sp, color = C.Faint, lineHeight = 11.sp)
                        Text(Rules.clock(ends[i]), fontSize = 9.sp, color = C.Faint, lineHeight = 11.sp)
                    }
                }
                // 课块：本周的在上；非本周的只在空位上淡淡地显示
                val cur = ArrayList<B>(); val off = ArrayList<B>()
                for (s in courses) {
                    val (a, b) = Scenes.secSpan(starts, ends, s.from, s.to)
                    val on = s.inWeek(week)
                    for (d in s.days) if (d in days) (if (on) cur else off) += B(s, d, a, b, on)
                }
                val shown = cur + (if (showOff) off.filter { o -> cur.none { it.d == o.d && it.a <= o.b && o.a <= it.b } } else emptyList())
                    .distinctBy { Triple(it.d, it.a, it.b) }
                var menu by remember { mutableStateOf("") }
                for (k in shown) {
                    val col = courseColor(k.s)
                    val span = (k.b - k.a + 1)
                    Box(Modifier.offset(x = leftW + colW * days.indexOf(k.d) + 1.5.dp, y = rowH * (k.a - 1) + 1.5.dp)
                        .size(colW - 3.dp, rowH * span - 3.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (k.on) col else col.copy(alpha = 0.28f))
                        .border(1.dp, Color.White.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                        .combinedClickable(onClick = { onOpen(k.s) }, onLongClick = { menu = k.s.id + "|" + k.d })
                        .padding(horizontal = 4.dp, vertical = 5.dp)) {
                        androidx.compose.material3.DropdownMenu(expanded = menu == k.s.id + "|" + k.d, onDismissRequest = { menu = "" },
                            modifier = Modifier.background(C.Card)) {
                            androidx.compose.material3.DropdownMenuItem(text = { Text(tr("复制"), color = C.Ink) }, onClick = { menu = ""; onCopy(k.s.copy(days = setOf(k.d))) })
                            androidx.compose.material3.DropdownMenuItem(text = { Text(tr("修改"), color = C.Ink) }, onClick = { menu = ""; onOpen(k.s) })
                            androidx.compose.material3.DropdownMenuItem(text = { Text(tr("删除"), color = C.ClayDeep) }, onClick = { menu = ""; onDelete(k.s, k.d) })
                        }
                        val fg = if (k.on) Color.White else Color.White.copy(alpha = 0.9f)
                        val lines = (span * 4).coerceAtMost(12)
                        Column(Modifier.fillMaxSize()) {
                            Text(k.s.name, fontSize = 12.sp, lineHeight = 15.sp, color = fg, fontWeight = FontWeight.SemiBold, maxLines = (lines - 2).coerceAtLeast(2), overflow = TextOverflow.Ellipsis)
                            if (k.s.room.isNotBlank()) Text("@" + k.s.room, fontSize = 11.sp, lineHeight = 14.sp, color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                            if (k.s.teacher.isNotBlank() && span >= 2) Text(k.s.teacher, fontSize = 11.sp, lineHeight = 14.sp, color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                            Spacer(Modifier.weight(1f))
                            if (!k.on) Text(tr("非本周"), fontSize = 9.sp, color = Color.White, maxLines = 1,
                                modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(Color.White.copy(alpha = 0.25f)).padding(horizontal = 3.dp, vertical = 1.dp))
                            else if (k.s.level != 1) Text(Slot.LEVELS[k.s.level], fontSize = 9.sp, color = col, maxLines = 1,
                                modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(Color.White.copy(alpha = 0.9f)).padding(horizontal = 3.dp, vertical = 1.dp))
                        }
                    }
                }
            }
            Text(tr("点空白格子加课，点课块修改，长按课块复制或删除"), fontSize = 11.sp, color = C.Faint, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun CField(value: String, hint: String, max: Int = 30, onChange: (String) -> Unit) {
    Box(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Sand).padding(horizontal = 14.dp, vertical = 12.dp)) {
        if (value.isEmpty()) Text(hint, fontSize = 14.sp, color = C.Faint)
        BasicTextField(value, { onChange(it.take(max)) }, singleLine = true, textStyle = TextStyle(fontSize = 14.sp, color = C.Ink), modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun WChip(t: String, on: Boolean, onClick: () -> Unit) {
    Text(t, fontSize = 12.sp, color = if (on) Color.White else C.Ink, maxLines = 1,
        modifier = Modifier.padding(top = 6.dp, end = 6.dp).clip(RoundedCornerShape(10.dp)).background(if (on) C.Clay else C.Sand).clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp))
}

/** 加课、改课：课名、教室、老师、星期、节次（或自定义时间）、周次、颜色、等级。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseSheet(start: Slot, onSave: (Slot) -> Unit, onDelete: () -> Unit, onClose: () -> Unit) {
    val c = LocalContext.current
    var s by remember { mutableStateOf(start) }
    val isNew = start.name.isEmpty()
    val total = remember { Scenes.totalWeeks(c) }
    val starts = remember { Scenes.secStarts(c) }
    val ends = remember { Scenes.secEnds(c) }
    var custom by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = C.Card) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Text(if (isNew) tr("添加一节课") else tr("编辑"), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = C.Ink)
            CField(s.name, tr("课名")) { s = s.copy(name = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { CField(s.room, tr("教室（可不填）"), 20) { s = s.copy(room = it) } }
                Box(Modifier.weight(1f)) { CField(s.teacher, tr("老师（可不填）"), 20) { s = s.copy(teacher = it) } }
            }

            Text(tr("星期"), fontSize = 13.sp, color = C.Soft, modifier = Modifier.padding(top = 14.dp))
            DayPicker(s.days) { s = s.copy(days = it) }

            // 节次
            val (a, b) = Scenes.secSpan(starts, ends, s.from, s.to)
            fun setSec(x: Int, y: Int) { val (f, t) = Scenes.secRange(c, x, y); s = s.copy(from = f, to = t) }
            Row(Modifier.padding(top = 14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("节次"), fontSize = 13.sp, color = C.Soft, modifier = Modifier.weight(1f))
                Stepper(a, 1, starts.size) { setSec(it, maxOf(it, b)) }
                Text(" – ", fontSize = 14.sp, color = C.Soft)
                Stepper(b, a, starts.size) { setSec(a, it) }
            }
            Row(Modifier.padding(top = 6.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${Rules.clock(s.from)} – ${Rules.clock(s.to)}", fontSize = 13.sp, color = C.Ink, modifier = Modifier.weight(1f))
                Text(if (custom) tr("收起") else tr("自定义时间"), fontSize = 13.sp, color = C.ClayDeep, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { custom = !custom }.padding(6.dp))
            }
            if (custom) {
                StepSlider(tr("开始"), s.from, 0..(23 * 60 + 55), 5, "", { Rules.clock(it) }) { s = s.copy(from = it) }
                StepSlider(tr("结束"), s.to, 5..(24 * 60), 5, "", { if (it == 1440) "24:00" else Rules.clock(it) }) { s = s.copy(to = it % 1440) }
            }

            // 周次
            Row(Modifier.padding(top = 14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("周次"), fontSize = 13.sp, color = C.Soft, modifier = Modifier.weight(1f))
                Text(Slot.weeksText(s.weeks), fontSize = 13.sp, color = C.Ink, fontWeight = FontWeight.SemiBold)
            }
            val all = (1..total).toSet()
            val shown = if (s.weeks.isEmpty()) all else s.weeks
            fun setW(w: Set<Int>) { s = s.copy(weeks = if (w == all || w.isEmpty()) emptySet() else w) }
            FlowRowLite {
                WChip(tr("每周"), s.weeks.isEmpty()) { setW(all) }
                WChip(tr("单周"), s.weeks.isNotEmpty() && s.weeks == all.filter { it % 2 == 1 }.toSet()) { setW(all.filter { it % 2 == 1 }.toSet()) }
                WChip(tr("双周"), s.weeks.isNotEmpty() && s.weeks == all.filter { it % 2 == 0 }.toSet()) { setW(all.filter { it % 2 == 0 }.toSet()) }
                WChip(tr("前半学期"), s.weeks == (1..total / 2).toSet()) { setW((1..total / 2).toSet()) }
                WChip(tr("后半学期"), s.weeks == (total / 2 + 1..total).toSet()) { setW((total / 2 + 1..total).toSet()) }
            }
            FlowRowLite {
                for (w in 1..total) {
                    val on = w in shown
                    Box(Modifier.padding(top = 6.dp, end = 6.dp).size(34.dp).clip(CircleShape).background(if (on) C.Clay else C.Sand)
                        .clickable { setW(if (on) shown - w else shown + w) }, contentAlignment = Alignment.Center) {
                        Text("$w", fontSize = 12.sp, color = if (on) Color.White else C.Soft)
                    }
                }
            }
            Text(tr("点数字单独选哪几周上；没选中的周这节课不拦"), fontSize = 11.sp, color = C.Faint, modifier = Modifier.padding(top = 4.dp))

            // 颜色
            Text(tr("颜色"), fontSize = 13.sp, color = C.Soft, modifier = Modifier.padding(top = 14.dp))
            FlowRowLite {
                Box(Modifier.padding(top = 6.dp, end = 8.dp).size(30.dp).clip(CircleShape).background(C.Sand)
                    .border(2.dp, if (s.color < 0) C.Ink else Color.Transparent, CircleShape).clickable { s = s.copy(color = -1) }, contentAlignment = Alignment.Center) {
                    Text(tr("自动"), fontSize = 9.sp, color = C.Soft, maxLines = 1)
                }
                COURSE_COLORS.forEachIndexed { i, col ->
                    Box(Modifier.padding(top = 6.dp, end = 8.dp).size(30.dp).clip(CircleShape).background(col)
                        .border(2.dp, if (s.color == i) C.Ink else Color.Transparent, CircleShape).clickable { s = s.copy(color = i) })
                }
            }
            Text(tr("同名的课会一起换颜色"), fontSize = 11.sp, color = C.Faint, modifier = Modifier.padding(top = 4.dp))

            Text(tr("这段时间"), fontSize = 13.sp, color = C.Soft, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
            Segments(Slot.LEVELS, s.level) { s = s.copy(level = it) }
            Text(Slot.LEVEL_NOTE[s.level], fontSize = 12.sp, color = C.Faint, modifier = Modifier.padding(top = 6.dp))
            Note(tr("加新的、调严马上生效；删除、调松明天 0:00 生效。"))
            Spacer(Modifier.height(12.dp))
            PrimaryButton(tr("保存"), enabled = s.name.isNotBlank() && s.days.isNotEmpty() && s.from != s.to) {
                onSave(s.copy(name = s.name.trim(), room = s.room.trim(), teacher = s.teacher.trim()))
            }
            if (!isNew) Text(tr("删除（明天生效）"), color = C.Soft, fontSize = 14.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp).clickable { onDelete() }.padding(12.dp))
        }
    }
}

/** 周次和显示：现在第几周、学期几周、显示周末、显示非本周。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermSheet(onChange: () -> Unit, onClose: () -> Unit) {
    val c = LocalContext.current
    var week by remember { mutableStateOf(Scenes.weekNow(c)) }
    var total by remember { mutableStateOf(Scenes.totalWeeks(c)) }
    var off by remember { mutableStateOf(Scenes.showOff(c)) }
    var wk by remember { mutableStateOf(Scenes.showWeekend(c)) }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = C.Card) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Text(tr("周次与显示"), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = C.Ink)
            Row(Modifier.padding(top = 16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("这周是第几周"), fontSize = 14.sp, color = C.Ink, modifier = Modifier.weight(1f))
                if (week == null) Text(tr("设置"), fontSize = 13.sp, color = C.ClayDeep, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { week = 1; Scenes.setWeekNow(c, 1); onChange() }.padding(6.dp))
                else Stepper(week!!, 1, 30) { week = it; Scenes.setWeekNow(c, it); onChange() }
            }
            Text(tr("每周一自动加一，不用再管"), fontSize = 11.sp, color = C.Faint)
            Row(Modifier.padding(top = 14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("这学期一共几周"), fontSize = 14.sp, color = C.Ink, modifier = Modifier.weight(1f))
                Stepper(total, 1, 30) { total = it; Scenes.setTotalWeeks(c, it); onChange() }
            }
            Toggle(tr("显示非本周的课"), tr("淡色显示，方便看全学期"), off) { off = it; Scenes.setShowOff(c, it); onChange() }
            Toggle(tr("显示周末"), tr("周末有课时总会显示"), wk) { wk = it; Scenes.setShowWeekend(c, it); onChange() }
            if (week != null) Text(tr("不分周次（每周都一样）"), fontSize = 13.sp, color = C.Soft,
                modifier = Modifier.padding(top = 14.dp).clip(RoundedCornerShape(10.dp)).clickable { Scenes.setTermStart(c, -1); week = null; onChange() }.padding(6.dp))
        }
    }
}

/** 每节课的上下课时间：每节都能单独改。 */
@Composable
fun SecTimesCard(onChange: () -> Unit) {
    val c = LocalContext.current
    var secs by remember { mutableStateOf(Scenes.secStarts(c)) }
    var ends by remember { mutableStateOf(Scenes.secEnds(c)) }
    var len by remember { mutableStateOf(Scenes.secLen(c)) }
    var open by remember { mutableStateOf(-1) }
    fun saveS(l: List<Int>) { secs = l; Scenes.setSecStarts(c, l); onChange() }
    fun saveE(l: List<Int>) { ends = l; Scenes.setSecEnds(c, l); onChange() }
    SoftCard(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Text(tr("点某一节单独改上下课时间；截图识别也按这里换算"), fontSize = 12.sp, color = C.Faint, lineHeight = 17.sp)
        StepSlider(tr("每节课"), len, 20..180, 5, tr("分钟")) {
            len = it; Scenes.setSecLen(c, it); Scenes.resetSecEnds(c); ends = Scenes.secEnds(c); onChange()
        }
        secs.forEachIndexed { i, m ->
            val e = ends.getOrElse(i) { m + len }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(10.dp)).clickable { open = if (open == i) -1 else i }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(tr("第 {0} 节", i + 1), fontSize = 13.sp, color = C.Soft, modifier = Modifier.weight(1f))
                Text("${Rules.clock(m)} – ${Rules.clock(e)}", fontSize = 15.sp, color = C.Ink, fontWeight = FontWeight.SemiBold)
                Text(if (open == i) "  ˄" else "  ˅", fontSize = 13.sp, color = C.Faint)
            }
            if (open == i) {
                StepSlider(tr("上课"), m, 0..(23 * 60 + 55), 5, "", { Rules.clock(it) }) { v ->
                    val d = v - m
                    saveS(secs.toMutableList().also { it[i] = v })
                    saveE(ends.toMutableList().also { if (i < it.size) it[i] = (it[i] + d).coerceAtMost(24 * 60 - 1) })
                }
                StepSlider(tr("下课"), e, (m + 5).coerceAtMost(24 * 60 - 1)..(24 * 60 - 1), 5, "", { Rules.clock(it) }) { v ->
                    saveE(ends.toMutableList().also { if (i < it.size) it[i] = v })
                }
            }
        }
        Row(Modifier.padding(top = 8.dp)) {
            Text(tr("＋ 加一节"), fontSize = 13.sp, color = C.ClayDeep, modifier = Modifier.clickable {
                val s0 = (ends.lastOrNull() ?: 470) + 10
                saveS(secs + s0); saveE(ends + (s0 + len))
            }.padding(6.dp))
            if (secs.size > 1) Text(tr("减一节"), fontSize = 13.sp, color = C.Soft, modifier = Modifier.clickable { saveS(secs.dropLast(1)); saveE(ends.dropLast(1)) }.padding(6.dp))
        }
    }
}

// ---------------- 修改课程（整页）：课名、颜色、学分、等级 + 好几个时段 ----------------

/** 一个时段：只有一个星期几。 */
private fun splitDays(l: List<Slot>): List<Slot> = l.flatMap { s ->
    if (s.days.size <= 1) listOf(s) else s.days.sorted().mapIndexed { i, d -> s.copy(id = if (i == 0) s.id else s.id + "-" + d, days = setOf(d)) }
}

@Composable
private fun EditCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(18.dp)).background(C.Card).padding(horizontal = 18.dp), content = content)
}

@Composable
private fun Divider() { Box(Modifier.fillMaxWidth().height(1.dp).background(C.Track.copy(alpha = 0.7f))) }

/** 一行：左边名字，右边值和 ›，点开在下面展开。 */
@Composable
private fun ERow(label: String, value: String, dim: Boolean = false, open: Boolean = false, arrow: Boolean = true, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 15.sp, color = C.Ink, modifier = Modifier.weight(1f))
        if (trailing != null) trailing()
        else Text(value, fontSize = 14.sp, color = if (dim) C.Faint else C.Soft, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
            modifier = Modifier.widthIn(max = 220.dp))
        if (arrow) Text(if (open) "  ˄" else "  ›", fontSize = 15.sp, color = C.Faint)
    }
}

/** 一行：左边名字，右边直接输入。 */
@Composable
private fun EInput(label: String, value: String, max: Int = 30, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 15.sp, color = C.Ink)
        Box(Modifier.weight(1f).padding(start = 16.dp), contentAlignment = Alignment.CenterEnd) {
            if (value.isEmpty()) Text(tr("选填"), fontSize = 14.sp, color = C.Faint)
            BasicTextField(value, { onChange(it.take(max)) }, singleLine = true,
                textStyle = TextStyle(fontSize = 14.sp, color = C.Soft, textAlign = TextAlign.End), modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun CourseEditor(start: List<Slot>, isNew: Boolean, onSave: (List<Slot>) -> Unit, onDeleteAll: () -> Unit, onClose: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        CourseEditorBody(start, isNew, onSave, onDeleteAll, onClose)
    }
}

@Composable
private fun CourseEditorBody(start: List<Slot>, isNew: Boolean, onSave: (List<Slot>) -> Unit, onDeleteAll: () -> Unit, onClose: () -> Unit) {
    val c = LocalContext.current
    val first = start.firstOrNull() ?: Slot("c" + System.currentTimeMillis(), "", setOf(1), 480, 570)
    var name by remember { mutableStateOf(first.name) }
    var color by remember { mutableStateOf(first.color) }
    var credit by remember { mutableStateOf(first.credit) }
    var level by remember { mutableStateOf(first.level) }
    val segs = remember { mutableStateListOf<Slot>().apply { addAll(splitDays(start).ifEmpty { listOf(first) }) } }
    var open by remember { mutableStateOf("") }   // 展开的是哪一行：「color」「level」「w0」「s0」「t0」…
    val total = remember { Scenes.totalWeeks(c) }
    val starts = remember { Scenes.secStarts(c) }
    val ends = remember { Scenes.secEnds(c) }
    var askDel by remember { mutableStateOf(false) }
    fun tog(k: String) { open = if (open == k) "" else k }
    val ok = name.isNotBlank() && segs.isNotEmpty() && segs.all { it.from != it.to && it.days.isNotEmpty() }

    Column(Modifier.fillMaxSize().background(C.Cream).systemBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("✕", fontSize = 20.sp, color = C.Ink, modifier = Modifier.clip(CircleShape).clickable { onClose() }.padding(10.dp))
            Text(if (isNew) tr("添加课程") else tr("修改课程"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = C.Ink,
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            Text(tr("保存"), fontSize = 15.sp, color = if (ok) C.ClayDeep else C.Faint, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(C.Card).clickable(enabled = ok) {
                    onSave(segs.map { it.copy(name = name.trim(), color = color, credit = credit.trim(), level = level,
                        room = it.room.trim(), teacher = it.teacher.trim(), note = it.note.trim()) })
                }.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            EditCard {
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        if (name.isEmpty()) Text(tr("课名"), fontSize = 18.sp, color = C.Faint)
                        BasicTextField(name, { name = it.take(30) }, singleLine = true, textStyle = TextStyle(fontSize = 18.sp, color = C.Ink, fontWeight = FontWeight.Medium), modifier = Modifier.fillMaxWidth())
                    }
                    if (name.isNotEmpty()) Text("✕", fontSize = 11.sp, color = Color.White, textAlign = TextAlign.Center,
                        modifier = Modifier.size(20.dp).clip(CircleShape).background(C.Faint.copy(alpha = 0.6f)).clickable { name = "" }.padding(top = 2.dp))
                }
                Divider()
                ERow(tr("颜色"), "", open = open == "color", arrow = false, onClick = { tog("color") }) {
                    val col = if (color in COURSE_COLORS.indices) COURSE_COLORS[color] else COURSE_COLORS[Math.floorMod(name.trim().hashCode(), COURSE_COLORS.size)]
                    Box(Modifier.size(26.dp).clip(CircleShape).background(col).border(2.dp, Color.White, CircleShape))
                }
                if (open == "color") {
                    FlowRowLite {
                        Box(Modifier.padding(end = 8.dp, bottom = 8.dp).size(30.dp).clip(CircleShape).background(C.Sand)
                            .border(2.dp, if (color < 0) C.Ink else Color.Transparent, CircleShape).clickable { color = -1 }, contentAlignment = Alignment.Center) {
                            Text(tr("自动"), fontSize = 9.sp, color = C.Soft, maxLines = 1)
                        }
                        COURSE_COLORS.forEachIndexed { i, col ->
                            Box(Modifier.padding(end = 8.dp, bottom = 8.dp).size(30.dp).clip(CircleShape).background(col)
                                .border(2.dp, if (color == i) C.Ink else Color.Transparent, CircleShape).clickable { color = i })
                        }
                    }
                }
                Divider()
                EInput(tr("学分"), credit, 6) { credit = it }
                Divider()
                ERow(tr("上课时"), Slot.LEVELS[level], open = open == "level", onClick = { tog("level") })
                if (open == "level") {
                    Segments(Slot.LEVELS, level) { level = it }
                    Text(Slot.LEVEL_NOTE[level], fontSize = 12.sp, color = C.Faint, modifier = Modifier.padding(top = 6.dp, bottom = 14.dp))
                }
            }

            segs.toList().forEachIndexed { i, s0 ->
                fun upd(n: Slot) { if (i < segs.size) segs[i] = n }
                Row(Modifier.fillMaxWidth().padding(top = 18.dp, start = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("时段{0}", i + 1), fontSize = 13.sp, color = C.Faint, modifier = Modifier.weight(1f))
                    Text(tr("复制"), fontSize = 13.sp, color = C.ClayDeep, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                        segs.add(i + 1, s0.copy(id = "c" + System.currentTimeMillis() + i))
                    }.padding(horizontal = 8.dp, vertical = 4.dp))
                    if (segs.size > 1) Text(tr("删除"), fontSize = 13.sp, color = C.Ink, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                        segs.removeAt(i); open = ""
                    }.padding(horizontal = 8.dp, vertical = 4.dp))
                }
                EditCard {
                    // 周数
                    ERow(tr("周数"), Slot.weeksText(s0.weeks), open = open == "w$i", onClick = { tog("w$i") })
                    if (open == "w$i") {
                        val all = (1..total).toSet()
                        val shown = if (s0.weeks.isEmpty()) all else s0.weeks
                        fun setW(w: Set<Int>) = upd(s0.copy(weeks = if (w == all || w.isEmpty()) emptySet() else w))
                        FlowRowLite {
                            WChip(tr("每周"), s0.weeks.isEmpty()) { setW(all) }
                            WChip(tr("单周"), s0.weeks.isNotEmpty() && s0.weeks == all.filter { it % 2 == 1 }.toSet()) { setW(all.filter { it % 2 == 1 }.toSet()) }
                            WChip(tr("双周"), s0.weeks.isNotEmpty() && s0.weeks == all.filter { it % 2 == 0 }.toSet()) { setW(all.filter { it % 2 == 0 }.toSet()) }
                            WChip(tr("前半学期"), s0.weeks == (1..total / 2).toSet()) { setW((1..total / 2).toSet()) }
                            WChip(tr("后半学期"), s0.weeks == (total / 2 + 1..total).toSet()) { setW((total / 2 + 1..total).toSet()) }
                        }
                        FlowRowLite {
                            for (w in 1..total) {
                                val on = w in shown
                                Box(Modifier.padding(top = 6.dp, end = 6.dp).size(34.dp).clip(CircleShape).background(if (on) C.Clay else C.Sand)
                                    .clickable { setW(if (on) shown - w else shown + w) }, contentAlignment = Alignment.Center) {
                                    Text("$w", fontSize = 12.sp, color = if (on) Color.White else C.Soft)
                                }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                    }
                    Divider()
                    // 节数：星期 + 第几到第几节
                    val (a, b) = Scenes.secSpan(starts, ends, s0.from, s0.to)
                    val d = s0.days.minOrNull() ?: 1
                    ERow(tr("节数"), "${dayLabel(d)}   " + tr("第 {0} - {1} 节", a, b), open = open == "s$i", onClick = { tog("s$i") })
                    if (open == "s$i") {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (x in 1..7) {
                                val on = x == d
                                Box(Modifier.size(32.dp).clip(CircleShape).background(if (on) C.Clay else C.Sand).clickable { upd(s0.copy(days = setOf(x))) },
                                    contentAlignment = Alignment.Center) { Text(dayShort(x), fontSize = 12.sp, color = if (on) Color.White else C.Soft) }
                            }
                        }
                        fun setSec(x: Int, y: Int) { val (f, t) = Scenes.secRange(c, x, y); upd(s0.copy(from = f, to = t)) }
                        Row(Modifier.padding(top = 12.dp, bottom = 14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(tr("第几节"), fontSize = 13.sp, color = C.Soft, modifier = Modifier.weight(1f))
                            Stepper(a, 1, starts.size) { setSec(it, maxOf(it, b)) }
                            Text(" – ", fontSize = 14.sp, color = C.Soft)
                            Stepper(b, a, starts.size) { setSec(a, it) }
                        }
                    }
                    Divider()
                    // 自定义时间：和节次对不上时就是开着
                    val std = Scenes.secRange(c, a, b)
                    val custom = s0.from != std.first || s0.to != std.second
                    ERow(tr("自定义时间"), if (custom) "${Rules.clock(s0.from)} – ${Rules.clock(s0.to)}" else tr("关闭"), dim = !custom, open = open == "t$i", onClick = { tog("t$i") })
                    if (open == "t$i") {
                        Toggle(tr("按自己的时间上课"), tr("关掉就按节次的上下课时间"), custom) { on ->
                            if (on) upd(s0.copy(to = (s0.to + 5).coerceAtMost(24 * 60 - 1))) else upd(s0.copy(from = std.first, to = std.second))
                        }
                        StepSlider(tr("开始"), s0.from, 0..(23 * 60 + 55), 5, "", { Rules.clock(it) }) { upd(s0.copy(from = it)) }
                        StepSlider(tr("结束"), s0.to, 5..(24 * 60 - 1), 5, "", { Rules.clock(it) }) { upd(s0.copy(to = it)) }
                        Spacer(Modifier.height(8.dp))
                    }
                    Divider()
                    EInput(tr("教室"), s0.room, 20) { upd(s0.copy(room = it)) }
                    Divider()
                    EInput(tr("老师"), s0.teacher, 20) { upd(s0.copy(teacher = it)) }
                    Divider()
                    EInput(tr("备注"), s0.note, 40) { upd(s0.copy(note = it)) }
                }
            }
            Text(tr("＋ 添加时段"), fontSize = 14.sp, color = C.ClayDeep, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Card).clickable {
                    val last = segs.lastOrNull() ?: first
                    segs.add(last.copy(id = "c" + System.currentTimeMillis(), room = last.room, teacher = last.teacher, note = ""))
                    open = "s${segs.lastIndex}"
                }.padding(vertical = 14.dp))
            Text(tr("加新的、调严马上生效；删除、调松明天 0:00 生效。"), fontSize = 11.sp, color = C.Faint, lineHeight = 16.sp,
                modifier = Modifier.padding(top = 12.dp, start = 6.dp))
            if (!isNew) Text(if (askDel) tr("再点一次确认删除整门课") else tr("删除这门课"), fontSize = 14.sp, color = if (askDel) C.ClayDeep else C.Soft,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .clickable { if (askDel) onDeleteAll() else askDel = true }.padding(vertical = 12.dp))
            Spacer(Modifier.height(40.dp))
        }
    }
}
