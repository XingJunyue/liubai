package com.liubai.guard

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 预览版关更新器；新手教程 / 新功能指引的判断；新文案六种语言都有。 */
class TourAndUpdaterTest {

    @Test fun updaterOffForPreviewAndPlay() {
        assertTrue(Updater.enabled(play = false, preview = false))
        assertFalse(Updater.enabled(play = false, preview = true))
        assertFalse(Updater.enabled(play = true, preview = false))
        // 这次构建本身：CI 的预览构建带 -Ppreview，此时更新检查直接返回 null，不发任何网络请求
        assertEquals(!BuildConfig.PLAY && !BuildConfig.PREVIEW, Updater.enabled())
        if (BuildConfig.PREVIEW) {
            assertNull(Updater.latest())
            assertEquals("preview", Updater.lastError)
        }
    }

    @Test fun firstRunVsUpgrade() {
        assertTrue(Tour.isFreshInstall(hasTourState = false, guided = false))
        assertFalse(Tour.isFreshInstall(hasTourState = false, guided = true))   // 老用户升级上来
        assertFalse(Tour.isFreshInstall(hasTourState = true, guided = false))
    }

    @Test fun whatsNewByVersion() {
        val rel = listOf(Tour.Release("b", emptyList()), Tour.Release("a", emptyList()))
        assertEquals(listOf("b", "a"), Tour.pending(10, 12, emptySet(), rel).map { it.id })
        assertEquals(listOf("b"), Tour.pending(10, 12, setOf("a"), rel).map { it.id })
        assertTrue(Tour.pending(12, 12, emptySet(), rel).isEmpty())   // 版本没变不弹
        assertTrue(Tour.pending(10, 12, setOf("a", "b"), rel).isEmpty())
        // 内容列表：id 不重复，每条至少一页
        assertEquals(Tour.WHATS_NEW.size, Tour.WHATS_NEW.map { it.id }.toSet().size)
        assertTrue(Tour.WHATS_NEW.all { it.pages.isNotEmpty() })
        assertTrue(Tour.FIRST.isNotEmpty())
    }

    private fun i18n(lang: String): JSONObject =
        listOf("src/main/assets/i18n/$lang.json", "app/src/main/assets/i18n/$lang.json").map { File(it) }.first { it.exists() }.readText().let { JSONObject(it) }

