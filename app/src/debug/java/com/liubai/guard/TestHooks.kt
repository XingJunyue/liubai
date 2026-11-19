package com.liubai.guard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * 只在调试版里有：自动化测试用。
 *  --ez seed true   跳过引导，放几条示例规则、课程、待办
 *  --es ocr 文件名  识别 app 专属目录里的图片，把结果打到 logcat（标签 LiubaiTest）
 */
object TestHooks {
    fun run(c: Context, i: Intent?) {
        i ?: return
        i.getStringExtra("setlang")?.let { Log.i("LiubaiTest", "lang -> $it"); Tr.set(c, it); return }
        if (i.getBooleanExtra("seed", false)) seed(c)
        // 课表网页导入：--es jwkind qz --es jwurl file:///android_asset/jwtest.html --ez jwauto true
        i.getStringExtra("jwkind")?.let { TimetableTest.webKind = it; TimetableTest.webUrl = i.getStringExtra("jwurl"); TimetableTest.autoImport = i.getBooleanExtra("jwauto", false) }
        i.getStringExtra("ocr")?.let { name ->
            val f = File(c.getExternalFilesDir(null), name)
            Log.i("LiubaiTest", "OCR start ${f.absolutePath} exists=${f.exists()}")
            Ocr.read(c, Uri.fromFile(f)) { r, e ->
                Ocr.last.forEach { Log.i("LiubaiTest", "OCR LINE ${it.t} @${it.r.left},${it.r.top},${it.r.right},${it.r.bottom}") }
                if (r == null) Log.i("LiubaiTest", "OCR FAIL $e")
                else { Log.i("LiubaiTest", "OCR OK ${r.size}"); r.forEach { Log.i("LiubaiTest", "OCR ROW ${it.days.first()}|${it.name}|${it.from}|${it.to}") } }
            }
        }
    }

    private fun seed(c: Context) {
        Unlock.setGuided(c); Store.setIntroDone(c)
        if (Store.desired(c).isEmpty()) {
            Store.setDesired(c, mapOf("com.android.chrome" to 30, "com.google.android.youtube" to 20))
            Store.setExtra(c, "com.android.chrome", Extra(delay = 5, grow = true, opens = 5, ask = true, again = 10))
        }
        if (Scenes.desired(c).isEmpty()) Scenes.setDesired(c, listOf(
            Slot("t1", "大学英语(一)", setOf(1), 480, 580, 1, room = "机401-语音室", teacher = "宋岩"),
            Slot("t2", "高等数学①(一)", setOf(1), 600, 700, 2, room = "教210", teacher = "刘思妤"),
            Slot("t3", "高等数学①(一)", setOf(3), 480, 580, 2, room = "教103", teacher = "刘思妤"),
            Slot("t4", "工程制图基础", setOf(2), 600, 700, 1, room = "大成111", teacher = "赵群超"),
            Slot("t5", "无机化学(1)", setOf(1), 840, 940, 1, room = "逸102", teacher = "徐君莉,谢宏伟"),
            Slot("t6", "无机化学(1)", setOf(4), 840, 940, 1, room = "逸310", teacher = "徐君莉,谢宏伟", weeks = (1..16).toSet()),
            Slot("t7", "大学生心理与健康教育(一)", setOf(2), 960, 1060, 0, room = "大成305", teacher = "刘洋", weeks = (1..8).toSet()),
            Slot("t8", "习近平新时代中国特色社会主义思想概论", setOf(1), 1140, 1240, 1, room = "教305", teacher = "王老师"),
            Slot("t9", "习近平新时代中国特色社会主义思想概论", setOf(5), 960, 1060, 1, room = "教305", weeks = (1..11).toSet()),
            Slot("t10", "大学物理实验", setOf(4), 600, 700, 1, room = "实验楼", weeks = setOf(1, 3, 5, 7, 9, 11, 13, 15)),
            Slot("t11", "形势与政策", setOf(5), 1140, 1240, 0, room = "大成101", weeks = setOf(2, 4, 6, 8, 10, 12)),
            Slot("t12", "体育(一)", setOf(3), 840, 940, 1, room = "体育馆", teacher = "孙老师"),
            Slot("s1", "图书馆", setOf(1, 2, 3, 4, 5), 540, 1080, 1, "LIB", "scene")))
        if (Scenes.termStart(c) < 0) Scenes.setWeekNow(c, 12)
        if (Tasks.todos(c).isEmpty()) Tasks.setTodos(c, listOf(Todo("a", "背 50 个单词", "学习"), Todo("b", "写实验报告", "学习", true)))
        if (Tasks.plans(c).isEmpty()) Tasks.setPlans(c, listOf(Plan("p", setOf(1, 2, 3, 4, 5), 20 * 60)))
        Log.i("LiubaiTest", "seeded")
    }
}
