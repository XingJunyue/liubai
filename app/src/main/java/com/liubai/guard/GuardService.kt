package com.liubai.guard

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

class GuardService : AccessibilityService() {
    companion object {
        @Volatile var running = false
        private val IGNORE = setOf("com.android.systemui")
        private val SETTINGS = setOf(
            "com.android.settings", "com.miui.securitycenter", "com.miui.packageinstaller",
            "com.android.packageinstaller", "com.google.android.packageinstaller", "com.miui.home",
            "com.miui.permcenter"
        )
        private val DANGER = listOf("卸载", "停用", "强行停止", "结束运行", "无障碍", "清除数据", "清除全部数据", "Uninstall", "Force stop", "Accessibility")
    }

    private val h = Handler(Looper.getMainLooper())
    private var fg: String? = null
    private var lastTick = 0L
    private var pendMs = 0L
    private var pendPkg: String? = null
    private var lastBlock = 0L
    private var lastGuard = 0L
    private var lastScan = 0L
    private var lastWidget = 0L
    private var day = ""
    private lateinit var pm: PowerManager

    private val tick = object : Runnable {
        override fun run() {
            step()
            h.postDelayed(this, 1000)
        }
    }

    override fun onServiceConnected() {
        running = true
        pm = getSystemService(POWER_SERVICE) as PowerManager
        lastTick = SystemClock.elapsedRealtime()
        h.removeCallbacks(tick)
        h.post(tick)
    }

    override fun onDestroy() {
        running = false
        flush()
        h.removeCallbacks(tick)
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        e ?: return
        val evPkg = e.packageName?.toString()
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            val pkg = try { rootInActiveWindow?.packageName?.toString() } catch (x: Exception) { null } ?: evPkg
            if (pkg != null && pkg !in IGNORE && pkg != fg) {
                flush()
                fg = pkg
            }
            fg?.let { if (blocked(it)) block(it) }
        }
        if (evPkg != null && evPkg in SETTINGS && Store.protectActive(this)) guardSettings()
    }

    private fun blocked(pkg: String): Boolean {
        if (pkg == packageName) return false
        val lim = Store.active(this)[pkg] ?: return false
        val used = Store.usedMs(this, pkg) + (if (pendPkg == pkg) pendMs else 0L)
        return used >= lim * 60_000L
    }

    private fun step() {
        val now = SystemClock.elapsedRealtime()
        val dt = now - lastTick
        lastTick = now
        val t = Store.today()
        if (t != day) { day = t; flush(); Widgets.updateAll(this) }
        if (now - lastWidget > 30_000) { lastWidget = now; flush(); Widgets.updateAll(this) }
        if (!::pm.isInitialized || !pm.isInteractive) { flush(); return }
        val sys = Usage.update(this)
        if (sys != null && sys != packageName && Store.active(this)[sys] != null && blocked(sys)) { block(sys); return }
        if (Usage.granted(this)) { pendMs = 0; pendPkg = null; return } // 系统用量为准，避免重复计时
        val p = fg ?: return
        if (p == packageName || Store.active(this)[p] == null) return
        if (dt in 1..5000) {
            if (pendPkg != p) { flush(); pendPkg = p }
            pendMs += dt
        }
        if (pendMs >= 5000) flush()
        if (blocked(p)) block(p)
    }

    private fun flush() {
        val p = pendPkg
        if (p != null && pendMs > 0) Store.addUsage(this, p, pendMs)
        pendMs = 0
    }

    private fun block(pkg: String) {
        flush()
        val now = SystemClock.elapsedRealtime()
        if (now - lastBlock < 1200) return
        lastBlock = now
        performGlobalAction(GLOBAL_ACTION_HOME)
        Widgets.updateAll(this)
        try {
            startActivity(Intent(this, BlockActivity::class.java)
                .putExtra("pkg", pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        } catch (x: Exception) {
            Toast.makeText(this, "今天的时间用完啦，明天 0:00 见", Toast.LENGTH_SHORT).show()
        }
        fg = null
    }

    private fun guardSettings() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastGuard < 800 || now - lastScan < 250) return
        lastScan = now
        val root = try { rootInActiveWindow } catch (x: Exception) { null } ?: return
        val label = getString(R.string.app_name)
        val hits = try { root.findAccessibilityNodeInfosByText(label) } catch (x: Exception) { null }
        if (hits.isNullOrEmpty()) return
        val danger = DANGER.any { w -> try { root.findAccessibilityNodeInfosByText(w).isNotEmpty() } catch (x: Exception) { false } }
        if (!danger) return
        lastGuard = now
        performGlobalAction(GLOBAL_ACTION_BACK)
        h.postDelayed({ performGlobalAction(GLOBAL_ACTION_HOME) }, 150)
        Toast.makeText(this, "留白正在守护你，今天先不改啦。想停用的话，在留白里移除所有应用，明天 0:00 起生效。", Toast.LENGTH_LONG).show()
    }
}
