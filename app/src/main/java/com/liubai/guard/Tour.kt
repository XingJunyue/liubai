package com.liubai.guard

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 教程的一页：标题、说明，open = 「去看看」打开哪一页（同 Nav.open 的写法，比如 "table:1"）。 */
data class TourPage(val mark: String, val title: String, val body: String, val open: String? = null)

/**
 * 应用内教程：
 *  - 首次安装：完整的新手引导（FIRST），可跳过。
 *  - 每次更新：这个版本的新功能（WHATS_NEW），可跳过。记下上次看过的 versionCode 和看过的条目 id。
 *  - 设置里「新手教程」随时重看。
 *
 * 以后每个版本：在 WHATS_NEW 最前面加一条 Release（id 起一个新的、以后不要改），文案六种语言都补上。
 * 规则见仓库根目录 AGENTS.md。
 */
object Tour {
    class Release(val id: String, val pages: List<TourPage>)

    val FIRST = listOf(
        TourPage("留", "欢迎来到留白", "给生活留一点空白。花半分钟看看几个主要功能，随时可以跳过，之后在设置里能重看。"),
        TourPage("专", "专注 · 转盘", "在「今天」点专注，绕着圆环拖动就能设分钟数（一圈 60 分钟，最多 240）。专注时只放行你允许的应用。", "focus"),
        TourPage("管", "管控", "在「分组额度」给应用设每天能用多久、能打开几次，用完了留白会拦住；「时段」里还能设上课和睡前。", "groups"),
        TourPage("课", "课表与教务导入", "「课表」里点导入 →「从教务导入」，输入学校名就能填好教务网址和类型，登录后点「导入课表」。", "table:1"),
        TourPage("期", "开学日期", "导入课表后，在课表的学期设置里确认开学日期——第几周、本周有没有这门课都靠它。", "table:5"),
        TourPage("数", "周报与时长统计", "「数据」页能看每天、每周在各个应用上花了多久，周报会指出最占时间的应用。", "weekly"),
    )

