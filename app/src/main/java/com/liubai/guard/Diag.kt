package com.liubai.guard

import android.content.Context
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 诊断记录：只存在内存里，最近 80 条，用来查「为什么没拦住」。 */
object Diag {
    private val buf = ArrayDeque<String>()
    private val f = SimpleDateFormat("HH:mm:ss", Locale.US)
    @Synchronized fun log(s: String) {
        val line = "${f.format(Date())} $s"
        buf.addLast(line)
        while (buf.size > 80) buf.removeFirst()
        persist(line)
    }

    // 同时写一份到「下载/留白」里：应用被卸载了，这份记录还在，方便查原因
    @Volatile private var app: Context? = null
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var uri: android.net.Uri? = null
    fun init(c: Context) { app = c.applicationContext }
    private fun persist(line: String) {
        val c = app ?: return
        if (android.os.Build.VERSION.SDK_INT < 29) return
        io.execute {
            try {
                val sp = c.getSharedPreferences("diag", Context.MODE_PRIVATE)
                if (uri == null) uri = sp.getString("uri", null)?.let { android.net.Uri.parse(it) }
                fun make(): android.net.Uri? {
                    val v = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Downloads.DISPLAY_NAME, "留白记录.txt")
                        put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                        put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/留白")
                    }
                    return c.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                        ?.also { sp.edit().putString("uri", it.toString()).apply() }
                }
                val bytes = (line + "\n").toByteArray()
                try { c.contentResolver.openOutputStream(uri ?: make().also { uri = it }!!, "wa")!!.use { it.write(bytes) } }
                catch (x: Exception) { uri = make(); uri?.let { u -> c.contentResolver.openOutputStream(u, "wa")?.use { it.write(bytes) } } }
            } catch (x: Exception) {}
        }
    }
    @Synchronized private fun lines() = buf.toList()

    fun report(c: Context): String {
        val sb = StringBuilder()
        sb.append("版本 ${BuildConfig.VERSION_NAME}\n")
        sb.append("服务在跑 ${GuardService.running}，距上次事件 ${(SystemClock.elapsedRealtime() - GuardService.lastEvent) / 1000} 秒\n")
        val s = GuardService.instance
        sb.append("前台 ${s?.fgNow()}，桌面 ${s?.homeNow()}\n")
        sb.append("守护生效 ${Store.protectActive(c)}，今日应用 ${Store.active(c).keys.joinToString(",")}\n")
        sb.append("用量权限 ${Usage.granted(c)}，放行中 ${Unlock.open(c)}，卸载锁 ${Admin.on(c)}\n")
        sb.append("系统前台 ${try { Usage.update(c) } catch (x: Exception) { "?" }}\n")
        for ((k, v) in Store.active(c)) sb.append("$k 限 $v 分，已用 ${Store.usedMs(c, k) / 60000} 分\n")
        sb.append("—— 最近事件 ——\n")
        lines().forEach { sb.append(it).append('\n') }
        return sb.toString()
    }
}
