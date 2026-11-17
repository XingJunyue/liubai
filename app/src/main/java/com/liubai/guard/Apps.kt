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
            .let { l -> if (Virt.WX in l) l + Virt.FINDER else l }
            .map { info(c, it) }
            .sortedWith(compareBy({ if (it.pkg == "com.ss.android.ugc.aweme" || it.pkg == Virt.FINDER) 0 else 1 }, { it.label }))
    }
}

/**
 * 应用里的一块：比如微信里的「视频号」。它不是独立应用，系统用量里也分不出来，
 * 只能靠无障碍看当前页面属于哪个模块，由留白自己计时。
 */
object Virt {
    const val WX = "com.tencent.mm"
    const val FINDER = "com.tencent.mm:finder"
    private const val FINDER_UI = "com.tencent.mm.plugin.finder"

    fun isVirt(pkg: String) = ':' in pkg
    fun real(pkg: String) = pkg.substringBefore(':')
    fun label(pkg: String) = when (pkg) { FINDER -> "微信视频号"; else -> null }

    @Volatile var inFinder = false

    /** 根据页面类名更新「是否在视频号里」；只认 Activity 级别的切换，弹窗和控件不算。 */
    fun see(pkg: String?, cls: String?) {
        if (pkg != WX || cls == null) return
        if (cls.startsWith(FINDER_UI)) inFinder = true
        else if (cls.startsWith("com.tencent.mm.") && (cls.endsWith("UI") || cls.endsWith("Activity"))) inFinder = false
    }

    /** 把真实包名换成该计入的那一块。 */
    fun map(pkg: String?): String? = if (pkg == WX && inFinder) FINDER else pkg
}
