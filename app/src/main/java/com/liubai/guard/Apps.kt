package com.liubai.guard

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

data class AppInfo(val pkg: String, val label: String, val icon: ImageBitmap?)

object Apps {
    private val cache = HashMap<String, AppInfo>()

    private fun toBitmap(d: Drawable): ImageBitmap {
        val s = 96
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        d.setBounds(0, 0, s, s)
        d.draw(cv)
        return b.asImageBitmap()
    }

    fun info(c: Context, pkg: String): AppInfo {
        cache[pkg]?.let { return it }
        Virt.label(pkg)?.let { l ->
            val base = info(c, Virt.real(pkg))
            val v = AppInfo(pkg, l, base.icon)
            cache[pkg] = v
            return v
        }
        val pm = c.packageManager
        val a = try {
            val ai = pm.getApplicationInfo(pkg, 0)
            AppInfo(pkg, pm.getApplicationLabel(ai).toString(), toBitmap(pm.getApplicationIcon(ai)))
        } catch (e: Exception) {
            AppInfo(pkg, pkg, null)
        }
        cache[pkg] = a
        return a
    }

    fun launchable(c: Context): List<AppInfo> {
        val pm = c.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != c.packageName }
            .let { l -> l + Virt.available(l) }
            .map { info(c, it) }
            .sortedWith(compareBy({ if (it.pkg == "com.ss.android.ugc.aweme" || Virt.isVirt(it.pkg)) 0 else 1 }, { it.label }))
    }
}

/**
 * 应用里的一块：比如微信里的「视频号」、B 站的竖屏视频、小红书的视频流。它们不是独立应用，
 * 系统用量里也分不出来，只能靠无障碍看当前页面属于哪个模块，由留白自己计时、单独守护。
 */
object Virt {
    class Sec(val id: String, val real: String, val label: String, val enter: (String) -> Boolean)

    const val WX = "com.tencent.mm"
    const val FINDER = "com.tencent.mm:finder"
    val SECS = listOf(
        Sec(FINDER, WX, "微信视频号") { it.startsWith("com.tencent.mm.plugin.finder") },
        Sec("tv.danmaku.bili:story", "tv.danmaku.bili", "B站竖屏视频") { val l = it.lowercase(); ".story." in l || "storyvideo" in l || "verticalvideo" in l },
        Sec("com.bilibili.app.in:story", "com.bilibili.app.in", "B站竖屏视频") { val l = it.lowercase(); ".story." in l || "storyvideo" in l },
        Sec("com.xingin.xhs:video", "com.xingin.xhs", "小红书视频流") { val l = it.lowercase(); "videofeed" in l || "detailfeed" in l && "video" in l },
        Sec("com.zhihu.android:video", "com.zhihu.android", "知乎视频") { val l = it.lowercase(); "videotab" in l || "zvideo" in l },
        Sec("com.taobao.taobao:video", "com.taobao.taobao", "淘宝逛逛视频") { val l = it.lowercase(); "videofeed" in l || "guangguang" in l || "fullscreenvideo" in l },
        Sec("com.baidu.searchbox:video", "com.baidu.searchbox", "百度视频") { val l = it.lowercase(); "minivideo" in l || "videodetail" in l || "immersive" in l },
        Sec("com.google.android.youtube:shorts", "com.google.android.youtube", "YouTube Shorts") { val l = it.lowercase(); "shorts" in l || "reel" in l },
        Sec("com.instagram.android:reels", "com.instagram.android", "Instagram Reels") { val l = it.lowercase(); "reel" in l || "clips" in l }
    )
    private val byId = SECS.associateBy { it.id }
    private val reals = SECS.map { it.real }.toSet()

    fun isVirt(pkg: String) = ':' in pkg
    fun real(pkg: String) = pkg.substringBefore(':')
    fun label(pkg: String) = byId[pkg]?.label
    fun watched(pkg: String?) = pkg != null && pkg in reals

    /** 每个应用此刻在不在它的那一块里：real -> 那块的 id。 */
    private val cur = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile var inFinder = false
        private set
    fun inSec(id: String) = cur[real(id)] == id

    /** 根据页面类名更新；只认 Activity 级别的切换，弹窗和控件不算。 */
    fun see(pkg: String?, cls: String?) {
        if (pkg == null || cls == null || pkg !in reals) return
        val hit = SECS.firstOrNull { it.real == pkg && it.enter(cls) }
        if (hit != null) cur[pkg] = hit.id
        else if (cls.endsWith("Activity") || cls.endsWith("UI")) cur.remove(pkg)
        if (pkg != WX) Diag.log("页面 $pkg $cls${if (hit != null) " → ${hit.label}" else ""}")
        inFinder = cur[WX] == FINDER
    }

    /** 把真实包名换成该计入的那一块。 */
    fun map(pkg: String?): String? = if (pkg == null) null else cur[pkg] ?: pkg

    /** 已装的应用里，能单独守护的那些块。 */
    fun available(installed: Collection<String>) = SECS.filter { it.real in installed }.map { it.id }
}
