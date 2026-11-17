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
            "com.android.settings", "com.miui.securitycenter", "com.miui.packageinstaller", "com.miui.home", "com.miui.permcenter",
            "com.android.packageinstaller", "com.google.android.packageinstaller", "com.google.android.permissioncontroller",
            // 三星
            "com.samsung.android.packageinstaller", "com.samsung.android.lool", "com.samsung.android.sm.devicesecurity",
            // 华为 / 荣耀
            "com.huawei.systemmanager", "com.huawei.android.launcher", "com.hihonor.systemmanager", "com.hihonor.android.launcher",
            // OPPO / 一加 / realme
            "com.coloros.safecenter", "com.oplus.safecenter", "com.coloros.securitypermission", "com.oplus.appdetail",
            "com.oppo.launcher", "com.android.launcher",
            // vivo / iQOO
            "com.iqoo.secure", "com.vivo.permissionmanager", "com.bbk.launcher2", "com.vivo.appfilter",
            // 魅族、联想、摩托罗拉、谷歌桌面
            "com.meizu.safe", "com.zui.safecenter", "com.motorola.launcher3", "com.google.android.apps.nexuslauncher",
            "com.sec.android.app.launcher"
        )
        /** 名单之外的品牌：包名像设置、安装器、手机管家的也一起看。 */
        private val HINTS = listOf("settings", "packageinstaller", "securitycenter", "safecenter", "systemmanager", "permission", "launcher", "appdetail")
        fun sensitive(pkg: String) = pkg in SETTINGS || HINTS.any { it in pkg }
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
            h.postDelayed(this, nextDelay())
        }
    }

    /** 省电：只有在用受守护的应用、或专注锁定中才每秒检查；平时 3 秒；熄屏 20 秒。 */
    private fun nextDelay(): Long {
        if (!::pm.isInitialized || !pm.isInteractive) return 20_000
        val f = fg
        val hot = (f != null && f != packageName && guarded(f)) || Focus.locking(this) || Focus.scholarLocking(this)
        return if (hot) 1_000 else 3_000
    }

    private var lastWin = 0L
    private var longSelf = false
    private var longAt = 0L
    private var contentOn = false

    /** 只有停在桌面 / 设置 / 安装器时才监听「界面内容变化」（用来抓卸载确认框），刷视频时不监听，省电。 */
    private fun watchContent(pkg: String?) {
        val want = pkg != null && (sensitive(pkg) || pkg == homePkg) && Store.protectActive(this)
        if (want == contentOn) return
        contentOn = want
        try {
            val i = serviceInfo ?: return
            val base = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED or AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
            i.eventTypes = if (want) base or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED else base
            serviceInfo = i
        } catch (x: Exception) {}
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
        val st = e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (st || e.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            // 「窗口列表变化」事件很频繁（输入法、悬浮窗都会触发）：节流，少跨进程取窗口树
            val nowW = SystemClock.elapsedRealtime()
            if (!st && nowW - lastWin < 700) return
            lastWin = nowW
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
        if (evPkg != null && (sensitive(evPkg) || evPkg == homePkg)) {
            // 桌面长按图标：记住按的是不是留白（小米桌面弹出的卸载菜单只有图标，没有字）
            if (e.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
                val said = e.text.joinToString(" ") + " " + (e.contentDescription ?: "")
                longSelf = getString(R.string.app_name) in said
                longAt = SystemClock.elapsedRealtime()
            }
            if (Store.protectActive(this) && !Unlock.open(this)) {
                if (e.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) guardClick(e)
                else if (e.eventType != AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) guardDialog()
            }
        }
        if (st) watchContent(fg ?: evPkg)
    }

    private var essCache: Set<String>? = null
    private var essAt = 0L
    private fun scholarEss(): Set<String> {
        val now = SystemClock.elapsedRealtime()
        if (essCache == null || now - essAt > 60_000) { essCache = Focus.essentials(this); essAt = now }
        return essCache!!
    }

    private fun guarded(pkg: String): Boolean =
        Store.active(this)[pkg] != null || Store.groupsActive(this).any { pkg in it.pkgs }

    private fun used(pkg: String): Long = Store.usedMs(this, pkg) + (if (pendPkg == pkg) pendMs else 0L)

    /** 现在该不该挡住它；返回原因，null 表示放行。 */
    private fun reason(pkg: String): String? {
        if (pkg == packageName) return null
        if (Focus.scholarLocking(this) && pkg !in Focus.whitelist(this) && pkg !in scholarEss()) return "scholar"
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
        // 正在用受守护的应用时，小部件 30 秒跟一次；平时 2 分钟一次（内容没变就不重绘）
        val hot = fg?.let { it != packageName && guarded(it) } == true
        if (now - lastWidget > (if (hot) 30_000 else 120_000)) { lastWidget = now; flush(); Widgets.updateAll(this) }
        if (!::pm.isInitialized || !pm.isInteractive) { flush(); passPkg = null; return }
        val sys = Usage.update(this)
        // 无障碍给的前台更实时；系统用量记录有几秒延迟，只在拿不到时兜底，避免挡完又反复挡
        val cur = fg ?: sys
        if (cur != null && Focus.scholarLocking(this) && reason(cur) == "scholar") { block(cur, "scholar"); return }
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
        val said = (e.text.joinToString(" ") + " " + (e.contentDescription ?: "") + " " + (try { e.source?.let { "${it.text ?: ""} ${it.contentDescription ?: ""} ${it.viewIdResourceName ?: ""}" } ?: "" } catch (x: Exception) { "" }))
        val danger = DANGER.any { it in said } || "删除" in said || "uninstall" in said.lowercase() || "delete" in said.lowercase()
        // 桌面：刚长按过留白，再点卸载 / 垃圾桶
        if ((e.packageName?.toString() == homePkg || "launcher" in (e.packageName?.toString() ?: "")) && longSelf && SystemClock.elapsedRealtime() - longAt < 15_000 && danger) { bounce(); return }
        val root = try { rootInActiveWindow } catch (x: Exception) { null } ?: return
        val label = getString(R.string.app_name)
        if (!hasText(root, label)) return
        val isSwitch = e.className?.toString()?.let { "Switch" in it || "CheckBox" in it || "Toggle" in it } == true
        if (danger || (isSwitch && hasText(root, "无障碍"))) bounce()
    }

    /** 弹出的确认框（卸载留白？停用留白？）：直接取消。 */
    private fun guardDialog() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastScan < 250) return
        lastScan = now
        // 确认框不一定是「当前活动窗口」，所有窗口都看一遍
        val roots = (try { windows.mapNotNull { it.root } } catch (x: Exception) { emptyList() }) +
            listOfNotNull(try { rootInActiveWindow } catch (x: Exception) { null })
        val label = getString(R.string.app_name)
        for (root in roots) {
            if (!hasText(root, label)) continue
            if (CANCEL.none { hasText(root, it) }) continue
            if (DANGER.any { hasText(root, it) } || hasText(root, "删除")) { bounce(); return }
        }
    }
}
