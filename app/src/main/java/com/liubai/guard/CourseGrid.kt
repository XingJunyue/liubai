package com.liubai.guard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

// ---------------- 配色 ----------------

/** 课块配色：浅底 + 同色系深字，对比度够。 */
val COURSE_COLORS = listOf(
    Color(0xFFF9D5DD) to Color(0xFF86283F), Color(0xFFFCE0CC) to Color(0xFF85400F), Color(0xFFFAEAC0) to Color(0xFF72540A),
    Color(0xFFE2EFC6) to Color(0xFF465E14), Color(0xFFD1EDDA) to Color(0xFF215C37), Color(0xFFCBEBE7) to Color(0xFF1B5A55),
    Color(0xFFD2E6F7) to Color(0xFF1C4F7A), Color(0xFFDADFF9) to Color(0xFF303C85), Color(0xFFE5D8F6) to Color(0xFF513085),
    Color(0xFFF2D6EE) to Color(0xFF752A6C), Color(0xFFEDE2D0) to Color(0xFF5A4527), Color(0xFFDCE4EB) to Color(0xFF364858)
)

/** 每门课一个颜色：按课名哈希，撞色时往后挪，同一张课表里尽量不重复。 */
fun coursePalette(courses: List<Slot>): Map<String, Pair<Color, Color>> {
    val out = HashMap<String, Pair<Color, Color>>()
    val used = HashSet<Int>()
    for (n in courses.map { it.name.trim() }.distinct().sorted()) {
        var h = Math.floorMod(n.hashCode(), COURSE_COLORS.size)
        var k = 0
        while (h in used && k < COURSE_COLORS.size) { h = (h + 1) % COURSE_COLORS.size; k++ }
        used += h; if (used.size >= COURSE_COLORS.size) used.clear()
        out[n] = COURSE_COLORS[h]
    }
    return out
}
private fun colorOf(s: Slot, pal: Map<String, Pair<Color, Color>>) =
    if (s.color in COURSE_COLORS.indices) COURSE_COLORS[s.color] else pal[s.name.trim()] ?: COURSE_COLORS[Math.floorMod(s.name.trim().hashCode(), COURSE_COLORS.size)]

private fun md(epochDay: Long): Pair<Int, Int> { val d = LocalDate.ofEpochDay(epochDay); return d.monthValue to d.dayOfMonth }
private fun ymd(epochDay: Long): String { val d = LocalDate.ofEpochDay(epochDay); return "${d.year}/${d.monthValue}/${d.dayOfMonth}" }
fun dateText(epochDay: Long): String {
    val d = LocalDate.ofEpochDay(epochDay)
    return if (Tr.cjk) tr("{0}年{1}月{2}日", d.year, d.monthValue, d.dayOfMonth) + " " + dayLabel(d.dayOfWeek.value)
    else "${dayLabel(d.dayOfWeek.value)}, ${d.month.getDisplayName(java.time.format.TextStyle.SHORT, Tr.locale())} ${d.dayOfMonth}, ${d.year}"
}

/** 调试版自动化测试用：直接打开某个网页导入。 */
object TimetableTest {
    @Volatile var webUrl: String? = null
    @Volatile var webKind: String? = null
    @Volatile var autoImport = false
    /** 教务导入页搜索框预填（冒烟测试截学校搜索用）。 */
    @Volatile var query: String? = null
}

private class Block(val s: Slot, val d: Int, val a: Int, val b: Int, val on: Boolean)

// ---------------- 课表整页 ----------------

