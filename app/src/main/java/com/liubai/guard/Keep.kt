package com.liubai.guard

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings

/**
 * 保活：锁屏后系统把留白杀掉，无障碍会被一起关掉。
 *  - 授权过 WRITE_SECURE_SETTINGS（电脑上执行一次 adb 命令）后，留白能自己把无障碍重新打开；
 *  - 开机、更新、打开应用、小部件刷新、每 15 分钟的闹钟，都会检查一次。
 */
object Keep {
    const val ADB = "adb shell pm grant com.liubai.guard android.permission.WRITE_SECURE_SETTINGS"

    fun canHeal(c: Context) = c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun heal(c: Context) {
        if (!canHeal(c) || GuardService.running) return
        try {
            val me = "${c.packageName}/${GuardService::class.java.name}"
            val cur = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val list = cur.split(':').filter { it.isNotBlank() && !it.startsWith("${c.packageName}/") }
            // 先去掉再加上：被杀后系统里可能还挂着旧记录，重新写一遍才会真正拉起
            Settings.Secure.putString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.joinToString(":"))
            Settings.Secure.putString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (list + me).joinToString(":"))
            Settings.Secure.putInt(c.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        } catch (x: Exception) {}
    }

    fun schedule(c: Context) {
        try {
            val am = c.getSystemService(AlarmManager::class.java) ?: return
            val pi = PendingIntent.getBroadcast(c, 31, Intent(c, KeepReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 15 * 60_000L, AlarmManager.INTERVAL_FIFTEEN_MINUTES, pi)
        } catch (x: Exception) {}
    }
}

/** 无线调试授权：借 Shizuku（在手机上用「无线调试」启动，不用电脑），让留白给自己授予 WRITE_SECURE_SETTINGS。 */
object Shz {
    const val PKG = "moe.shizuku.privileged.api"
    const val DOWNLOAD = "https://github.com/RikkaApps/Shizuku/releases"

    fun installed(c: Context) = try { c.packageManager.getPackageInfo(PKG, 0); true } catch (x: Exception) { false }
    fun running() = try { rikka.shizuku.Shizuku.pingBinder() } catch (x: Throwable) { false }
    fun permitted() = try { rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED } catch (x: Throwable) { false }
    fun ask() { try { rikka.shizuku.Shizuku.requestPermission(7) } catch (x: Throwable) {} }

    /** 在后台线程调用 */
    fun grant(c: Context): Boolean {
        try {
            val m = rikka.shizuku.Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
            )
            m.isAccessible = true
            val p = m.invoke(null, arrayOf("pm", "grant", c.packageName, Manifest.permission.WRITE_SECURE_SETTINGS), null, null) as Process
            p.waitFor()
        } catch (x: Throwable) {}
        return Keep.canHeal(c)
    }
}

class KeepReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent?) {
        Keep.heal(c)
        Keep.schedule(c)
    }
}