    /** 新版本在最前面加。id 一旦发出去就不要改（用来记「看过没有」）。 */
    val WHATS_NEW = listOf(
        Release("sleep-custom", listOf(
            TourPage("眠", "睡眠守护可以自定义", "「时段 → 睡前」里除了睡前锁定，还能加午睡等别的时段：每段自己起名字、设开始结束时间和星期几，到点守护中的应用都不开放。"),
        )),
        Release("wl-sync", listOf(
            TourPage("白", "白名单两边一致", "重要课和无敌模式用的是同一份白名单：白名单里的应用，即使也在守护中，上重要课、开无敌模式专注时都能打开。每日额度和睡前锁定照旧。", "focus"),
        )),
        Release("course-widget", listOf(
            TourPage("课", "课表小部件", "桌面长按添加「留白 · 课表」，有 4×2 和 2×2 两种：上面是正在上的课（课间就是最近的下一节），课名、时间、节次、教室、老师、等级和剩余时间都在；下面是再下一节。今天没课就是一张白卡。"),
            TourPage("级", "水课、普通也标出来", "课表的课块和小部件上，重要、普通、水课都会用不同颜色标出来。不想要的话，在课表「更多 → 学期与显示」里分别关掉。", "table:1"),
        )),
        Release("picker-pass", listOf(
            TourPage("图", "上传图片不再被挡", "专注和无敌模式里，在白名单应用中上传图片、拍照、选文件、分享时跳出的系统选图器、相机、相册、文件管理都会跟着放行；回到桌面后照常拦。"),
        )),
        Release("split-window", listOf(
            TourPage("盖", "小窗、分屏也挡得住", "到点的应用开成小窗或分屏，也会被一块板盖住，点不到、看不到，声音也会停。关掉那个小窗就好。"),
        )),
        Release("intl-import", listOf(
            TourPage("约", "按日期预约番茄", "预约番茄除了每周几，还可以选「按日期」，挑具体哪几天到点自动开始专注。", "focus"),
            TourPage("搜", "选应用都能搜", "添加守护、分组、白名单、小部件这些选应用的地方，顶上都有搜索框，输应用名或包名就行。"),
            TourPage("链", "国外学校的课表导入", "「课表」→ 导入 →「日历订阅链接」：贴上 Canvas、Moodle 或学校课表系统的 iCal 链接，自动认出每门课的星期、时间、教室和周次。Workday 导出的 Excel、英文课表截图也能认。", "table:1"),
        )),
        Release("hard-block", listOf(
            TourPage("挡", "到点了就打不开", "时间用完后，在桌面点它会直接挡住，不再先闪出画面；挡完马上再点也一样，后台也会被清掉，不会接着刚才的视频播。"),
            TourPage("新", "必须用最新版", "从这个版本起，有新版本时要先更新才能打开留白，不更新只能退出。没网查不到时照常用，守护也一直在后台工作。"),
        )),
        Release("sys-pass", listOf(
            TourPage("系", "系统服务不拦", "专注、无敌模式和所有上课时段（重要、普通、水课）里，系统设置、谷歌服务（GMS）、华为 HMS 这类系统服务都直接放行，水课也不再先停一下。防卸载的保护照旧。", "focus"),
            TourPage("搜", "白名单可以搜索", "无敌模式的白名单顶上加了搜索框，输入应用名或包名就能找到。系统设置和系统服务已经默认放行，不用再勾。", "focus"),
        )),
        Release("eams-direct", listOf(
            TourPage("导", "树维教务直接导入", "用树维 eams 的学校（如电子科技大学），登录后不用打开课表页面，直接点「导入课表」就会自己去取课表。", "table:1"),
            TourPage("稳", "教务导入更可靠", "各类教务登录后直接点「导入课表」就会自己去取课表，不用先找课表页面。卡住会提示卡在哪一步，可以重试，也能复制诊断信息发给开发者。", "table:1"),
            TourPage("认", "登录页更兼容", "学校统一认证的二次认证、弹窗和新窗口在内置浏览器里也能用了。页面还是显示不出来时，点网页上方的「电脑版」再试。"),
            TourPage("搜", "不再默认东北大学", "从教务导入打开时是空的：输入学校名搜索，或手动选教务类型。选过一次后，下次会放在「上次用的」。", "table:1"),
        )),
        Release("jw-adapters", listOf(
            TourPage("搜", "新功能：按学校名找教务", "从教务导入时输入学校名，收录了一千六百多所学校的教务网址和系统类型，点一下就填好。", "table:1"),
            TourPage("新", "教务导入更稳了", "正方、强智、新旧 URP、青果、超星、南软的课表识别都加强了。读不出来时，点网页上方的类型名换一种解析方式。"),
        )),
    )

    /** 正在显示哪一套："first" / "new" / null。 */
    val show = mutableStateOf<String?>(null)

    private fun p(c: Context) = c.getSharedPreferences("tour", Context.MODE_PRIVATE)

    // ---------------- 纯逻辑（单测） ----------------

    /** 第一次跑到有教程的版本：没走过引导的是新装（要看完整引导），走过的是老用户升级（只看新功能）。 */
    fun isFreshInstall(hasTourState: Boolean, guided: Boolean) = !hasTourState && !guided

    /** 升级后要看的新功能：versionCode 比上次看过的大，且还有没看过的条目。 */
    fun pending(lastCode: Int, cur: Int, seen: Set<String>, all: List<Release> = WHATS_NEW): List<Release> =
        if (cur > lastCode) all.filter { it.id !in seen } else emptyList()

    // ---------------- 存储 ----------------

    /** 在 MainActivity.onCreate 里调用一次：第一次装好时记下「新装」，并把已有的新功能都当看过。 */
    fun init(c: Context) {
        val pr = p(c)
        if (pr.contains("code")) return
        val fresh = isFreshInstall(false, Unlock.guided(c))
        pr.edit().putInt("code", if (fresh) BuildConfig.VERSION_CODE else 0)
            .putBoolean("firstPending", fresh)
            .putStringSet("seen", if (fresh) WHATS_NEW.map { it.id }.toSet() else emptySet()).apply()
    }