    @Test fun newStringsTranslated() {
        val keys = (Tour.FIRST + Tour.WHATS_NEW.flatMap { it.pages }).flatMap { listOf(it.title, it.body) } + listOf(
            "预览版，不检查更新", "{0} 已到点，这一块先盖住了", "小窗、分屏也挡得住", "到点的应用开成小窗或分屏，也会被一块板盖住，点不到、看不到，声音也会停。关掉那个小窗就好。", "Canvas、Moodle、学校课表系统的 iCal 链接", "Workday 等选课系统导出的课程清单，或自己整理的表格", "{0}月{1}日", "只读到 {0} 个截止日期，没有上下课时间的课。换成学校课表系统的订阅链接试试。", "学习平台的链接里多是作业截止，留白只导入有上下课时间的课，截止日期会跳过。", "学校的课表或学习平台（Canvas、Moodle 等）里复制「日历订阅 / iCal」链接，贴在这里。只读取，不会改你的日历。", "已按日历把第 1 周设为 {0} 那周", "按日期", "日历订阅链接", "粘贴", "认出来了：{0}", "读取失败：{0}", "跳过了 {0} 个截止日期", "这一天到点自动开始专注", "这个链接打开的不是日历（多半是登录页）。要复制「订阅 / iCal」那个链接，不是网页地址。", "连不上：{0}", "选择日期", "链接在哪找？", "链接打不开（{0}），可能已经失效。到学校网站重新复制一次订阅链接。", "＋ 日期", "预约番茄除了每周几，还可以选「按日期」，挑具体哪几天到点自动开始专注。", "选应用都能搜", "添加守护、分组、白名单、小部件这些选应用的地方，顶上都有搜索框，输应用名或包名就行。", "国外学校的课表导入", "「课表」→ 导入 →「日历订阅链接」：贴上 Canvas、Moodle 或学校课表系统的 iCal 链接，自动认出每门课的星期、时间、教室和周次。Workday 导出的 Excel、英文课表截图也能认。", "退出", "要更新到最新版才能继续用。守护设置更新后都会保留，不更新也照常在后台守护。", "声音素材", "无敌模式下只有这些能打开。电话、短信、输入法、系统设置和系统服务已默认放行。", "雨声、海浪、篝火等是真实录音，白、粉、棕噪音为实时合成。默认只在专注时播放。", "在专注页打开，并选好白名单。专注期间除了白名单和电话、短信、输入法、系统设置和系统服务，其他应用都打不开；这个番茄也不能中途暂停、重置或跳过。", "新手教程", "这个版本的新功能", "跳过", "去看看 ›", "上一步", "下一步", "开始使用", "打开 ›",
            "再看一遍选应用、专注、睡眠守护这些怎么用", "看看这次更新加了什么",
            "好梦模式", "这段时间里，所有应用都不开放，只留电话、短信这些必需的", "好梦里收起通知", "白名单、电话和闹钟照常",
            "好梦时间", "好梦守护到 {0}，{1} 明早再见",
            "今天只能更严：开启、新加一段、提前开始、推迟结束、多选星期、打开好梦模式立刻生效；关闭、缩短、删除、关掉好梦模式要等明天 0:00。",
            "输入学校名搜索，已收录 {0} 所学校的教务入口", "搜到的学校",
            "学校列表来自开源项目，网址和类型不一定准；进去后可以改网址、点上方类型名换解析方式。",
            "有人适配过", "换教务类型", "导入不出来时换一个试试，网页和登录状态不变",
            "课程名、星期、节次、老师、地点、周数", "课表 App 或教务导出的日历文件", "其他课表 App 可以先同步到系统日历", "上次用的", "电脑版", "电脑版 ✓",
            "登录后直接点导入，不用打开课表页面", "重试", "复制诊断信息", "已复制，可以发给开发者", "页面还在加载，加载完自动导入…", "正在{0}…",
            "读取页面", "读取学期", "读取校区", "读取课表", "解析课表",
            "登录未完成：请先在网页里登录（二次认证也要做完），再点导入。", "没找到课表：教务系统没有返回课表数据。可以在网页里打开「我的课表」再点导入，或者换一个教务类型。",
            "解析为空：拿到了数据，但没认出课程。可以换一个教务类型试试，或者复制诊断信息发给开发者。", "请求失败（{0}）：教务系统没有响应。检查网络（有的学校要校园网或学校的 VPN），再点重试。",
            "超时：卡在「{0}」这一步。点重试；还不行就复制诊断信息。", "找不到教务系统的地址。用 WebVPN 时，请先在网页里打开「我的课表」页面再点导入。", "导入失败：{0}",
            "比如 jwxt.xxx.edu.cn。登录后直接点下面的「导入课表」；读不到时再打开「我的课表」页面试试。") + Jw.KINDS.flatMap { listOf(it.name, it.note) }.filter { it.isNotEmpty() }
        for (lang in listOf("en", "fr", "ja", "ko", "de", "es")) {
            val j = i18n(lang)
            val missing = keys.filter { !j.has(it) || j.optString(it).isBlank() }
            assertTrue("$lang missing: $missing", missing.isEmpty())
        }
    }

    /** 界面上不再出现 WakeUp 字样（六种语言的键和译文、源码里的 tr 文案都查）。 */
    @Test fun noWakeUpWording() {
        for (lang in listOf("en", "fr", "ja", "ko", "de", "es")) {
            val j = i18n(lang)
            val bad = j.keys().asSequence().filter { k -> (k + j.optString(k)).contains("wakeup", ignoreCase = true) }.toList()
            assertTrue("$lang: $bad", bad.isEmpty())
        }
        val src = listOf("src/main/java", "app/src/main/java").map { File(it) }.first { it.exists() }
        val hits = src.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .flatMap { f -> Regex("tr\\(\"([^\"]*)\"").findAll(f.readText()).map { it.groupValues[1] } }
            .filter { it.contains("wakeup", ignoreCase = true) }.toList()
        assertTrue("$hits", hits.isEmpty())
    }
}
