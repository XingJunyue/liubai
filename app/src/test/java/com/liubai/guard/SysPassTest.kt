package com.liubai.guard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SysPassTest {
    @Test fun systemServicesByName() {
        for (p in listOf("com.android.settings", "com.google.android.gms", "com.google.android.gms.ui", "com.google.android.gsf",
            "com.huawei.hwid", "com.miui.securitycenter", "com.coloros.settings", "com.google.android.permissioncontroller",
            "com.android.packageinstaller")) assertTrue(p, Focus.sysByName(p))
        for (p in listOf("com.ss.android.ugc.aweme", "com.tencent.mm", "com.android.chrome", "com.android.vending",
            "com.google.android.youtube", "com.miui.gallery", "tv.danmaku.bili")) assertFalse(p, Focus.sysByName(p))
    }
    @Test fun pickersAreSystem() {
        for (p in listOf("com.android.photopicker", "com.google.android.providers.media.module", "com.android.intentresolver",
            "com.google.android.documentsui")) assertTrue(p, Focus.sysByName(p))
    }
    @Test fun helperChain() {
        assertTrue(Focus.helperPass(true, false, true, "com.android.camera"))
        assertTrue(Focus.helperPass(true, false, true, "com.miui.gallery"))
        assertFalse(Focus.helperPass(false, false, true, "com.android.camera"))
        assertFalse(Focus.helperPass(true, true, true, "com.android.camera"))
        assertFalse(Focus.helperPass(true, false, false, "com.tencent.mm"))
        assertFalse(Focus.helperPass(true, false, true, "com.android.browser"))
        assertFalse(Focus.helperPass(true, false, true, "com.xiaomi.market"))
        assertFalse(Focus.helperPass(true, false, true, "com.miui.video"))
    }
    @Test fun systemComponentsWithoutIcon() {
        assertTrue(Focus.sysByFlags(system = true, launchable = false))
        assertFalse(Focus.sysByFlags(system = true, launchable = true))
        assertFalse(Focus.sysByFlags(system = false, launchable = false))
    }
}

class TappedTest {
    private val labels = mapOf("com.ss.android.ugc.aweme" to "抖音", "com.tencent.mobileqq" to "QQ")
    @org.junit.Test fun exactAndBadge() {
        org.junit.Assert.assertEquals("com.ss.android.ugc.aweme", GuardService.tapped(listOf("抖音"), labels))
        org.junit.Assert.assertEquals("com.ss.android.ugc.aweme", GuardService.tapped(listOf("抖音，3条未读"), labels))
    }
    @org.junit.Test fun noPrefixFalsePositive() {
        org.junit.Assert.assertNull(GuardService.tapped(listOf("QQ音乐"), labels))
        org.junit.Assert.assertNull(GuardService.tapped(listOf("抖音极速版"), labels))
        org.junit.Assert.assertNull(GuardService.tapped(listOf(""), labels))
    }
}

class WhitelistSyncTest {
    @Test fun strictSessionsLetWhitelistThrough() {
        // 重要课 / 无敌模式：白名单里的应用（哪怕也在守护中）不被这一层挡
        assertFalse(Focus.sessionBlocks(lock = true, strict = true, inWhitelist = true))
        assertTrue(Focus.sessionBlocks(lock = true, strict = true, inWhitelist = false))
        // 普通课 / 普通专注：照旧挡守护中的应用
        assertTrue(Focus.sessionBlocks(lock = true, strict = false, inWhitelist = true))
        assertFalse(Focus.sessionBlocks(lock = false, strict = true, inWhitelist = true))
    }
}

class SleepGuardTest {
    @Test fun legacySingleObjectStillReads() {
        val l = Bed.list("""{"on":true,"f":1380,"t":420}""")
        org.junit.Assert.assertEquals(1, l.size)
        org.junit.Assert.assertEquals(Bed.MAIN, l[0].id)
        assertTrue(l[0].on); org.junit.Assert.assertEquals(Bed.ALL, l[0].days)
        org.junit.Assert.assertEquals(Bed.MAIN, Bed.list("{}")[0].id)
        org.junit.Assert.assertEquals(Bed.MAIN, Bed.list(null)[0].id)
    }
    @Test fun roundTripKeepsNap() {
        val nap = Bed(true, 750, 840, "b1", "午睡", setOf(1, 2, 3, 4, 5))
        val l = Bed.list(Bed.listJson(listOf(Bed(), nap)))
        org.junit.Assert.assertEquals(listOf(Bed(), nap), l)
        // 没存睡前那一段时也会补上
        org.junit.Assert.assertEquals(Bed.MAIN, Bed.list(Bed.listJson(listOf(nap)))[0].id)
    }
    @Test fun hitRespectsDaysAndMidnight() {
        val nap = Bed(true, 750, 840, "b1", "午睡", setOf(1, 2, 3, 4, 5))
        assertTrue(nap.hit(1, 800)); assertFalse(nap.hit(6, 800)); assertFalse(nap.hit(1, 840))
        val bed = Bed(true, 23 * 60, 7 * 60, days = setOf(5))      // 只有周五晚上
        assertTrue(bed.hit(5, 23 * 60 + 30)); assertTrue(bed.hit(6, 60)); assertFalse(bed.hit(5, 60)); assertFalse(bed.hit(4, 23 * 60 + 30))
        assertFalse(nap.copy(on = false).hit(1, 800))
    }
    @Test fun onlyStricterToday() {
        val a = listOf(Bed(true, 23 * 60, 7 * 60))
        val nap = Bed(true, 750, 840, "b1", "午睡")
        // 新加午睡：马上生效
        org.junit.Assert.assertEquals(listOf(a[0], nap), Rules.stricterBeds(a, listOf(a[0], nap)))
        // 删掉的段今天照旧
        org.junit.Assert.assertEquals(listOf(a[0], nap), Rules.stricterBeds(listOf(a[0], nap), listOf(a[0])))
        // 少选星期 = 放宽：今天不变
        org.junit.Assert.assertEquals(a[0], Rules.stricterBed(a[0], a[0].copy(days = setOf(1, 2))))
        // 提前开始 = 更严：马上生效
        val early = a[0].copy(from = 22 * 60)
        org.junit.Assert.assertEquals(early, Rules.stricterBed(a[0], early))
    }
}