/**
 * 课表：整页的一周格子，左右滑切换周，点空格加课，点课块改。
 * 右上角：添加、导入、分享备份、更多（学期与显示、每节课时间）。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TimetableScreen(onMenu: () -> Unit) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var ver by remember { mutableStateOf(0) }
    val all = remember(ver) { Scenes.desired(c) }
    val courses = remember(all) { all.filter { it.kind == "course" } }
    val pal = remember(courses) { coursePalette(courses) }
    val starts = remember(ver) { Scenes.secStarts(c) }
    val ends = remember(ver) { Scenes.secEnds(c) }
    val term = remember(ver) { Scenes.termStart(c) }
    val total = remember(ver) { Scenes.totalWeeks(c) }
    val showOff = remember(ver) { Scenes.showOff(c) }
    val nowWeek = remember(ver) { Scenes.weekNow(c) }
    val weekend = remember(ver, courses) { Scenes.showWeekend(c) || courses.any { s -> s.days.any { it >= 6 } } }
    val today = Scenes.todayEpoch()
    fun save(l: List<Slot>) { Scenes.setDesired(c, l); ver++ }

    // 弹出的东西
    var edit by remember { mutableStateOf<Pair<Slot, Int?>?>(null) }      // 正在改的课 + 点的是星期几（新加的为 null）
    var editNew by remember { mutableStateOf(false) }
    var showTerm by remember { mutableStateOf(false) }
    var termPicker by remember { mutableStateOf(false) }
    var showSecs by remember { mutableStateOf(false) }
    var importMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var shareMenu by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Pair<List<JwCourse>, Boolean>?>(null) }   // 课程 + 导完要不要确认开学日期
    var confirmTerm by remember { mutableStateOf(false) }
    var review by remember { mutableStateOf<List<Slot>?>(null) }
    var shot by remember { mutableStateOf<Uri?>(null) }
    var pickCal by remember { mutableStateOf<List<Pair<Long, String>>?>(null) }
    var icsLink by remember { mutableStateOf(false) }
    var backup by remember { mutableStateOf<Scenes.Backup?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }

    fun closeAll() { edit = null; showTerm = false; termPicker = false; showSecs = false; importMenu = false; moreMenu = false; shareMenu = false; web = false; preview = null; review = null; backup = null; confirmTerm = false }
    fun addAt(d: Int, sec: Int) {
        val n = starts.size
        val (f, t) = Scenes.secRange(c, sec, minOf(sec + 1, n))
        edit = Slot("c" + System.currentTimeMillis(), "", setOf(d), f, t) to null; editNew = true
    }

    // 自动化测试 / 外部跳转：table:N
    LaunchedEffect(Nav.subState.value) {
        when (Nav.sub) {
            0 -> closeAll()
            1 -> { closeAll(); web = true }
            2 -> { closeAll(); showTerm = true }
            3 -> { closeAll(); addAt(1, 1) }
            4 -> { closeAll(); courses.minWithOrNull(compareBy({ it.days.minOrNull() ?: 9 }, { it.from }))?.let { edit = it to (it.days.minOrNull() ?: 1); editNew = false } }
            5 -> { closeAll(); showTerm = true; termPicker = true }
            6 -> { closeAll(); web = true }
            7 -> { closeAll(); showSecs = true }
            8 -> { closeAll(); importMenu = true }
        }
    }

    // ----- 各种导入 -----
    suspend fun readText(u: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val bytes = c.contentResolver.openInputStream(u)?.use { it.readBytes() } ?: return@withContext null
            decodeText(bytes)
        } catch (x: Exception) { null }
    }
    val img = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null) { busy = tr("正在识别课表…"); msg = null; shot = u
            Ocr.read(c, u) { r, e -> busy = null; if (r != null) review = r else msg = e } }
    }
    /** 日历（文件或订阅链接）读出来后：需要时按日历定开学日期和总周数，再让用户勾选要加的课。 */
    fun useIcs(r: Ics.Result?, err: String?) {
        busy = null
        when {
            r == null -> msg = err ?: tr("这个文件里没读到课程")
            r.slots.isEmpty() -> msg = if (r.deadlines > 0) tr("只读到 {0} 个截止日期，没有上下课时间的课。换成学校课表系统的订阅链接试试。", r.deadlines) else tr("这个文件里没读到课程")
            else -> {
                r.firstMonday?.let { Scenes.setTermStart(c, it) }
                if (r.maxWeek > Scenes.totalWeeks(c)) Scenes.setTotalWeeks(c, r.maxWeek)
                ver++
                msg = listOfNotNull(
                    r.firstMonday?.let { tr("已按日历把第 1 周设为 {0} 那周", ymd(it)) },
                    if (r.deadlines > 0) tr("跳过了 {0} 个截止日期", r.deadlines) else null
                ).joinToString("；").ifEmpty { null }
                shot = null; review = r.slots
            }
        }
    }
    fun runIcs(read: () -> String) = scope.launch {
        busy = tr("正在读取…"); msg = null
        var err: String? = null
        val r = withContext(Dispatchers.IO) {
            try { Ics.parse(read(), Scenes.termStart(c), Scenes.totalWeeks(c)) }
            catch (x: Ics.LinkDead) { err = tr("链接打不开（{0}），可能已经失效。到学校网站重新复制一次订阅链接。", x.code); null }
            catch (x: Ics.NotCalendar) { err = tr("这个链接打开的不是日历（多半是登录页）。要复制「订阅 / iCal」那个链接，不是网页地址。"); null }
            catch (x: java.io.IOException) { err = tr("连不上：{0}", x.message ?: ""); null }
            catch (x: Exception) { err = tr("读取失败：{0}", x.message ?: ""); null }
        }
        useIcs(r, err)
    }
    val ics = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) runIcs { c.contentResolver.openInputStream(u)?.bufferedReader()?.use { it.readText() } ?: "" }
    }
    val calPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) pickCal = try { Scenes.calendars(c) } catch (x: Exception) { emptyList() } else msg = tr("没有日历权限，读不到")
    }
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch {
            busy = tr("正在读取…"); msg = null
            val raw = withContext(Dispatchers.IO) { try { c.contentResolver.openInputStream(u)?.use { it.readBytes() } } catch (x: Exception) { null } }
            busy = null
            when {
                raw == null -> msg = tr("读不了这个文件")
                raw.size > 4 && raw[0] == 'P'.code.toByte() && raw[1] == 'K'.code.toByte() -> {
                    // Workday「Export to Excel」之类的选课清单：直接认
                    val sis = withContext(Dispatchers.Default) { try { SisSheet.meets(SisSheet.xlsx(raw) ?: emptyList()) } catch (x: Exception) { emptyList() } }
                    if (sis.isNotEmpty()) useIcs(SisSheet.slots(sis, Scenes.termStart(c), Scenes.totalWeeks(c)), null)
                    else msg = tr("这是 .xlsx 文件，请在 Excel / WPS 里「另存为」CSV（逗号分隔）再导入")
                }
                else -> {
                    val text = decodeText(raw)
                    val r = withContext(Dispatchers.Default) { try { Jw.clean(Jw.csv(text)) } catch (x: Exception) { emptyList() } }
                    val sis = if (r.isEmpty()) withContext(Dispatchers.Default) { try { SisSheet.meets(SisSheet.csv(text)) } catch (x: Exception) { emptyList() } } else emptyList()
                    if (sis.isNotEmpty()) useIcs(SisSheet.slots(sis, Scenes.termStart(c), Scenes.totalWeeks(c)), null)
                    else if (r.isEmpty()) msg = tr("没读到课程。表头应为：课程名称,星期,开始节数,结束节数,老师,地点,周数") else preview = r to true
                }
            }
        }
    }
    val html = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch {
            busy = tr("正在识别…"); msg = null
            val t = readText(u)
            val r = if (t == null) emptyList() else withContext(Dispatchers.Default) {
                try { Jw.parse("generic", org.json.JSONObject().put("html", org.json.JSONArray().put(t)).toString()) } catch (x: Exception) { emptyList() }
            }
            busy = null
            if (r.isEmpty()) msg = tr("这个网页里没找到课表。要保存「我的课表」那一页（网页，全部）") else preview = r to true
        }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) scope.launch {
            val b = readText(u)?.let { Scenes.parseBackup(it) }
            if (b == null || b.courses.isEmpty() && b.starts.isEmpty()) msg = tr("这不是留白的课表备份") else backup = b
        }
    }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { u ->
        if (u != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { try { c.contentResolver.openOutputStream(u)?.use { it.write(Scenes.exportBackup(c).toByteArray()) }; true } catch (x: Exception) { false } }
            msg = if (ok) tr("已导出课表备份") else tr("导出失败")
        }
    }
    fun share() {
        try {
            val dir = java.io.File(c.cacheDir, "share").apply { mkdirs() }
            val f = java.io.File(dir, "liubai-timetable.json"); f.writeText(Scenes.exportBackup(c))
            val uri = androidx.core.content.FileProvider.getUriForFile(c, c.packageName + ".files", f)
            c.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), tr("分享课表")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (x: Exception) { msg = tr("分享失败") }
    }

    if (web) {
        BackHandler { web = false }
        JwImportScreen(onClose = { web = false }, onParsed = { r -> web = false; preview = r to true })
        preview?.let { (r, ask) -> ImportPreview(r, onSave = { replace -> applyImport(c, r, replace); preview = null; ver++; if (ask) confirmTerm = true }, onClose = { preview = null }) }
        return
    }

    val pages = if (term >= 0) maxOf(total, nowWeek ?: 1, 1) else 1
    val pager = rememberPagerState(initialPage = ((nowWeek ?: 1) - 1).coerceIn(0, pages - 1), pageCount = { pages })
    LaunchedEffect(term) { if (term >= 0 && nowWeek != null) pager.scrollToPage((nowWeek - 1).coerceIn(0, pages - 1)) }
    val shownWeek: Int? = if (term >= 0) pager.currentPage + 1 else null

    Column(Modifier.fillMaxSize()) {
        // ----- 顶栏 -----
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable { onMenu() }, contentAlignment = Alignment.Center) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(3) { i -> Box(Modifier.width(if (i == 1) 11.dp else 17.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(C.Ink)) }
                }
            }
            Column(Modifier.weight(1f).padding(start = 4.dp).clip(RoundedCornerShape(12.dp)).clickable { showTerm = true }.padding(horizontal = 6.dp, vertical = 2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (shownWeek == null) tr("课表") else tr("第 {0} 周", shownWeek), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = C.Ink, maxLines = 1)
                    val tag = when {
                        shownWeek == null -> null
                        shownWeek == nowWeek -> tr("本周")
                        nowWeek != null && nowWeek > total && shownWeek == pages -> null
                        else -> tr("非本周")
                    }
                    if (tag != null) Text(tag, fontSize = 11.sp, color = if (shownWeek == nowWeek) C.ClayDeep else C.Soft, maxLines = 1,
                        modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(8.dp)).background(if (shownWeek == nowWeek) C.ClayPale else C.Sand)
                            .padding(horizontal = 7.dp, vertical = 2.dp))
                }
                Text(
                    if (term < 0) tr("点这里设置开学日期") else ymd(today) + "  " + dayLabel(LocalDate.ofEpochDay(today).dayOfWeek.value) +
                        (if (nowWeek != null && nowWeek > total) "  · " + tr("学期已结束") else if (nowWeek != null && nowWeek < 1) "  · " + tr("还没开学") else ""),
                    fontSize = 12.sp, color = if (term < 0) C.ClayDeep else C.Soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TopIcon(onClick = { addAt(Tasks.weekday().coerceAtMost(if (weekend) 7 else 5), 1) }) { Icon(Icons.Filled.Add, tr("添加课程"), tint = C.Ink) }
            Box {
                TopIcon(onClick = { importMenu = true }) { ImportGlyph(C.Ink) }
                DropdownMenu(importMenu, { importMenu = false }, modifier = Modifier.background(C.Card)) {
                    // 国外的学校多半给日历订阅链接：非中文界面放在最上面
                    if (!Tr.cjk) MenuRow(tr("日历订阅链接"), tr("Canvas、Moodle、学校课表系统的 iCal 链接")) { importMenu = false; icsLink = true }
                    MenuRow(tr("从教务导入"), tr("登录学校教务网站，一键导入")) { importMenu = false; web = true }
                    MenuRow(tr("Excel / CSV 导入"), if (Tr.cjk) tr("课程名、星期、节次、老师、地点、周数") else tr("Workday 等选课系统导出的课程清单，或自己整理的表格")) { importMenu = false; csv.launch(arrayOf("text/*", "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "*/*")) }
                    MenuRow(tr("HTML 导入"), tr("保存下来的教务课表网页")) { importMenu = false; html.launch(arrayOf("text/html", "application/xhtml+xml", "*/*")) }
                    MenuRow(tr("截图识别"), tr("课表截图，本机识别")) { importMenu = false; img.launch("image/*") }
                    MenuRow(tr("从日历导入"), tr("系统日历里的课程日程")) {
                        importMenu = false
                        if (ContextCompat.checkSelfPermission(c, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED)
                            pickCal = try { Scenes.calendars(c) } catch (x: Exception) { emptyList() }
                        else calPerm.launch(Manifest.permission.READ_CALENDAR)
                    }
                    if (Tr.cjk) MenuRow(tr("日历订阅链接"), tr("Canvas、Moodle、学校课表系统的 iCal 链接")) { importMenu = false; icsLink = true }
                    MenuRow(tr(".ics 文件"), tr("课表 App 或教务导出的日历文件")) { importMenu = false; ics.launch(arrayOf("text/calendar", "*/*")) }
                    HorizontalDivider(color = C.Track)
                    MenuRow(tr("从备份导入"), tr("留白导出的课表备份")) { importMenu = false; restore.launch(arrayOf("application/json", "text/*", "*/*")) }
                }
            }
            Box {
                TopIcon(onClick = { shareMenu = true }) { Icon(Icons.Filled.Share, tr("导出"), tint = C.Ink, modifier = Modifier.size(21.dp)) }
                DropdownMenu(shareMenu, { shareMenu = false }, modifier = Modifier.background(C.Card)) {
                    MenuRow(tr("分享课表备份"), tr("发给自己或同学，可在留白里导入")) { shareMenu = false; share() }
                    MenuRow(tr("导出到文件"), tr("保存为 .json，换手机时导入")) { shareMenu = false; saveFile.launch("liubai-timetable.json") }
                }
            }
            Box {
                TopIcon(onClick = { moreMenu = true }) { Icon(Icons.Filled.MoreVert, tr("更多"), tint = C.Ink) }
                DropdownMenu(moreMenu, { moreMenu = false }, modifier = Modifier.background(C.Card)) {
                    MenuRow(tr("学期与显示"), tr("开学日期、总周数、周末")) { moreMenu = false; showTerm = true }
                    MenuRow(tr("每节课时间"), tr("上下课时间、节数")) { moreMenu = false; showSecs = true }
                }
            }
        }
        busy?.let { Row(Modifier.padding(horizontal = 18.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(14.dp), color = C.Clay, strokeWidth = 2.dp); Text("  $it", fontSize = 12.sp, color = C.Soft) } }
        msg?.let { m ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(C.ClayPale).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(m, fontSize = 12.sp, color = C.ClayDeep, lineHeight = 17.sp, modifier = Modifier.weight(1f))
                if (shot != null && review == null) Text(tr("自己填"), fontSize = 12.sp, color = C.Ink, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { msg = null; review = emptyList() }.padding(6.dp))
                Text("✕", fontSize = 13.sp, color = C.Soft, modifier = Modifier.clip(CircleShape).clickable { msg = null }.padding(6.dp))
            }
        }

        // ----- 格子 -----
        val days = if (weekend) (1..7).toList() else (1..5).toList()
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondBoundsPageCount = 1) { page ->
            val week: Int? = if (term >= 0) page + 1 else null
            val monday = if (week != null) term + (week - 1) * 7L else Scenes.mondayOf(today)
            WeekGrid(courses, pal, days, week, monday, today, starts, ends, showOff,
                onOpen = { s, d -> edit = s to d; editNew = false }, onAdd = ::addAt)
        }
    }

    // ----- 弹窗 -----
    edit?.let { (s0, d) ->
        val start = if (d != null && s0.days.size > 1) s0.copy(days = setOf(d)) else s0
        key(s0.id, d) { CourseSheet(start, isNew = editNew, onSave = { n ->
            val cur = Scenes.desired(c)
            val rest = cur.flatMap { if (it.id != s0.id) listOf(it) else if (d != null && it.days.size > 1) listOf(it.copy(days = it.days - d)) else emptyList() }
            val nn = if (!editNew && s0.days.size > 1) n.copy(id = "c" + System.currentTimeMillis()) else n
            save(rest + nn); edit = null
        }, onDelete = {
            val cur = Scenes.desired(c)
            save(cur.flatMap { if (it.id != s0.id) listOf(it) else if (d != null && it.days.size > 1) listOf(it.copy(days = it.days - d)) else emptyList() })
            edit = null
        }, onClose = { edit = null }) }
    }
    if (showTerm) TermSheet(openPicker = termPicker, onChange = { ver++ }, onClose = { showTerm = false; termPicker = false })
    if (showSecs) SecTimesSheet(onChange = { ver++ }, onClose = { showSecs = false })
    preview?.let { (r, ask) -> ImportPreview(r, onSave = { replace -> applyImport(c, r, replace); preview = null; ver++; if (ask) confirmTerm = true }, onClose = { preview = null }) }
    if (confirmTerm) TermDateDialog(title = tr("确认开学日期"), sub = tr("选第 1 周里的任意一天，用来区分单双周和非本周的课"),
        initial = if (Scenes.termStart(c) >= 0) Scenes.termStart(c) else Scenes.mondayOf(today),
        onPick = { Scenes.setTermStart(c, Scenes.mondayOf(it)); confirmTerm = false; ver++ }, onClose = { confirmTerm = false })
    review?.let { r -> ReviewSheet(r, shot, onAdd = { picked ->
        val cur = Scenes.desired(c)
        val names = picked.map { "${it.name}|${it.days}|${it.from}" }.toSet()
        save(cur.filter { "${it.name}|${it.days}|${it.from}" !in names } + picked); review = null
    }, onClose = { review = null }) }
    if (icsLink) IcsLinkSheet(onGo = { u -> icsLink = false; runIcs { Ics.fetch(u) } }, onClose = { icsLink = false })
    pickCal?.let { cals -> CalendarPick(cals, onPick = { id ->
        pickCal = null
        scope.launch {
            busy = tr("正在读取日历…")
            val r = withContext(Dispatchers.IO) { try { Scenes.fromCalendar(c, id) } catch (x: Exception) { null } }
            busy = null
            if (r.isNullOrEmpty()) msg = tr("这个日历接下来两周没有日程") else { shot = null; review = r }
        }
    }, onClose = { pickCal = null }) }
    backup?.let { b ->
        AlertDialog(onDismissRequest = { backup = null }, containerColor = C.Card,
            title = { Text(tr("从备份恢复课表？"), color = C.Ink) },
            text = { Text(tr("备份里有 {0} 节课。会替换现在的课表、每节课时间和开学日期；删掉的课明天 0:00 才停止拦截。", b.courses.size), color = C.Soft, lineHeight = 20.sp) },
            confirmButton = { TextButton({ Scenes.applyBackup(c, b); backup = null; ver++; msg = tr("已从备份恢复") }) { Text(tr("恢复"), color = C.ClayDeep) } },
            dismissButton = { TextButton({ backup = null }) { Text(tr("取消"), color = C.Soft) } })
    }
}

