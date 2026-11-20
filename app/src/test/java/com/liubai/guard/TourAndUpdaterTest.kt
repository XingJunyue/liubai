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
            "预览版，不检查更新", "退出", "要更新到最新版才能继续用。守护设置更新后都会保留，不更新也照常在后台守护。", "声音素材", "无敌模式下只有这些能打开。电话、短信、输入法、系统设置和系统服务已默认放行。", "雨声、海浪、篝火等是真实录音，白、粉、棕噪音为实时合成。默认只在专注时播放。", "在专注页打开，并选好白名单。专注期间除了白名单和电话、短信、输入法、系统设置和系统服务，其他应用都打不开；这个番茄也不能中途暂停、重置或跳过。", "新手教程", "这个版本的新功能", "跳过", "去看看 ›", "上一步", "下一步", "开始使用", "打开 ›",
            "再看一遍专注、管控、课表和教务导入怎么用", "看看这次更新加了什么",
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
