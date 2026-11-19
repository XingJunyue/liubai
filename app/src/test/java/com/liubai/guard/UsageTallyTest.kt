package com.liubai.guard

import org.junit.Assert.assertEquals
import org.junit.Test

class UsageTallyTest {
    private val dy = "com.ss.android.ugc.aweme"

    @Test fun switchingScreensInsideAppKeepsCounting() {
        val t = UsageTally(0)
        t.resumed(1_000, dy, "Main")
        // 打开评论页：旧界面先暂停，新界面恢复，旧界面再停止
        t.left(61_000, dy, "Main")
        t.resumed(61_010, dy, "Comment")
        t.left(61_500, dy, "Main")
        t.left(121_000, dy, "Comment")
        t.screenOff(121_000)
        assertEquals(119_990L, t.totals[dy])
    }

    @Test fun leavingToAnotherAppStops() {
        val t = UsageTally(0)
        t.resumed(0, dy, "Main")
        t.left(10_000, dy, "Main")
        t.resumed(10_000, "launcher", "Home")
        t.left(20_000, dy, "Main") // 迟到的 STOPPED
        assertEquals(10_000L, t.totals[dy])
        assertEquals(10_000L, t.live("launcher", 20_000))
    }

    @Test fun screenOffStops() {
        val t = UsageTally(0)
        t.resumed(0, dy, "Main")
        t.screenOff(5_000)
        assertEquals(5_000L, t.live(dy, 99_000))
    }

    @Test fun openAcrossMidnightCountsFromStart() {
        val t = UsageTally(100)
        t.left(1_100, dy, "Main")
        assertEquals(1_000L, t.totals[dy])
    }
}
