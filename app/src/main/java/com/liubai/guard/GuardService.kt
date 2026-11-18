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
        /** 最近一次收到无障碍事件的时间；太久没有说明服务被系统冻住了（看起来开着，其实不干活）。 */
        @Volatile var lastEvent = 0L
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
        private val DANGER = listOf("卸载", "停用", "强行停止", "结束运行", "清除数据", "清除全部数据", "清空数据", "取消激活", "Uninstall", "Force stop", "Clear data", "Disable", "Deactivate")
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

    /**
     * 省电：切换应用由无障碍事件即时处理，定时器只负责「计时到点」。
     * 正在用受守护的应用：2 秒一次；平时 15 秒；熄屏 60 秒（亮屏时会立刻补一次）。
     */
    private fun hot(): Boolean {
        val f = fg
        return (f != null && f != packageName && guarded(f)) || Focus.scholarLocking(this)
    }
    private fun nextDelay(): Long {
        if (!::pm.isInitialized || !pm.isInteractive) {
            // 番茄钟在走：熄屏也要准时提醒，按剩余时间来排
            return if (Focus.running(this) && Focus.pausedLeft(this) < 0) Focus.leftMs(this).coerceIn(1_000, 60_000) else 60_000
        }
        return if (hot()) 2_000 else 15_000
    }
    /** 马上检查一次，并按新的节奏排下一次。 */
    private fun kick() { h.removeCallbacks(tick); h.post(tick) }
    private val screen = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, i: Intent?) { kick() }
    }

    private var lastWin = 0L
    private var longSelf = false
    private var longAt = 0L
    private var contentOn = false
    private var sysSeen: String? = null
    private var sysSince = 0L

    /** 只有停在桌面 / 设置 / 安装器时才监听「界面内容变化」（用来抓卸载确认框），刷视频时不监听，省电。 */
    private fun watchContent(pkg: String?) {
        // 桌面不监听（桌面的时钟、动画会不停触发，最耗电）；桌面的卸载靠「点击」事件就能挡住
        val want = pkg != null && locked() && (sensitive(pkg) || pkg == homePkg || "launcher" in pkg || pkg == "com.miui.home")
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

    fun fgNow() = fg
    fun homeNow() = homePkg

    override fun onServiceConnected() {
        Diag.init(this)
        Diag.log("服务连上")
        running = true
        instance = this
        pm = getSystemService(POWER_SERVICE) as PowerManager
        lastTick = SystemClock.elapsedRealtime()
        lastEvent = lastTick
        homePkg = try {
            packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
        } catch (x: Exception) { null }
        h.removeCallbacks(tick)
        h.post(tick)
        try {
            registerReceiver(screen, android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
            })
        } catch (x: Exception) {}
        pinNotice()
        Keep.schedule(this)
    }

    /** 常驻一条安静的通知：系统把它当前台服务，锁屏后不容易被杀。 */
    private fun pinNotice() {
        try {
            val nm = getSystemService(android.app.NotificationManager::class.java) ?: return
            nm.createNotificationChannel(android.app.NotificationChannel("keep", "守护常驻", android.app.NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
            val pi = android.app.PendingIntent.getActivity(this, 8, Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), android.app.PendingIntent.FLAG_IMMUTABLE)
            val n = androidx.core.app.NotificationCompat.Builder(this, "keep").setSmallIcon(R.drawable.ic_mono)
                .setContentTitle("留白守护中").setContentText("锁屏后也会一直在").setOngoing(true)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MIN).setContentIntent(pi).build()
            if (android.os.Build.VERSION.SDK_INT >= 34) startForeground(88, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(88, n)
        } catch (x: Exception) {}
    }

    override fun onDestroy() {
        running = false
        instance = null
        flush()
        h.removeCallbacks(tick)
        h.removeCallbacks(dialogCheck)
        h.removeCallbacks(armScan)
        try { unregisterReceiver(screen) } catch (x: Exception) {}
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        e ?: return
        lastEvent = SystemClock.elapsedRealtime()
        val evPkg = e.packageName?.toString()
        val st = e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (st) Virt.see(evPkg, e.className?.toString())
        if (WAdb.status == "waiting" && evPkg != null && "settings" in evPkg) {
            WAdb.scan(this, try { windows.map { it.root } + rootInActiveWindow } catch (x: Exception) { listOf(rootInActiveWindow) })
        }
        if (st || e.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            // 「窗口列表变化」事件很频繁（输入法、悬浮窗都会触发）：节流，少跨进程取窗口树
            val nowW = SystemClock.elapsedRealtime()
            val skip = !st && nowW - lastWin < 700
            if (!skip) {
            lastWin = nowW
            val pkg = Virt.map(try { rootInActiveWindow?.packageName?.toString() } catch (x: Exception) { null } ?: evPkg)
            if (pkg != null && pkg !in IGNORE && pkg != fg) {
                flush()
                if (fg != null && fg != packageName && guarded(fg!!)) { lastWidget = 0L }  // 离开受守护应用，马上刷新一次
                if (pkg == homePkg || (pkg != passPkg && pkg != packageName && guarded(pkg))) passPkg = null
                val wasHot = hot()
                fg = pkg
                if (pkg != packageName) maybePause(pkg)
                if (hot() != wasHot) kick()
            }
            fg?.let { p -> reason(p)?.let { block(p, it) } }
            // 桌面弹出长按菜单 / 卸载框时只有「窗口变化」事件，没有包名：在桌面就盯一下
            val f0 = fg
            if (!st && f0 != null && (f0 == homePkg || "launcher" in f0) && locked()) {
                Diag.log("桌面窗口变化 ${try { windows.joinToString(",") { "${it.root?.packageName}:${it.title ?: ""}" } } catch (x: Exception) { "?" }}")
                armLauncher(); guardDialog()
            }
            }
        }
        // 卸载锁开着：任何应用的窗口一变就扫一眼（窗口变化不频繁，耗电很少）
        if ((st || e.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) && evPkg != packageName && locked()) guardDialog()
        if (evPkg != null && (sensitive(evPkg) || evPkg == homePkg || "launcher" in evPkg || evPkg == "com.miui.home")) {
            if (e.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
                Diag.log("事件 ${AccessibilityEvent.eventTypeToString(e.eventType).removePrefix("TYPE_")} $evPkg ${e.className ?: ""} 「${(e.text.joinToString(" ") + " " + (e.contentDescription ?: "")).trim().take(40)}」")
            // 桌面长按图标：记住按的是不是留白（小米桌面弹出的卸载菜单只有图标，没有字）
            if (e.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
                val said = e.text.joinToString(" ") + " " + (e.contentDescription ?: "")
                longSelf = getString(R.string.app_name) in said
                longAt = SystemClock.elapsedRealtime()
                // 卸载锁开着：长按留白的图标就直接挡回去（小米桌面的卸载不弹确认框，点了就没了，只能在长按这一步拦）
                if (longSelf && (evPkg == homePkg || "launcher" in evPkg) && locked()) { Diag.log("长按了留白图标，挡回去"); bounce() }
            }
            // 桌面上任何点按 / 长按 / 拖动后的 20 秒都盯一下：有的桌面长按、点「卸载」都不报事件
            if ((evPkg == homePkg || "launcher" in evPkg) && e.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                locked()) armLauncher()
            if (locked()) {
                if (e.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) guardClick(e)
                else if (e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) laterDialog()
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
        // 熄屏时什么都不做（小部件也看不见，不用刷新）
        if (!::pm.isInitialized || !pm.isInteractive) { flush(); passPkg = null; return }
        // 正在用受守护的应用时，小部件 1 分钟跟一次；平时 5 分钟一次（内容没变就不重绘）
        val hot = fg?.let { it != packageName && guarded(it) } == true
        if (now - lastWidget > (if (hot) 60_000 else 300_000)) { lastWidget = now; flush(); Widgets.updateAll(this) }
        // 系统用量记录做对照：无障碍事件被系统停掉时（常见于更新后、一夜之后），靠它也能认出前台是谁
        val sys = Usage.update(this)
        val sysApp = Virt.map(sys)
        if (sysApp != null && sysApp != fg && sysApp != packageName && guarded(sysApp) && now - lastBlock > 10_000) {
            if (sysSeen != sysApp) { sysSeen = sysApp; sysSince = now }
            else if (now - sysSince >= 4_000) { Diag.log("无障碍没报，按系统记录认出 $sysApp（原 $fg）"); fg = sysApp; sysSeen = null }
        } else sysSeen = null
        // 无障碍给的前台更实时；系统用量记录有几秒延迟，只在拿不到时兜底，避免挡完又反复挡
        val cur = fg ?: Virt.map(sys)
        if (cur != null && Focus.scholarLocking(this) && reason(cur) == "scholar") { block(cur, "scholar"); return }
        if (cur != null && cur != packageName && guarded(cur)) {
            reason(cur)?.let { block(cur, it); return }
            watch(cur)
        }
        // 系统用量为准，避免重复计时；视频号这类应用内的一块系统分不出来，只能自己计
        if (Usage.grantedCached(this) && fg?.let { Virt.isVirt(it) } != true) { pendMs = 0; pendPkg = null; return }
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
        Diag.log("拦 $pkg $why")
        val now = SystemClock.elapsedRealtime()
        if (now - lastBlock < 1500 || (pkg == lastBlockPkg && now - lastBlock < 5000)) return
        lastBlock = now
        lastBlockPkg = pkg
        if (Virt.isVirt(pkg)) {
            // 只退出视频号，不把整个微信关掉：连按返回直到离开，再盖上提示
            fg = packageName
            var n = 0
            val back = object : Runnable {
                override fun run() {
                    if (Virt.inFinder && n < 8) { n++; performGlobalAction(GLOBAL_ACTION_BACK); h.postDelayed(this, 280) }
                    else try {
                        startActivity(Intent(this@GuardService, BlockActivity::class.java)
                            .putExtra("pkg", pkg).putExtra("why", why)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    } catch (x: Exception) {}
                }
            }
            h.post(back)
            Widgets.updateAll(this)
            passPkg = null
            return
        }
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
        Diag.log("挡住卸载/关闭")
        dismissDialog()
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
        if (adminAdd(root)) return
        val isSwitch = e.className?.toString()?.let { "Switch" in it || "CheckBox" in it || "Toggle" in it } == true
        if (danger || (isSwitch && hasText(root, "无障碍"))) bounce()
    }

    /**
     * 桌面平时不监听界面变化（省电），所以桌面弹出的「卸载留白？」框收不到事件。
     * 在桌面长按过图标（含拖到卸载区）后的 20 秒里，每半秒看一眼有没有这个框，看到就挡。
     */
    private var armUntil = 0L
    private val armScan = object : Runnable {
        override fun run() {
            if (SystemClock.elapsedRealtime() > armUntil) return
            val f = fg
            if (f != null && f != homePkg && "launcher" !in f && !sensitive(f)) { armUntil = 0L; return }
            if (locked()) { lastScan = 0L; guardDialog() }
            h.postDelayed(this, 500)
        }
    }
    private fun armLauncher() {
        if (SystemClock.elapsedRealtime() > armUntil) Diag.log("开始盯桌面弹框")
        val fresh = SystemClock.elapsedRealtime() > armUntil
        armUntil = SystemClock.elapsedRealtime() + 20_000
        if (fresh) { h.removeCallbacks(armScan); h.postDelayed(armScan, 300) }
    }

    /** 先直接点确认框里的「取消」，返回键关不掉的桌面弹框也能关掉。 */
    private fun dismissDialog() {
        try {
            val roots = windows.mapNotNull { it.root } + listOfNotNull(rootInActiveWindow)
            val label = getString(R.string.app_name)
            for (root in roots) {
                if (!hasText(root, label)) continue
                for (w in CANCEL) for (n in root.findAccessibilityNodeInfosByText(w)) {
                    if ((n.text?.toString() ?: "").trim() != w) continue
                    var x: android.view.accessibility.AccessibilityNodeInfo? = n
                    var k = 0
                    while (x != null && !x.isClickable && k < 4) { x = x.parent; k++ }
                    if (x?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true) return
                }
            }
        } catch (x: Exception) {}
    }

    /** 界面内容变化很频繁：合并成 0.4 秒后检查一次，而不是每次都把所有窗口翻一遍。 */
    private var dialogPending = false
    private val dialogCheck = Runnable { dialogPending = false; guardDialog() }
    private fun laterDialog() {
        if (dialogPending) return
        dialogPending = true
        h.postDelayed(dialogCheck, 150)
    }

    /** 把窗口里的字串起来（只用来判断和记录）。 */
    private fun dump(n: android.view.accessibility.AccessibilityNodeInfo?, sb: StringBuilder = StringBuilder(), depth: Int = 0): String {
        if (n == null || depth > 25 || sb.length > 400) return sb.toString()
        n.text?.let { if (it.isNotBlank()) sb.append(it).append(' ') }
        n.contentDescription?.let { if (it.isNotBlank() && it != n.text) sb.append(it).append(' ') }
        for (i in 0 until n.childCount) dump(try { n.getChild(i) } catch (x: Exception) { null }, sb, depth + 1)
        return sb.toString()
    }

    /** 有守护在跑，或者开着卸载锁：都要挡卸载（连点 10 次放行期间除外）。 */
    private fun locked() = (Store.protectActive(this) || Admin.on(this)) && !Unlock.open(this)

    /** 「激活卸载锁」的系统页面：上面也有「卸载」「取消」字样，但这是在加锁，不能挡。 */
    private fun adminAdd(root: android.view.accessibility.AccessibilityNodeInfo) =
        !Admin.on(this) && (hasText(root, "激活此") || hasText(root, "Activate this") || hasText(root, "启用此设备管理") || hasText(root, "设备管理应用") || hasText(root, "安全服务"))

    private var lastHomeDump = 0L
    /** 同一行字里同时有「留白」和卸载 / 删除等字样（图标名字单独一行，不会误挡）。 */
    private fun sameNode(root: android.view.accessibility.AccessibilityNodeInfo, label: String): Boolean {
        val ns = try { root.findAccessibilityNodeInfosByText(label) } catch (x: Exception) { return false }
        val words = DANGER + listOf("删除", "移除")
        return ns.any { n -> val t = "${n.text ?: ""} ${n.contentDescription ?: ""}"; t.trim() != label && words.any { it in t } }
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
        val mark = getString(R.string.sc_focus)
        for (root in roots) {
            val pk = root.packageName?.toString() ?: continue
            if (pk == packageName || pk in IGNORE || "inputmethod" in pk || "keyboard" in pk) continue
            val launcher = pk == homePkg || "launcher" in pk || pk == "com.miui.home"
            // 系统会缓存窗口内容：不刷新就看不到刚弹出来的菜单
            try { root.refresh() } catch (x: Exception) {}
            if (launcher) {
                // 桌面长按留白图标时，弹出的菜单里会有留白自己的快捷方式：看到它就说明正对着留白，菜单和拖去卸载都挡掉
                if (hasText(root, mark)) { Diag.log("桌面弹出了留白的长按菜单，挡回去"); bounce(); return }
                // 桌面自己弹的确认框：「卸载“留白”？」这类一句话里同时有名字和危险字
                if (sameNode(root, label)) { Diag.log("桌面卸载确认框，挡回去：${dump(root).take(160)}"); bounce(); return }
                if (now - lastHomeDump > 3000 && (hasText(root, "卸载") || hasText(root, "应用信息") || hasText(root, label))) {
                    if (hasText(root, label) && !hasText(root, "卸载") && !hasText(root, "应用信息")) {} else
                    { lastHomeDump = now; Diag.log("桌面弹框内容：${dump(root).take(300)}") }
                }
                if (false) { lastHomeDump = now; Diag.log("桌面出现卸载字样：${dump(root).take(300)}") }
                continue
            }
            if (!hasText(root, label)) continue
            if (adminAdd(root)) continue
            val danger = DANGER.any { hasText(root, it) } || hasText(root, "删除") || hasText(root, "移除")
            if (!danger) continue
            // 任何别的应用里（安全中心、设置、应用商店、安装器……）同时出现「留白」和卸载 / 停用 / 清除等字样：一律挡
            Diag.log("挡住 $pk：${dump(root).take(160)}")
            bounce()
            h.postDelayed({ lastScan = 0L; lastGuard = 0L; guardDialog() }, 900)   // 一次没关掉就再来一次
            return
        }
    }
}