/** 把导入的课存进课表：覆盖 = 换掉所有课（场景不动），追加 = 加在后面。 */
fun applyImport(c: android.content.Context, r: List<JwCourse>, replace: Boolean) {
    val slots = Jw.toSlots(r, Scenes.secStarts(c), Scenes.secEnds(c), Scenes.totalWeeks(c))
    val cur = Scenes.desired(c)
    Scenes.setDesired(c, (if (replace) cur.filter { it.kind != "course" } else cur) + slots)
    // 导入的课超过 20 周时，总周数跟着变
    val maxW = r.flatMap { it.weeks }.maxOrNull() ?: 0
    if (maxW > Scenes.totalWeeks(c)) Scenes.setTotalWeeks(c, maxW)
}

/** 文本文件解码：先当 UTF-8，乱码再按 GBK（Excel 存的 CSV 常是 GBK）。 */
fun decodeText(b: ByteArray): String {
    val u = String(b, Charsets.UTF_8)
    if (!u.contains('\uFFFD')) return u.removePrefix("\uFEFF")
    return try { String(b, charset("GBK")) } catch (x: Exception) { u }
}

@Composable
private fun TopIcon(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.size(42.dp).clip(CircleShape).clickable { onClick() }, contentAlignment = Alignment.Center) { content() }
}

