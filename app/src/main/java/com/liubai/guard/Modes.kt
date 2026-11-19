package com.liubai.guard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// ---------------- 通用小组件 ----------------

@Composable
fun Segments(options: List<String>, sel: Int, onSel: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Sand).padding(4.dp)) {
        options.forEachIndexed { i, t ->
            val on = i == sel
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (on) C.Card else Color.Transparent)
                    .clickable { onSel(i) }.padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(t, fontSize = 13.sp, color = if (on) C.ClayDeep else C.Soft, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
fun SectionTitle(t: String, sub: String? = null) {
    Text(t, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = C.Ink, modifier = Modifier.padding(top = 22.dp))
    if (sub != null) Text(sub, fontSize = 12.sp, color = C.Faint, lineHeight = 18.sp, modifier = Modifier.padding(top = 3.dp, bottom = 8.dp))
    else Spacer(Modifier.height(8.dp))
}

@Composable
fun StepSlider(label: String, value: Int, range: IntRange, step: Int, unit: String, fmt: (Int) -> String = { "$it" }, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
        Text(label, fontSize = 13.sp, color = C.Soft, modifier = Modifier.weight(1f))
        Text(fmt(value), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = C.Clay)
        if (unit.isNotEmpty()) Text(" $unit", fontSize = 13.sp, color = C.Soft, modifier = Modifier.padding(bottom = 2.dp))
    }
    Slider(
        value = value.toFloat(),
        onValueChange = { onChange(((Math.round(it / step) * step).coerceIn(range.first, range.last))) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        colors = SliderDefaults.colors(thumbColor = C.Clay, activeTrackColor = C.Clay, inactiveTrackColor = C.Track)
    )
}

/** 自由时长：点数字直接输入任意分钟数（至少 1），两边的 −/+ 每次加减 1；不设上限。 */
@Composable
fun FreeMinutes(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, color = C.Ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text("−", fontSize = 22.sp, color = C.Clay, modifier = Modifier.clip(CircleShape).clickable { if (value > 1) onChange(value - 1) }.padding(horizontal = 14.dp, vertical = 4.dp))
        BasicTextField(
            value = text,
            onValueChange = { t ->
                val d = t.filter { it.isDigit() }.take(6)
                text = d
                d.toIntOrNull()?.let { if (it >= 1) onChange(it) }
            },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
            textStyle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = C.Clay, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
            modifier = Modifier.width(76.dp).clip(RoundedCornerShape(12.dp)).background(C.ClayPale).padding(vertical = 6.dp)
        )
        Text("+", fontSize = 22.sp, color = C.Clay, modifier = Modifier.clip(CircleShape).clickable { onChange(value + 1) }.padding(horizontal = 14.dp, vertical = 4.dp))
        Text(tr("分钟"), fontSize = 13.sp, color = C.Soft)
    }
}

@Composable
fun Note(t: String, warm: Boolean = false) {
    Text(
        t, fontSize = 13.sp, color = if (warm) C.ClayDeep else Color(0xFF5E7A66), lineHeight = 21.sp,
        modifier = Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (warm) C.ClayPale else Color(0xFFF4F8F2)).padding(horizontal = 14.dp, vertical = 12.dp)
    )
}

@Composable
fun Toggle(label: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, color = C.Ink, fontWeight = FontWeight.SemiBold)
            Text(sub, fontSize = 12.sp, color = C.Faint, lineHeight = 17.sp)
        }
        Switch(on, onChange, colors = SwitchDefaults.colors(checkedTrackColor = C.Clay, checkedThumbColor = Color.White, uncheckedTrackColor = C.Track, uncheckedBorderColor = C.Track))
    }
}

// ---------------- 守护方式（编辑面板里用） ----------------

