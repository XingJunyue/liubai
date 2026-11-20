package com.liubai.guard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * 专注 / 上课时收起通知：不删掉，只是先「稍后提醒」，时间一到系统自己放回来。
 * 白名单、电话短信、闹钟和留白自己的通知照常显示。
 */
object Hush {
    private fun p(c: Context) = c.getSharedPreferences("hush", Context.MODE_PRIVATE)
    fun on(c: Context) = p(c).getBoolean("on", false)
    fun setOn(c: Context, v: Boolean) = p(c).edit().putBoolean("on", v).apply()
    fun granted(c: Context) = NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)
    fun settingsIntent() = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")

    /** 现在要收起多久（毫秒），0 表示不收。 */
    fun holdMs(c: Context): Long {
        if (!granted(c)) return 0
        if (on(c) && Focus.phase(c) == Focus.WORK) return Focus.leftMs(c).coerceAtLeast(60_000L)
        return Scenes.quietLeftMs(c)
    }

    fun count(c: Context, pkg: String) {
        val pr = p(c); val n = pr.getInt("n", 0) + 1
        val apps = (pr.getString("apps", "") ?: "").split(',').filter { it.isNotBlank() }.toMutableList()
        if (pkg !in apps) apps += pkg
        pr.edit().putInt("n", n).putString("apps", apps.joinToString(",")).apply()
    }

    /** 安静时段结束后发一条小结，告诉你收起了多少条，现在都回来了。 */
    fun summary(c: Context) {
        val pr = p(c); val n = pr.getInt("n", 0)
        if (n <= 0) return
        val names = (pr.getString("apps", "") ?: "").split(',').filter { it.isNotBlank() }.take(4).joinToString("、") { Apps.info(c, it).label }
        pr.edit().putInt("n", 0).putString("apps", "").apply()
        try {
            val nm = c.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel("hush", tr("收起的通知"), NotificationManager.IMPORTANCE_DEFAULT))
            val pi = PendingIntent.getActivity(c, 9, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
            nm.notify(79, NotificationCompat.Builder(c, "hush").setSmallIcon(R.drawable.ic_mono)
                .setContentTitle(tr("刚才收起了 {0} 条通知", n)).setContentText(tr("来自 {0}，现在都放回来了", names)).setAutoCancel(true).setContentIntent(pi).build())
        } catch (x: Exception) {}
    }

    private val ALWAYS = setOf("android", "com.android.systemui", "com.android.phone", "com.android.server.telecom", "com.android.deskclock",
        "com.google.android.deskclock", "com.android.incallui", "com.android.mms", "com.android.dialer", "com.google.android.dialer")

    fun keep(c: Context, sbn: StatusBarNotification): Boolean {
        val pkg = sbn.packageName
        if (pkg == c.packageName || pkg in ALWAYS || pkg in Focus.whitelist(c) || pkg in Focus.essentials(c) || Focus.systemService(c, pkg)) return true
        val n = sbn.notification
        if (sbn.isOngoing || n.category == android.app.Notification.CATEGORY_CALL || n.category == android.app.Notification.CATEGORY_ALARM) return true
        return false
    }
}

class HushService : NotificationListenerService() {
    private var lastActive = false

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val hold = Hush.holdMs(this)
        if (hold <= 0 || Hush.keep(this, sbn)) return
        try {
            snoozeNotification(sbn.key, hold)
            Hush.count(this, sbn.packageName)
        } catch (x: Exception) { Diag.log("收起通知失败 ${x.message}") }
    }

    override fun onListenerConnected() { Diag.log("通知收起已连上") }
}

/** 由守护服务定时调用：安静时段刚结束时发小结。 */
object HushWatch {
    @Volatile private var was = false
    fun check(c: Context) {
        val now = Hush.holdMs(c) > 0
        if (was && !now) Hush.summary(c)
        was = now
    }
}

/** 收起通知的开关：没给「通知使用权」时先带去设置。 */
@androidx.compose.runtime.Composable
fun HushToggle(label: String, sub: String, get: (Context) -> Boolean, set: (Context, Boolean) -> Unit) {
    val c = androidx.compose.ui.platform.LocalContext.current
    var v by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(get(c) && Hush.granted(c)) }
    Every(1500) { if (get(c) && Hush.granted(c) != v) v = get(c) && Hush.granted(c) }
    Toggle(label, if (!Hush.granted(c)) tr("{0}（需要先允许读取通知）", sub) else sub, v) { want ->
        set(c, want); v = want && Hush.granted(c)
        if (want && !Hush.granted(c)) try { c.startActivity(Hush.settingsIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (x: Exception) {}
    }
}
