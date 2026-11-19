package com.liubai.guard

import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class LogicTest {
    private val secs = listOf(480, 535, 600, 655, 840, 895, 960, 1015, 1140, 1195, 1250, 1305)
    private fun sec(a: Int, b: Int) = secs[a - 1] to secs[b - 1] + 45
    private fun l(t: String, x: Int, y: Int, w: Int = 80, h: Int = 24) = Ocr.L(t, Ocr.R(x - w / 2, y, x + w / 2, y + h))

    /** 模拟一张常见的课表截图：表头周一到周日，左边 1–12 节，每节 60 像素高。 */
    private fun grid(): MutableList<Ocr.L> {
        val out = ArrayList<Ocr.L>()
        listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日").forEachIndexed { i, d -> out += l(d, 200 + i * 120, 40) }
        out += l("11/18", 200, 66)  // 表头下的日期
        for (n in 1..12) out += l("$n", 60, 100 + (n - 1) * 60 + 18, 30)
        return out
    }

    @Test fun ocrBasic() {
        val g = grid()
        // 周一 1–2 节：高等数学，三行字
        g += l("高等数学", 200, 104); g += l("@A101", 200, 132); g += l("张老师", 200, 160)
        // 周三 3–4 节：大学英语@B203 一行
        g += l("大学英语@B203", 440, 224)
        // 周五 9–10 节
        g += l("形势与政策", 680, 584); g += l("1-16周", 680, 612); g += l("C楼", 680, 640)
        val r = Ocr.parse(g, 1000, ::sec)
        val byName = r.associateBy { it.name }
        assertEquals(setOf("高等数学", "大学英语", "形势与政策"), byName.keys)
        assertEquals(setOf(1), byName["高等数学"]!!.days)
        assertEquals(480 to 580, byName["高等数学"]!!.from to byName["高等数学"]!!.to)
        assertEquals(setOf(3), byName["大学英语"]!!.days)
        assertEquals(600 to 700, byName["大学英语"]!!.from to byName["大学英语"]!!.to)
        assertEquals(setOf(5), byName["形势与政策"]!!.days)
        assertEquals(1140, byName["形势与政策"]!!.from)
        assertEquals(1195 + 45, byName["形势与政策"]!!.to)
    }

    @Test fun ocrBackToBackCourses() {
        val g = grid()
        g += l("线性代数", 320, 104)      // 周二 1–2
        g += l("大学物理", 320, 224)      // 周二 3–4
        g += l("体育", 320, 344)          // 周二 5（只有一节，后面紧跟别的课）
        g += l("程序设计", 320, 404)      // 周二 6–7
        val r = Ocr.parse(g, 1000, ::sec).filter { 2 in it.days }.sortedBy { it.from }
        assertEquals(listOf("线性代数", "大学物理", "体育", "程序设计"), r.map { it.name })
        assertEquals(840 to 885, r[2].from to r[2].to)
        assertEquals(895, r[3].from)
    }

    @Test fun ocrMissingHeaders() {
        val g = grid().filterNot { it.t == "周一" || it.t == "周四" }.toMutableList()
        g += l("周-", 200, 40)
        g += l("高等数学", 200, 104); g += l("程序设计", 560, 464)
        val r = Ocr.parse(g, 1000, ::sec).associateBy { it.name }
        assertEquals(setOf(1), r["高等数学"]!!.days)
        assertEquals(setOf(4), r["程序设计"]!!.days)
    }

    @Test fun ocrEnglish() {
        val g = ArrayList<Ocr.L>()
        listOf("Mon", "Tue", "Wed", "Thu", "Fri").forEachIndexed { i, d -> g += l(d, 200 + i * 120, 40) }
        for (n in 1..12) g += l("$n", 60, 100 + (n - 1) * 60 + 18, 30)
        g += l("Linear", 200, 104); g += l("Algebra", 200, 132); g += l("Room 204", 200, 160)
        g += l("Physics", 440, 224); g += l("Prof. Smith", 440, 252)
        val r = Ocr.parse(g, 1000, ::sec).associateBy { it.name }
        assertEquals(setOf("Linear Algebra", "Physics"), r.keys)
        assertEquals(setOf(1), r["Linear Algebra"]!!.days); assertEquals(480, r["Linear Algebra"]!!.from)
        assertEquals(setOf(3), r["Physics"]!!.days); assertEquals(600, r["Physics"]!!.from)
    }

    @Test fun ocrGermanFrenchSpanish() {
        for (heads in listOf(listOf("Mo", "Di", "Mi", "Do", "Fr"), listOf("Lundi", "Mardi", "Mercredi", "Jeudi", "Vendredi"), listOf("Lun.", "Mar.", "Mié.", "Jue.", "Vie."))) {
            val g = ArrayList<Ocr.L>()
            heads.forEachIndexed { i, d -> g += l(d, 200 + i * 120, 40) }
            for (n in 1..12) g += l("$n.", 60, 100 + (n - 1) * 60 + 18, 30)
            g += l("Mathematik", 680, 224)
            val r = Ocr.parse(g, 1000, ::sec)
            assertEquals(heads.toString(), 1, r.size); assertEquals(setOf(5), r[0].days); assertEquals(600, r[0].from)
        }
    }

    @Test fun ocrJapaneseKorean() {
        for ((heads, unit) in listOf(listOf("月", "火", "水", "木", "金") to "限", listOf("월", "화", "수", "목", "금") to "교시")) {
            val g = ArrayList<Ocr.L>()
            heads.forEachIndexed { i, d -> g += l(d, 200 + i * 120, 40) }
            for (n in 1..12) g += l("$n$unit", 60, 100 + (n - 1) * 60 + 18, 30)
            g += l("線形代数", 320, 344); g += l("講義室3", 320, 372)
            val r = Ocr.parse(g, 1000, ::sec)
            assertEquals(1, r.size); assertEquals("線形代数", r[0].name); assertEquals(setOf(2), r[0].days); assertEquals(840, r[0].from)
        }
    }

    @Test fun ocrSplitsWideLines() {
        val g = ArrayList<Ocr.L>()
        // 表头被认成一整行
        val hs = listOf("周一", "周二", "周三", "周四", "周五").mapIndexed { i, d -> l(d, 200 + i * 120, 40) }
        g += Ocr.L("周一 周二 周三 周四 周五", Ocr.R(160, 40, 720, 64), hs)
        for (n in 1..12) g += l("$n", 60, 100 + (n - 1) * 60 + 18, 30)
        // 周一、周二两格的字连成了一行
        val p = listOf(l("高等数学", 200, 104), l("大学物理", 320, 104))
        g += Ocr.L("高等数学 大学物理", Ocr.R(160, 104, 360, 128), p)
        val r = Ocr.parse(g, 1000, ::sec).associateBy { it.name }
        assertEquals(setOf(1), r["高等数学"]!!.days); assertEquals(setOf(2), r["大学物理"]!!.days)
    }

    @Test(expected = IllegalStateException::class) fun ocrNoHeader() {
        Ocr.parse(listOf(l("随便一张图", 100, 100)), 500, ::sec)
    }

    @Test fun icsWeekly() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        val ics = """BEGIN:VCALENDAR
BEGIN:VEVENT
SUMMARY:高等数学
DTSTART;TZID=Asia/Shanghai:20261116T080000
DTEND;TZID=Asia/Shanghai:20261116T094000
RRULE:FREQ=WEEKLY;COUNT=16
END:VEVENT
BEGIN:VEVENT
SUMMARY:大学英语
DTSTART:20261118T020000Z
DTEND:20261118T034000Z
END:VEVENT
BEGIN:VEVENT
SUMMARY:高等数学
DTSTART;TZID=Asia/Shanghai:20261123T080000
DTEND;TZID=Asia/Shanghai:20261123T094000
END:VEVENT
END:VCALENDAR"""
        val r = Scenes.parseIcs(ics).sortedBy { it.days.first() }
        assertEquals(2, r.size)
        assertEquals("高等数学", r[0].name); assertEquals(setOf(1), r[0].days); assertEquals(480 to 580, r[0].from to r[0].to)
        assertEquals("大学英语", r[1].name); assertEquals(setOf(3), r[1].days); assertEquals(600 to 700, r[1].from to r[1].to)
    }

    @Test fun pickStrictest() {
        val a = Slot("a", "水课", setOf(1), 480, 580, 0)
        val b = Slot("b", "重要", setOf(1), 540, 640, 2)
        val night = Slot("n", "夜里", setOf(7), 1380, 60, 1)
        val lib = Slot("w", "图书馆", setOf(1), 0, 1439, 1, ssid = "LIB")
        val l = listOf(a, b, night, lib)
        assertEquals("水课", Scenes.pick(l, 1, 500) { "" }?.name)
        assertEquals("重要", Scenes.pick(l, 1, 560) { "" }?.name)
        assertNull(Scenes.pick(l, 2, 560) { "" })
        assertEquals("夜里", Scenes.pick(l, 1, 30) { "" }?.name)      // 周日晚跨到周一凌晨
        assertEquals("图书馆", Scenes.pick(l, 1, 700) { "LIB" }?.name)
        assertNull(Scenes.pick(l, 1, 700) { "HOME" })
    }

    @Test fun stricterKeepsTightest() {
        val a = Extra(opens = 5, again = 10)
        val loose = Extra(opens = 10, again = 0, grow = true, ask = true)
        val r = Rules.stricter(a, loose)
        assertEquals(5, r.opens); assertEquals(10, r.again); assertTrue(r.grow); assertTrue(r.ask)
        val r2 = Rules.stricter(Extra(), Extra(opens = 3))
        assertEquals(3, r2.opens)
    }
}