/** 每日额度那块的全部模式。返回值通过 onChange 回传。 */
@Composable
fun ModeEditor(x: Extra, onChange: (Extra) -> Unit) {
    SectionTitle(tr("守护方式"), tr("都会同时受上面的每日额度约束，「完全不开」除外"))
    Segments(Extra.MODES, x.mode) { onChange(x.copy(mode = it)) }
    when (x.mode) {
        0 -> Note(tr("一天累计用满就休息，最简单。"))
        1 -> {
            StepSlider(tr("从"), x.from, 0..(23 * 60 + 30), 30, "", { Rules.clock(it) }) { onChange(x.copy(from = it, to = maxOf(x.to, it + 30))) }
            StepSlider(tr("到"), x.to, 30..(24 * 60), 30, "", { if (it == 1440) "24:00" else Rules.clock(it) }) { onChange(x.copy(to = it, from = minOf(x.from, it - 30))) }
            Note(tr("只在 {0}–{1} 开放，其他时间打开会直接回桌面。", Rules.clock(x.from), if (x.to == 1440) "24:00" else Rules.clock(x.to)))
        }
        2 -> {
            StepSlider(tr("每次最多"), x.session, 5..60, 5, tr("分钟")) { onChange(x.copy(session = it)) }
            StepSlider(tr("用满后歇"), x.cool, 5..120, 5, tr("分钟")) { onChange(x.copy(cool = it)) }
            Note(tr("连续刷 {0} 分钟就停，歇 {1} 分钟再开。中途离开超过 1 分钟算新的一次。", x.session, x.cool))
        }
        3 -> Note(tr("今天起完全不开放。想改回来要等明天 0:00。"), warm = true)
    }
    SectionTitle(tr("打开前停一下"), tr("打开前先跟着呼吸几秒，打断「手比脑子快」"))
    Segments(listOf(tr("不停"), tr("5 秒"), tr("15 秒"), tr("30 秒")), listOf(0, 5, 15, 30).indexOf(x.delay).coerceAtLeast(0)) {
        onChange(x.copy(delay = listOf(0, 5, 15, 30)[it]))
    }
    Toggle(tr("剩 5 分钟提醒"), tr("快用完时轻轻提醒一下，别被突然弹走"), x.warn) { onChange(x.copy(warn = it)) }
    var more by remember { mutableStateOf(x.grow || x.opens > 0 || x.ask || x.again > 0) }
    Text(
        if (more) tr("收起") else tr("更多方式"), fontSize = 13.sp, color = C.Soft,
        modifier = Modifier.padding(top = 12.dp).clip(RoundedCornerShape(10.dp)).clickable { more = !more }.padding(vertical = 6.dp)
    )
    if (more) {
        Toggle(tr("越开越慢"), tr("今天每打开一次，多停 5 秒，最多 60 秒"), x.grow) { onChange(x.copy(grow = it, delay = if (it && x.delay == 0) 5 else x.delay)) }
        SectionTitle(tr("每天最多打开几次"), tr("到了次数今天就不再开放"))
        Segments(listOf(tr("不限"), tr("3 次"), tr("5 次"), tr("10 次"), tr("20 次")), listOf(0, 3, 5, 10, 20).indexOf(x.opens).coerceAtLeast(0)) {
            onChange(x.copy(opens = listOf(0, 3, 5, 10, 20)[it]))
        }
        Toggle(tr("打开前问一句"), tr("「现在想做什么」，选完再进去"), x.ask) { onChange(x.copy(ask = it)) }
        SectionTitle(tr("刷久了再问一次"), tr("连续用满这么久，轻轻问一句还要不要继续"))
        Segments(listOf(tr("不问"), tr("5 分"), tr("10 分"), tr("20 分")), listOf(0, 5, 10, 20).indexOf(x.again).coerceAtLeast(0)) {
            onChange(x.copy(again = listOf(0, 5, 10, 20)[it]))
        }
    }
}

// ---------------- 分组额度 ----------------

