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