/** 导入图标：托盘 + 向下的箭头。 */
@Composable
private fun ImportGlyph(col: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
        val w = size.width; val sw = w * 0.09f
        val st = Stroke(sw, cap = StrokeCap.Round)
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.14f, w * 0.52f); lineTo(w * 0.14f, w * 0.84f); lineTo(w * 0.86f, w * 0.84f); lineTo(w * 0.86f, w * 0.52f)
        }
        drawPath(p, col, style = st)
        drawLine(col, Offset(w * 0.5f, w * 0.12f), Offset(w * 0.5f, w * 0.62f), sw, StrokeCap.Round)
        drawLine(col, Offset(w * 0.32f, w * 0.45f), Offset(w * 0.5f, w * 0.63f), sw, StrokeCap.Round)
        drawLine(col, Offset(w * 0.68f, w * 0.45f), Offset(w * 0.5f, w * 0.63f), sw, StrokeCap.Round)
    }
}

@Composable
private fun MenuRow(t: String, sub: String, onClick: () -> Unit) {
    DropdownMenuItem(text = {
        Column(Modifier.padding(vertical = 2.dp)) {
            Text(t, fontSize = 15.sp, color = C.Ink)
            Text(sub, fontSize = 11.sp, color = C.Faint, maxLines = 1)
        }
    }, onClick = onClick)
}