    /** 冒烟测试 / 示例数据：全部当看过，不弹。 */
    fun skipAll(c: Context) {
        p(c).edit().putInt("code", BuildConfig.VERSION_CODE).putBoolean("firstPending", false)
            .putStringSet("seen", WHATS_NEW.map { it.id }.toSet()).apply()
    }

    /** 该弹什么就弹什么（在主界面出现时调用）。 */
    fun check(c: Context) {
        if (show.value != null) return
        val pr = p(c)
        if (!pr.contains("code")) init(c)
        if (pr.getBoolean("firstPending", false)) { show.value = "first"; return }
        val seen = pr.getStringSet("seen", emptySet()) ?: emptySet()
        if (pending(pr.getInt("code", 0), BuildConfig.VERSION_CODE, seen).isNotEmpty()) show.value = "new"
        else if (pr.getInt("code", 0) < BuildConfig.VERSION_CODE) pr.edit().putInt("code", BuildConfig.VERSION_CODE).apply()
    }

    fun pages(c: Context, which: String): List<TourPage> = when (which) {
        "first" -> FIRST
        "new" -> {
            val seen = p(c).getStringSet("seen", emptySet()) ?: emptySet()
            WHATS_NEW.filter { it.id !in seen }.flatMap { it.pages }.ifEmpty { WHATS_NEW.firstOrNull()?.pages ?: emptyList() }
        }
        else -> emptyList()
    }

    /** 看完或跳过：都算看过。 */
    fun done(c: Context, which: String) {
        val e = p(c).edit().putInt("code", BuildConfig.VERSION_CODE)
        if (which == "first") e.putBoolean("firstPending", false)
        e.putStringSet("seen", WHATS_NEW.map { it.id }.toSet()).apply()
        show.value = null
    }
}

/** 盖在主界面上的教程卡片。 */
@Composable
fun TourHost() {
    val which = Tour.show.value ?: return
    val c = LocalContext.current
    val pages = remember(which) { Tour.pages(c, which) }
    if (pages.isEmpty()) { LaunchedEffect(which) { Tour.done(c, which) }; return }
    var i by remember(which) { mutableStateOf(0) }
    val pg = pages[i.coerceIn(0, pages.size - 1)]
    BackHandler { if (i > 0) i-- else Tour.done(c, which) }
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(enabled = true, onClick = {}), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(24.dp).widthIn(max = 420.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(C.Cream).padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (which == "new") tr("这个版本的新功能") else tr("新手教程"), fontSize = 12.sp, color = C.Faint, modifier = Modifier.weight(1f))
                Text(tr("跳过"), fontSize = 13.sp, color = C.Soft, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { Tour.done(c, which) }.padding(horizontal = 10.dp, vertical = 6.dp))
            }
            Box(Modifier.padding(top = 14.dp).size(56.dp).clip(CircleShape).background(C.ClayPale), contentAlignment = Alignment.Center) {
                Text(if (Tr.cjk) pg.mark else "${i + 1}", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = C.ClayDeep)
            }
            Text(tr(pg.title), fontSize = 21.sp, fontWeight = FontWeight.Bold, color = C.Ink, modifier = Modifier.padding(top = 14.dp))
            Text(tr(pg.body), fontSize = 14.sp, color = C.Soft, lineHeight = 21.sp, modifier = Modifier.padding(top = 8.dp))
            pg.open?.let { o ->
                Text(tr("去看看 ›"), fontSize = 13.sp, color = C.ClayDeep, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(10.dp)).clickable { Tour.done(c, which); Nav.open.value = o }.padding(vertical = 6.dp, horizontal = 2.dp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    pages.indices.forEach { k -> Box(Modifier.size(if (k == i) 18.dp else 7.dp, 7.dp).clip(CircleShape).background(if (k == i) C.Clay else C.Track)) }
                }
                if (i > 0) Text(tr("上一步"), fontSize = 14.sp, color = C.Soft, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { i-- }.padding(horizontal = 12.dp, vertical = 10.dp))
                val last = i == pages.size - 1
                Text(if (last) tr("开始使用") else tr("下一步"), fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(C.Clay).clickable { if (last) Tour.done(c, which) else i++ }.padding(horizontal = 18.dp, vertical = 10.dp))
            }
        }
    }
}
