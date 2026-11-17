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
            .map { info(c, it) }
            .sortedWith(compareBy({ if (it.pkg == "com.ss.android.ugc.aweme") 0 else 1 }, { it.label }))
    }
}