@Composable
fun GroupsScreen() {
    val c = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<Group?>(null) }
    Every(5000) { tick++ }
    val active = remember(tick, editing) { Store.groupsActive(c) }
    val desired = remember(tick, editing) { Store.groupsDesired(c).associateBy { it.id } }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            Spacer(Modifier.height(8.dp))
            Text(tr("分组额度"), fontSize = 27.sp, fontWeight = FontWeight.Bold, color = C.Ink)
            Text(tr("几个应用共用一份时间，比如抖音、快手、小红书一天一共 1 小时。"), fontSize = 14.sp, color = C.Soft, lineHeight = 21.sp, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(16.dp))
            if (active.isEmpty()) {
                SoftCard(Modifier.fillMaxWidth()) {
                    Text(tr("还没有分组"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = C.Ink)
                    Text(tr("把同一类应用放一起，换着刷也逃不掉。"), fontSize = 13.sp, color = C.Soft, modifier = Modifier.padding(top = 6.dp))
                }
            }
            for (g in active) {
                val usedMin = remember(tick, g) { (g.pkgs.sumOf { Store.usedMs(c, it) } / 60_000L).toInt() }
                val d = desired[g.id]
                SoftCard(Modifier.fillMaxWidth().padding(bottom = 12.dp).clickable { editing = d ?: g }) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(g.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = C.Ink, modifier = Modifier.weight(1f))
                        Text(tr("{0} / {1} 分钟", usedMin.coerceAtMost(g.min), g.min), fontSize = 13.sp, color = C.Soft)
                    }
                    Row(Modifier.padding(top = 10.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (p in g.pkgs.take(7)) AppIcon(Apps.info(c, p), 30.dp)
                        if (g.pkgs.size > 7) Text("+${g.pkgs.size - 7}", fontSize = 12.sp, color = C.Faint, modifier = Modifier.align(Alignment.CenterVertically))
                    }
                    SoftBar(if (g.min <= 0) 1f else usedMin.toFloat() / g.min, usedMin >= g.min)
                    when {
                        d == null -> Chip(tr("明天起解散"), warm = true)
                        d.min != g.min -> Chip(tr("明天起 {0} 分钟", d.min), warm = true)
                    }
                }
            }
            Spacer(Modifier.height(110.dp))
        }
        Box(
            Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(22.dp)
                .clip(RoundedCornerShape(22.dp)).background(C.Clay)
                .clickable { editing = Group("g" + System.currentTimeMillis(), tr("短视频"), emptyList(), 60) }
                .padding(horizontal = 22.dp, vertical = 15.dp)
        ) { Text(tr("＋ 新建分组"), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
    }
    editing?.let { g -> GroupSheet(g) { editing = null; tick++ } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupSheet(start: Group, onClose: () -> Unit) {
    val c = LocalContext.current
    val isNew = Store.groupsDesired(c).none { it.id == start.id } && Store.groupsActive(c).none { it.id == start.id }
    var name by remember { mutableStateOf(start.name) }
    var min by remember { mutableStateOf(start.min) }
    val sel = remember { mutableStateListOf<String>().apply { addAll(start.pkgs) } }
    var apps by remember { mutableStateOf<List<AppInfo>?>(null) }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { Apps.launchable(c) } }
    val todayMin = remember { Store.groupsActive(c).firstOrNull { it.id == start.id }?.min }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onClose, sheetState = state, containerColor = C.Card) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
            Text(if (isNew) tr("新建分组") else tr("编辑分组"), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = C.Ink)
            Box(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Sand).padding(horizontal = 14.dp, vertical = 12.dp)) {
                if (name.isEmpty()) Text(tr("分组名字"), fontSize = 14.sp, color = C.Faint)
                BasicTextField(name, { name = it.take(12) }, singleLine = true, textStyle = TextStyle(fontSize = 14.sp, color = C.Ink), modifier = Modifier.fillMaxWidth())
            }
            StepSlider(tr("一天一共"), min, 5..240, 5, tr("分钟")) { min = it }
            Text(tr("选应用（已选 {0}）", sel.size), fontSize = 13.sp, color = C.Soft, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            val list = apps
            Box(Modifier.fillMaxWidth().height(260.dp)) {
                if (list == null) CircularProgressIndicator(color = C.Clay, modifier = Modifier.align(Alignment.Center))
                else LazyColumn {
                    items(list, key = { it.pkg }) { a ->
                        val on = a.pkg in sel
                        Row(
                            Modifier.fillMaxWidth().clickable { if (on) sel.remove(a.pkg) else sel.add(a.pkg) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppIcon(a, 34.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(a.label, fontSize = 15.sp, color = C.Ink, modifier = Modifier.weight(1f))
                            Box(
                                Modifier.size(22.dp).clip(CircleShape)
                                    .then(if (on) Modifier.background(C.Clay) else Modifier.border(2.dp, Color(0xFFDCCFC2), CircleShape)),
                                contentAlignment = Alignment.Center
                            ) { if (on) Text("✓", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
            val t = todayMin
            Note(
                when {
                    t == null -> tr("保存后立刻生效。")
                    min < t -> tr("调低马上生效，今天就按 {0} 分钟。", min)
                    min > t -> tr("调高要等明天 0:00，今天仍按 {0} 分钟。", t)
                    else -> tr("加应用马上生效；移出应用、解散要等明天 0:00。")
                }
            )
            Spacer(Modifier.height(14.dp))
            PrimaryButton(tr("保存"), enabled = sel.isNotEmpty() && name.isNotBlank()) {
                val d = Store.groupsDesired(c).filter { it.id != start.id } + Group(start.id, name.trim(), sel.toList(), min)
                Store.setGroupsDesired(c, d); Widgets.updateAll(c); onClose()
            }
            if (!isNew) Text(
                tr("解散这个分组（明天生效）"), color = C.Soft, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp).clip(RoundedCornerShape(12.dp)).clickable {
                    Store.setGroupsDesired(c, Store.groupsDesired(c).filter { it.id != start.id }); onClose()
                }.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
    }
}

// ---------------- 睡前锁定 ----------------

@Composable
fun BedScreen() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
        Spacer(Modifier.height(8.dp))
        Text(tr("睡前锁定"), fontSize = 27.sp, fontWeight = FontWeight.Bold, color = C.Ink)
        Spacer(Modifier.height(16.dp))
        BedCard()
    }
}

/** 睡前锁定的设置卡片，「时段」页里也用它。 */
@Composable
fun BedCard() {
    val c = LocalContext.current
    val today = remember { mutableStateOf(Store.bedActive(c)) }
    var want by remember { mutableStateOf(Store.bedDesired(c)) }
    var saved by remember { mutableStateOf<String?>(null) }
    // 开始 20:00–次日 2:00，结束 5:00–10:00
    val startVal = if (want.from < 12 * 60) want.from + 1440 else want.from

    Column {
        Text(tr("到点后，所有守护中的应用和分组都不开放，第二天早上自动恢复。"), fontSize = 13.sp, color = C.Soft, lineHeight = 20.sp, modifier = Modifier.padding(bottom = 12.dp))
        SoftCard(Modifier.fillMaxWidth()) {
            Toggle(tr("开启睡前锁定"), if (today.value.on) tr("今天在执行：{0}–{1}", Rules.clock(today.value.from), Rules.clock(today.value.to)) else tr("今天还没开启"), want.on) {
                want = want.copy(on = it); saved = null
            }
            if (want.on) {
                StepSlider(tr("几点开始"), startVal, 20 * 60..26 * 60, 15, "", { Rules.clock(it) }) { want = want.copy(from = it % 1440); saved = null }
                StepSlider(tr("几点结束"), want.to, 5 * 60..10 * 60, 15, "", { Rules.clock(it) }) { want = want.copy(to = it); saved = null }
            }
            Note(tr("今天只能更严：开启、提前开始、推迟结束立刻生效；关闭、缩短要等明天 0:00。"))
            Spacer(Modifier.height(14.dp))
            PrimaryButton(tr("保存")) {
                val eff = Store.setBed(c, want)
                today.value = eff
                saved = if (eff == want) tr("已生效") else tr("已保存，放宽的部分明天 0:00 生效")
                Widgets.updateAll(c)
            }
            saved?.let { Text(it, fontSize = 13.sp, color = C.SageInk, modifier = Modifier.padding(top = 10.dp).align(Alignment.CenterHorizontally)) }
        }
    }
}
