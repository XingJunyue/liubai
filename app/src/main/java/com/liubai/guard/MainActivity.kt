package com.liubai.guard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun serviceOn(c: Context): Boolean {
    if (GuardService.running) return true
    val s = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    return s.contains("${c.packageName}/") && s.contains("GuardService")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContent { LiubaiTheme { App() } }
    }

    override fun onResume() {
        super.onResume()
        Keep.heal(this)
        Keep.schedule(this)
        Widgets.updateAll(this)
    }
}

@Composable
fun App() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val openExtra = remember { (ctx as? android.app.Activity)?.intent?.getStringExtra("open") }
    var screen by remember { mutableStateOf(if (!Unlock.guided(ctx)) "intro" else openExtra ?: "home") }
    var picked by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var poemsOn by remember { mutableStateOf(Poems.on(ctx)) }
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    if (screen != "setup" && screen != "picker" && screen != "intro") UpdatePrompt()
    BackHandler(enabled = !drawer.isOpen && screen != "home") { screen = if (screen == "setup") "picker" else "home" }
    val go: (String) -> Unit = { screen = it; scope.launch { drawer.close() } }
    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = screen in setOf("home", "weekly", "about", "groups", "bed", "guide", "widget"),
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = C.Cream, drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp)) {
                Column(Modifier.fillMaxHeight().systemBarsPadding().padding(horizontal = 14.dp)) {
                    Row(Modifier.padding(start = 8.dp, top = 22.dp, bottom = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                        Sprout(44.dp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("留白", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = C.Ink)
                            Text("给生活留一点空白", fontSize = 12.sp, color = C.Faint)
                        }
                    }
                    DrawerItem("今天", screen == "home") { go("home") }
                    DrawerItem("分组额度", screen == "groups") { go("groups") }
                    DrawerItem("睡前锁定", screen == "bed") { go("bed") }
                    DrawerItem("每周小结", screen == "weekly") { go("weekly") }
                    DrawerItem("小部件", screen == "widget") { go("widget") }
                    Spacer(Modifier.weight(1f))
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("首页诗句", fontSize = 15.sp, color = C.Ink)
                            Text("轮换温暖的诗词，点一下换一句", fontSize = 12.sp, color = C.Faint)
                        }
                        Switch(poemsOn, { poemsOn = it; Poems.setOn(ctx, it) },
                            colors = SwitchDefaults.colors(checkedTrackColor = C.Clay, checkedThumbColor = androidx.compose.ui.graphics.Color.White, uncheckedTrackColor = C.Track, uncheckedBorderColor = C.Track))
                    }
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("版本 ${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = C.Faint)
                        Text("检查更新", fontSize = 12.sp, color = C.ClayDeep,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { go("about") }.padding(horizontal = 8.dp, vertical = 6.dp))
                        Spacer(Modifier.weight(1f))
                        Text(
                            "使用说明", fontSize = 13.sp,
                            color = if (screen == "guide") C.ClayDeep else C.Soft,
                            fontWeight = if (screen == "guide") FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { go("guide") }.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    ) {
        Surface(color = C.Cream, modifier = Modifier.fillMaxSize()) {
            val menu = { scope.launch { drawer.open() }; Unit }
            when (screen) {
                "home" -> Home(onAdd = { screen = "picker" }, onMenu = menu, onFocus = { screen = "focus" }, poemsOn = poemsOn)
                "focus" -> Box(Modifier.fillMaxSize().systemBarsPadding()) { FocusScreen(onBack = { screen = "home" }) }
                "weekly" -> Paged(menu) { WeeklyScreen() }
                "groups" -> Paged(menu) { GroupsScreen() }
                "bed" -> Paged(menu) { BedScreen() }
                "about" -> Paged(menu) { AboutScreen() }
                "widget" -> Paged(menu) { Box(Modifier.padding(horizontal = 22.dp, vertical = 8.dp)) { WidgetScreen() } }
                "guide" -> Paged(menu) { GuideScreen() }
                "intro" -> Column(Modifier.fillMaxSize().systemBarsPadding().padding(top = 16.dp)) {
                    GuideScreen { Unlock.setGuided(ctx); screen = "home" }
                }
                "picker" -> Picker(onBack = { screen = "home" }, onNext = { picked = it; screen = "setup" })
                "setup" -> Setup(picked, onBack = { screen = "picker" }, onDone = { screen = "home" })
            }
        }
    }
}

@Composable
fun MenuButton(onMenu: () -> Unit) {
    Box(
        Modifier.size(42.dp).clip(CircleShape).background(C.Card).clickable { onMenu() },
        contentAlignment = Alignment.Center
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(3) { i -> Box(Modifier.width(if (i == 1) 12.dp else 18.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(C.Ink)) }
        }
    }
}

@Composable
fun Paged(onMenu: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Box(Modifier.padding(start = 18.dp, top = 12.dp)) { MenuButton(onMenu) }
        content()
    }
}

