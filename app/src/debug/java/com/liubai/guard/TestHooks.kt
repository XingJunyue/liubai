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
        if (i.getBooleanExtra("seed", false)) seed(c)
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
            Slot("t1", "高等数学", setOf(1, 3), 480, 580, 2), Slot("t2", "大学英语", setOf(2), 600, 700, 1),
            Slot("t3", "形势与政策", setOf(5), 1140, 1240, 0), Slot("s1", "图书馆", setOf(1, 2, 3, 4, 5), 540, 1080, 1, "LIB", "scene")))
        if (Tasks.todos(c).isEmpty()) Tasks.setTodos(c, listOf(Todo("a", "背 50 个单词", "学习"), Todo("b", "写实验报告", "学习", true)))
        if (Tasks.plans(c).isEmpty()) Tasks.setPlans(c, listOf(Plan("p", setOf(1, 2, 3, 4, 5), 20 * 60)))
        Log.i("LiubaiTest", "seeded")
    }
}
