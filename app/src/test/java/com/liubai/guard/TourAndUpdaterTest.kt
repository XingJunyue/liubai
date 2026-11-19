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
            "预览版，不检查更新", "新手教程", "这个版本的新功能", "跳过", "去看看 ›", "上一步", "下一步", "开始使用", "打开 ›",
            "再看一遍专注、管控、课表和教务导入怎么用", "看看这次更新加了什么",
            "输入学校名搜索，已收录 {0} 所学校的教务入口", "搜到的学校",
            "学校列表来自开源项目，网址和类型不一定准；进去后可以改网址、点上方类型名换解析方式。",
            "有人适配过", "换教务类型", "导入不出来时换一个试试，网页和登录状态不变") + Jw.KINDS.flatMap { listOf(it.name, it.note) }.filter { it.isNotEmpty() }
        for (lang in listOf("en", "fr", "ja", "ko", "de", "es")) {
            val j = i18n(lang)
            val missing = keys.filter { !j.has(it) || j.optString(it).isBlank() }
            assertTrue("$lang missing: $missing", missing.isEmpty())
        }
    }
}