@Composable
fun DrawerItem(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label, fontSize = 16.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        color = if (selected) C.ClayDeep else C.Ink,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(16.dp))
            .background(if (selected) C.ClayPale else Color.Transparent)
            .clickable { onClick() }.padding(horizontal = 16.dp, vertical = 14.dp)
    )
}

@Composable
fun Home(onAdd: () -> Unit, onMenu: () -> Unit, onFocus: () -> Unit = {}, poemsOn: Boolean = true) {
    val c = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2000); tick++ } }
    var editing by remember { mutableStateOf<String?>(null) }
    val rows = remember(tick, editing) { Stats.rows(c) }
    val on = remember(tick) { serviceOn(c) }
    val usageOk = remember(tick) { Usage.granted(c) }
    val (used, total) = Stats.totals(rows)
    val left = (total - used).coerceAtLeast(0)
    val date = remember { SimpleDateFormat("M月d日 · EEEE", Locale.CHINA).format(Date()) }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).systemBarsPadding().padding(horizontal = 22.dp)
        ) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                MenuButton(onMenu)
                Spacer(Modifier.weight(1f))
                FocusButton(onFocus)
            }
            Spacer(Modifier.height(14.dp))
            Text(date, fontSize = 13.sp, color = C.Faint, letterSpacing = 0.6.sp)
            GreetingArea(poemsOn)
            val bed = remember(tick) { Store.bedActive(c) }
            if (bed.on) Chip("睡前锁定 ${Rules.clock(bed.from)}–${Rules.clock(bed.to)}")

            if (!usageOk) {
                Spacer(Modifier.height(18.dp))
                UsageGuide()
            }
            if (!on) {
                Spacer(Modifier.height(18.dp))
                SetupGuide()
            }
            if (on && !Keep.canHeal(c)) {
                Spacer(Modifier.height(14.dp))
                KeepTip()
            }

            Spacer(Modifier.height(18.dp))
            SoftCard(Modifier.fillMaxWidth(), brush = Brush.linearGradient(listOf(Color(0xFFFFF4EA), Color(0xFFFDEBDD)))) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Ring(if (total == 0) 0f else used.toFloat() / total) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (total == 0) "—" else "$left", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = C.Ink)
                            Text("分钟可用", fontSize = 12.sp, color = C.Faint)
                        }
                    }
                    Spacer(Modifier.width(18.dp))
                    Column {
                        if (total == 0) {
                            Text("还没有守护的应用", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = C.Ink)
                            Text("点右下角，给常刷的应用留点白", fontSize = 13.sp, color = C.Soft, modifier = Modifier.padding(top = 6.dp), lineHeight = 20.sp)
                        } else {
                            Text("今天已用 $used 分钟", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = C.Ink)
                            Text(
                                "总额度 $total 分钟\n" + when {
                                    left == 0 -> "今天辛苦啦，好好休息"
                                    used * 2 < total -> "节奏很好，继续保持"
                                    else -> "慢一点，留些时间给自己"
                                },
                                fontSize = 13.sp, color = C.Soft, lineHeight = 20.sp, modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                    }
                }
            }

            if (rows.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
                    Text("守护中的应用", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = C.Ink, modifier = Modifier.weight(1f))
                    Text("0:00 自动重置", fontSize = 12.sp, color = C.Faint)
                }
                SoftCard(Modifier.fillMaxWidth()) {
                    rows.forEachIndexed { i, r ->
                        val app = remember(r.pkg) { Apps.info(c, r.pkg) }
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { editing = r.pkg }
                                .padding(vertical = 6.dp)
                        ) {
                            AppIcon(app)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(app.label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = C.Ink, modifier = Modifier.weight(1f), maxLines = 1)
                                    Text("${r.usedMin} / ${r.limit} 分钟", fontSize = 13.sp, color = C.Soft)
                                }
                                Spacer(Modifier.height(9.dp))
                                SoftBar(r.frac, r.done)
                                FlowRowLite {
                                    if (r.done) Chip("今天已休息 · 明天 0:00 见")
                                    else if (r.left <= 10) Chip("只剩 ${r.left} 分钟啦", warm = true)
                                    for (t in Rules.summary(Store.extraActive(c, r.pkg))) Chip(t)
                                    when (r.tomorrow) {
                                        null -> {}
                                        0 -> Chip("明天起不再守护", warm = true)
                                        else -> Chip("明天起 ${r.tomorrow} 分钟", warm = true)
                                    }
                                }
                            }
                        }
                        if (i != rows.lastIndex) Spacer(Modifier.height(8.dp))
                    }
                }
            }

            if (on && rows.isNotEmpty()) {
                Text(
                    "今天只能改得更严：调低立刻生效；调高、移除要等明天 0:00。",
                    fontSize = 12.sp, color = C.Faint, lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 14.dp, start = 4.dp, end = 4.dp)
                )
            }
            Spacer(Modifier.height(110.dp))
        }

        Box(
            Modifier.align(Alignment.BottomEnd).systemBarsPadding().padding(22.dp)
                .clip(RoundedCornerShape(22.dp)).background(C.Clay).clickable { onAdd() }
                .padding(horizontal = 22.dp, vertical = 15.dp)
        ) { Text("＋ 添加应用", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
    }

    editing?.let { pkg -> EditSheet(pkg) { editing = null; tick++ } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FlowRowLite(content: @Composable RowScope.() -> Unit) {
    FlowRow(Modifier.fillMaxWidth()) { content() }
}

@Composable
fun UsageGuide() {
    val c = LocalContext.current
    SoftCard(Modifier.fillMaxWidth()) {
        Text("先让留白看见你的使用时长", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = C.Ink)
        Text(
            "打开「使用情况访问权限」，留白就能读到今天每个应用真实用了多久，和系统自带的屏幕使用时间是同一份数据，装之前用掉的也会算上。",
            fontSize = 13.sp, color = C.Soft, lineHeight = 20.sp, modifier = Modifier.padding(top = 6.dp)
        )
        Spacer(Modifier.height(12.dp))
        PrimaryButton("去开启") {
            try {
                c.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (x: Exception) {
                c.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}

/** 一次性的 adb 授权：之后无障碍被系统关掉，留白会自己重新打开。 */
@Composable
fun KeepTip() {
    val c = LocalContext.current
    var hide by remember { mutableStateOf(c.getSharedPreferences("liubai_ui", Context.MODE_PRIVATE).getBoolean("keepTipHide", false)) }
    var done by remember { mutableStateOf(Keep.canHeal(c)) }
    var st by remember { mutableStateOf(WAdb.status) }
    LaunchedEffect(Unit) {
        while (!done) { st = WAdb.status; done = Keep.canHeal(c); delay(1000) }
    }
    if (hide || done) return
    SoftCard(Modifier.fillMaxWidth()) {
        Text("锁屏后留白总被关掉？", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = C.Ink)
        if (!WAdb.supported()) {
            Text("允许它自启动、电池设成不限制、在最近任务里给它上把锁，就能少被关掉。",
                fontSize = 13.sp, color = C.Soft, lineHeight = 20.sp, modifier = Modifier.padding(top = 6.dp))
        } else {
            Text(
                "授权一次，以后被系统关掉它会自己重新打开。连着 Wi-Fi 就行，不用电脑，也不用装别的：\n" +
                    "1. 点下面的按钮，通知栏会出现一个输入框\n" +
                    "2. 打开开发者选项里的「无线调试」，点「使用配对码配对设备」\n" +
                    "3. 弹窗别关，下拉通知栏，把弹窗上的 6 位数字填进去\n" +
                    "没有开发者选项的话，去「关于手机」连点版本号 7 次。",
                fontSize = 13.sp, color = C.Soft, lineHeight = 21.sp, modifier = Modifier.padding(top = 6.dp)
            )
            when {
                st == "working" -> Text("正在配对…", fontSize = 13.sp, color = C.ClayDeep, modifier = Modifier.padding(top = 8.dp))
                st.startsWith("error:") -> Text(st.removePrefix("error:"), fontSize = 13.sp, color = C.ClayDeep, lineHeight = 20.sp, modifier = Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(12.dp))
            PrimaryButton(if (st == "waiting") "已发通知，去无线调试" else "开始授权") {
                WAdb.begin(c)
                try { c.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                catch (x: Exception) { c.startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
        Row(Modifier.padding(top = 8.dp)) {
            Text("打开应用信息", color = C.ClayDeep, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                    c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.padding(vertical = 6.dp))
            Spacer(Modifier.weight(1f))
            Text("知道了", color = C.Faint, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                    c.getSharedPreferences("liubai_ui", Context.MODE_PRIVATE).edit().putBoolean("keepTipHide", true).apply(); hide = true
                }.padding(vertical = 6.dp, horizontal = 8.dp))
        }
    }
}

@Composable
fun SetupGuide() {
    val c = LocalContext.current
    SoftCard(Modifier.fillMaxWidth()) {
        Text("让留白陪着你", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = C.Ink)
        Text(
            "打开「无障碍」，留白才知道你此刻在用什么。它只看应用名字，不看内容，也不会上传。",
            fontSize = 13.sp, color = C.Soft, lineHeight = 20.sp, modifier = Modifier.padding(top = 6.dp)
        )
        Spacer(Modifier.height(12.dp))
        PrimaryButton("去打开") { c.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        Spacer(Modifier.height(14.dp))
        Text("系统有时会悄悄把它关掉，顺手做这几件小事就稳了：", fontSize = 13.sp, color = C.Ink, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp)
        Text(
            "· 开关是灰的、点不动：进应用信息，右上角菜单里选「允许受限制的设置」\n" +
                "· 允许留白自启动、在后台运行\n" +
                "· 电池 / 省电里，把留白设成不限制\n" +
                "· 允许留白在后台弹出界面\n" +
                "· 在最近任务里给留白上把锁",
            fontSize = 13.sp, color = C.Soft, lineHeight = 21.sp, modifier = Modifier.padding(top = 6.dp)
        )
        Text(
            "打开应用信息",
            color = C.ClayDeep, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(12.dp)).clickable {
                c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.padding(vertical = 6.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditSheet(pkg: String, onClose: () -> Unit) {
    val c = LocalContext.current
    val app = remember { Apps.info(c, pkg) }
    val today = remember { Store.active(c)[pkg] ?: 30 }
    val wanted = remember { Store.desired(c)[pkg] ?: today }
    var v by remember { mutableStateOf(wanted.toFloat()) }
    val usedMin = remember { (Store.usedMs(c, pkg) / 60_000L).toInt() }
    val xToday = remember { Store.extraActive(c, pkg) }
    var x by remember { mutableStateOf(Store.extraDesired(c, pkg)) }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val n = v.toInt()

    ModalBottomSheet(onDismissRequest = onClose, sheetState = state, containerColor = C.Card,
        dragHandle = { Box(Modifier.padding(top = 14.dp, bottom = 18.dp).size(40.dp, 5.dp).clip(CircleShape).background(Color(0xFFE5DACE))) }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 30.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(app)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(app.label, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = C.Ink)
                    Text("今天已用 $usedMin 分钟 · 今天的额度 $today 分钟", fontSize = 13.sp, color = C.Soft)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
                Text("$n", fontSize = 56.sp, fontWeight = FontWeight.Bold, color = C.Clay)
                Text(" 分钟 / 天", fontSize = 18.sp, color = C.Soft, modifier = Modifier.padding(bottom = 12.dp))
            }
            MinuteSlider(v) { v = it }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (q in listOf(15, 30, 45, 60, 90)) {
                    val sel = n == q
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (sel) Color(0xFFFBE3D7) else C.Sand)
                            .clickable { v = q.toFloat() }.padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("$q", fontSize = 14.sp, color = if (sel) C.ClayDeep else C.Ink, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal) }
                }
            }
            ModeEditor(x) { x = it }
            val looser = Rules.stricter(xToday, x) != x
            val note = when {
                n > today && looser -> "调高时长、放宽守护方式要明天 0:00 才生效，更严的部分现在就生效。"
                n > today -> "调高的部分要明天 0:00 才生效，今天仍按 $today 分钟。"
                looser -> "放宽守护方式要明天 0:00 才生效，今天先按原来的。"
                n < today -> "调低会马上生效，今天就按 $n 分钟。"
                else -> "今天只能改得更严：更严的立刻生效，放宽、移除要等明天 0:00。"
            }
            Note(note, warm = looser || n > today)
            Spacer(Modifier.height(18.dp))
            PrimaryButton("保存") {
                val d = Store.desired(c).toMutableMap(); d[pkg] = n
                Store.setDesired(c, d); Store.setExtra(c, pkg, x); Widgets.updateAll(c); onClose()
            }
            Text(
                "不再守护它（明天生效）", color = C.Soft, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp).clip(RoundedCornerShape(12.dp)).clickable {
                    val d = Store.desired(c).toMutableMap(); d.remove(pkg)
                    Store.setDesired(c, d); Store.setExtra(c, pkg, null); Widgets.updateAll(c); onClose()
                }.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
    }
}

@Composable
fun MinuteSlider(v: Float, onChange: (Float) -> Unit) {
    Slider(
        value = v, onValueChange = { onChange((Math.round(it / 5f) * 5f).coerceIn(5f, 240f)) },
        valueRange = 5f..240f,
        colors = SliderDefaults.colors(thumbColor = C.Clay, activeTrackColor = C.Clay, inactiveTrackColor = C.Track),
        modifier = Modifier.padding(top = 12.dp)
    )
    Row(Modifier.fillMaxWidth()) {
        Text("5 分钟", fontSize = 12.sp, color = C.Faint, modifier = Modifier.weight(1f))
        Text("4 小时", fontSize = 12.sp, color = C.Faint)
    }
}

@Composable
fun Picker(onBack: () -> Unit, onNext: (List<AppInfo>) -> Unit) {
    val c = LocalContext.current
    var apps by remember { mutableStateOf<List<AppInfo>?>(null) }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { Apps.launchable(c) } }
    val guarded = remember { Store.active(c).keys }
    var q by remember { mutableStateOf("") }
    val sel = remember { mutableStateListOf<String>() }

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 22.dp)) {
        Text("‹ 返回", fontSize = 15.sp, color = C.Soft, modifier = Modifier.padding(top = 14.dp).clip(RoundedCornerShape(10.dp)).clickable { onBack() }.padding(vertical = 4.dp))
        Text("想给谁留点白？", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = C.Ink, modifier = Modifier.padding(top = 10.dp))
        Text("选好应用，再给它一个每天的时长", fontSize = 14.sp, color = C.Soft, modifier = Modifier.padding(top = 4.dp))
        Box(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Sand).padding(horizontal = 16.dp, vertical = 13.dp)) {
            if (q.isEmpty()) Text("搜索应用", fontSize = 14.sp, color = C.Faint)
            BasicTextField(q, { q = it }, singleLine = true, textStyle = TextStyle(fontSize = 14.sp, color = C.Ink), modifier = Modifier.fillMaxWidth())
        }
        val list = apps
        if (list == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.Clay) }
        } else {
            val shown = list.filter { q.isBlank() || it.label.contains(q.trim(), ignoreCase = true) }
            LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
                items(shown, key = { it.pkg }) { a ->
                    val done = a.pkg in guarded
                    val on = a.pkg in sel
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !done) { if (on) sel.remove(a.pkg) else sel.add(a.pkg) }.padding(vertical = 12.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppIcon(a)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.label, fontSize = 16.sp, color = if (done) C.Faint else C.Ink)
                            if (done) Text("已在守护", fontSize = 12.sp, color = C.SageInk)
                        }
                        Box(
                            Modifier.size(24.dp).clip(CircleShape)
                                .then(if (on || done) Modifier.background(if (done) C.Sage else C.Clay) else Modifier.border(2.dp, Color(0xFFDCCFC2), CircleShape)),
                            contentAlignment = Alignment.Center
                        ) { if (on || done) Text("✓", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                    }
                    HorizontalDivider(color = C.Track)
                }
            }
        }
        PrimaryButton(if (sel.isEmpty()) "选几个应用吧" else "下一步：设置时长（${sel.size}）", Modifier.padding(vertical = 16.dp), enabled = sel.isNotEmpty()) {
            val all = apps ?: return@PrimaryButton
            onNext(all.filter { it.pkg in sel })
        }
    }
}

@Composable
fun Setup(apps: List<AppInfo>, onBack: () -> Unit, onDone: () -> Unit) {
    val c = LocalContext.current
    val mins = remember { mutableStateMapOf<String, Float>().apply { apps.forEach { put(it.pkg, 30f) } } }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 22.dp)) {
        Text("‹ 返回", fontSize = 15.sp, color = C.Soft, modifier = Modifier.padding(top = 14.dp).clip(RoundedCornerShape(10.dp)).clickable { onBack() }.padding(vertical = 4.dp))
        Text("每天给它们多久？", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = C.Ink, modifier = Modifier.padding(top = 10.dp))
        Text("保存后立刻生效。之后点首页的应用，可以改成限定时段、单次限时、打开前停一下等方式。", fontSize = 14.sp, color = C.Soft, lineHeight = 21.sp, modifier = Modifier.padding(top = 4.dp))
        LazyColumn(Modifier.weight(1f).padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(apps, key = { it.pkg }) { a ->
                SoftCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(a, 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(a.label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = C.Ink, modifier = Modifier.weight(1f))
                        Text("${mins[a.pkg]!!.toInt()}", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = C.Clay)
                        Text(" 分钟", fontSize = 14.sp, color = C.Soft)
                    }
                    MinuteSlider(mins[a.pkg]!!) { mins[a.pkg] = it }
                }
            }
        }
        PrimaryButton("开始守护", Modifier.padding(vertical = 16.dp)) {
            val d = Store.desired(c).toMutableMap()
            for (a in apps) d[a.pkg] = mins[a.pkg]!!.toInt()
            Store.setDesired(c, d)
            Widgets.updateAll(c)
            onDone()
        }
    }
}
