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

/** 外部要求打开某一页（比如专注中被挡回来时直接回到专注界面）。 */
object Nav {
    val open = androidx.compose.runtime.mutableStateOf<String?>(null)
    /** 页面里的第几个分页（「时段」的上课 / 睡前 / 场景），open 写成 "time:1" 这样。 */
    val subState = androidx.compose.runtime.mutableStateOf(0)
    var sub: Int get() = subState.value; set(v) { subState.value = v }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        Diag.init(this)
        super.onCreate(b)
        if (BuildConfig.DEBUG) TestHooks.run(this, intent)
        Tour.init(this)
        setContent { LiubaiTheme { Box(Modifier.fillMaxSize()) { App(); TourHost() } } }
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
        if (BuildConfig.DEBUG) TestHooks.run(this, i)
        i.getStringExtra("open")?.let { Nav.open.value = it }
    }

    override fun onResume() {
        GuardService.instance?.uncover()
        super.onResume()
        Keep.heal(this)
        Keep.schedule(this)
        // 卸载锁一旦开启就一直锁着，只有连点 10 次才解开
        Widgets.updateAll(this)
    }
}

@Composable
fun App() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val openExtra = remember { (ctx as? android.app.Activity)?.intent?.getStringExtra("open") }
    var screen by remember { mutableStateOf(if (!Unlock.guided(ctx)) "intro" else openExtra?.let { Nav.sub = it.substringAfter(':', "0").toIntOrNull() ?: 0; it.substringBefore(':') } ?: if (Focus.phase(ctx) == Focus.WORK) "focus" else "home") }
    var picked by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var poemsOn by remember { mutableStateOf(Poems.on(ctx)) }
    val req = Nav.open.value
    LaunchedEffect(req) {
        if (req != null) { if (Unlock.guided(ctx)) { Nav.sub = req.substringAfter(':', "0").toIntOrNull() ?: 0; screen = req.substringBefore(':'); drawer.close() }; Nav.open.value = null }
    }
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    if (screen != "setup" && screen != "picker" && screen != "intro" && Updater.enabled()) UpdatePrompt()
    // 新手引导 / 新功能指引：到了主界面再弹，不挡首次的权限引导和专注
    LaunchedEffect(screen) { if (screen !in setOf("setup", "picker", "intro", "focus")) Tour.check(ctx) }
    ConsentDialog()
    BackHandler(enabled = !drawer.isOpen && screen != "home") { screen = if (screen == "setup") "picker" else "home" }
    val go: (String) -> Unit = { screen = it; scope.launch { drawer.close() } }
    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = screen in setOf("home", "weekly", "about", "groups", "bed", "time", "guide", "widget", "settings", "look"),
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = C.Cream, drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp)) {
                Column(Modifier.fillMaxHeight().systemBarsPadding().padding(horizontal = 14.dp)) {
                    Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 22.dp, bottom = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                        Sprout(44.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(tr("留白"), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = C.Ink)
                            Text(tr("给生活留一点空白"), fontSize = 12.sp, color = C.Faint)
                        }
                        Box(
                            Modifier.size(42.dp).clip(CircleShape).background(if (screen == "settings") C.ClayPale else C.Card).clickable { go("settings") },
                            contentAlignment = Alignment.Center
                        ) {
                            Gear(if (screen == "settings") C.ClayDeep else C.Ink)
                        }
                    }
                    DrawerItem(Look.label("menu_home", tr("今天")), screen == "home") { go("home") }
                    DrawerItem(Look.label("menu_groups", tr("分组额度")), screen == "groups") { go("groups") }
                    DrawerItem(Look.label("menu_table", tr("课表")), screen == "table") { go("table") }
                    DrawerItem(Look.label("menu_time", tr("时段")), screen == "time") { go("time") }
                    DrawerItem(Look.label("menu_data", tr("数据")), screen == "weekly") { go("weekly") }
                    DrawerItem(Look.label("menu_widget", tr("小部件")), screen == "widget") { go("widget") }
                    DrawerItem(Look.label("menu_look", tr("外观")), screen == "look") { go("look") }
                    Spacer(Modifier.weight(1f))
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("版本 {0}", BuildConfig.VERSION_NAME), fontSize = 12.sp, color = C.Faint)
                        Text(if (!Updater.enabled()) tr("关于") else Look.label("menu_update", tr("检查更新")), fontSize = 12.sp, color = C.ClayDeep,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { go("about") }.padding(horizontal = 8.dp, vertical = 6.dp))
                        Spacer(Modifier.weight(1f))
                        Text(
                            Look.label("menu_guide", tr("使用说明")), fontSize = 13.sp,
                            color = if (screen == "guide") C.ClayDeep else C.Soft,
                            fontWeight = if (screen == "guide") FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { go("guide") }.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    ) {
        LookBackground {
            val menu = { scope.launch { drawer.open() }; Unit }
            when (screen) {
                "home" -> Home(onAdd = { screen = "picker" }, onMenu = menu, onFocus = { screen = "focus" }, poemsOn = poemsOn)
                "focus" -> Box(Modifier.fillMaxSize().systemBarsPadding()) { FocusScreen(onBack = { screen = "home" }) }
                "weekly" -> Paged(menu) { WeeklyScreen() }
                "groups" -> Paged(menu) { GroupsScreen() }
                "bed", "time" -> Paged(menu) { TimeScreen() }
                "table" -> Box(Modifier.fillMaxSize().systemBarsPadding()) { TimetableScreen(menu) }
                "about" -> Paged(menu) { AboutScreen() }
                "widget" -> Paged(menu) { Box(Modifier.padding(horizontal = 22.dp, vertical = 8.dp)) { WidgetScreen() } }
                "guide" -> Paged(menu) { GuideScreen() }
                "look" -> Paged(menu) { LookScreen() }
                "settings" -> Paged(menu) { SettingsScreen(poemsOn) { poemsOn = it; Poems.setOn(ctx, it) } }
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
    val s = Look.scale("menu")
    Box(
        Modifier.size((42 * s).dp).clip(CircleShape).background(Look.keyBg("menu")).clickable { onMenu() },
        contentAlignment = Alignment.Center
    ) {
        Column(verticalArrangement = Arrangement.spacedBy((4 * s).dp)) {
            repeat(3) { i -> Box(Modifier.width(((if (i == 1) 12 else 18) * s).dp).height((2 * s).dp).clip(RoundedCornerShape(1.dp)).background(Look.keyFg("menu"))) }
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
    Every(5000) { tick++ }
    var editing by remember { mutableStateOf<String?>(null) }
    val rows = remember(tick, editing) { Stats.rows(c) }
    val on = remember(tick) { serviceOn(c) }
    val usageOk = remember(tick) { Usage.granted(c) }
    val (used, total) = Stats.totals(rows)
    val left = (total - used).coerceAtLeast(0)
    val date = remember { SimpleDateFormat(tr("M月d日 · EEEE"), Tr.locale()).format(Date()) }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).systemBarsPadding().padding(horizontal = 22.dp)
        ) {
            val topKeys = listOf("menu", "focus", "add").filter { Look.pos(it).startsWith("t") }
            Spacer(Modifier.height(if (topKeys.isEmpty()) 12.dp else (12 + 42 * topKeys.maxOf { Look.scale(it) } + 14).dp))
            Text(date, fontSize = 13.sp, color = C.Faint, letterSpacing = 0.6.sp)
            GreetingArea(poemsOn)
            val bed = remember(tick) { Store.bedActive(c) }
            if (bed.on) Chip(tr("睡前锁定 {0}–{1}", Rules.clock(bed.from), Rules.clock(bed.to)))

            if (!usageOk) {
                Spacer(Modifier.height(18.dp))
                UsageGuide()
            }
            if (!on) {
                Spacer(Modifier.height(18.dp))
                SetupGuide()
            }


            Spacer(Modifier.height(18.dp))
            SoftCard(Modifier.fillMaxWidth(), brush = Brush.linearGradient(listOf(Color(0xFFFFF4EA), Color(0xFFFDEBDD)))) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Ring(if (total == 0) 0f else used.toFloat() / total) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (total == 0) "—" else "$left", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = C.Ink)
                            Text(tr("分钟可用"), fontSize = 12.sp, color = C.Faint)
                        }
                    }
                    Spacer(Modifier.width(18.dp))
                    Column {
                        if (total == 0) {
                            Text(tr("还没有守护的应用"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = C.Ink)
                            Text(tr("点右下角，给常刷的应用留点白"), fontSize = 13.sp, color = C.Soft, modifier = Modifier.padding(top = 6.dp), lineHeight = 20.sp)
                        } else {
                            Text(tr("今天已用 {0} 分钟", used), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = C.Ink)
                            Text(
                                tr("总额度 {0} 分钟\n", total) + when {
                                    left == 0 -> tr("今天辛苦啦，好好休息")
                                    used * 2 < total -> tr("节奏很好，继续保持")
                                    else -> tr("慢一点，留些时间给自己")
                                },
                                fontSize = 13.sp, color = C.Soft, lineHeight = 20.sp, modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                    }
                }
            }

            if (rows.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
                    Text(tr("守护中的应用"), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = C.Ink, modifier = Modifier.weight(1f))
                    Text(tr("0:00 自动重置"), fontSize = 12.sp, color = C.Faint)
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
                                    Text(tr("{0} / {1} 分钟", r.usedMin, r.limit), fontSize = 13.sp, color = C.Soft)
                                }
                                Spacer(Modifier.height(9.dp))
                                SoftBar(r.frac, r.done)
                                FlowRowLite {
                                    if (r.done) Chip(tr("今天已休息 · 明天 0:00 见"))
                                    else if (r.left <= 10) Chip(tr("只剩 {0} 分钟啦", r.left), warm = true)
                                    for (t in Rules.summary(Store.extraActive(c, r.pkg))) Chip(t)
                                    when (r.tomorrow) {
                                        null -> {}
                                        0 -> Chip(tr("明天起不再守护"), warm = true)
                                        else -> Chip(tr("明天起 {0} 分钟", r.tomorrow), warm = true)
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
                    tr("今天只能改得更严：调低立刻生效；调高、移除要等明天 0:00。"),
                    fontSize = 12.sp, color = C.Faint, lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 14.dp, start = 4.dp, end = 4.dp)
                )
            }
            Spacer(Modifier.height(110.dp))
        }

        // 三个按键可以放到四个角，同一个角的排成一行
        for ((corner, _) in Look.CORNERS) {
            val keys = listOf("menu", "focus", "add").filter { Look.pos(it) == corner }
            if (keys.isEmpty()) continue
            val al = when (corner) { "tl" -> Alignment.TopStart; "tr" -> Alignment.TopEnd; "bl" -> Alignment.BottomStart; else -> Alignment.BottomEnd }
            val top = corner.startsWith("t")
            Row(
                Modifier.align(al).systemBarsPadding().padding(horizontal = 22.dp, vertical = if (top) 12.dp else 22.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically
            ) {
                for (k in keys) when (k) {
                    "menu" -> MenuButton(onMenu)
                    "focus" -> FocusButton(onFocus)
                    else -> {
                        val s = Look.scale("add")
                        Box(
                            Modifier.clip(RoundedCornerShape((22 * s).dp)).background(Look.keyBg("add")).clickable { onAdd() }
                                .padding(horizontal = (22 * s).dp, vertical = (15 * s).dp)
                        ) { Text(Look.label("add", tr("＋ 添加应用")), color = Look.keyFg("add"), fontSize = (15 * s).sp, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
        }
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
        Text(tr("先让留白看见你的使用时长"), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = C.Ink)
        Text(
            tr("打开「使用情况访问权限」，留白就能读到今天每个应用真实用了多久，和系统自带的屏幕使用时间是同一份数据，装之前用掉的也会算上。"),
            fontSize = 13.sp, color = C.Soft, lineHeight = 20.sp, modifier = Modifier.padding(top = 6.dp)
        )
        Spacer(Modifier.height(12.dp))
        PrimaryButton(tr("去开启")) {
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
fun KeepTip(force: Boolean = false) {
    if (BuildConfig.PLAY) return
    val c = LocalContext.current
    var hide by remember { mutableStateOf(!force && c.getSharedPreferences("liubai_ui", Context.MODE_PRIVATE).getBoolean("keepTipHide", false)) }
    var done by remember { mutableStateOf(Keep.canHeal(c) || WAdb.adbMode(c)) }
    var st by remember { mutableStateOf(WAdb.status) }
    if (!done) Every(1000) { st = WAdb.status; done = Keep.canHeal(c) || WAdb.adbMode(c) }
    fun startPair() {
        WAdb.retry(c)   // 配对过就直接授权；没配对过会自动转去等配对码
        try { c.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (x: Exception) { c.startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    val notifAsk = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { startPair() }
    if (hide || done) return
    SoftCard(Modifier.fillMaxWidth()) {
        Text(tr("锁屏后留白总被关掉？"), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = C.Ink)
        if (!WAdb.supported()) {
            Text(tr("允许它自启动、电池设成不限制、在最近任务里给它上把锁，就能少被关掉。"),
                fontSize = 13.sp, color = C.Soft, lineHeight = 20.sp, modifier = Modifier.padding(top = 6.dp))
        } else {
            Text(
                tr("授权一次，以后被系统关掉它会自己重新打开。连着 Wi-Fi 就行，不用电脑，也不用装别的：\n") +
                    tr("1. 点「开始授权」，会跳到开发者选项\n") +
                    tr("2. 打开「无线调试」，点进去，再点「使用配对码配对设备」\n") +
                    tr("3. 出现 6 位配对码后，留白会自己读出来完成配对；没反应的话，下拉通知栏把数字填进去\n") +
                    tr("没有开发者选项的话，去「关于手机」连点版本号 7 次。"),
                fontSize = 13.sp, color = C.Soft, lineHeight = 21.sp, modifier = Modifier.padding(top = 6.dp)
            )
            when {
                st == "working" -> Text(tr("正在配对…"), fontSize = 13.sp, color = C.ClayDeep, modifier = Modifier.padding(top = 8.dp))
                st.startsWith("error:") -> Text(st.removePrefix("error:").take(160), fontSize = 13.sp, color = C.ClayDeep, lineHeight = 20.sp, modifier = Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(12.dp))
            PrimaryButton(if (st == "waiting") tr("去无线调试") else tr("开始授权")) {
                if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(c, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    notifAsk.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                else startPair()
            }
        }
        Row(Modifier.padding(top = 8.dp)) {
            Text(tr("打开应用信息"), color = C.ClayDeep, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                    c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.padding(vertical = 6.dp))
            Spacer(Modifier.weight(1f))
            if (!force) Text(tr("知道了"), color = C.Faint, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                    c.getSharedPreferences("liubai_ui", Context.MODE_PRIVATE).edit().putBoolean("keepTipHide", true).apply(); hide = true
                }.padding(vertical = 6.dp, horizontal = 8.dp))
        }
    }
}

@Composable
fun LockTip(force: Boolean = false, onResult: () -> Unit = {}) {
    if (BuildConfig.PLAY) return
    val c = LocalContext.current
    var hide by remember { mutableStateOf(!force && c.getSharedPreferences("liubai_ui", Context.MODE_PRIVATE).getBoolean("lockTipHide", false)) }
    // 系统规定：激活设备管理器的页面不能用「新任务」方式打开，否则会立刻自己关掉，所以这里用 ActivityResult 启动。
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
        Diag.log("卸载锁授权返回：${Admin.on(c)}"); onResult()
    }
    if (hide) return
    SoftCard(Modifier.fillMaxWidth()) {
        Text(tr("给留白加一把卸载锁"), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = C.Ink)
        Text(tr("开启后，长按图标或在应用信息里都不能直接卸载。真想卸载，在留白里连点 10 次「确定」，锁会自己解开；今天不再守护任何应用时也会自动解开。"),
            fontSize = 13.sp, color = C.Soft, lineHeight = 20.sp, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(12.dp))
        PrimaryButton(tr("开启卸载锁")) {
            try { ask.launch(Admin.askIntent(c)) } catch (x: Exception) {
                Diag.log("卸载锁打不开：${x.message}")
                try { c.startActivity(Intent().setClassName("com.android.settings", "com.android.settings.DeviceAdminSettings").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (y: Exception) {}
            }
        }
        if (!force) Row(Modifier.padding(top = 8.dp)) {
            Spacer(Modifier.weight(1f))
            Text(tr("以后再说"), color = C.Faint, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                    c.getSharedPreferences("liubai_ui", Context.MODE_PRIVATE).edit().putBoolean("lockTipHide", true).apply(); hide = true
                }.padding(vertical = 6.dp, horizontal = 8.dp))
        }
    }
}

@Composable
fun SetupGuide() {
    val c = LocalContext.current
    SoftCard(Modifier.fillMaxWidth()) {
        Text(tr("让留白陪着你"), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = C.Ink)
        Text(
            tr("打开「无障碍」，留白才知道你此刻在用什么。它只看应用名字，不看内容，也不会上传。"),
            fontSize = 13.sp, color = C.Soft, lineHeight = 20.sp, modifier = Modifier.padding(top = 6.dp)
        )
        Spacer(Modifier.height(12.dp))
        PrimaryButton(tr("去打开")) { Consent.openA11y(c) }
        Spacer(Modifier.height(14.dp))
        Text(tr("系统有时会悄悄把它关掉，顺手做这几件小事就稳了："), fontSize = 13.sp, color = C.Ink, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp)
        Text(
            tr("· 开关是灰的、点不动：进应用信息，右上角菜单里选「允许受限制的设置」\n") +
                tr("· 允许留白自启动、在后台运行\n") +
                tr("· 电池 / 省电里，把留白设成不限制\n") +
                tr("· 允许留白在后台弹出界面\n") +
                tr("· 在最近任务里给留白上把锁"),
            fontSize = 13.sp, color = C.Soft, lineHeight = 21.sp, modifier = Modifier.padding(top = 6.dp)
        )
        Text(
            tr("打开应用信息"),
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
                    Text(tr("今天已用 {0} 分钟 · 今天的额度 {1} 分钟", usedMin, today), fontSize = 13.sp, color = C.Soft)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
                Text("$n", fontSize = 56.sp, fontWeight = FontWeight.Bold, color = C.Clay)
                Text(tr(" 分钟 / 天"), fontSize = 18.sp, color = C.Soft, modifier = Modifier.padding(bottom = 12.dp))
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
                n > today && looser -> tr("调高时长、放宽守护方式要明天 0:00 才生效，更严的部分现在就生效。")
                n > today -> tr("调高的部分要明天 0:00 才生效，今天仍按 {0} 分钟。", today)
                looser -> tr("放宽守护方式要明天 0:00 才生效，今天先按原来的。")
                n < today -> tr("调低会马上生效，今天就按 {0} 分钟。", n)
                else -> tr("今天只能改得更严：更严的立刻生效，放宽、移除要等明天 0:00。")
            }
            Note(note, warm = looser || n > today)
            Spacer(Modifier.height(18.dp))
            PrimaryButton(tr("保存")) {
                val d = Store.desired(c).toMutableMap(); d[pkg] = n
                Store.setDesired(c, d); Store.setExtra(c, pkg, x); Widgets.updateAll(c); onClose()
            }
            Text(
                tr("不再守护它（明天生效）"), color = C.Soft, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
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
        Text(tr("5 分钟"), fontSize = 12.sp, color = C.Faint, modifier = Modifier.weight(1f))
        Text(tr("4 小时"), fontSize = 12.sp, color = C.Faint)
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
        Text(tr("‹ 返回"), fontSize = 15.sp, color = C.Soft, modifier = Modifier.padding(top = 14.dp).clip(RoundedCornerShape(10.dp)).clickable { onBack() }.padding(vertical = 4.dp))
        Text(tr("想给谁留点白？"), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = C.Ink, modifier = Modifier.padding(top = 10.dp))
        Text(tr("选好应用，再给它一个每天的时长"), fontSize = 14.sp, color = C.Soft, modifier = Modifier.padding(top = 4.dp))
        Box(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Sand).padding(horizontal = 16.dp, vertical = 13.dp)) {
            if (q.isEmpty()) Text(tr("搜索应用"), fontSize = 14.sp, color = C.Faint)
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
                            if (done) Text(tr("已在守护"), fontSize = 12.sp, color = C.SageInk)
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
        PrimaryButton(if (sel.isEmpty()) tr("选几个应用吧") else tr("下一步：设置时长（{0}）", sel.size), Modifier.padding(vertical = 16.dp), enabled = sel.isNotEmpty()) {
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
        Text(tr("‹ 返回"), fontSize = 15.sp, color = C.Soft, modifier = Modifier.padding(top = 14.dp).clip(RoundedCornerShape(10.dp)).clickable { onBack() }.padding(vertical = 4.dp))
        Text(tr("每天给它们多久？"), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = C.Ink, modifier = Modifier.padding(top = 10.dp))
        Text(tr("保存后立刻生效。之后点首页的应用，可以改成限定时段、单次限时、打开前停一下等方式。"), fontSize = 14.sp, color = C.Soft, lineHeight = 21.sp, modifier = Modifier.padding(top = 4.dp))
        LazyColumn(Modifier.weight(1f).padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(apps, key = { it.pkg }) { a ->
                SoftCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(a, 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(a.label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = C.Ink, modifier = Modifier.weight(1f))
                        Text("${mins[a.pkg]!!.toInt()}", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = C.Clay)
                        Text(tr(" 分钟"), fontSize = 14.sp, color = C.Soft)
                    }
                    MinuteSlider(mins[a.pkg]!!) { mins[a.pkg] = it }
                }
            }
        }
        PrimaryButton(tr("开始守护"), Modifier.padding(vertical = 16.dp)) {
            val d = Store.desired(c).toMutableMap()
            for (a in apps) d[a.pkg] = mins[a.pkg]!!.toInt()
            Store.setDesired(c, d)
            Widgets.updateAll(c)
            onDone()
        }
    }
}


/** 机械齿轮图标：8 个齿 + 中间的孔。 */
@Composable
fun Gear(color: Color, size: androidx.compose.ui.unit.Dp = 22.dp) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val cx = this.size.width / 2; val cy = this.size.height / 2
        val rOut = this.size.minDimension * 0.48f
        val rIn = this.size.minDimension * 0.36f
        val teeth = 8
        val path = androidx.compose.ui.graphics.Path()
        for (i in 0 until teeth * 4) {
            val a = Math.PI * 2 * i / (teeth * 4) - Math.PI / 2
            val r = if ((i % 4) == 1 || (i % 4) == 2) rOut else rIn
            val x = cx + (r * Math.cos(a)).toFloat(); val y = cy + (r * Math.sin(a)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, color)
        drawCircle(Color.White, radius = this.size.minDimension * 0.14f, center = androidx.compose.ui.geometry.Offset(cx, cy))
    }
}
