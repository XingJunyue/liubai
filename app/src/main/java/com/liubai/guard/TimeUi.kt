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
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.abs
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
        Text(tr("时段"), fontSize = 27.sp, fontWeight = FontWeight.Bold, color = C.Ink)
        Text(tr("到点自动变严，不用每次自己开"), fontSize = 14.sp, color = C.Soft, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
        Segments(listOf(tr("上课"), tr("睡前"), tr("场景")), tab) { tab = it }
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
    if (s != null) Note(tr("现在：{0}（{1}）· 到 {2}。{3}", s.name, Slot.LEVELS[s.level], Rules.clock(s.to), Slot.LEVEL_NOTE[s.level]), warm = s.level == 2)
}

@Composable
private fun CourseTab() {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var all by remember { mutableStateOf(Scenes.desired(c)) }
    val courses = all.filter { it.kind == "course" }
    var editing by remember { mutableStateOf<Slot?>(null) }
    var review by remember { mutableStateOf<List<Slot>?>(null) }
    var shot by remember { mutableStateOf<android.net.Uri?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var pickCal by remember { mutableStateOf<List<Pair<Long, String>>?>(null) }
    var showSecs by remember { mutableStateOf(false) }
    var on by remember { mutableStateOf(Scenes.enabled(c)) }
    fun save(l: List<Slot>) { Scenes.setDesired(c, l); all = Scenes.desired(c) }

    val img = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null) { busy = tr("正在识别课表…"); err = null; shot = u
            Ocr.read(c, u) { r, e -> busy = null; if (r != null) review = r else { err = e; review = null } } }
    }
    val ics = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch {
            busy = tr("正在读取…"); err = null
            val r = withContext(Dispatchers.IO) { try { Scenes.fromIcs(c, u) } catch (x: Exception) { null } }
            busy = null
            if (r.isNullOrEmpty()) err = tr("这个文件里没读到课程") else { shot = null; review = r }
        }
    }
    val calPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) pickCal = try { Scenes.calendars(c) } catch (x: Exception) { emptyList() } else err = tr("没有日历权限，读不到")
    }

    NowBanner()
    SoftCard(Modifier.fillMaxWidth()) {
        Toggle(tr("按课表自动上课模式"), if (Scenes.enabledToday(c) && !on) tr("已关闭，明天 0:00 起停用") else tr("放假时关掉，次日生效"), on) { on = it; Scenes.setEnabled(c, it) }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (i in 0..2) Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(levelColor(i)))
                Text(" ${Slot.LEVELS[i]}", fontSize = 12.sp, color = C.Soft)
            }
        }
        Text(tr("重要：只开放白名单 · 普通：守护的应用不开放 · 水课：打开前先停一下"), fontSize = 12.sp, color = C.Faint, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp))
        HushToggle(tr("上课时收起通知"), tr("重要课和普通课期间先收起，下课自动放回"), { Scenes.quiet(it) }, { x, v -> Scenes.setQuiet(x, v) })
    }

    Text(tr("导入课表"), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = C.Ink, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ImportBtn(tr("截图识别"), Modifier.weight(1f)) { img.launch("image/*") }
        ImportBtn(tr("日历"), Modifier.weight(1f)) {
            if (ContextCompat.checkSelfPermission(c, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED)
                pickCal = try { Scenes.calendars(c) } catch (x: Exception) { emptyList() }
            else calPerm.launch(Manifest.permission.READ_CALENDAR)
        }
        ImportBtn(tr(".ics 文件"), Modifier.weight(1f)) { ics.launch(arrayOf("text/calendar", "*/*")) }
    }
    busy?.let { Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(16.dp), color = C.Clay, strokeWidth = 2.dp); Text("  $it", fontSize = 13.sp, color = C.Soft) } }
    err?.let { Text(it, fontSize = 13.sp, color = C.ClayDeep, modifier = Modifier.padding(top = 10.dp)) }
    if (err != null && shot != null) Text(tr("对着截图自己填 ›"), fontSize = 13.sp, color = C.ClayDeep, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(10.dp)).clickable { err = null; review = emptyList() }.padding(vertical = 6.dp))

    Row(Modifier.padding(top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(tr("我的课（{0}）", courses.size), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = C.Ink, modifier = Modifier.weight(1f))
        Text(tr("每节课时间"), fontSize = 13.sp, color = C.Soft, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { showSecs = !showSecs }.padding(6.dp))
        Text(tr("＋ 添加"), fontSize = 13.sp, color = C.ClayDeep, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { editing = Slot("c" + System.currentTimeMillis(), "", setOf(Tasks.weekday()), 480, 570) }.padding(6.dp))
    }
    if (showSecs) SecTimes()
    if (courses.isEmpty()) Text(tr("还没有课。截图识别最快：在课表应用里截一张整周的图就行。"), fontSize = 13.sp, color = C.Soft, lineHeight = 20.sp)
    for (d in 1..7) {
        val l = courses.filter { d in it.days }.sortedBy { it.from }
        if (l.isEmpty()) continue
        Text(dayLabel(d), fontSize = 12.sp, color = C.Faint, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
        for (s in l) SlotRow(s) { editing = s }
    }

    editing?.let { s -> SlotSheet(s, wifi = false, onSave = { n -> save(all.filter { it.id != s.id } + n); editing = null },
        onDelete = { save(all.filter { it.id != s.id }); editing = null }, onClose = { editing = null }) }
    review?.let { r -> ReviewSheet(r, shot, onAdd = { picked ->
        val names = picked.map { "${it.name}|${it.days}|${it.from}" }.toSet()
        save(all.filter { "${it.name}|${it.days}|${it.from}" !in names } + picked); review = null
    }, onClose = { review = null }) }
    pickCal?.let { cals -> CalendarPick(cals, onPick = { id ->
        pickCal = null
        scope.launch {
            busy = tr("正在读取日历…")
            val r = withContext(Dispatchers.IO) { try { Scenes.fromCalendar(c, id) } catch (x: Exception) { null } }
            busy = null
            if (r.isNullOrEmpty()) err = tr("这个日历接下来两周没有日程") else { shot = null; review = r }
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
        if (s.kind == "scene") Text(daysText(s.days) + " ", fontSize = 12.sp, color = C.Faint)
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
        Text(tr("截图识别按「第几节」换算成时间，按你学校的作息改一下"), fontSize = 12.sp, color = C.Faint)
        StepSlider(tr("每节课"), len, 30..120, 5, tr("分钟")) { len = it; Scenes.setSecLen(c, it) }
        secs.forEachIndexed { i, m ->
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("第 {0} 节", i + 1), fontSize = 13.sp, color = C.Soft, modifier = Modifier.weight(1f))
                Text("−", fontSize = 18.sp, color = C.Clay, modifier = Modifier.clip(CircleShape).clickable { secs = secs.toMutableList().also { it[i] = m - 5 }; Scenes.setSecStarts(c, secs) }.padding(horizontal = 12.dp))
                Text(Rules.clock(m), fontSize = 15.sp, color = C.Ink, fontWeight = FontWeight.SemiBold)
                Text("+", fontSize = 18.sp, color = C.Clay, modifier = Modifier.clip(CircleShape).clickable { secs = secs.toMutableList().also { it[i] = m + 5 }; Scenes.setSecStarts(c, secs) }.padding(horizontal = 12.dp))
            }
        }
        Row(Modifier.padding(top = 8.dp)) {
            Text(tr("＋ 加一节"), fontSize = 13.sp, color = C.ClayDeep, modifier = Modifier.clickable { secs = secs + ((secs.lastOrNull() ?: 480) + len + 10); Scenes.setSecStarts(c, secs) }.padding(6.dp))
            if (secs.size > 1) Text(tr("减一节"), fontSize = 13.sp, color = C.Soft, modifier = Modifier.clickable { secs = secs.dropLast(1); Scenes.setSecStarts(c, secs) }.padding(6.dp))
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
            Text(if (isNew) (if (wifi) tr("新建场景") else tr("添加一节课")) else tr("编辑"), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = C.Ink)
            Box(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Sand).padding(horizontal = 14.dp, vertical = 12.dp)) {
                if (s.name.isEmpty()) Text(if (wifi) tr("名字，比如 图书馆、上班") else tr("课名"), fontSize = 14.sp, color = C.Faint)
                BasicTextField(s.name, { s = s.copy(name = it.take(20)) }, singleLine = true, textStyle = TextStyle(fontSize = 14.sp, color = C.Ink), modifier = Modifier.fillMaxWidth())
            }
            DayPicker(s.days) { s = s.copy(days = it) }
            if (!wifi) SecPicker(s) { f, t -> s = s.copy(from = f, to = t) }
            StepSlider(tr("开始"), s.from, 0..(23 * 60 + 55), 5, "", { Rules.clock(it) }) { s = s.copy(from = it) }
            StepSlider(tr("结束"), s.to, 5..(24 * 60), 5, "", { if (it == 1440) "24:00" else Rules.clock(it) }) { s = s.copy(to = it % 1440) }
            Text(tr("这段时间"), fontSize = 13.sp, color = C.Soft, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
            Segments(Slot.LEVELS, s.level) { s = s.copy(level = it) }
            Text(Slot.LEVEL_NOTE[s.level], fontSize = 12.sp, color = C.Faint, modifier = Modifier.padding(top = 6.dp))
            if (wifi) {
                Row(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Sand).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (s.ssid.isEmpty()) tr("不限 Wi-Fi") else tr("只在连着「{0}」时", s.ssid), fontSize = 14.sp, color = C.Ink, modifier = Modifier.weight(1f))
                    Text(if (s.ssid.isEmpty()) tr("用当前 Wi-Fi") else tr("清除"), fontSize = 13.sp, color = C.ClayDeep, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable {
                            if (s.ssid.isNotEmpty()) s = s.copy(ssid = "")
                            else if (ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                                val w = Scenes.wifi(c); if (w.isNotEmpty()) s = s.copy(ssid = w)
                            } else loc.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        })
                }
                Text(tr("读 Wi-Fi 名字需要位置权限，留白不记录位置"), fontSize = 11.sp, color = C.Faint, modifier = Modifier.padding(top = 4.dp))
            }
            Note(tr("加新的、调严马上生效；删除、调松明天 0:00 生效。"))
            Spacer(Modifier.height(12.dp))
            PrimaryButton(tr("保存"), enabled = s.name.isNotBlank() && s.days.isNotEmpty() && s.from != s.to) { onSave(s.copy(name = s.name.trim())) }
            if (!isNew) Text(tr("删除（明天生效）"), color = C.Soft, fontSize = 14.sp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp).clickable { onDelete() }.padding(12.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewSheet(found: List<Slot>, shot: android.net.Uri?, onAdd: (List<Slot>) -> Unit, onClose: () -> Unit) {
    val c = LocalContext.current
    val items = remember { mutableStateListOf<Slot>().apply { addAll(found) } }
    val off = remember { mutableStateListOf<String>() }
    var editing by remember { mutableStateOf<Slot?>(null) }
    var big by remember { mutableStateOf(false) }
    val pic = remember(shot) {
        shot?.let { u -> try {
            val o = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
            c.contentResolver.openInputStream(u)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }?.asImageBitmap()
        } catch (x: Exception) { null } }
    }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = C.Card) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Text(if (items.isEmpty()) tr("自己填课表") else tr("认出 {0} 节课", items.size), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = C.Ink)
            Text(tr("对照截图核对：点一行可以改课名、星期、节次和时间，漏掉的点「补一节」，不要的取消勾选"), fontSize = 12.sp, color = C.Faint, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            if (pic != null) {
                androidx.compose.foundation.Image(pic, null, contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxWidth().heightIn(max = if (big) 520.dp else 150.dp).clip(RoundedCornerShape(12.dp)).clickable { big = !big })
                Text(if (big) tr("点图片收起") else tr("点图片放大对照"), fontSize = 11.sp, color = C.Faint, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
            }
            Column(Modifier.heightIn(max = if (big) 200.dp else 360.dp).verticalScroll(rememberScrollState())) {
                for (s in items.sortedWith(compareBy({ it.days.minOrNull() ?: 0 }, { it.from }))) {
                    val skip = s.id in off
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp)).background(C.Sand.copy(alpha = 0.5f)).clickable { editing = s }
                        .padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (skip) "○" else "●", fontSize = 16.sp, color = if (skip) C.Faint else C.Clay, modifier = Modifier.clip(CircleShape).clickable { if (skip) off.remove(s.id) else off.add(s.id) }.padding(end = 8.dp, start = 2.dp))
                        Text(daysText(s.days), fontSize = 12.sp, color = C.Faint, modifier = Modifier.width(44.dp), maxLines = 1)
                        Text(s.name, fontSize = 14.sp, color = if (skip) C.Faint else C.Ink, modifier = Modifier.weight(1f), maxLines = 1)
                        Text("${Rules.clock(s.from)}–${Rules.clock(s.to)}", fontSize = 12.sp, color = C.Soft)
                        Text(Slot.LEVELS[s.level], fontSize = 12.sp, color = levelColor(s.level), fontWeight = FontWeight.SemiBold, maxLines = 1,
                            modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(8.dp)).background(C.Card)
                                .clickable { val i = items.indexOfFirst { it.id == s.id }; if (i >= 0) items[i] = s.copy(level = (s.level + 1) % 3) }
                                .padding(horizontal = 8.dp, vertical = 4.dp))
                        Text(" ✎", fontSize = 13.sp, color = C.Soft)
                    }
                }
            }
            Text(tr("＋ 补一节"), fontSize = 13.sp, color = C.ClayDeep, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).clickable {
                    editing = Slot("m" + System.currentTimeMillis(), "", setOf(Tasks.weekday()), Scenes.secRange(c, 1, 2).first, Scenes.secRange(c, 1, 2).second)
                }.padding(vertical = 6.dp))
            if (items.isNotEmpty()) {
                Text(tr("一键设置等级："), fontSize = 12.sp, color = C.Faint, modifier = Modifier.padding(top = 8.dp))
                FlowRowLite {
                    for (lv in 0..2) Text(tr("全部设为{0}", Slot.LEVELS[lv]), fontSize = 12.sp, color = C.Ink,
                        modifier = Modifier.padding(top = 4.dp, end = 8.dp).clip(RoundedCornerShape(10.dp)).background(C.Sand).clickable {
                            for (i in items.indices) if (items[i].id !in off) items[i] = items[i].copy(level = lv)
                        }.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
            PrimaryButton(tr("导入 {0} 节", items.count { it.id !in off }), enabled = items.any { it.id !in off }) { onAdd(items.filter { it.id !in off }) }
        }
    }
    editing?.let { s -> SlotSheet(s, wifi = false, onSave = { n ->
        val i = items.indexOfFirst { it.id == s.id }
        // 同名课一起改等级
        for (j in items.indices) if (items[j].name == s.name && items[j].id != s.id && s.name.isNotEmpty()) items[j] = items[j].copy(level = n.level)
        if (i >= 0) items[i] = n else items += n
        editing = null
    }, onDelete = { items.removeAll { it.id == s.id }; editing = null }, onClose = { editing = null }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarPick(cals: List<Pair<Long, String>>, onPick: (Long) -> Unit, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, containerColor = C.Card) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Text(tr("选课表所在的日历"), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = C.Ink)
            Text(tr("WakeUp、小爱课程表等可以先同步到系统日历"), fontSize = 12.sp, color = C.Faint, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            if (cals.isEmpty()) Text(tr("没有找到日历"), fontSize = 14.sp, color = C.Soft)
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
    Text(tr("按星期、时间，或者连上某个 Wi-Fi 时自动变严。比如工作日 9–18 点、图书馆 Wi-Fi。"), fontSize = 13.sp, color = C.Soft, lineHeight = 20.sp)
    Spacer(Modifier.height(12.dp))
    for (s in scenes) SlotRow(s) { editing = s }
    Text(tr("＋ 新建场景"), fontSize = 14.sp, color = C.ClayDeep, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(12.dp)).clickable {
            editing = Slot("s" + System.currentTimeMillis(), "", setOf(1, 2, 3, 4, 5), 9 * 60, 18 * 60, 1, "", "scene")
        }.padding(vertical = 8.dp))
    editing?.let { s -> SlotSheet(s, wifi = true, onSave = { n -> save(all.filter { it.id != s.id } + n); editing = null },
        onDelete = { save(all.filter { it.id != s.id }); editing = null }, onClose = { editing = null }) }
}

/** 按「第几节到第几节」改时间：比拖滑块快，识别错了节次时一下就能改对。 */
@Composable
private fun SecPicker(s: Slot, onSet: (Int, Int) -> Unit) {
    val c = LocalContext.current
    val starts = remember { Scenes.secStarts(c) }
    val len = remember { Scenes.secLen(c) }
    fun near(m: Int) = starts.indices.minByOrNull { abs(starts[it] - m) } ?: 0
    val a = near(s.from) + 1
    val b = (starts.indices.minByOrNull { abs(starts[it] + len - s.to) } ?: 0).plus(1).coerceAtLeast(a)
    fun set(x: Int, y: Int) { val (f, t) = Scenes.secRange(c, x, y); onSet(f, t) }
    Row(Modifier.padding(top = 12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(tr("节次"), fontSize = 13.sp, color = C.Soft, modifier = Modifier.weight(1f))
        Stepper(a, 1, starts.size) { set(it, maxOf(it, b)) }
        Text(" – ", fontSize = 14.sp, color = C.Soft)
        Stepper(b, a, starts.size) { set(a, it) }
    }
}

@Composable
private fun Stepper(v: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(12.dp)).background(C.Sand), verticalAlignment = Alignment.CenterVertically) {
        Text("−", fontSize = 18.sp, color = if (v > min) C.Clay else C.Faint, modifier = Modifier.clickable(enabled = v > min) { onChange(v - 1) }.padding(horizontal = 12.dp, vertical = 4.dp))
        Text("$v", fontSize = 15.sp, color = C.Ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.widthIn(min = 20.dp))
        Text("+", fontSize = 18.sp, color = if (v < max) C.Clay else C.Faint, modifier = Modifier.clickable(enabled = v < max) { onChange(v + 1) }.padding(horizontal = 12.dp, vertical = 4.dp))
    }
}
