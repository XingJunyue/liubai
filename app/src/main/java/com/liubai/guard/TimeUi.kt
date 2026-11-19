package com.liubai.guard

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun levelColor(l: Int) = when (l) { 2 -> C.Clay; 1 -> C.Sage; else -> C.Faint }

/** 侧栏「时段」：上课、睡前、场景三块。 */
@Composable
fun TimeScreen() {
    var tab by remember { mutableStateOf(Nav.sub.coerceIn(0, 2)) }
    LaunchedEffect(Nav.subState.value) { tab = Nav.sub.coerceIn(0, 2) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
        Spacer(Modifier.height(8.dp))
        Text("时段", fontSize = 27.sp, fontWeight = FontWeight.Bold, color = C.Ink)
        Text("到点自动变严，不用每次自己开", fontSize = 14.sp, color = C.Soft, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
        Segments(listOf("上课", "睡前", "场景"), tab) { tab = it }
        Spacer(Modifier.height(14.dp))
        when (tab) {
            0 -> CourseTab()
            1 -> BedCard()
            else -> SceneTab()
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun NowBanner() {
    val c = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    Every(30_000) { tick++ }
    val s = remember(tick) { Scenes.now(c) }
    if (s != null) Note("现在：${s.name}（${Slot.LEVELS[s.level]}）· 到 ${Rules.clock(s.to)}。${Slot.LEVEL_NOTE[s.level]}", warm = s.level == 2)
}

@Composable
private fun CourseTab() {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var all by remember { mutableStateOf(Scenes.desired(c)) }
    val courses = all.filter { it.kind == "course" }
    var editing by remember { mutableStateOf<Slot?>(null) }
    var review by remember { mutableStateOf<List<Slot>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var pickCal by remember { mutableStateOf<List<Pair<Long, String>>?>(null) }
    var showSecs by remember { mutableStateOf(false) }
    var on by remember { mutableStateOf(Scenes.enabled(c)) }
    fun save(l: List<Slot>) { Scenes.setDesired(c, l); all = Scenes.desired(c) }

    val img = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null) { busy = "正在识别课表…"; err = null
            Ocr.read(c, u) { r, e -> busy = null; if (r != null) review = r else err = e } }
    }
    val ics = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch {
            busy = "正在读取…"; err = null
            val r = withContext(Dispatchers.IO) { try { Scenes.fromIcs(c, u) } catch (x: Exception) { null } }
            busy = null
            if (r.isNullOrEmpty()) err = "这个文件里没读到课程" else review = r
        }
    }
    val calPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) pickCal = try { Scenes.calendars(c) } catch (x: Exception) { emptyList() } else err = "没有日历权限，读不到"
    }

    NowBanner()
    SoftCard(Modifier.fillMaxWidth()) {
        Toggle("按课表自动上课模式", if (Scenes.enabledToday(c) && !on) "已关闭，明天 0:00 起停用" else "放假时关掉，次日生效", on) { on = it; Scenes.setEnabled(c, it) }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (i in 0..2) Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(levelColor(i)))
                Text(" ${Slot.LEVELS[i]}", fontSize = 12.sp, color = C.Soft)
            }
        }
        Text("重要：只开放白名单 · 普通：守护的应用不开放 · 水课：打开前先停一下", fontSize = 12.sp, color = C.Faint, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp))
        HushToggle("上课时收起通知", "重要课和普通课期间先收起，下课自动放回", { Scenes.quiet(it) }, { x, v -> Scenes.setQuiet(x, v) })
    }

    Text("导入课表", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = C.Ink, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ImportBtn("截图识别", Modifier.weight(1f)) { img.launch("image/*") }
        ImportBtn("日历", Modifier.weight(1f)) {
            if (ContextCompat.checkSelfPermission(c, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED)
                pickCal = try { Scenes.calendars(c) } catch (x: Exception) { emptyList() }
            else calPerm.launch(Manifest.permission.READ_CALENDAR)
        }
        ImportBtn(".ics 文件", Modifier.weight(1f)) { ics.launch(arrayOf("text/calendar", "*/*")) }
    }
    busy?.let { Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(16.dp), color = C.Clay, strokeWidth = 2.dp); Text("  $it", fontSize = 13.sp, color = C.Soft) } }
    err?.let { Text(it, fontSize = 13.sp, color = C.ClayDeep, modifier = Modifier.padding(top = 10.dp)) }

    Row(Modifier.padding(top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("我的课（${courses.size}）", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = C.Ink, modifier = Modifier.weight(1f))
        Text("每节课时间", fontSize = 13.sp, color = C.Soft, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { showSecs = !showSecs }.padding(6.dp))
        Text("＋ 添加", fontSize = 13.sp, color = C.ClayDeep, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { editing = Slot("c" + System.currentTimeMillis(), "", setOf(Tasks.weekday()), 480, 570) }.padding(6.dp))
    }
    if (showSecs) SecTimes()
    if (courses.isEmpty()) Text("还没有课。截图识别最快：在课表应用里截一张整周的图就行。", fontSize = 13.sp, color = C.Soft, lineHeight = 20.sp)
    for (d in 1..7) {
        val l = courses.filter { d in it.days }.sortedBy { it.from }
        if (l.isEmpty()) continue
        Text("周${DAY_NAMES[d - 1]}", fontSize = 12.sp, color = C.Faint, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
        for (s in l) SlotRow(s) { editing = s }
    }

    editing?.let { s -> SlotSheet(s, wifi = false, onSave = { n -> save(all.filter { it.id != s.id } + n); editing = null },
        onDelete = { save(all.filter { it.id != s.id }); editing = null }, onClose = { editing = null }) }
    review?.let { r -> ReviewSheet(r, onAdd = { picked ->
        val names = picked.map { "${it.name}|${it.days}|${it.from}" }.toSet()
        save(all.filter { "${it.name}|${it.days}|${it.from}" !in names } + picked); review = null
    }, onClose = { review = null }) }
    pickCal?.let { cals -> CalendarPick(cals, onPick = { id ->
        pickCal = null
        scope.launch {
            busy = "正在读取日历…"
            val r = withContext(Dispatchers.IO) { try { Scenes.fromCalendar(c, id) } catch (x: Exception) { null } }
            busy = null
            if (r.isNullOrEmpty()) err = "这个日历接下来两周没有日程" else review = r
        }
    }, onClose = { pickCal = null }) }
}

@Composable
private fun ImportBtn(t: String, m: Modifier, onClick: () -> Unit) {
    Box(m.clip(RoundedCornerShape(16.dp)).background(C.Card).clickable { onClick() }.padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
        Text(t, fontSize = 13.sp, color = C.Ink, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SlotRow(s: Slot, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(14.dp)).background(C.Card).clickable { onClick() }.padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(levelColor(s.level)))
        Text(s.name, fontSize = 14.sp, color = C.Ink, modifier = Modifier.padding(start = 10.dp).weight(1f), maxLines = 1)
        if (s.kind == "scene") Text(s.days.sorted().joinToString("") { DAY_NAMES[it - 1] } + " ", fontSize = 12.sp, color = C.Faint)
        Text("${Rules.clock(s.from)}–${Rules.clock(s.to)}", fontSize = 12.sp, color = C.Soft)
        Text("  ${Slot.LEVELS[s.level]}", fontSize = 12.sp, color = levelColor(s.level), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SecTimes() {
    val c = LocalContext.current
    var secs by remember { mutableStateOf(Scenes.secStarts(c)) }
    var len by remember { mutableStateOf(Scenes.secLen(c)) }
    SoftCard(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Text("截图识别按「第几节」换算成时间，按你学校的作息改一下", fontSize = 12.sp, color = C.Faint)
        StepSlider("每节课", len, 30..120, 5, "分钟") { len = it; Scenes.setSecLen(c, it) }
        secs.forEachIndexed { i, m ->
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("第 ${i + 1} 节", fontSize = 13.sp, color = C.Soft, modifier = Modifier.weight(1f))
                Text("−", fontSize = 18.sp, color = C.Clay, modifier = Modifier.clip(CircleShape).clickable { secs = secs.toMutableList().also { it[i] = m - 5 }; Scenes.setSecStarts(c, secs) }.padding(horizontal = 12.dp))
                Text(Rules.clock(m), fontSize = 15.sp, color = C.Ink, fontWeight = FontWeight.SemiBold)
                Text("+", fontSize = 18.sp, color = C.Clay, modifier = Modifier.clip(CircleShape).clickable { secs = secs.toMutableList().also { it[i] = m + 5 }; Scenes.setSecStarts(c, secs) }.padding(horizontal = 12.dp))
            }
        }
        Row(Modifier.padding(top = 8.dp)) {
            Text("＋ 加一节", fontSize = 13.sp, color = C.ClayDeep, modifier = Modifier.clickable { secs = secs + ((secs.lastOrNull() ?: 480) + len + 10); Scenes.setSecStarts(c, secs) }.padding(6.dp))
            if (secs.size > 1) Text("减一节", fontSize = 13.sp, color = C.Soft, modifier = Modifier.clickable { secs = secs.dropLast(1); Scenes.setSecStarts(c, secs) }.padding(6.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlotSheet(start: Slot, wifi: Boolean, onSave: (Slot) -> Unit, onDelete: () -> Unit, onClose: () -> Unit) {
    val c = LocalContext.current
    var s by remember { mutableStateOf(start) }
    val isNew = start.name.isEmpty()
    val loc = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { val w = Scenes.wifi(c); if (w.isNotEmpty()) s = s.copy(ssid = w) }
    }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = C.Card) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Text(if (isNew) (if (wifi) "新建场景" else "添加一节课") else "编辑", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = C.Ink)
            Box(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Sand).padding(horizontal = 14.dp, vertical = 12.dp)) {
                if (s.name.isEmpty()) Text(if (wifi) "名字，比如 图书馆、上班" else "课名", fontSize = 14.sp, color = C.Faint)
                BasicTextField(s.name, { s = s.copy(name = it.take(20)) }, singleLine = true, textStyle = TextStyle(fontSize = 14.sp, color = C.Ink), modifier = Modifier.fillMaxWidth())
            }
            DayPicker(s.days) { s = s.copy(days = it) }
            StepSlider("开始", s.from, 0..(23 * 60 + 55), 5, "", { Rules.clock(it) }) { s = s.copy(from = it) }
            StepSlider("结束", s.to, 5..(24 * 60), 5, "", { if (it == 1440) "24:00" else Rules.clock(it) }) { s = s.copy(to = it % 1440) }
            Text("这段时间", fontSize = 13.sp, color = C.Soft, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
            Segments(Slot.LEVELS, s.level) { s = s.copy(level = it) }
            Text(Slot.LEVEL_NOTE[s.level], fontSize = 12.sp, color = C.Faint, modifier = Modifier.padding(top = 6.dp))
            if (wifi) {
                Row(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Sand).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (s.ssid.isEmpty()) "不限 Wi-Fi" else "只在连着「${s.ssid}」时", fontSize = 14.sp, color = C.Ink, modifier = Modifier.weight(1f))
                    Text(if (s.ssid.isEmpty()) "用当前 Wi-Fi" else "清除", fontSize = 13.sp, color = C.ClayDeep, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable {
                            if (s.ssid.isNotEmpty()) s = s.copy(ssid = "")
                            else if (ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                                val w = Scenes.wifi(c); if (w.isNotEmpty()) s = s.copy(ssid = w)
                            } else loc.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        })
                }
                Text("读 Wi-Fi 名字需要位置权限，留白不记录位置", fontSize = 11.sp, color = C.Faint, modifier = Modifier.padding(top = 4.dp))
            }
            Note("加新的、调严马上生效；删除、调松明天 0:00 生效。")
            Spacer(Modifier.height(12.dp))
            PrimaryButton("保存", enabled = s.name.isNotBlank() && s.days.isNotEmpty() && s.from != s.to) { onSave(s.copy(name = s.name.trim())) }
            if (!isNew) Text("删除（明天生效）", color = C.Soft, fontSize = 14.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp).clickable { onDelete() }.padding(12.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewSheet(found: List<Slot>, onAdd: (List<Slot>) -> Unit, onClose: () -> Unit) {
    val items = remember { mutableStateListOf<Slot>().apply { addAll(found) } }
    val off = remember { mutableStateListOf<String>() }
    var editing by remember { mutableStateOf<Slot?>(null) }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = C.Card) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Text("认出 ${items.size} 节课", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = C.Ink)
            Text("核对一下：点圆点切换水课、普通、重要，点课名改时间，取消勾选的不导入", fontSize = 12.sp, color = C.Faint, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                for (s in items.sortedWith(compareBy({ it.days.first() }, { it.from }))) {
                    val skip = s.id in off
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (skip) "○" else "●", fontSize = 16.sp, color = if (skip) C.Faint else C.Clay, modifier = Modifier.clickable { if (skip) off.remove(s.id) else off.add(s.id) }.padding(end = 10.dp))
                        Text("周${DAY_NAMES[s.days.first() - 1]}", fontSize = 12.sp, color = C.Faint, modifier = Modifier.width(34.dp))
                        Text(s.name, fontSize = 14.sp, color = if (skip) C.Faint else C.Ink, modifier = Modifier.weight(1f).clickable { editing = s }, maxLines = 1)
                        Text("${Rules.clock(s.from)}–${Rules.clock(s.to)}", fontSize = 12.sp, color = C.Soft)
                        Text(Slot.LEVELS[s.level], fontSize = 12.sp, color = levelColor(s.level), fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(8.dp)).background(C.Sand)
                                .clickable { val i = items.indexOfFirst { it.id == s.id }; if (i >= 0) items[i] = s.copy(level = (s.level + 1) % 3) }
                                .padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                }
            }
            Text("同名的课会一起改：", fontSize = 12.sp, color = C.Faint, modifier = Modifier.padding(top = 10.dp))
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (lv in 0..2) Text("全部设为${Slot.LEVELS[lv]}", fontSize = 12.sp, color = C.Ink,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(C.Sand).clickable {
                        for (i in items.indices) if (items[i].id !in off) items[i] = items[i].copy(level = lv)
                    }.padding(horizontal = 10.dp, vertical = 6.dp))
            }
            Spacer(Modifier.height(14.dp))
            PrimaryButton("导入 ${items.count { it.id !in off }} 节") { onAdd(items.filter { it.id !in off }) }
        }
    }
    editing?.let { s -> SlotSheet(s, wifi = false, onSave = { n ->
        val i = items.indexOfFirst { it.id == s.id }
        // 同名课一起改等级
        for (j in items.indices) if (items[j].name == s.name && items[j].id != s.id) items[j] = items[j].copy(level = n.level)
        if (i >= 0) items[i] = n; editing = null
    }, onDelete = { items.removeAll { it.id == s.id }; editing = null }, onClose = { editing = null }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarPick(cals: List<Pair<Long, String>>, onPick: (Long) -> Unit, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, containerColor = C.Card) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Text("选课表所在的日历", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = C.Ink)
            Text("WakeUp、小爱课程表等可以先同步到系统日历", fontSize = 12.sp, color = C.Faint, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            if (cals.isEmpty()) Text("没有找到日历", fontSize = 14.sp, color = C.Soft)
            for ((id, n) in cals) Text(n, fontSize = 15.sp, color = C.Ink, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(id) }.padding(vertical = 12.dp))
        }
    }
}

@Composable
private fun SceneTab() {
    val c = LocalContext.current
    var all by remember { mutableStateOf(Scenes.desired(c)) }
    var editing by remember { mutableStateOf<Slot?>(null) }
    val scenes = all.filter { it.kind == "scene" }
    fun save(l: List<Slot>) { Scenes.setDesired(c, l); all = Scenes.desired(c) }
    NowBanner()
    Text("按星期、时间，或者连上某个 Wi-Fi 时自动变严。比如工作日 9–18 点、图书馆 Wi-Fi。", fontSize = 13.sp, color = C.Soft, lineHeight = 20.sp)
    Spacer(Modifier.height(12.dp))
    for (s in scenes) SlotRow(s) { editing = s }
    Text("＋ 新建场景", fontSize = 14.sp, color = C.ClayDeep, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(12.dp)).clickable {
            editing = Slot("s" + System.currentTimeMillis(), "", setOf(1, 2, 3, 4, 5), 9 * 60, 18 * 60, 1, "", "scene")
        }.padding(vertical = 8.dp))
    editing?.let { s -> SlotSheet(s, wifi = true, onSave = { n -> save(all.filter { it.id != s.id } + n); editing = null },
        onDelete = { save(all.filter { it.id != s.id }); editing = null }, onClose = { editing = null }) }
}