// ---------------- 一周的格子 ----------------

@Composable
private fun WeekGrid(
    courses: List<Slot>, pal: Map<String, Pair<Color, Color>>, days: List<Int>, week: Int?, monday: Long, today: Long,
    starts: List<Int>, ends: List<Int>, showOff: Boolean, onOpen: (Slot, Int) -> Unit, onAdd: (Int, Int) -> Unit
) {
    val n = starts.size.coerceAtLeast(1)
    val leftW = 38.dp
    var tick by remember { mutableStateOf(0) }
    Every(60_000) { tick++ }
    Column(Modifier.fillMaxSize()) {
        // 表头：月份 + 星期和日期
        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (Tr.cjk) tr("{0}月", md(monday).first) else LocalDate.ofEpochDay(monday).month.getDisplayName(java.time.format.TextStyle.SHORT, Tr.locale()),
                fontSize = 11.sp, color = C.Soft, textAlign = TextAlign.Center, lineHeight = 13.sp, modifier = Modifier.width(leftW))
            for (d in days) {
                val day = monday + (d - 1)
                val isToday = day == today
                Column(Modifier.weight(1f).padding(horizontal = 2.dp).clip(RoundedCornerShape(10.dp)).background(if (isToday) C.ClayPale else Color.Transparent)
                    .padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(dayLabel(d), fontSize = 12.sp, color = if (isToday) C.ClayDeep else C.Ink, fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                    Text(md(day).let { "${it.first}/${it.second}" }, fontSize = 10.sp, color = if (isToday) C.ClayDeep else C.Faint, maxLines = 1)
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(top = 4.dp)) {
            val rowH = maxOf(56.dp, (maxHeight - 8.dp) / n)
            val colW: Dp = (maxWidth - leftW - 4.dp) / days.size
            val density = LocalDensity.current
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Box(Modifier.padding(start = 2.dp, end = 2.dp).fillMaxWidth().height(rowH * n + 8.dp).pointerInput(days, n, colW, rowH) {
                    detectTapGestures { o ->
                        val x = with(density) { o.x.toDp() } - leftW
                        if (x < 0.dp) return@detectTapGestures
                        val di = (x / colW).toInt().coerceIn(0, days.lastIndex)
                        val si = (with(density) { o.y.toDp() } / rowH).toInt().coerceIn(0, n - 1)
                        onAdd(days[di], si + 1)
                    }
                }) {
                    // 今天这一列淡淡地底色
                    val ti = days.indexOfFirst { monday + (it - 1) == today }
                    if (ti >= 0) Box(Modifier.offset(x = leftW + colW * ti).width(colW).height(rowH * n).clip(RoundedCornerShape(10.dp)).background(C.ClayPale.copy(alpha = 0.45f)))
                    // 左边节次和时间
                    for (i in 0 until n) {
                        if (i > 0) Box(Modifier.offset(x = leftW, y = rowH * i).width(colW * days.size).height(1.dp).background(C.Track.copy(alpha = 0.55f)))
                        Column(Modifier.offset(y = rowH * i).width(leftW).height(rowH), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text("${i + 1}", fontSize = 14.sp, color = C.Ink, fontWeight = FontWeight.SemiBold, lineHeight = 16.sp)
                            Text(Rules.clock(starts[i]), fontSize = 9.sp, color = C.Faint, lineHeight = 11.sp)
                            Text(Rules.clock(ends.getOrElse(i) { starts[i] + 45 }), fontSize = 9.sp, color = C.Faint, lineHeight = 11.sp)
                        }
                    }
                    // 课块：本周的在上；非本周的只在空位上淡淡显示
                    val cur = ArrayList<Block>(); val off = ArrayList<Block>()
                    for (s in courses) {
                        val (a, b) = Scenes.secSpan(starts, ends, s.from, s.to)
                        val on = s.inWeek(week)
                        for (d in s.days) if (d in days) (if (on) cur else off) += Block(s, d, a, b, on)
                    }
                    val shown = cur.distinctBy { listOf(it.d, it.a, it.b, it.s.name) } +
                        (if (showOff) off.filter { o -> cur.none { it.d == o.d && it.a <= o.b && o.a <= it.b } }.distinctBy { Triple(it.d, it.a, it.b) } else emptyList())
                    for (k in shown) CourseBlock(k, colorOf(k.s, pal),
                        Modifier.offset(x = leftW + colW * days.indexOf(k.d) + 1.5.dp, y = rowH * (k.a - 1) + 1.5.dp)
                            .size(colW - 3.dp, rowH * (k.b - k.a + 1) - 3.dp)) { onOpen(k.s, k.d) }
                    // 现在的时间线
                    if (ti >= 0) {
                        val now = remember(tick) { Rules.nowMin() }
                        val y = nowY(now, starts, ends, rowH)
                        if (y != null) {
                            Box(Modifier.offset(x = leftW + colW * ti - 3.dp, y = y - 3.dp).size(6.dp).clip(CircleShape).background(C.ClayDeep))
                            Box(Modifier.offset(x = leftW + colW * ti, y = y - 1.dp).width(colW).height(2.dp).background(C.ClayDeep))
                        }
                    }
                }
            }
            if (courses.isEmpty()) Column(Modifier.align(Alignment.Center).padding(horizontal = 40.dp).clip(RoundedCornerShape(18.dp)).background(C.Card.copy(alpha = 0.92f)).padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(tr("还没有课"), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = C.Ink)
                Text(tr("点右上角的导入，从教务网站、截图或文件导入；也可以直接点格子加课。"), fontSize = 13.sp, color = C.Soft, lineHeight = 19.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

/** 现在这一刻在格子里的高度；不在上课时间范围内返回 null。 */
private fun nowY(now: Int, starts: List<Int>, ends: List<Int>, rowH: Dp): Dp? {
    if (starts.isEmpty() || now < starts.first() || now > (ends.lastOrNull() ?: starts.last() + 45)) return null
    for (i in starts.indices) {
        val s = starts[i]; val e = ends.getOrElse(i) { s + 45 }
        if (now in s until e) return rowH * i + rowH * ((now - s).toFloat() / (e - s).coerceAtLeast(1))
        val next = starts.getOrNull(i + 1)
        if (next != null && now in e until next) return rowH * (i + 1)
    }
    return null
}

/** 课块里能放下几行：课名优先，其次教室，最后老师；放不下整行的就不放，避免半截字。 */
private class Fit(val name: Int, val room: Int, val teacher: Int)

@Composable
private fun CourseBlock(k: Block, col: Pair<Color, Color>, m: Modifier, onClick: () -> Unit) {
    val (bg, fg) = col
    val span = k.b - k.a + 1
    val tm = rememberTextMeasurer()
    val density = LocalDensity.current
    Box(m.clip(RoundedCornerShape(9.dp)).background(if (k.on) bg else C.Sand.copy(alpha = 0.75f))
        .then(if (k.on) Modifier else Modifier.border(1.dp, C.Track, RoundedCornerShape(9.dp)))
        .clickable { onClick() }) {
        if (k.on) Box(Modifier.fillMaxHeight().width(3.dp).background(fg.copy(alpha = 0.35f)))
        val tc = if (k.on) fg else C.Faint
        val tag: String? = when {
            !k.on -> if (Tr.cjk) tr("非本周") else tr("非本周（短）")
            k.s.level == 2 && span >= 2 -> if (Tr.cjk) Slot.LEVELS[2] else "!"
            else -> null
        }
        val sName = TextStyle(fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.SemiBold)
        val sRoom = TextStyle(fontSize = 11.sp, lineHeight = 13.sp)
        val sTeach = TextStyle(fontSize = 10.sp, lineHeight = 12.sp)
        BoxWithConstraints(Modifier.fillMaxSize().padding(start = 5.dp, end = 3.dp, top = 5.dp, bottom = 4.dp)) {
            val wPx = constraints.maxWidth; val hPx = constraints.maxHeight
            val room = if (k.s.room.isNotBlank()) "@" + k.s.room else ""
            val fit = remember(k.s.name, room, k.s.teacher, wPx, hPx, tag) {
                val cons = androidx.compose.ui.unit.Constraints(maxWidth = wPx.coerceAtLeast(1))
                fun lines(t: String, st: TextStyle) = if (t.isBlank()) 0 else tm.measure(androidx.compose.ui.text.AnnotatedString(t), st, constraints = cons).lineCount
                fun h(t: String, st: TextStyle, n: Int) = if (n <= 0 || t.isBlank()) 0 else
                    tm.measure(androidx.compose.ui.text.AnnotatedString(t), st, overflow = TextOverflow.Ellipsis, maxLines = n, constraints = cons).size.height
                val gap = with(density) { 3.dp.toPx() }
                val avail = hPx - (if (tag != null) with(density) { 17.dp.toPx() } else 0f)
                val nL = lines(k.s.name, sName); val rL = minOf(lines(room, sRoom), 3); val tL = minOf(lines(k.s.teacher, sTeach), 2)
                fun cost(n: Int, r: Int, t: Int) = h(k.s.name, sName, n) + (if (r > 0) gap + h(room, sRoom, r) else 0f) + (if (t > 0) gap + h(k.s.teacher, sTeach, t) else 0f)
                var n = minOf(nL, 2).coerceAtLeast(1); var r = 0; var t = 0
                while (n > 1 && cost(n, 0, 0) > avail) n--
                if (rL > 0 && cost(n, 1, 0) <= avail) r = 1
                while (n < nL && cost(n + 1, r, 0) <= avail) n++
                while (r in 1 until rL && cost(n, r + 1, 0) <= avail) r++
                while (t < tL && cost(n, r, t + 1) <= avail) t++
                Fit(n, r, t)
            }
            Column(Modifier.fillMaxSize()) {
                Text(k.s.name, style = sName, color = tc, maxLines = fit.name, overflow = TextOverflow.Ellipsis)
                if (fit.room > 0) Text(room, style = sRoom, color = tc.copy(alpha = 0.9f), maxLines = fit.room, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                if (fit.teacher > 0) Text(k.s.teacher, style = sTeach, color = tc.copy(alpha = 0.75f), maxLines = fit.teacher, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                Spacer(Modifier.weight(1f))
                if (tag != null) Text(tag, fontSize = 9.sp, lineHeight = 11.sp, maxLines = 1, softWrap = false,
                    color = if (k.on) Color.White else C.Soft,
                    modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(if (k.on) fg.copy(alpha = 0.75f) else C.Card).padding(horizontal = 4.dp, vertical = 2.dp))
            }
        }
    }
}

// ---------------- 加课 / 改课 ----------------

@Composable
private fun CField(value: String, hint: String, max: Int = 30, big: Boolean = false, onChange: (String) -> Unit) {
    Box(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Sand).padding(horizontal = 14.dp, vertical = 12.dp)) {
        if (value.isEmpty()) Text(hint, fontSize = if (big) 16.sp else 14.sp, color = C.Faint)
        BasicTextField(value, { onChange(it.take(max)) }, singleLine = true,
            textStyle = TextStyle(fontSize = if (big) 16.sp else 14.sp, color = C.Ink, fontWeight = if (big) FontWeight.SemiBold else FontWeight.Normal), modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun WChip(t: String, on: Boolean, onClick: () -> Unit) {
    Text(t, fontSize = 12.sp, color = if (on) Color.White else C.Ink, maxLines = 1,
        modifier = Modifier.padding(top = 6.dp, end = 6.dp).clip(RoundedCornerShape(10.dp)).background(if (on) C.Clay else C.Sand).clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp))
}

@Composable
private fun Label(t: String, value: String? = null) {
    Row(Modifier.padding(top = 16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(t, fontSize = 13.sp, color = C.Soft, modifier = Modifier.weight(1f))
        if (value != null) Text(value, fontSize = 13.sp, color = C.Ink, fontWeight = FontWeight.SemiBold)
    }
}

/** 加课、改课：课名、教室、老师、星期、第几节、周次、上课时的等级、颜色。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseSheet(start: Slot, isNew: Boolean = start.name.isEmpty(), onSave: (Slot) -> Unit, onDelete: () -> Unit, onClose: () -> Unit) {
    val c = LocalContext.current
    var s by remember { mutableStateOf(start) }
    val total = remember { Scenes.totalWeeks(c) }
    val starts = remember { Scenes.secStarts(c) }
    val ends = remember { Scenes.secEnds(c) }
    var askDel by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = C.Card) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 20.dp).navigationBarsPadding().imePadding().verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (isNew) tr("添加课程") else tr("编辑课程"), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = C.Ink, modifier = Modifier.weight(1f))
                val ok = s.name.isNotBlank() && s.days.isNotEmpty() && s.from != s.to
                Text(tr("保存"), fontSize = 15.sp, color = if (ok) Color.White else C.Faint, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (ok) C.Clay else C.Sand).clickable(enabled = ok) {
                        onSave(s.copy(name = s.name.trim(), room = s.room.trim(), teacher = s.teacher.trim()))
                    }.padding(horizontal = 18.dp, vertical = 8.dp))
            }
            CField(s.name, tr("课名"), big = true) { s = s.copy(name = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { CField(s.room, tr("教室（选填）"), 30) { s = s.copy(room = it) } }
                Box(Modifier.weight(1f)) { CField(s.teacher, tr("老师（选填）"), 30) { s = s.copy(teacher = it) } }
            }

            Label(tr("星期"))
            Row(Modifier.padding(top = 8.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (x in 1..7) {
                    val on = x in s.days
                    Box(Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(10.dp)).background(if (on) C.Clay else C.Sand).clickable { s = s.copy(days = setOf(x)) },
                        contentAlignment = Alignment.Center) { Text(dayShort(x), fontSize = 13.sp, color = if (on) Color.White else C.Soft, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal) }
                }
            }

            val (a, b) = Scenes.secSpan(starts, ends, s.from, s.to)
            fun setSec(x: Int, y: Int) { val (f, t) = Scenes.secRange(c, x, y); s = s.copy(from = f, to = t) }
            Row(Modifier.padding(top = 16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("节次"), fontSize = 13.sp, color = C.Soft)
                    Text("${Rules.clock(s.from)} – ${Rules.clock(s.to)}", fontSize = 12.sp, color = C.Faint)
                }
                Stepper(a, 1, starts.size) { setSec(it, maxOf(it, b)) }
                Text(" – ", fontSize = 14.sp, color = C.Soft)
                Stepper(b, a, starts.size) { setSec(a, it) }
            }

            Label(tr("周次"), Slot.weeksText(s.weeks))
            val all = (1..total).toSet()
            val shown = if (s.weeks.isEmpty()) all else s.weeks
            fun setW(w: Set<Int>) { s = s.copy(weeks = if (w == all || w.isEmpty()) emptySet() else w) }
            FlowRowLite {
                WChip(tr("全部"), s.weeks.isEmpty()) { setW(all) }
                WChip(tr("单周"), s.weeks.isNotEmpty() && s.weeks == all.filter { it % 2 == 1 }.toSet()) { setW(all.filter { it % 2 == 1 }.toSet()) }
                WChip(tr("双周"), s.weeks.isNotEmpty() && s.weeks == all.filter { it % 2 == 0 }.toSet()) { setW(all.filter { it % 2 == 0 }.toSet()) }
                WChip(tr("前半学期"), s.weeks == (1..total / 2).toSet()) { setW((1..total / 2).toSet()) }
                WChip(tr("后半学期"), s.weeks == (total / 2 + 1..total).toSet()) { setW((total / 2 + 1..total).toSet()) }
            }
            FlowRowLite {
                for (w in 1..total) {
                    val on = w in shown
                    Box(Modifier.padding(top = 6.dp, end = 6.dp).size(36.dp).clip(CircleShape).background(if (on) C.Clay else C.Sand)
                        .clickable { setW(if (on) shown - w else shown + w) }, contentAlignment = Alignment.Center) {
                        Text("$w", fontSize = 12.sp, color = if (on) Color.White else C.Soft)
                    }
                }
            }

            Label(tr("上课时"))
            Spacer(Modifier.height(8.dp))
            Segments(Slot.LEVELS, s.level) { s = s.copy(level = it) }
            Text(Slot.LEVEL_NOTE[s.level], fontSize = 12.sp, color = C.Faint, modifier = Modifier.padding(top = 6.dp))

            Label(tr("颜色"))
            FlowRowLite {
                Box(Modifier.padding(top = 8.dp, end = 8.dp).size(30.dp).clip(CircleShape).background(C.Sand)
                    .border(2.dp, if (s.color < 0) C.Ink else Color.Transparent, CircleShape).clickable { s = s.copy(color = -1) }, contentAlignment = Alignment.Center) {
                    Text(tr("自动"), fontSize = 9.sp, color = C.Soft, maxLines = 1)
                }
                COURSE_COLORS.forEachIndexed { i, (bg, fg) ->
                    Box(Modifier.padding(top = 8.dp, end = 8.dp).size(30.dp).clip(CircleShape).background(bg)
                        .border(2.dp, if (s.color == i) fg else Color.Transparent, CircleShape).clickable { s = s.copy(color = i) })
                }
            }
            Text(tr("加新的、调严马上生效；删除、调松明天 0:00 生效。"), fontSize = 11.sp, color = C.Faint, lineHeight = 16.sp, modifier = Modifier.padding(top = 14.dp))
            if (!isNew) Text(if (askDel) tr("再点一次确认删除") else tr("删除这节课"), fontSize = 14.sp, color = if (askDel) C.ClayDeep else C.Soft, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Sand.copy(alpha = 0.6f))
                    .clickable { if (askDel) onDelete() else askDel = true }.padding(vertical = 12.dp))
        }
    }
}

// ---------------- 学期与显示 ----------------

/** 日期选择：按界面语言显示。返回选中那天的 epoch 天。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermDateDialog(title: String, sub: String, initial: Long, onPick: (Long) -> Unit, onClose: () -> Unit) {
    val conf = LocalConfiguration.current
    val loc = remember(conf) { android.content.res.Configuration(conf).apply { setLocale(Tr.locale()) } }
    CompositionLocalProvider(LocalConfiguration provides loc) {
        val st = rememberDatePickerState(initialSelectedDateMillis = initial * 86_400_000L)
        DatePickerDialog(onDismissRequest = onClose,
            colors = DatePickerDefaults.colors(containerColor = C.Card),
            confirmButton = { TextButton({ st.selectedDateMillis?.let { onPick(Math.floorDiv(it, 86_400_000L)) } ?: onClose() }) { Text(tr("确定"), color = C.ClayDeep, fontWeight = FontWeight.SemiBold) } },
            dismissButton = { TextButton(onClose) { Text(tr("取消"), color = C.Soft) } }) {
            // 对话框是单独的窗口，会重新提供 LocalConfiguration，所以在里面再按界面语言覆盖一次
            CompositionLocalProvider(LocalConfiguration provides android.content.res.Configuration(LocalConfiguration.current).apply { setLocale(Tr.locale()) }) {
            DatePicker(st, title = {
                Column(Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp)) {
                    Text(title, fontSize = 14.sp, color = C.Soft)
                    Text(sub, fontSize = 11.sp, color = C.Faint, lineHeight = 15.sp)
                }
            }, colors = DatePickerDefaults.colors(containerColor = C.Card, selectedDayContainerColor = C.Clay, todayDateBorderColor = C.Clay, todayContentColor = C.ClayDeep,
                selectedYearContainerColor = C.Clay))
            }
        }
    }
}

/** 学期与显示：开学日期、这周第几周（快捷）、总周数、显示周末、显示非本周。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermSheet(openPicker: Boolean = false, onChange: () -> Unit, onClose: () -> Unit) {
    val c = LocalContext.current
    var term by remember { mutableStateOf(Scenes.termStart(c)) }
    var week by remember { mutableStateOf(Scenes.weekNow(c)) }
    var total by remember { mutableStateOf(Scenes.totalWeeks(c)) }
    var off by remember { mutableStateOf(Scenes.showOff(c)) }
    var wk by remember { mutableStateOf(Scenes.showWeekend(c)) }
    var pick by remember { mutableStateOf(openPicker) }
    LaunchedEffect(openPicker) { if (openPicker) pick = true }
    fun reload() { term = Scenes.termStart(c); week = Scenes.weekNow(c); onChange() }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = C.Card) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 24.dp).navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Text(tr("学期与显示"), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = C.Ink)
            Row(Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Sand).clickable { pick = true }.padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("开学日期"), fontSize = 14.sp, color = C.Ink, fontWeight = FontWeight.SemiBold)
                    Text(if (term < 0) tr("没设置：所有课按每周都上") else tr("第 1 周从 {0} 开始", dateText(term)), fontSize = 12.sp, color = C.Faint)
                }
                Text(if (term < 0) tr("设置") else tr("修改"), fontSize = 13.sp, color = C.ClayDeep, fontWeight = FontWeight.SemiBold)
            }
            Row(Modifier.padding(top = 14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("这周是第几周"), fontSize = 14.sp, color = C.Ink)
                    Text(tr("快捷设置，会反推开学日期"), fontSize = 11.sp, color = C.Faint)
                }
                if (week == null) Text(tr("设置"), fontSize = 13.sp, color = C.ClayDeep, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { Scenes.setWeekNow(c, 1); reload() }.padding(6.dp))
                else Stepper(week!!, 1, 30) { Scenes.setWeekNow(c, it); reload() }
            }
            Row(Modifier.padding(top = 14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("这学期一共几周"), fontSize = 14.sp, color = C.Ink, modifier = Modifier.weight(1f))
                Stepper(total, 1, 30) { total = it; Scenes.setTotalWeeks(c, it); onChange() }
            }
            Toggle(tr("显示周末"), tr("周末有课时总会显示"), wk) { wk = it; Scenes.setShowWeekend(c, it); onChange() }
            Toggle(tr("显示非本周的课"), tr("淡色显示，标「非本周」"), off) { off = it; Scenes.setShowOff(c, it); onChange() }
            if (term >= 0) Text(tr("不分周次（每周都一样）"), fontSize = 13.sp, color = C.Soft,
                modifier = Modifier.padding(top = 14.dp).clip(RoundedCornerShape(10.dp)).clickable { Scenes.setTermStart(c, -1); reload() }.padding(6.dp))
        }
    }
    if (pick) TermDateDialog(tr("开学日期"), tr("选第 1 周里的任意一天，按那周的周一算"),
        if (term >= 0) term else Scenes.mondayOf(Scenes.todayEpoch()),
        onPick = { Scenes.setTermStart(c, Scenes.mondayOf(it)); pick = false; reload() }, onClose = { pick = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecTimesSheet(onChange: () -> Unit, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = C.Cream) {
        Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 20.dp).navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Text(tr("每节课时间"), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = C.Ink, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
            SecTimesCard(onChange)
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

// ---------------- 导入预览 ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportPreview(found: List<JwCourse>, onSave: (Boolean) -> Unit, onClose: () -> Unit) {
    val c = LocalContext.current
    var replace by remember { mutableStateOf(1) }
    val had = remember { Scenes.desired(c).count { it.kind == "course" } }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = C.Card) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 20.dp).navigationBarsPadding()) {
            Text(tr("识别到 {0} 节课", found.size), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = C.Ink)
            Text(tr("{0} 门课程。核对一下，导入后还能在课表里点开修改。", found.map { it.name }.distinct().size), fontSize = 12.sp, color = C.Faint, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                for (k in found.sortedWith(compareBy({ it.day }, { it.start }))) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp)).background(C.Sand.copy(alpha = 0.55f)).padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.width(52.dp)) {
                            Text(dayLabel(k.day), fontSize = 12.sp, color = C.Ink, fontWeight = FontWeight.SemiBold)
                            Text(tr("{0}-{1}节", k.start, k.end), fontSize = 11.sp, color = C.Faint)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(k.name, fontSize = 14.sp, color = C.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOf(Slot.weeksText(k.weeks), if (k.room.isNotBlank()) "@" + k.room else "", k.teacher).filter { it.isNotBlank() }.joinToString("  "),
                                fontSize = 11.sp, color = C.Soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (had > 0) {
                Spacer(Modifier.height(12.dp))
                Segments(listOf(tr("覆盖现有"), tr("追加")), if (replace == 1) 0 else 1) { replace = if (it == 0) 1 else 0 }
                Text(if (replace == 1) tr("换掉现在的 {0} 节课（场景不受影响）", had) else tr("保留现在的课，加在后面"), fontSize = 11.sp, color = C.Faint, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(12.dp))
            PrimaryButton(tr("导入 {0} 节", found.size), enabled = found.isNotEmpty()) { onSave(replace == 1 || had == 0) }
        }
    }
}
