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
        @Volatile var instance: GuardService? = null
        private val IGNORE = setOf("com.android.systemui")
        private val SETTINGS = setOf(
            "com.android.settings", "com.miui.securitycenter", "com.miui.packageinstaller",
            "com.android.packageinstaller", "com.google.android.packageinstaller", "com.miui.home",
            "com.miui.permcenter"
        )
        // 只拦真正会关掉守护的按钮；页面本身随便看
        private val DANGER = listOf("卸载", "停用", "强行停止", "结束运行", "清除数据", "清除全部数据", "清空数据", "Uninstall", "Force stop", "Clear data", "Disable")
        private val CANCEL = listOf("取消", "Cancel")
    }

    private val h = Handler(Looper.getMainLooper())
    private var fg: String? = null
    private var lastTick = 0L
    private var pendMs = 0L
    private var pendPkg: String? = null
    private var lastBlock = 0L
    private var lastBlockPkg: String? = null
    private var lastGuard = 0L
    private var lastScan = 0L
    private var lastWidget = 0L
    private var day = ""
    private var passPkg: String? = null      // 已经停过几秒、本次放行的应用
    private var lastPause = 0L
    private var sessPkg: String? = null
    private var sessStart = 0L
    private var sessLast = 0L
    private var homePkg: String? = null
    private lateinit var pm: PowerManager

    private val tick = object : Runnable {
        override fun run() {
            step()
            h.postDelayed(this, 1000)
        }
    }

    override fun onServiceConnected() {
        running = true
        instance = this
        pm = getSystemService(POWER_SERVICE) as PowerManager
        lastTick = SystemClock.elapsedRealtime()
        homePkg = try {
            packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
        } catch (x: Exception) { null }
        h.removeCallbacks(tick)
        h.post(tick)
    }

    override fun onDestroy() {
        running = false
        instance = null
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
                if (fg != null && fg != packageName && guarded(fg!!)) { lastWidget = 0L }  // 离开受守护应用，马上刷新一次
                if (pkg == homePkg || (pkg != passPkg && pkg != packageName && guarded(pkg))) passPkg = null
                fg = pkg
                if (pkg != packageName) maybePause(pkg)
            }
            fg?.let { p -> reason(p)?.let { block(p, it) } }
        }
        if (evPkg != null && evPkg in SETTINGS && Store.protectActive(this) && !Unlock.open(this)) {
            if (e.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) guardClick(e) else guardDialog()
        }
    }

    private fun guarded(pkg: String): Boolean =
        Store.active(this)[pkg] != null || Store.groupsActive(this).any { pkg in it.pkgs }

    private fun used(pkg: String): Long = Store.usedMs(this, pkg) + (if (pendPkg == pkg) pendMs else 0L)

    /** 现在该不该挡住它；返回原因，null 表示放行。 */
    private fun reason(pkg: String): String? {
        if (pkg == packageName) return null
        val lim = Store.active(this)[pkg]
        val groups = Store.groupsActive(this).filter { pkg in it.pkgs }
        if (lim == null && groups.isEmpty()) return null
        if (Focus.locking(this)) return "focus"
        val m = Rules.nowMin()
        val bed = Store.bedActive(this)
        if (bed.on && Rules.inWin(m, bed.from, bed.to)) return "bed"
        if (lim != null) {
            val x = Store.extraActive(this, pkg)
            if (x.mode == 3) return "hard"
            if (x.mode == 1 && !Rules.inWin(m, x.from, x.to)) return "window"
            if (x.mode == 2 && System.currentTimeMillis() < Store.coolUntil(this, pkg)) return "cool"
            if (used(pkg) >= lim * 60_000L) return "daily"
        }
        for (g in groups) if (g.pkgs.sumOf { used(it) } >= g.min * 60_000L) return "group:${g.id}"
        return null
    }

    private fun maybePause(pkg: String) {
        if (pkg == passPkg || reason(pkg) != null || Store.active(this)[pkg] == null) return
        val d = Store.extraActive(this, pkg).delay
        if (d <= 0) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPause < 1500) return
        lastPause = now
        try {
            startActivity(Intent(this, PauseActivity::class.java)
                .putExtra("pkg", pkg).putExtra("sec", d)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        } catch (x: Exception) {}
    }

    /** PauseActivity 点了「继续」后调用：本次放行，直到离开它。 */
    fun pass(pkg: String) { passPkg = pkg }

    /** 单次限时与剩 5 分钟提醒。 */
    private fun watch(p: String) {
        val lim = Store.active(this)[p] ?: return
        val x = Store.extraActive(this, p)
        val wall = System.currentTimeMillis()
        if (x.mode == 2) {
            if (sessPkg != p || wall - sessLast > 60_000) { sessPkg = p; sessStart = wall }
            sessLast = wall
            if (wall - sessStart >= x.session * 60_000L) {
                Store.setCoolUntil(this, p, wall + x.cool * 60_000L)
                sessPkg = null
                block(p, "cool"); return
            }
        }
        if (x.warn && !Store.warned(this, p)) {
            val left = lim * 60_000L - used(p)
            if (left in 1..5 * 60_000L) {
                Store.setWarned(this, p)
                Toast.makeText(this, "${Apps.info(this, p).label} 今天还剩 ${(left + 59_999) / 60_000} 分钟", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun step() {
        Focus.tick(this)
        val now = SystemClock.elapsedRealtime()
        val dt = now - lastTick
        lastTick = now
        val t = Store.today()
        if (t != day) { day = t; flush(); Widgets.updateAll(this) }
        // 正在用受守护的应用时，小部件每 3 秒跟一次；平时一分钟一次
        val hot = fg?.let { it != packageName && guarded(it) } == true
        if (now - lastWidget > (if (hot) 3_000 else 60_000)) { lastWidget = now; flush(); Widgets.updateAll(this) }
        if (!::pm.isInitialized || !pm.isInteractive) { flush(); passPkg = null; return }
        val sys = Usage.update(this)
        // 无障碍给的前台更实时；系统用量记录有几秒延迟，只在拿不到时兜底，避免挡完又反复挡
        val cur = fg ?: sys
        if (cur != null && cur != packageName && guarded(cur)) {
            reason(cur)?.let { block(cur, it); return }
            watch(cur)
        }
        if (Usage.grantedCached(this)) { pendMs = 0; pendPkg = null; return } // 系统用量为准，避免重复计时
        val p = fg ?: return
        if (p == packageName || !guarded(p)) return
        if (dt in 1..5000) {
            if (pendPkg != p) { flush(); pendPkg = p }
            pendMs += dt
        }
        if (pendMs >= 5000) flush()
        reason(p)?.let { block(p, it) }
    }

    private fun flush() {
        val p = pendPkg
        if (p != null && pendMs > 0) Store.addUsage(this, p, pendMs)
        pendMs = 0
    }

    private fun block(pkg: String, why: String) {
        flush()
        val now = SystemClock.elapsedRealtime()
        if (now - lastBlock < 1500 || (pkg == lastBlockPkg && now - lastBlock < 5000)) return
        lastBlock = now
        lastBlockPkg = pkg
        performGlobalAction(GLOBAL_ACTION_HOME)
        Widgets.updateAll(this)
        try {
            startActivity(Intent(this, BlockActivity::class.java)
                .putExtra("pkg", pkg).putExtra("why", why)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        } catch (x: Exception) {
            Toast.makeText(this, "今天的时间用完啦，明天 0:00 见", Toast.LENGTH_SHORT).show()
        }
        fg = packageName   // 挡住后前台就是留白自己，等无障碍事件更新，不再用延迟的系统记录反复判断
        passPkg = null
    }

    private fun hasText(root: android.view.accessibility.AccessibilityNodeInfo, w: String) =
        try { root.findAccessibilityNodeInfosByText(w).isNotEmpty() } catch (x: Exception) { false }

    private fun bounce() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastGuard < 800) return
        lastGuard = now
        performGlobalAction(GLOBAL_ACTION_BACK)
        h.postDelayed({
            try {
                startActivity(Intent(this, UnlockActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            } catch (x: Exception) {}
        }, 200)
    }

    /** 在留白的应用信息页、无障碍页里点了危险按钮或开关：退回去。 */
    private fun guardClick(e: AccessibilityEvent) {
        val root = try { rootInActiveWindow } catch (x: Exception) { null } ?: return
        val label = getString(R.string.app_name)
        if (!hasText(root, label)) return
        val said = (e.text.joinToString(" ") + " " + (e.contentDescription ?: "") + " " + (try { e.source?.text ?: "" } catch (x: Exception) { "" }))
        val isSwitch = e.className?.toString()?.let { "Switch" in it || "CheckBox" in it || "Toggle" in it } == true
        if (DANGER.any { it in said } || (isSwitch && hasText(root, "无障碍"))) bounce()
    }

    /** 弹出的确认框（卸载留白？停用留白？）：直接取消。 */
    private fun guardDialog() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastScan < 250) return
        lastScan = now
        val root = try { rootInActiveWindow } catch (x: Exception) { null } ?: return
        if (!hasText(root, getString(R.string.app_name))) return
        if (CANCEL.none { hasText(root, it) }) return
        if (DANGER.any { hasText(root, it) }) bounce()
    }
}
