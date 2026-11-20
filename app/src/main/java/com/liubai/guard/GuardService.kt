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
        private val SEP = setOf(',', '，', '、', ' ', '(', '（', ':', '：', ';', '；', '\n')
        /** 桌面上点的是哪个应用图标：图标的字要么就是应用名，要么是「应用名，1 条未读」这种。 */
        fun tapped(said: List<String>, labels: Map<String, String>): String? {
            var best: String? = null; var len = 0
            for (raw in said) {
                val t = raw.trim()
                for ((pkg, l) in labels) {
                    if (l.isBlank() || l.length <= len) continue
                    if (t == l || (t.startsWith(l) && t[l.length] in SEP)) { best = pkg; len = l.length }
                }
            }
            return best
        }
        // 只拦真正会关掉守护的按钮；页面本身随便看
        private val DANGER = listOf("卸载", "停用", "强行停止", "结束运行", "清除数据", "清除全部数据", "清空数据", "取消激活",
            "Uninstall", "Force stop", "Clear data", "Clear storage", "Disable", "Deactivate",
            "Désinstaller", "Forcer l'arrêt", "Effacer les données", "Vider l'espace", "Désactiver",
            "Deinstallieren", "erzwingen", "Daten löschen", "Speicher leeren", "Deaktivieren",
            "Desinstalar", "Forzar", "Borrar datos", "Borrar almacenamiento", "Desactivar",
            "アンインストール", "強制停止", "データを削除", "ストレージを消去", "無効にする", "無効化",
            "제거", "강제 중지", "데이터 삭제", "저장공간 지우기", "사용 안함", "비활성화")
        // 删除、移除这类字太常见，只在确认是留白的页面里才算
        private val REMOVE = listOf("删除", "移除", "Delete", "Remove", "Supprimer", "Löschen", "Entfernen", "Eliminar", "Quitar", "削除", "삭제")
        private val FRIENDLY = listOf("自启动", "自动启动", "关联启动", "后台", "省电", "电池", "耗电", "通知", "悬浮", "锁屏显示", "弹出界面", "权限",
            "autostart", "auto-start", "auto start", "battery", "background", "notification", "pop-up", "permission",
            "démarrage auto", "batterie", "arrière-plan", "autostart", "akku", "hintergrund", "benachrichtigung",
            "inicio automático", "batería", "segundo plano", "notificaci", "自動起動", "バッテリー", "バックグラウンド", "通知",
            "자동 실행", "배터리", "백그라운드", "알림")
        private val A11Y = listOf("无障碍", "Accessibility", "Accessibilité", "Bedienungshilfen", "Accesibilidad", "ユーザー補助", "접근성")
        private val CANCEL = listOf("取消", "Cancel", "Annuler", "Abbrechen", "Cancelar", "キャンセル", "취소")
        // 桌面长按菜单里一定会有的系统项：和留白的快捷方式同时出现才算长按了留白
        private val MENU = listOf("卸载", "应用信息", "分享", "编辑", "移除", "删除", "App info", "Uninstall", "Infos sur l'appli", "Désinstaller",
            "App-Info", "Deinstallieren", "Info. de la app", "Información de la aplicación", "Desinstalar", "アプリ情報", "アンインストール", "앱 정보", "제거")
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
        return (f != null && f != packageName && guarded(f)) || Focus.scholarLocking(this) || Scenes.strictNow(this)
    }
    private var quick = false
    private fun nextDelay(): Long {
        if (quick) { quick = false; return 4_500 }
        if (!::pm.isInitialized || !pm.isInteractive) {
            // 番茄钟在走：熄屏也要准时提醒，按剩余时间来排
            return if (Focus.running(this) && Focus.pausedLeft(this) < 0) Focus.leftMs(this).coerceIn(1_000, 60_000) else 60_000
        }
        return if (hot()) 2_000 else 15_000
    }
    /** 马上检查一次，并按新的节奏排下一次。 */
    private fun kick() { h.removeCallbacks(tick); h.post(tick) }
    private val screen = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, i: Intent?) {
            if (i?.action == Intent.ACTION_USER_PRESENT) Hist.add(this@GuardService, "pick", 1)
            kick()
        }
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

    /** 屏幕上面积最大的应用窗口（不算系统栏、输入法、悬浮小窗）。 */
    private fun bigApp(): String? = try {
        val r = android.graphics.Rect()
        windows.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { w -> w.getBoundsInScreen(r); val p = w.root?.packageName?.toString(); if (p == null || p in IGNORE) null else p to r.width().toLong() * r.height() }
            .maxByOrNull { it.second }?.first
    } catch (x: Exception) { null }

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
            nm.createNotificationChannel(android.app.NotificationChannel("keep", tr("守护常驻"), android.app.NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
            val pi = android.app.PendingIntent.getActivity(this, 8, Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), android.app.PendingIntent.FLAG_IMMUTABLE)
            val n = androidx.core.app.NotificationCompat.Builder(this, "keep").setSmallIcon(R.drawable.ic_mono)
                .setContentTitle(tr("留白守护中")).setContentText(tr("锁屏后也会一直在")).setOngoing(true)
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
        clearFloats()
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
        // 在桌面点了一个已经到点的应用：不等它打开，马上挡住（不让它闪出画面）
        if (e.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && evPkg != null && (evPkg == homePkg || "launcher" in evPkg || evPkg == "com.miui.home")) preempt(e)
        if (WAdb.status == "waiting" && evPkg != null && "settings" in evPkg) {
            WAdb.scan(this, try { windows.map { it.root } + rootInActiveWindow } catch (x: Exception) { listOf(rootInActiveWindow) })
        }
        if (st || e.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            // 「窗口列表变化」事件很频繁（输入法、悬浮窗都会触发）：节流，少跨进程取窗口树
            val nowW = SystemClock.elapsedRealtime()
            val skip = !st && nowW - lastWin < 700
            if (!skip) {
            lastWin = nowW
            val act = try { rootInActiveWindow?.packageName?.toString() } catch (x: Exception) { null } ?: evPkg
            // 小窗、悬浮便签之类可能抢走「活动窗口」：铺满屏幕的那个应用才是真正在用的
            val big = bigApp()
            // 留白（或别的应用）开成小窗时，底下铺满的那个应用该挡也要挡（以前只看活动窗口，小窗一开就能绕过去）
            val bigWhy = if (big != null && big != act && big != packageName && big != homePkg) reason(Virt.map(big) ?: big) else null
            val pkg = Virt.map(if (big != null && big != act && (bigWhy != null || guarded(big) && (act == null || !guarded(act)))) big else act)
            val behind = pkg != null && pkg != Virt.map(act)
            if (pkg != null && pkg !in IGNORE && pkg != fg) {
                flush()
                if (fg != null && fg != packageName && guarded(fg!!)) { lastWidget = 0L }  // 离开受守护应用，马上刷新一次
                if (pkg == homePkg || (pkg != passPkg && pkg != packageName && guarded(pkg))) passPkg = null
                val wasHot = hot()
                fg = pkg
                if (guarded(pkg)) Diag.log("进入 $pkg（活动窗口 $act）")
                if (pkg != packageName) { if (guarded(pkg)) enter(pkg) else inOpen = null }
                if (hot() != wasHot) kick()
            }
            fg?.let { p -> reason(p)?.let { block(p, it, behind = behind && p == pkg) } }
            floatScan()
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

    private var lblCache: Map<String, String>? = null
    private var lblAt = 0L
    private fun guardedLabels(): Map<String, String> {
        val now = SystemClock.elapsedRealtime()
        lblCache?.let { if (now - lblAt < 60_000) return it }
        val ps = Store.active(this).keys + Store.groupsActive(this).flatMap { it.pkgs }
        return ps.filter { !Virt.isVirt(it) }.associateWith { Apps.info(this, it).label }.also { lblCache = it; lblAt = now }
    }

    private fun preempt(e: AccessibilityEvent) {
        val said = e.text.map { it.toString() } + listOfNotNull(e.contentDescription?.toString()) +
            (try { e.source?.let { listOfNotNull(it.text?.toString(), it.contentDescription?.toString()) } } catch (x: Exception) { null } ?: emptyList())
        val labels = guardedLabels()
        if (labels.isEmpty()) return
        val pkg = tapped(said, labels) ?: run { Diag.log("桌面点击「${said.joinToString("|").take(40)}」"); return }
        val why = reason(pkg) ?: return
        Diag.log("桌面点了 $pkg，已到点（$why），直接挡")
        block(pkg, why, early = true)
    }

    private fun guarded(pkg: String): Boolean =
        Store.active(this)[pkg] != null || Store.groupsActive(this).any { pkg in it.pkgs }

    private fun used(pkg: String): Long = Store.usedMs(this, pkg) + (if (pendPkg == pkg) pendMs else 0L)

    /** 现在该不该挡住它；返回原因，null 表示放行。 */
    private var instCache: Set<String>? = null
    private fun installers(): Set<String> = instCache ?: Updater.installerPkgs(this).also { instCache = it }
    /** 正在更新留白：安装器和授权页一律放行，装完专注照常继续。 */
    private fun updatePass(pkg: String) = Updater.updating(this) && (pkg in installers() || "packageinstaller" in pkg)

    private fun reason(pkg: String): String? {
        if (pkg == packageName) return null
        if (updatePass(pkg)) return null
        // 系统设置、GMS 等系统服务：专注和所有课程时段都不拦
        val sys = Focus.systemService(this, pkg)
        if (!sys && Focus.scholarLocking(this) && pkg !in Focus.whitelist(this) && pkg !in scholarEss()) return "scholar"
        // 重要的课：和无敌模式一样，只开放白名单
        if (!sys && Scenes.strictNow(this) && pkg !in Focus.whitelist(this) && pkg !in scholarEss() && pkg != homePkg) return "class"
        val lim = Store.active(this)[pkg]
        val groups = Store.groupsActive(this).filter { pkg in it.pkgs }
        if (lim == null && groups.isEmpty()) return null
        if (!sys && Focus.locking(this)) return "focus"
        // 紧急用一下：这几分钟放行（专注、重要课不放）
        if (System.currentTimeMillis() < Store.sosUntil(this, pkg)) return null
        if (!sys && Scenes.lockNow(this)) return "scene"
        val m = Rules.nowMin()
        val bed = Store.bedActive(this)
        if (bed.on && Rules.inWin(m, bed.from, bed.to)) return "bed"
        if (lim != null) {
            val x = Store.extraActive(this, pkg)
            if (x.mode == 3) return "hard"
            if (x.mode == 1 && !Rules.inWin(m, x.from, x.to)) return "window"
            if (x.mode == 2 && System.currentTimeMillis() < Store.coolUntil(this, pkg)) return "cool"
            if (used(pkg) >= lim * 60_000L) return "daily"
            if (x.opens > 0 && pkg != inOpen && Store.opens(this, pkg) >= x.opens) return "opens"
        }
        for (g in groups) if (g.pkgs.sumOf { used(it) } >= g.min * 60_000L) return "group:${g.id}"
        return null
    }

    /** 这次打开前要不要先停一下；停了返回 true（这次还不算打开）。 */
    private fun maybePause(pkg: String): Boolean {
        if (pkg == passPkg || reason(pkg) != null || !guarded(pkg)) return false
        val x = Store.extraActive(this, pkg)
        val light = Scenes.lightNow(this) && !Focus.systemService(this, pkg)   // 水课：每次打开都先停一下、问一句（系统服务不停）
        var d = x.delay
        if (x.grow) d += 5 * Store.opens(this, pkg)
        if (light) d = maxOf(d, 10)
        d = d.coerceAtMost(60)
        val ask = x.ask || light
        if (d <= 0 && !ask) return false
        val now = SystemClock.elapsedRealtime()
        if (now - lastPause < 1500) return true
        lastPause = now
        try {
            startActivity(Intent(this, PauseActivity::class.java)
                .putExtra("pkg", pkg).putExtra("sec", d.coerceAtLeast(if (ask) 3 else 1)).putExtra("ask", ask)
                .putExtra("light", light)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        } catch (x: Exception) { return false }
        return true
    }

    /** 进到一个受守护的应用：没被停下来就记一次打开。 */
    private fun enter(pkg: String) {
        if (pkg == packageName) return
        if (pkg != inOpen) inOpen = null
        if (!guarded(pkg) || pkg == inOpen) return
        if (maybePause(pkg)) return
        if (reason(pkg) != null) return
        Store.addOpen(this, pkg)
        inOpen = pkg
        againPkg = pkg; againStart = System.currentTimeMillis(); againLast = againStart
    }

    private var inOpen: String? = null
    private var againPkg: String? = null
    private var againStart = 0L
    private var againLast = 0L

    /** PauseActivity 点了「继续」后调用：本次放行，直到离开它。 */
    fun pass(pkg: String) { passPkg = pkg }

    /** 单次限时与剩 5 分钟提醒。 */
    private fun watch(p: String) {
        val lim = Store.active(this)[p] ?: return
        val x = Store.extraActive(this, p)
        val wall = System.currentTimeMillis()
        if (x.again > 0) {
            if (againPkg != p || wall - againLast > 60_000) { againPkg = p; againStart = wall }
            againLast = wall
            if (wall - againStart >= x.again * 60_000L) {
                againStart = wall
                try {
                    startActivity(Intent(this, PauseActivity::class.java).putExtra("pkg", p).putExtra("sec", 5)
                        .putExtra("again", x.again).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    fg = packageName
                } catch (e: Exception) {}
                return
            }
        }
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
                Toast.makeText(this, tr("{0} 今天还剩 {1} 分钟", Apps.info(this, p).label, (left + 59_999) / 60_000), Toast.LENGTH_LONG).show()
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
        if (hot() || floatViews.isNotEmpty()) floatScan()
        // 正在用受守护的应用时，小部件 1 分钟跟一次；平时 5 分钟一次（内容没变就不重绘）
        val hot = fg?.let { it != packageName && guarded(it) } == true
        if (now - lastWidget > (if (hot) 60_000 else 300_000)) { lastWidget = now; flush(); Widgets.updateAll(this) }
        // 系统用量记录做对照：无障碍事件被系统停掉时（常见于更新后、一夜之后），靠它也能认出前台是谁
        val sys = Usage.update(this)
        val sysApp = Virt.map(sys)
        if (sysApp != null && sysApp != fg && sysApp != packageName && guarded(sysApp) && now - lastBlock > 10_000) {
            if (sysSeen != sysApp) { sysSeen = sysApp; sysSince = now; quick = true }
            else if (now - sysSince >= 4_000) { Diag.log("无障碍没报，按系统记录认出 $sysApp（原 $fg）"); flush(); fg = sysApp; sysSeen = null; enter(sysApp) }
        } else sysSeen = null
        // 无障碍给的前台更实时；系统用量记录有几秒延迟，只在拿不到时兜底，避免挡完又反复挡
        val cur = fg ?: Virt.map(sys)
        HushWatch.check(this)
        if (cur != null && (Focus.scholarLocking(this) || Scenes.strictNow(this))) reason(cur)?.let { r -> if (r == "scholar" || r == "class") { block(cur, r); return } }
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

    private fun block(pkg: String, why: String, early: Boolean = false, behind: Boolean = false) {
        flush()
        Diag.log("拦 $pkg $why")
        val now = SystemClock.elapsedRealtime()
        // 只合并同一瞬间的重复事件；挡完再点开，立刻再挡（以前 5 秒内再打开会漏掉，能看好几秒）
        if (now - lastBlock < 600) return
        lastBlock = now
        lastBlockPkg = pkg
        cover(why == "focus" || why == "scholar" || (why == "class" && Focus.phase(this) == Focus.WORK))
        // 连点：挡完一两秒内它又冒出来（桌面点得快、动画还没走完），再挡一次
        for (d in longArrayOf(700, 1600)) h.postDelayed({ if (fg == pkg) reason(pkg)?.let { block(pkg, it) } }, d)
        if (Virt.isVirt(pkg)) {
            // 只退出视频号，不把整个微信关掉：连按返回直到离开，再盖上提示
            fg = packageName
            var n = 0
            val back = object : Runnable {
                override fun run() {
                    if (Virt.inSec(pkg) && n < 8) { n++; performGlobalAction(GLOBAL_ACTION_BACK); h.postDelayed(this, 280) }
                    else if (why == "focus" || why == "scholar") toFocus()
                    else try {
                        startActivity(Intent(this@GuardService, BlockActivity::class.java)
                            .putExtra("pkg", pkg).putExtra("why", why)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
                    } catch (x: Exception) {}
                }
            }
            h.post(back)
            Widgets.updateAll(this)
            passPkg = null
            return
        }
        if (why == "focus" || why == "scholar" || (why == "class" && Focus.phase(this) == Focus.WORK)) {
            // 专注中：不回桌面，直接回到留白的专注界面
            Widgets.updateAll(this)
            // 要挡的应用在底下铺满、留白开在小窗里：先回桌面把它收起来，不然打开专注页它还在底下
            if (behind) { performGlobalAction(GLOBAL_ACTION_HOME); h.postDelayed({ toFocus() }, 350) } else toFocus()
            fg = packageName
            passPkg = null
            return
        }
        if (!early) performGlobalAction(GLOBAL_ACTION_HOME)
        Widgets.updateAll(this)
        // 挡完把它的后台进程清掉：再点开只能冷启动，画面还没出来就会被挡
        h.postDelayed({ try { (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).killBackgroundProcesses(pkg) } catch (x: Exception) {} }, 800)
        try {
            startActivity(Intent(this, BlockActivity::class.java)
                .putExtra("pkg", pkg).putExtra("why", why)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        } catch (x: Exception) {
            Toast.makeText(this, tr("今天的时间用完啦，明天 0:00 见"), Toast.LENGTH_SHORT).show()
        }
        fg = packageName   // 挡住后前台就是留白自己，等无障碍事件更新，不再用延迟的系统记录反复判断
        passPkg = null
    }

    /**
     * 先盖一层全屏遮罩（无障碍自己的窗口，不用等活动启动，几乎是立刻出现），
     * 挡住应用的画面，等提示页出来再撤掉。
     */
    private var coverView: android.view.View? = null
    private val uncoverLater = Runnable { uncover() }
    private fun cover(toFocusPage: Boolean = false) {
        try {
            // 遮罩画成接下来那一页的样子（提示页的渐变 / 专注页的米色），页面出来后淡出，看起来就是直接到了提示页
            val bg: android.graphics.drawable.Drawable = if (toFocusPage) android.graphics.drawable.ColorDrawable(0xFFFBF6EF.toInt())
                else (getDrawable(R.drawable.block_bg) ?: android.graphics.drawable.ColorDrawable(0xFFF8E6D8.toInt()))
            val old = coverView
            if (old != null) { old.animate().cancel(); old.alpha = 1f; old.background = bg }
            else {
                val v = android.view.View(this).apply { background = bg }
                val lp = android.view.WindowManager.LayoutParams(
                    android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    android.graphics.PixelFormat.TRANSLUCENT).apply { windowAnimations = 0 }
                (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).addView(v, lp)
                coverView = v
            }
            h.removeCallbacks(uncoverLater)
            h.postDelayed(uncoverLater, 2500)   // 兜底：提示页没出来也不会一直盖着
        } catch (x: Exception) {}
    }
    /** 提示页 / 专注页已经画出第一帧：遮罩淡出再撤掉（不是一下子消失）。 */
    fun uncover() {
        h.removeCallbacks(uncoverLater)
        val v = coverView ?: return
        coverView = null
        val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        try {
            v.animate().alpha(0f).setDuration(160).withEndAction { try { wm.removeView(v) } catch (x: Exception) {} }.start()
        } catch (x: Exception) { try { wm.removeView(v) } catch (y: Exception) {} }
    }
    /** 页面这一帧画完之后再撤遮罩：在 onResume 时页面还没画出来，马上撤会先露出一下应用。 */
    fun uncoverAfterDraw(a: android.app.Activity) {
        if (coverView == null) return
        val d = a.window?.decorView ?: run { uncover(); return }
        val once = object : android.view.ViewTreeObserver.OnDrawListener {
            var done = false
            override fun onDraw() {
                if (done) return
                done = true
                d.post { try { d.viewTreeObserver.removeOnDrawListener(this) } catch (x: Exception) {}; h.postDelayed({ uncover() }, 32) }
            }
        }
        try { d.viewTreeObserver.addOnDrawListener(once); d.invalidate() } catch (x: Exception) { uncover() }
    }

    /**
     * 小窗 / 分屏 / 悬浮窗 / 画中画里的应用：到点了也要挡。系统不让别的应用关掉别人的小窗，
     * 所以在那个小窗的位置盖一块不透明的板（无障碍自己的窗口，在所有应用之上），点不到、看不到；
     * 再抢一下音频焦点，让它的声音停下来。小窗关掉或者不该挡了就撤掉。
     */
    private val floatViews = HashMap<Int, android.view.View>()
    private var floatAudio: Any? = null
    private fun floatScan() {
        val wins = try {
            windows.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }.mapNotNull { w ->
                val r = android.graphics.Rect(); w.getBoundsInScreen(r)
                val p = w.root?.packageName?.toString() ?: return@mapNotNull null
                Triple(w.id, p, r)
            }
        } catch (x: Exception) { return }
        val dm = resources.displayMetrics
        val screenArea = dm.widthPixels.toLong() * dm.heightPixels
        val want = HashMap<Int, Pair<android.graphics.Rect, String>>()
        for ((id, p0, r) in wins) {
            val p = Virt.map(p0) ?: continue
            if (p == packageName || p0 == packageName || p in IGNORE || p == homePkg || "launcher" in p) continue
            if (r.width() <= 0 || r.height() <= 0) continue
            // 铺满屏幕的交给上面的正常拦截；这里只管小窗
            if (r.width().toLong() * r.height() >= screenArea * 8 / 10) continue
            reason(p) ?: continue
            want[id] = r to p
        }
        val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        for (id in floatViews.keys - want.keys) { try { wm.removeView(floatViews[id]) } catch (x: Exception) {}; floatViews.remove(id) }
        for ((id, v) in want) {
            val (r, p) = v
            val lp = android.view.WindowManager.LayoutParams(r.width(), r.height(),
                android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.OPAQUE).apply { gravity = android.view.Gravity.TOP or android.view.Gravity.START; x = r.left; y = r.top }
            val old = floatViews[id]
            try {
                if (old != null) wm.updateViewLayout(old, lp) else {
                    val tv = android.widget.TextView(this).apply {
                        text = tr("{0} 已到点，这一块先盖住了", try { Apps.info(this@GuardService, p).label } catch (x: Exception) { p })
                        setTextColor(0xFF8A5A44.toInt()); textSize = 14f; gravity = android.view.Gravity.CENTER
                        setBackgroundColor(0xFFF8E6D8.toInt()); setPadding(24, 24, 24, 24)
                        setOnTouchListener { _, _ -> true }
                    }
                    wm.addView(tv, lp); floatViews[id] = tv
                    Diag.log("小窗里的 $p 已到点，盖住（${r.width()}x${r.height()}）")
                }
            } catch (x: Exception) { Diag.log("小窗盖不住：${x.message}") }
        }
        holdAudio(floatViews.isNotEmpty())
    }
    private fun holdAudio(on: Boolean) {
        try {
            val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            if (on && floatAudio == null && android.os.Build.VERSION.SDK_INT >= 26) {
                val req = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setOnAudioFocusChangeListener { }.build()
                am.requestAudioFocus(req); floatAudio = req
            } else if (!on && floatAudio != null && android.os.Build.VERSION.SDK_INT >= 26) {
                am.abandonAudioFocusRequest(floatAudio as android.media.AudioFocusRequest); floatAudio = null
            }
        } catch (x: Exception) {}
    }
    private fun clearFloats() {
        val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        for (v in floatViews.values) try { wm.removeView(v) } catch (x: Exception) {}
        floatViews.clear(); holdAudio(false)
    }

    /** 打开留白的专注界面；万一打不开，退回桌面兜底。 */
    private fun toFocus() {
        val m = Focus.leftMs(this) / 60_000L + 1
        try {
            startActivity(Intent(this, MainActivity::class.java).putExtra("open", "focus")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            Toast.makeText(this, tr("正在专注，还剩 {0} 分钟", m), Toast.LENGTH_SHORT).show()
        } catch (x: Exception) {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun hasText(root: android.view.accessibility.AccessibilityNodeInfo, w: String) =
        try { root.findAccessibilityNodeInfosByText(w).isNotEmpty() } catch (x: Exception) { false }

    private fun bounce() {
        if (fg?.let { updatePass(it) } == true) return
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
        val said = (e.text.joinToString(" ") + " " + (e.contentDescription ?: "") + " " + (try { e.source?.let { "${it.text ?: ""} ${it.contentDescription ?: ""}" } ?: "" } catch (x: Exception) { "" }))
        val low = said.lowercase()
        // 开自启动、电池不限制、后台运行、通知这些是帮留白活得更稳的设置，从来不挡
        if (FRIENDLY.any { it.lowercase() in low }) return
        val danger = DANGER.any { it.lowercase() in low } || REMOVE.any { it.lowercase() in low }
        // 桌面：刚长按过留白，再点卸载 / 垃圾桶
        if ((e.packageName?.toString() == homePkg || "launcher" in (e.packageName?.toString() ?: "")) && longSelf && SystemClock.elapsedRealtime() - longAt < 15_000 && danger) { bounce(); return }
        val root = try { rootInActiveWindow } catch (x: Exception) { null } ?: return
        val label = getString(R.string.app_name)
        if (!hasText(root, label)) return
        if (adminAdd(root)) return
        val isSwitch = e.className?.toString()?.let { "Switch" in it || "CheckBox" in it || "Toggle" in it } == true
        if (danger || (isSwitch && A11Y.any { hasText(root, it) })) bounce()
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
        !Admin.on(this) && (hasText(root, "激活此") || hasText(root, "Activate this") || hasText(root, "Activer") || hasText(root, "aktivieren") || hasText(root, "Activar") || hasText(root, "有効にする") || hasText(root, "활성화") || hasText(root, "启用此设备管理") || hasText(root, "设备管理应用") || hasText(root, "安全服务"))

    private var lastHomeDump = 0L
    /** 同一行字里同时有「留白」和卸载 / 删除等字样（图标名字单独一行，不会误挡）。 */
    private fun sameNode(root: android.view.accessibility.AccessibilityNodeInfo, label: String): Boolean {
        val ns = try { root.findAccessibilityNodeInfosByText(label) } catch (x: Exception) { return false }
        val words = DANGER + REMOVE
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
                if (hasText(root, mark) && MENU.any { hasText(root, it) }) { Diag.log("桌面弹出了留白的长按菜单，挡回去：${dump(root).take(160)}"); bounce(); return }
                // 桌面自己弹的确认框：「卸载“留白”？」这类一句话里同时有名字和危险字
                if (sameNode(root, label)) { Diag.log("桌面卸载确认框，挡回去：${dump(root).take(160)}"); bounce(); return }
                if (now - lastHomeDump > 3000 && (hasText(root, "卸载") || hasText(root, "应用信息") || hasText(root, label))) {
                    if (hasText(root, label) && !hasText(root, "卸载") && !hasText(root, "应用信息")) {} else
                    { lastHomeDump = now; Diag.log("桌面弹框内容：${dump(root).take(300)}") }
                }
                if (false) { lastHomeDump = now; Diag.log("桌面出现卸载字样：${dump(root).take(300)}") }
                continue
            }
            // 只看系统的设置 / 安全中心 / 安装器 / 应用商店；聊天、浏览器里出现这些字不算
            val store = "market" in pk || "appstore" in pk || "vending" in pk
            if (!sensitive(pk) && !store) continue
            // 应用商店：搜「留白」、详情页的「卸载」按钮都不算，只挡「确定卸载留白？」这种一句话的确认框
            if (store && !sensitive(pk)) { if (sameNode(root, label) && CANCEL.any { hasText(root, it) }) { Diag.log("挡住 $pk：${dump(root).take(160)}"); bounce(); return }; continue }
            if (!hasText(root, label)) continue
            if (adminAdd(root)) continue
            val danger = DANGER.any { hasText(root, it) } || REMOVE.any { hasText(root, it) }
            if (!danger) continue
            // 应用信息页本身就写着「卸载」「结束运行」：只看不挡。真要卸载、停止、清数据，系统会弹带「取消」的确认框，那时再挡
            if (!CANCEL.any { hasText(root, it) }) continue
            // 任何别的应用里（安全中心、设置、应用商店、安装器……）同时出现「留白」和卸载 / 停用 / 清除等字样：一律挡
            Diag.log("挡住 $pk：${dump(root).take(160)}")
            bounce()
            h.postDelayed({ lastScan = 0L; lastGuard = 0L; guardDialog() }, 900)   // 一次没关掉就再来一次
            return
        }
    }
}
