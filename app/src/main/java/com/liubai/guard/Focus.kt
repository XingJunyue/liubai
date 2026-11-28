package com.liubai.guard

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import kotlinx.coroutines.delay
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/** 番茄钟：状态全在 SharedPreferences 里，关掉应用也照样走；守护服务负责到点提醒。 */
object Focus {
    const val IDLE = 0; const val WORK = 1; const val SHORT = 2; const val LONG = 3
    val NAMES = mapOf(WORK to tr("专注"), SHORT to tr("短休"), LONG to tr("长休"))
    private fun p(c: Context) = c.getSharedPreferences("focus", Context.MODE_PRIVATE)

    fun phase(c: Context) = p(c).getInt("phase", IDLE)
    fun endAt(c: Context) = p(c).getLong("end", 0L)
    fun pausedLeft(c: Context) = p(c).getLong("paused", -1L)
    fun running(c: Context) = phase(c) != IDLE && pausedLeft(c) < 0
    fun round(c: Context) = p(c).getInt("round", 0)            // 旧版：本轮已完成的专注数

    // ---------- 自定义流程：专注和休息的顺序 + 循环次数 ----------
    const val DEFAULT_SEQ = "12121213"
    /** 顺序，比如 [专注, 短休, 专注, 长休]。 */
    fun seq(c: Context): List<Int> = (p(c).getString("seq", DEFAULT_SEQ) ?: DEFAULT_SEQ).mapNotNull { it.digitToIntOrNull() }.filter { it in 1..3 }.ifEmpty { listOf(WORK) }
    fun setSeq(c: Context, l: List<Int>) {
        val v = l.filter { it in 1..3 }.ifEmpty { listOf(WORK) }
        p(c).edit().putString("seq", v.joinToString("")).putInt("pos", 0).putInt("loop", 0)
            .apply { if (phase(c) == IDLE) putInt("next", v[0]) }.apply()
    }
    /** 流程走到第几段（下一段 / 正在进行的那段）。 */
    fun pos(c: Context) = p(c).getInt("pos", 0).let { if (it in seq(c).indices) it else 0 }
    /** 整个流程已经完整走完几遍。 */
    fun loop(c: Context) = p(c).getInt("loop", 0)
    /** 循环几遍后停下；0 = 一直循环。 */
    fun loops(c: Context) = p(c).getInt("loops", 0)
    fun setLoops(c: Context, n: Int) = p(c).edit().putInt("loops", n.coerceAtLeast(0)).apply()
    fun next(c: Context) = p(c).getInt("next", WORK)            // 空闲时建议的下一段
    fun len(c: Context, ph: Int) = when (ph) { SHORT -> p(c).getInt("ls", 5); LONG -> p(c).getInt("ll", 15); else -> p(c).getInt("lw", 25) }
    fun setLen(c: Context, ph: Int, m: Int) = p(c).edit().putInt(when (ph) { SHORT -> "ls"; LONG -> "ll"; else -> "lw" }, m).apply()
    fun lockApps(c: Context) = p(c).getBoolean("lock", true)
    fun setLockApps(c: Context, v: Boolean) = p(c).edit().putBoolean("lock", v).apply()
    fun autoNext(c: Context) = p(c).getBoolean("auto", false)
    fun setAutoNext(c: Context, v: Boolean) = p(c).edit().putBoolean("auto", v).apply()

    private fun dayFix(c: Context) {
        val t = Store.today()
        if (p(c).getString("day", "") != t) p(c).edit().putString("day", t).putInt("cnt", 0).putLong("ms", 0L).apply()
    }
    fun todayCount(c: Context): Int { dayFix(c); return p(c).getInt("cnt", 0) }
    /** 今天专注的分钟数：已记下的 + 正在进行这一段已走过的。 */
    fun todayMin(c: Context): Int {
        dayFix(c)
        val live = if (phase(c) == WORK) (totalMs(c) - leftMs(c)).coerceAtLeast(0) else 0L
        return ((p(c).getLong("ms", 0L) + live) / 60_000L).toInt()
    }
    private fun addFocused(c: Context, ms: Long) {
        if (ms <= 0) return
        dayFix(c); p(c).edit().putLong("ms", p(c).getLong("ms", 0L) + ms).apply()
        Hist.add(c, "focus", ms)
        Tasks.addFocus(c, ms)
    }

    fun leftMs(c: Context): Long = when {
        phase(c) == IDLE -> len(c, next(c)) * 60_000L
        pausedLeft(c) >= 0 -> pausedLeft(c)
        else -> (endAt(c) - System.currentTimeMillis()).coerceAtLeast(0)
    }
    fun totalMs(c: Context): Long = len(c, if (phase(c) == IDLE) next(c) else phase(c)) * 60_000L

    fun scholar(c: Context) = p(c).getBoolean("scholar", false)
    fun setScholar(c: Context, v: Boolean) = p(c).edit().putBoolean("scholar", v).apply()
    fun whitelist(c: Context): Set<String> = p(c).getStringSet("wl", emptySet())!!.toSet()
    fun setWhitelist(c: Context, s: Set<String>) = p(c).edit().putStringSet("wl", s).apply()
    /** 无敌模式生效中：专注阶段（含暂停）只放行白名单。 */
    fun scholarLocking(c: Context) = phase(c) == WORK && scholar(c)

    /** 永远放行的系统必需品：桌面、电话、短信、输入法、权限弹窗等。 */
    fun essentials(c: Context): Set<String> {
        val s = mutableSetOf(c.packageName, "android", "com.android.systemui", "com.android.phone", "com.android.incallui",
            "com.android.server.telecom", "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.android.packageinstaller", "com.google.android.packageinstaller", "com.android.emergency", "com.android.contacts", "com.android.mms")
        try {
            c.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName?.let { s += it }
            c.getSystemService(android.telecom.TelecomManager::class.java)?.defaultDialerPackage?.let { s += it }
            android.provider.Telephony.Sms.getDefaultSmsPackage(c)?.let { s += it }
            android.provider.Settings.Secure.getString(c.contentResolver, "enabled_input_methods")
                ?.split(':')?.forEach { s += it.substringBefore('/') }
        } catch (x: Exception) {}
        return s
    }

    /** 系统设置、谷歌 / 华为移动服务这类系统服务：专注、无敌模式、各种课程时段都不拦（防卸载的保护照旧）。 */
    val SYS_PKGS = setOf("com.android.settings", "com.android.settings.intelligence", "com.google.android.settings.intelligence",
        "com.google.android.gms", "com.google.android.gsf", "com.google.android.gms.policy_sidecar_aps", "com.google.android.ext.services",
        "com.google.android.webview", "com.android.webview", "com.android.providers.downloads.ui", "com.android.documentsui",
        "com.google.android.documentsui", "com.android.vpndialogs", "com.android.captiveportallogin", "com.google.android.captiveportallogin",
        "com.android.bluetooth", "com.android.nfc", "com.android.printspooler", "com.android.keychain", "com.android.certinstaller",
        "com.android.wallpaper.livepicker", "com.android.managedprovisioning", "com.google.android.apps.restore", "com.google.android.setupwizard",
        "com.android.provision", "com.android.credentialmanager", "com.android.shell",
        // 选图 / 选文件 / 分享面板：白名单应用里上传图片、发文件时会跳到这些系统组件
        "com.android.photopicker", "com.google.android.photopicker", "com.android.providers.media", "com.android.providers.media.module",
        "com.google.android.providers.media.module", "com.android.intentresolver", "com.google.android.intentresolver",
        "com.android.htmlviewer", "com.android.mtp", "com.android.externalstorage",
        // 华为 / 荣耀：HMS Core、账号、设置
        "com.huawei.hwid", "com.huawei.android.hwouc", "com.hihonor.id", "com.huawei.systemmanager", "com.hihonor.systemmanager",
        // 小米、OPPO、vivo、三星等的设置与系统服务
        "com.xiaomi.account", "com.miui.securitycenter", "com.miui.securitycore", "com.miui.permcenter", "com.miui.securityadd",
        "com.xiaomi.xmsf", "com.miui.powerkeeper", "com.oplus.battery", "com.coloros.safecenter", "com.oplus.safecenter",
        "com.coloros.securitypermission", "com.oplus.appdetail", "com.iqoo.secure", "com.vivo.permissionmanager",
        "com.samsung.android.lool", "com.samsung.android.sm.devicesecurity", "com.samsung.android.app.settings.bixby",
        "com.meizu.safe", "com.zui.safecenter")
    private val SYS_HINTS = listOf(".settings", "settings.", "permissioncontroller", "packageinstaller", "photopicker", "providers.media", "intentresolver", "documentsui")
    /** 看包名是否在系统服务名单里，或者带设置 / 权限 / 安装器字样（纯函数，单测）。 */
    fun sysByName(pkg: String) = pkg in SYS_PKGS || pkg.startsWith("com.google.android.gms") || SYS_HINTS.any { it in pkg }
    /** 系统自带、桌面上没有图标的后台组件（账号选择、授权弹窗等）也算系统服务；桌面有图标的系统应用（浏览器、相册、应用商店）不算。 */
    fun sysByFlags(system: Boolean, launchable: Boolean) = system && !launchable
    private val sysCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    fun systemService(c: Context, pkg: String): Boolean {
        if (sysByName(pkg)) return true
        return sysCache.getOrPut(pkg) {
            try {
                val pm = c.packageManager
                val ai = pm.getApplicationInfo(pkg, 0)
                val system = ai.flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                sysByFlags(system, pm.getLaunchIntentForPackage(pkg) != null)
            } catch (x: android.content.pm.PackageManager.NameNotFoundException) {
                // 看不到的包 = 桌面上没图标的系统组件（有图标的应用都在清单 <queries> 里看得到），比如选图器
                true
            } catch (x: Exception) { false }
        }
    }

    /** 系统自带的应用（含桌面有图标的相机、相册、文件管理），或看不到的系统组件。 */
    private val sysAppCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    fun systemApp(c: Context, pkg: String): Boolean {
        if (systemService(c, pkg)) return true
        return sysAppCache.getOrPut(pkg) {
            try {
                val ai = c.packageManager.getApplicationInfo(pkg, 0)
                ai.flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            } catch (x: Exception) { false }
        }
    }
    private val NO_HELPER = listOf("browser", "chrome", "market", "appstore", "vending", "gamecenter", "video", "music", "game")
    /** 从放行的应用直接跳进系统应用（拍照、选图、选文件）就跟着放行；浏览器、应用商店、影音游戏不算（纯函数，单测）。 */
    fun helperPass(prevAllowed: Boolean, prevHome: Boolean, nextSystem: Boolean, next: String) =
        prevAllowed && !prevHome && nextSystem && NO_HELPER.none { it in next.lowercase() }

    /** 专注进行中，且选了「专注时锁住守护中的应用」。 */
    fun locking(c: Context) = phase(c) == WORK && lockApps(c)

    /**
     * 专注锁定 / 上课锁定这一层要不要挡（纯函数，单测）。
     * 无敌模式、重要课时白名单里的应用照常能开（两边同一份白名单）；每日额度、睡前锁定等应用自己的限制照旧。
     */
    fun sessionBlocks(lock: Boolean, strict: Boolean, inWhitelist: Boolean) = lock && !(strict && inWhitelist)

    /**
     * 好梦模式要不要挡（纯函数，单测）。
     * sys 系统服务、essential 必需应用（电话、短信、桌面、输入法等）不挡，其他的在好梦时间里都挡，
     * 不看它在不在守护名单里。白名单和无敌模式那一套不管这里。
     */
    fun goodnightBlocks(sys: Boolean, goodnight: Boolean, essential: Boolean) = goodnight && !sys && !essential

    fun start(c: Context, ph: Int) {
        p(c).edit().putInt("phase", ph).putLong("end", System.currentTimeMillis() + len(c, ph) * 60_000L).putLong("paused", -1L).apply()
    }
    fun pause(c: Context) { if (running(c)) p(c).edit().putLong("paused", leftMs(c)).apply() }
    fun resume(c: Context) {
        val l = pausedLeft(c); if (l < 0) return
        p(c).edit().putLong("end", System.currentTimeMillis() + l).putLong("paused", -1L).apply()
    }
    fun stop(c: Context) {
        if (phase(c) == WORK) addFocused(c, totalMs(c) - leftMs(c))
        p(c).edit().putInt("phase", IDLE).putLong("paused", -1L).putInt("pos", 0).putInt("loop", 0).putInt("next", seq(c)[0]).apply() }
    fun skip(c: Context) { finish(c, counted = false) }
    fun choose(c: Context, ph: Int) { if (phase(c) == IDLE) p(c).edit().putInt("next", ph).apply() }

    private fun finish(c: Context, counted: Boolean) {
        val ph = phase(c)
        val e = p(c).edit()
        if (ph == WORK) {
            addFocused(c, if (counted) totalMs(c) else totalMs(c) - leftMs(c))
            if (counted) { dayFix(c); e.putInt("cnt", p(c).getInt("cnt", 0) + 1) }
        }
        // 按自定义流程走到下一段；手动选的另一种阶段不推进流程
        val sq = seq(c)
        var ps = pos(c)
        var lp = loop(c)
        var allDone = false
        if (sq[ps] == ph) {
            ps += 1
            if (ps >= sq.size) { ps = 0; lp += 1; if (loops(c) in 1..lp) { allDone = true; lp = 0 } }
        }
        val nx = sq[ps]
        e.putInt("phase", IDLE).putInt("next", nx).putInt("pos", ps).putInt("loop", lp).putLong("paused", -1L).apply()
        if (allDone) p(c).edit().putBoolean("allDone", true).apply()
        if (autoNext(c) && !allDone) start(c, nx)
    }

    /** 守护服务和界面都会调用：到点就收尾并提醒。 */
    @Synchronized
    fun tick(c: Context) {
        Tasks.checkPlans(c)
        Noise.sync(c)
        if (!running(c) || System.currentTimeMillis() < endAt(c)) return
        val ph = phase(c)
        finish(c, counted = true)
        Noise.sync(c)
        val all = p(c).getBoolean("allDone", false)
        if (all) p(c).edit().putBoolean("allDone", false).apply()
        notifyDone(c, ph, all)
    }

    /** 预约的番茄自动开始时提醒一下。 */
    fun notifyStart(c: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel("focus", tr("番茄钟"), NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(c, 7, Intent(c, MainActivity::class.java).putExtra("open", "focus")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(77, NotificationCompat.Builder(c, "focus").setSmallIcon(R.drawable.ic_mono)
            .setContentTitle(tr("预约的专注开始了")).setContentText(tr("{0} · {1} 分钟", Tasks.curTag(c), len(c, WORK))).setAutoCancel(true).setContentIntent(pi).build())
    }

    private fun notifyDone(c: Context, ph: Int, all: Boolean = false) {
        try {
            val v = c.getSystemService(Vibrator::class.java)
            v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 120, 120, 120), -1))
        } catch (x: Exception) {}
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel("focus", tr("番茄钟"), NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(c, 7, Intent(c, MainActivity::class.java).putExtra("open", "focus")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)
        val (t, s) = when {
            all -> tr("这一组都完成了") to tr("辛苦了，好好休息一下。")
            ph == WORK -> tr("专注完成") to tr("起来走走，喝口水。")
            else -> tr("休息结束") to tr("准备好了，就开始下一个番茄。")
        }
        nm.notify(77, NotificationCompat.Builder(c, "focus").setSmallIcon(R.drawable.ic_mono)
            .setContentTitle(t).setContentText(s).setAutoCancel(true).setContentIntent(pi).build())
    }
}

private fun mmss(ms: Long): String {
    val s = (ms + 999) / 1000
    return "%02d:%02d".format(s / 60, s % 60)
}

@Composable
fun FocusScreen(onBack: () -> Unit = {}) {
    val c = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    Every(1000) { Focus.tick(c); tick++ }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    val phase = remember(tick) { Focus.phase(c) }
    val running = remember(tick) { Focus.running(c) }
    val left = remember(tick) { Focus.leftMs(c) }
    val total = remember(tick) { Focus.totalMs(c) }
    val shown = if (phase == Focus.IDLE) Focus.next(c) else phase
    val isBreak = shown != Focus.WORK
    val accent by animateColorAsState(if (isBreak) C.Sage else C.Clay, tween(600), label = "a")
    val frac by animateFloatAsState(if (total <= 0) 0f else 1f - left.toFloat() / total, tween(300), label = "f")
    var showSet by remember { mutableStateOf(false) }
    var showWl by remember { mutableStateOf(false) }
    var showNoise by remember { mutableStateOf(false) }
    var showSeq by remember { mutableStateOf(false) }
    var scholar by remember { mutableStateOf(Focus.scholar(c)) }
    // 无敌模式下，专注一旦开始就不能中途暂停、重置或跳过
    val strict = scholar && phase == Focus.WORK
    if (showWl) { WhitelistScreen(onBack = { showWl = false }); return }
    if (showNoise) { NoiseScreen(onBack = { showNoise = false }); return }
    if (showSeq) { SeqScreen(idle = phase == Focus.IDLE, onBack = { showSeq = false; tick++ }); return }
    if (showSet) { FocusSettings(idle = phase == Focus.IDLE, strict = strict, onWhitelist = { showWl = true }, onNoise = { showNoise = true },
        onBack = { showSet = false; scholar = Focus.scholar(c); tick++ }); return }

    fun startNow(ph: Int) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notif.launch(Manifest.permission.POST_NOTIFICATIONS)
        Focus.start(c, ph); tick++
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", fontSize = 26.sp, color = C.Soft, modifier = Modifier.clip(CircleShape).clickable { onBack() }.padding(horizontal = 10.dp))
            Column(Modifier.weight(1f)) {
                Text(tr("专注"), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = C.Ink)
                Text(
                    when {
                        scholar -> tr("无敌模式：只开放白名单")
                        Focus.lockApps(c) -> tr("守护中的应用会暂停开放")
                        else -> tr("一次只做一件事")
                    },
                    fontSize = 12.sp, color = C.Soft, maxLines = 1
                )
            }
            Text(tr("设置"), fontSize = 14.sp, color = C.Ink, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(C.Card).clickable { showSet = true }.padding(horizontal = 14.dp, vertical = 8.dp))
        }

        // 阶段选择：只在空闲时可切换，轻量的文字标签
        Row(Modifier.padding(top = 30.dp), horizontalArrangement = Arrangement.spacedBy(26.dp)) {
            for (ph in listOf(Focus.WORK, Focus.SHORT, Focus.LONG)) {
                val on = ph == shown
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = phase == Focus.IDLE) { Focus.choose(c, ph); tick++ }
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    Text(Focus.NAMES[ph]!!, fontSize = 14.sp, color = if (on) C.Ink else C.Faint,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                    Box(Modifier.padding(top = 6.dp).size(4.dp).clip(CircleShape).background(if (on) accent else Color.Transparent))
                }
            }
        }

        // 主环：细线、圆头，中间大号细体数字。空闲时它是转盘：沿着圆环拖动设定时长，一圈 60 分钟，可多转几圈
        val idle = phase == Focus.IDLE
        var dialMin by remember(shown, idle) { mutableStateOf(Focus.len(c, shown)) }
        val haptic = LocalHapticFeedback.current
        val ringFrac = if (idle) { if (dialMin % 60 == 0) 1f else (dialMin % 60) / 60f } else frac
        Box(Modifier.padding(top = 26.dp).size(272.dp).pointerInput(idle, shown) {
            if (!idle) return@pointerInput
            fun ang(o: Offset): Double {
                val a = Math.toDegrees(Math.atan2((o.y - size.height / 2f).toDouble(), (o.x - size.width / 2f).toDouble())) + 90.0
                return if (a < 0) a + 360.0 else a
            }
            var last = 0.0
            var acc = 0.0
            detectDragGestures(
                onDragStart = { o -> last = ang(o); acc = dialMin * 6.0 },
                onDrag = { ch, _ ->
                    ch.consume()
                    val a = ang(ch.position)
                    var d = a - last
                    if (d > 180) d -= 360.0
                    if (d < -180) d += 360.0
                    last = a
                    acc = (acc + d).coerceIn(6.0, 240 * 6.0)
                    val m = Math.round(acc / 6.0).toInt().coerceIn(1, 240)
                    if (m != dialMin) {
                        dialMin = m; Focus.setLen(c, shown, m)
                        if (m % 5 == 0) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                }
            )
        }, contentAlignment = Alignment.Center) {
            val frac = ringFrac
            Canvas(Modifier.fillMaxSize()) {
                if (idle) { // 转盘刻度：每 5 分钟一格
                    val rr = size.width / 2 - 2.dp.toPx()
                    for (i in 0 until 12) {
                        val t = Math.toRadians(i * 30.0 - 90.0)
                        val cx0 = size.width / 2; val cy0 = size.height / 2
                        drawLine(C.Faint.copy(alpha = 0.6f),
                            Offset(cx0 + (rr * Math.cos(t)).toFloat(), cy0 + (rr * Math.sin(t)).toFloat()),
                            Offset(cx0 + ((rr - 5.dp.toPx()) * Math.cos(t)).toFloat(), cy0 + ((rr - 5.dp.toPx()) * Math.sin(t)).toFloat()),
                            strokeWidth = 1.5.dp.toPx())
                    }
                }
                val sw = 6.dp.toPx()
                val inset = sw / 2 + 10.dp.toPx()
                val sz = Size(size.width - inset * 2, size.height - inset * 2)
                val tl = Offset(inset, inset)
                drawArc(C.Track, 0f, 360f, false, tl, sz, style = Stroke(sw))
                if (frac > 0f) drawArc(accent, -90f, 360f * frac, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
                // 进度端点的小圆点
                val ang = Math.toRadians((-90.0 + 360.0 * frac))
                val r = sz.width / 2
                val cx = size.width / 2 + (r * Math.cos(ang)).toFloat()
                val cy = size.height / 2 + (r * Math.sin(ang)).toFloat()
                if (frac > 0f) drawCircle(Color.White, sw * 0.9f, Offset(cx, cy))
                if (frac > 0f) drawCircle(accent, sw * 0.55f, Offset(cx, cy))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (idle) mmss(dialMin * 60_000L) else mmss(left), fontSize = 60.sp, fontWeight = FontWeight.Light, color = C.Ink,
                    fontFamily = FontFamily.SansSerif, letterSpacing = 1.sp)
                Text(
                    when {
                        phase == Focus.IDLE -> tr("沿圆环拖动调时长")
                        !running -> tr("已暂停")
                        isBreak -> tr("放松一下")
                        else -> tr("正在专注")
                    },
                    fontSize = 13.sp, color = C.Soft, modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        // 流程进度：每一段一个点，专注大、休息小
        val sq = remember(tick) { Focus.seq(c) }
        val ps = remember(tick) { Focus.pos(c) }
        val lps = remember(tick) { Focus.loops(c) }
        val lp = remember(tick) { Focus.loop(c) }
        TaskChip(enabled = !strict)
        FlowRowDots(sq, ps, phase != Focus.IDLE)
        if (lps > 0) Text(tr("第 {0} / {1} 遍", lp + 1, lps), fontSize = 12.sp, color = C.Faint, modifier = Modifier.padding(top = 6.dp))

        // 主按钮 + 两个轻按钮
        Row(Modifier.padding(top = 30.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr("重置"), fontSize = 14.sp, color = if (phase != Focus.IDLE && !strict) C.Soft else C.Faint,
                modifier = Modifier.width(72.dp).clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = phase != Focus.IDLE && !strict) { Focus.stop(c); tick++ }.padding(vertical = 12.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Box(
                Modifier.padding(horizontal = 20.dp).size(76.dp).clip(CircleShape).background(accent)
                    .clickable(enabled = !(strict && running)) {
                        when {
                            phase == Focus.IDLE -> startNow(Focus.next(c))
                            running -> { Focus.pause(c); tick++ }
                            else -> { Focus.resume(c); tick++ }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (strict && running) {
                    Canvas(Modifier.size(22.dp)) { // 小锁
                        val w = size.width; val hh = size.height
                        drawRoundRect(Color.White, Offset(w * 0.12f, hh * 0.45f), Size(w * 0.76f, hh * 0.55f),
                            androidx.compose.ui.geometry.CornerRadius(w * 0.12f))
                        drawArc(Color.White, 180f, 180f, false, Offset(w * 0.27f, hh * 0.08f), Size(w * 0.46f, hh * 0.6f),
                            style = Stroke(w * 0.11f))
                    }
                } else if (running) {
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        repeat(2) { Box(Modifier.width(5.dp).height(22.dp).clip(RoundedCornerShape(3.dp)).background(Color.White)) }
                    }
                } else {
                    Canvas(Modifier.size(24.dp).offset(x = 2.dp)) {
                        val path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(size.width * 0.18f, 0f); lineTo(size.width, size.height / 2); lineTo(size.width * 0.18f, size.height); close()
                        }
                        drawPath(path, Color.White)
                    }
                }
            }
            Text(
                tr("跳过"), fontSize = 14.sp, color = if (phase != Focus.IDLE && !strict) C.Soft else C.Faint,
                modifier = Modifier.width(72.dp).clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = phase != Focus.IDLE && !strict) { Focus.skip(c); tick++ }.padding(vertical = 12.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }

        if (strict) Text(tr("无敌模式下，这个番茄要做完才能停"), fontSize = 12.sp, color = C.Faint, modifier = Modifier.padding(top = 12.dp))

        // 今天：一行
        val cnt = remember(tick / 8) { Focus.todayCount(c) }
        val mins = remember(tick / 4) { Focus.todayMin(c) }
        Text(tr("今天 {0} 个番茄 · {1}", cnt, if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins}m"),
            fontSize = 13.sp, color = C.Soft, modifier = Modifier.padding(top = 26.dp))

        // 无敌模式、白噪音、顺序：放在外面，一眼看到，点进去再改
        val nk = remember(showNoise, tick / 4) { Noise.kind(c) }
        val wl = remember(showWl, tick / 20) { Focus.whitelist(c) }
        Column(Modifier.padding(top = 18.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.Card).padding(horizontal = 18.dp, vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("无敌模式"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = C.Ink)
                    Text(if (strict) tr("这个番茄做完才能停") else tr("除了白名单都打不开"), fontSize = 12.sp, color = C.Faint)
                }
                androidx.compose.material3.Switch(scholar, { scholar = it; Focus.setScholar(c, it) }, enabled = !strict,
                    colors = androidx.compose.material3.SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = C.Clay,
                        uncheckedThumbColor = Color.White, uncheckedTrackColor = C.Track, uncheckedBorderColor = C.Track))
            }
            if (scholar) Row(Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(12.dp)).background(C.Sand)
                .clickable(enabled = !strict) { showWl = true }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("白名单"), fontSize = 14.sp, color = C.Ink, modifier = Modifier.weight(1f))
                Text(if (wl.isEmpty()) tr("还没选") else tr("{0} 个应用", wl.size), fontSize = 13.sp, color = C.Soft)
                Text("  ›", fontSize = 15.sp, color = C.Faint)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.Track.copy(alpha = 0.7f)))
            Row(Modifier.fillMaxWidth().clickable { showNoise = true }.padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("白噪音"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = C.Ink, modifier = Modifier.weight(1f))
                Text(Noise.label(nk), fontSize = 14.sp, color = if (nk == "off") C.Faint else accent, fontWeight = FontWeight.Medium)
                Text("  ›", fontSize = 15.sp, color = C.Faint)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.Track.copy(alpha = 0.7f)))
            Row(Modifier.fillMaxWidth().clickable { showSeq = true }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("顺序"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = C.Ink)
                    Text(if (lps == 0) tr("循环不限") else tr("循环 {0} 遍", lps), fontSize = 12.sp, color = C.Faint)
                }
                MiniPuzzle(sq)
                Text("  ›", fontSize = 15.sp, color = C.Faint)
            }
        }
        Spacer(Modifier.height(36.dp))
    }
}

/** 外面那行：小拼图块排出当前顺序。 */
@Composable
private fun MiniPuzzle(sq: List<Int>) {
    Row(horizontalArrangement = Arrangement.spacedBy(1.dp), verticalAlignment = Alignment.CenterVertically) {
        val shape = remember { PuzzleShape(7f) }
        sq.take(8).forEach { ph ->
            Box(Modifier.size(if (ph == Focus.WORK) 20.dp else 14.dp, 22.dp).clip(shape).background(pieceColor(ph)))
        }
        if (sq.size > 8) Text(" +${sq.size - 8}", fontSize = 11.sp, color = C.Faint)
    }
}

/** 顺序页：拖拼图排专注和休息，下面是循环次数。 */
@Composable
private fun SeqScreen(idle: Boolean, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler { onBack() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", fontSize = 26.sp, color = C.Soft, modifier = Modifier.clip(CircleShape).clickable { onBack() }.padding(horizontal = 10.dp))
            Text(tr("顺序"), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = C.Ink)
        }
        Spacer(Modifier.height(12.dp))
        SeqEditor(enabled = idle) {}
        Spacer(Modifier.height(40.dp))
    }
}

/** 专注设置：分组一行一项，点开的再展开。 */
@Composable
private fun FocusSettings(idle: Boolean, strict: Boolean, onWhitelist: () -> Unit, onNoise: () -> Unit, onBack: () -> Unit) {
    val c = LocalContext.current
    var lw by remember { mutableStateOf(Focus.len(c, Focus.WORK)) }
    var ls by remember { mutableStateOf(Focus.len(c, Focus.SHORT)) }
    var ll by remember { mutableStateOf(Focus.len(c, Focus.LONG)) }
    var lock by remember { mutableStateOf(Focus.lockApps(c)) }
    var auto by remember { mutableStateOf(Focus.autoNext(c)) }
    var planOpen by remember { mutableStateOf(false) }
    var v by remember { mutableStateOf(0) }
    androidx.activity.compose.BackHandler { onBack() }
    @Composable
    fun group(t: String, content: @Composable ColumnScope.() -> Unit) {
        Text(t, fontSize = 13.sp, color = C.Faint, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp, start = 4.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.Card).padding(horizontal = 18.dp, vertical = 6.dp), content = content)
    }
    @Composable
    fun row(label: String, value: String, open: Boolean? = null, enabled: Boolean = true, onClick: () -> Unit) {
        Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onClick() }.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 15.sp, color = if (enabled) C.Ink else C.Faint, modifier = Modifier.weight(1f))
            Text(value, fontSize = 14.sp, color = C.Soft, maxLines = 1)
            Text(if (open == true) "  ˄" else "  ›", fontSize = 15.sp, color = C.Faint)
        }
    }
    @Composable
    fun switch(label: String, sub: String?, on: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 15.sp, color = C.Ink)
                if (sub != null) Text(sub, fontSize = 12.sp, color = C.Faint, lineHeight = 16.sp)
            }
            androidx.compose.material3.Switch(on, onChange, enabled = enabled, colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = C.Clay, uncheckedThumbColor = Color.White, uncheckedTrackColor = C.Track, uncheckedBorderColor = C.Track))
        }
    }
    @Composable
    fun line() { Box(Modifier.fillMaxWidth().height(1.dp).background(C.Track.copy(alpha = 0.7f))) }
    @Composable
    fun mins(label: String, value: Int, onChange: (Int) -> Unit) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 15.sp, color = C.Ink, modifier = Modifier.weight(1f))
            Text("−", fontSize = 20.sp, color = C.Clay, modifier = Modifier.clip(CircleShape).clickable { if (value > 1) onChange(value - 1) }.padding(horizontal = 12.dp, vertical = 2.dp))
            // 点数字直接输入任意分钟数（至少 1）
            var text by remember(value) { mutableStateOf(value.toString()) }
            androidx.compose.foundation.text.BasicTextField(
                value = text,
                onValueChange = { t ->
                    val d = t.filter { it.isDigit() }.take(4)
                    text = d
                    d.toIntOrNull()?.let { if (it >= 1) onChange(it) }
                },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = C.Clay,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                modifier = Modifier.width(56.dp).clip(RoundedCornerShape(10.dp)).background(C.ClayPale).padding(vertical = 4.dp)
            )
            Text(tr("分钟"), fontSize = 13.sp, color = C.Soft, modifier = Modifier.padding(start = 4.dp))
            Text("+", fontSize = 20.sp, color = C.Clay, modifier = Modifier.clip(CircleShape).clickable { onChange(value + 1) }.padding(horizontal = 12.dp, vertical = 2.dp))
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", fontSize = 26.sp, color = C.Soft, modifier = Modifier.clip(CircleShape).clickable { onBack() }.padding(horizontal = 10.dp))
            Text(tr("专注设置"), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = C.Ink)
        }

        group(tr("时长")) {
            mins(tr("专注"), lw) { lw = it; Focus.setLen(c, Focus.WORK, it) }
            line()
            mins(tr("短休"), ls) { ls = it; Focus.setLen(c, Focus.SHORT, it) }
            line()
            mins(tr("长休"), ll) { ll = it; Focus.setLen(c, Focus.LONG, it) }
        }

        group(tr("节奏")) {
            switch(tr("自动开始下一段"), null, auto) { auto = it; Focus.setAutoNext(c, it) }
        }

        group(tr("专注时")) {
            switch(tr("锁住守护中的应用"), null, lock) { lock = it; Focus.setLockApps(c, it) }
            line()
            HushToggle(tr("收起通知"), tr("白名单和电话照常"), { Hush.on(it) }, { x, b -> Hush.setOn(x, b) })
            Spacer(Modifier.height(8.dp))
        }

        group(tr("定时")) {
            row(tr("到点自动开始"), "", open = planOpen) { planOpen = !planOpen }
            AnimatedVisibility(planOpen) { Column(Modifier.padding(bottom = 10.dp)) { PlanEditor() } }
        }
        if (strict) Text(tr("专注中不能改，休息时再调整"), fontSize = 12.sp, color = C.Faint, modifier = Modifier.padding(top = 10.dp, start = 4.dp))
        Spacer(Modifier.height(40.dp))
    }
}

/** 首页上的一条小入口：专注中时显示倒计时。 */
@Composable
fun FocusPill(onOpen: () -> Unit) {
    val c = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    Every(1000) { tick++ }
    val phase = remember(tick) { Focus.phase(c) }
    val running = remember(tick) { Focus.running(c) }
    val text = when {
        phase == Focus.IDLE -> tr("开始一个番茄")
        !running -> tr("{0} 已暂停 · {1}", Focus.NAMES[phase], mmss(Focus.leftMs(c)))
        else -> tr("{0}中 · {1}", Focus.NAMES[phase], mmss(Focus.leftMs(c)))
    }
    val dot = if (phase == Focus.SHORT || phase == Focus.LONG) C.Sage else C.Clay
    Row(
        Modifier.padding(top = 12.dp).clip(RoundedCornerShape(20.dp)).background(C.Card).clickable { onOpen() }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (phase == Focus.IDLE) C.Faint else dot))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 13.sp, color = C.Ink)
    }
}


/** 无敌模式白名单。电话、短信、输入法、桌面默认放行，不用选。 */
@Composable
fun WhitelistScreen(onBack: () -> Unit) {
    val c = LocalContext.current
    var apps by remember { mutableStateOf<List<AppInfo>?>(null) }
    LaunchedEffect(Unit) { apps = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Apps.launchable(c) } }
    val sel = remember { mutableStateListOf<String>().apply { addAll(Focus.whitelist(c)) } }
    val ess = remember { Focus.essentials(c) }
    var q by remember { mutableStateOf("") }
    androidx.activity.compose.BackHandler { Focus.setWhitelist(c, sel.toSet()); onBack() }
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
        Text(tr("‹ 完成"), fontSize = 15.sp, color = C.Soft, modifier = Modifier.padding(top = 14.dp)
            .clip(RoundedCornerShape(10.dp)).clickable { Focus.setWhitelist(c, sel.toSet()); onBack() }.padding(vertical = 4.dp))
        Text(tr("白名单"), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = C.Ink, modifier = Modifier.padding(top = 10.dp))
        Text(tr("无敌模式下只有这些能打开。电话、短信、输入法、系统设置和系统服务已默认放行。"), fontSize = 14.sp, color = C.Soft,
            lineHeight = 21.sp, modifier = Modifier.padding(top = 4.dp))
        Box(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Sand).padding(horizontal = 16.dp, vertical = 13.dp)) {
            if (q.isEmpty()) Text(tr("搜索应用"), fontSize = 14.sp, color = C.Faint)
            androidx.compose.foundation.text.BasicTextField(q, { q = it }, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = C.Ink), modifier = Modifier.fillMaxWidth())
        }
        val list = apps
        if (list == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(color = C.Clay)
            }
        } else {
            val sorted = remember(list) { list.filter { it.pkg !in ess && !Focus.systemService(c, it.pkg) }.sortedBy { if (it.pkg in sel) 0 else 1 } }
            val k = q.trim()
            val shown = if (k.isEmpty()) sorted else sorted.filter { it.matches(k) }
            androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f).padding(top = 10.dp)) {
                items(shown.size, key = { shown[it].pkg }) { i ->
                    val a = shown[i]
                    val on = a.pkg in sel
                    Row(
                        Modifier.fillMaxWidth().clickable { if (on) sel.remove(a.pkg) else sel.add(a.pkg) }
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppIcon(a)
                        Spacer(Modifier.width(14.dp))
                        Text(a.label, fontSize = 16.sp, color = C.Ink, modifier = Modifier.weight(1f))
                        Box(
                            Modifier.size(24.dp).clip(CircleShape).then(
                                if (on) Modifier.background(C.Sage)
                                else Modifier.border(2.dp, Color(0xFFDCCFC2), CircleShape)
                            ),
                            contentAlignment = Alignment.Center
                        ) { if (on) Text("✓", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                    }
                    androidx.compose.material3.HorizontalDivider(color = C.Track)
                }
            }
        }
    }
}

/** 首页右上角的专注入口：空闲时是一个淡淡的圆，专注中外圈走进度。 */
@Composable
fun FocusButton(onOpen: () -> Unit) {
    val c = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    Every(1000) { tick++ }
    val phase = remember(tick) { Focus.phase(c) }
    val frac = remember(tick) { if (phase == Focus.IDLE) 0f else 1f - Focus.leftMs(c).toFloat() / Focus.totalMs(c).coerceAtLeast(1) }
    val col = if (phase == Focus.SHORT || phase == Focus.LONG) C.Sage else C.Clay
    val s = Look.scale("focus")
    val ink = Look.keyFg("focus")
    Row(
        Modifier.clip(RoundedCornerShape((21 * s).dp)).background(Look.keyBg("focus")).clickable { onOpen() }
            .padding(start = (10 * s).dp, end = (14 * s).dp).height((42 * s).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(Modifier.size((20 * s).dp)) {
            val sw = 2.dp.toPx()
            val tl = Offset(sw / 2, sw / 2); val sz = Size(size.width - sw, size.height - sw)
            drawArc(if (phase == Focus.IDLE) ink else C.Track, 0f, 360f, false, tl, sz, style = Stroke(sw))
            if (phase != Focus.IDLE) drawArc(col, -90f, 360f * frac, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
            else drawLine(ink, center, Offset(center.x, size.height * 0.28f), sw, StrokeCap.Round)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (phase == Focus.IDLE) Look.label("focus", tr("专注")) else mmss(Focus.leftMs(c)),
            fontSize = (14 * s).sp, color = ink, fontWeight = FontWeight.Medium
        )
    }
}


/** 白噪音选择页：两列卡片，点一下即试听，返回后停止试听。 */
@Composable
fun NoiseScreen(onBack: () -> Unit) {
    val c = LocalContext.current
    var sel by remember { mutableStateOf(Noise.kind(c)) }
    var vol by remember { mutableStateOf(Noise.volume(c)) }
    var nb by remember { mutableStateOf(Noise.inBreak(c)) }
    val leave = { Noise.preview = null; Noise.sync(c); onBack() }
    DisposableEffect(Unit) { onDispose { Noise.preview = null; Noise.sync(c) } }
    androidx.activity.compose.BackHandler { leave() }
    val groups = listOf(
        tr("自然") to listOf("rain", "storm", "waves", "stream", "wind", "fireplace", "summer_night", "birds"),
        tr("城市") to listOf("coffee_shop", "city", "train", "boat"),
        tr("纯噪音") to listOf("white", "pink", "brown")
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
        Text(tr("‹ 完成"), fontSize = 15.sp, color = C.Soft, modifier = Modifier.padding(top = 14.dp)
            .clip(RoundedCornerShape(10.dp)).clickable { leave() }.padding(vertical = 4.dp))
        Text(tr("白噪音"), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = C.Ink, modifier = Modifier.padding(top = 10.dp))
        Text(tr("点一下试听，选好后专注时自动播放"), fontSize = 14.sp, color = C.Soft, modifier = Modifier.padding(top = 4.dp))

        @Composable
        fun cell(k: String, mod: Modifier) {
            val on = k == sel
            Box(
                mod.height(60.dp).clip(RoundedCornerShape(18.dp))
                    .background(if (on) C.Clay else C.Card)
                    .clickable {
                        sel = k; Noise.setKind(c, k)
                        Noise.preview = if (k == "off") null else k
                        Noise.sync(c)
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(Noise.label(k), fontSize = 15.sp, color = if (on) Color.White else C.Ink,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
        }

        cell("off", Modifier.padding(top = 20.dp).fillMaxWidth())
        for ((title, ks) in groups) {
            Text(title, fontSize = 13.sp, color = C.Faint, letterSpacing = 0.6.sp, modifier = Modifier.padding(top = 22.dp, bottom = 10.dp))
            for (row in ks.chunked(2)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (k in row) cell(k, Modifier.weight(1f))
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        SoftCard(Modifier.fillMaxWidth().padding(top = 16.dp)) {
            StepSlider(tr("音量"), vol, 10..100, 10, "%") { vol = it; Noise.setVolume(c, it) }
            Toggle(tr("休息时也播放"), tr("默认只在专注时播放"), nb) { nb = it; Noise.setInBreak(c, it) }
        }
        Spacer(Modifier.height(32.dp))
    }
}


@Composable
private fun FlowRowDots(sq: List<Int>, pos: Int, live: Boolean) {
    Row(Modifier.padding(top = 18.dp).padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        sq.take(16).forEachIndexed { i, ph ->
            val done = i < pos
            val cur = i == pos
            val work = ph == Focus.WORK
            val col = when {
                done -> if (work) C.Clay else C.SageInk
                cur -> if (work) C.ClayPale else Color(0xFFDDEBDF)
                else -> C.Track
            }
            Box(
                Modifier.size(if (work) (if (cur && live) 11.dp else 9.dp) else 6.dp).clip(CircleShape).background(col)
                    .then(if (cur) Modifier.border(1.dp, if (work) C.Clay else C.SageInk, CircleShape) else Modifier)
            )
        }
        if (sq.size > 16) Text("…", fontSize = 12.sp, color = C.Faint)
    }
}

/** 设置里的流程编辑：点一段切换类型，× 删掉，＋ 加一段；下面是循环次数。 */
@Composable
private fun SeqEditor(enabled: Boolean, onChange: () -> Unit) {
    val c = LocalContext.current
    var sq by remember { mutableStateOf(Focus.seq(c)) }
    var loops by remember { mutableStateOf(Focus.loops(c)) }
    fun save(l: List<Int>) { sq = l; Focus.setSeq(c, l); onChange() }
    Text(tr("顺序"), fontSize = 14.sp, color = C.Ink, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
    Text(if (enabled) tr("把右边的拼块拖进左边的槽里，拖到哪就插在哪；槽里的拼块可以上下拖动换顺序，拖出槽就删掉，点一下换类型")
        else tr("专注或休息进行中，结束后再改"),
        fontSize = 12.sp, color = C.Faint, lineHeight = 17.sp)
    PuzzleBoard(sq, enabled) { save(it) }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Spacer(Modifier.weight(1f))
        Text(tr("恢复默认"), fontSize = 13.sp, color = C.Soft, modifier = Modifier.clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled) { save(Focus.DEFAULT_SEQ.map { it.digitToInt() }) }.padding(vertical = 6.dp, horizontal = 4.dp))
    }
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(tr("循环次数"), fontSize = 14.sp, color = C.Ink, fontWeight = FontWeight.SemiBold)
            Text(tr("整组走完几遍后停下"), fontSize = 12.sp, color = C.Faint)
        }
        Text("−", fontSize = 22.sp, color = C.Clay, modifier = Modifier.clip(CircleShape)
            .clickable { if (loops > 0) { loops -= 1; Focus.setLoops(c, loops); onChange() } }.padding(horizontal = 14.dp, vertical = 4.dp))
        Text(if (loops == 0) tr("不限") else tr("{0} 遍", loops), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = C.Clay,
            modifier = Modifier.widthIn(min = 56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text("+", fontSize = 22.sp, color = C.Clay, modifier = Modifier.clip(CircleShape)
            .clickable { loops += 1; Focus.setLoops(c, loops); onChange() }.padding(horizontal = 14.dp, vertical = 4.dp))
    }
}


// ---------------- 拖动拼图：编排专注和休息的顺序 ----------------

private fun pieceColor(ph: Int) = when (ph) { Focus.WORK -> C.Clay; Focus.SHORT -> Color(0xFF8DB59A); else -> Color(0xFF5E8A6B) }

/** 拼图块：上边一个凹口，下边一个凸起，一块块扣在一起。 */
private class PuzzleShape(private val tab: Float) : androidx.compose.ui.graphics.Shape {
    override fun createOutline(size: Size, layoutDirection: androidx.compose.ui.unit.LayoutDirection, density: androidx.compose.ui.unit.Density): androidx.compose.ui.graphics.Outline {
        val r = tab
        val h = size.height - r
        val w = size.width
        val cr = r * 0.9f
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(cr, 0f)
            lineTo(w / 2 - r, 0f)
            arcTo(androidx.compose.ui.geometry.Rect(w / 2 - r, -r, w / 2 + r, r), 180f, -180f, false)   // 凹口
            lineTo(w - cr, 0f)
            quadraticBezierTo(w, 0f, w, cr)
            lineTo(w, h - cr)
            quadraticBezierTo(w, h, w - cr, h)
            lineTo(w / 2 + r, h)
            arcTo(androidx.compose.ui.geometry.Rect(w / 2 - r, h - r, w / 2 + r, h + r), 0f, 180f, false)  // 凸起
            lineTo(cr, h)
            quadraticBezierTo(0f, h, 0f, h - cr)
            lineTo(0f, cr)
            quadraticBezierTo(0f, 0f, cr, 0f)
            close()
        }
        return androidx.compose.ui.graphics.Outline.Generic(p)
    }
}

private data class PDrag(val ph: Int, val from: Int, val pos: Offset)

@Composable
private fun PuzzleBoard(sq: List<Int>, enabled: Boolean, onSave: (List<Int>) -> Unit) {
    val d = androidx.compose.ui.platform.LocalDensity.current
    val pitch = 46.dp          // 每块占的高度（凸起扣进下一块的凹口）
    val tab = 9.dp
    val pad = 12.dp
    val pitchPx = with(d) { pitch.toPx() }
    val padPx = with(d) { pad.toPx() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var track by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var drag by remember { mutableStateOf<PDrag?>(null) }
    val pieceOrigin = remember { mutableStateMapOf<String, Offset>() }

    fun restOf(g: PDrag?) = if (g != null && g.from >= 0) sq.filterIndexed { i, _ -> i != g.from } else sq
    fun targetOf(g: PDrag?): Int? {
        g ?: return null
        val t = track
        val inside = g.pos.x > t.left - 40f && g.pos.x < t.right + 40f && g.pos.y > t.top - 30f && g.pos.y < t.bottom + pitchPx
        return if (!inside) null else ((g.pos.y - t.top - padPx) / pitchPx).toInt().coerceIn(0, restOf(g).size)
    }
    val rest = restOf(drag)
    val target = targetOf(drag)
    val slots = rest.size + (if (target != null) 1 else 0)
    val trackH = pad * 2 + pitch * maxOf(slots, 3) + tab

    fun drop() {
        val g = drag ?: return
        val t = targetOf(g)
        val out = restOf(g).toMutableList()
        drag = null
        if (t != null) out.add(t, g.ph)
        if (out.isEmpty()) return
        if (out != sq) onSave(out)
    }
    fun Modifier.pieceDrag(key: String, ph: Int, from: Int) = this
        .onGloballyPositioned { pieceOrigin[key] = it.positionInRoot() }
        .pointerInput(enabled, ph, from, sq) {
            if (!enabled) return@pointerInput
            detectDragGestures(
                onDragStart = { o -> drag = PDrag(ph, from, (pieceOrigin[key] ?: Offset.Zero) - origin + o) },
                onDrag = { ch, amt -> ch.consume(); drag = drag?.let { it.copy(pos = it.pos + amt) } },
                onDragEnd = { drop() },
                onDragCancel = { drag = null }
            )
        }

    Box(Modifier.fillMaxWidth().padding(top = 10.dp).onGloballyPositioned { origin = it.positionInRoot() }) {
        Row(Modifier.fillMaxWidth()) {
            // 左边：槽
            Box(
                Modifier.weight(1f).height(trackH).clip(RoundedCornerShape(18.dp))
                    .background(if (target != null) C.ClayPale else C.Sand)
                    .border(1.dp, if (target != null) C.Clay else C.Track, RoundedCornerShape(18.dp))
                    .onGloballyPositioned { val p = it.positionInRoot() - origin; track = androidx.compose.ui.geometry.Rect(p, Size(it.size.width.toFloat(), it.size.height.toFloat())) }
            ) {
                if (rest.isEmpty() && target == null) Text(tr("把拼块拖进来"), fontSize = 13.sp, color = C.Faint, modifier = Modifier.align(Alignment.Center))
                val tabPx = with(d) { tab.toPx() }
                if (target != null) {
                    val y by animateDpAsState(pad + pitch * target, label = "ph")
                    Box(Modifier.padding(horizontal = 14.dp).offset(y = y).fillMaxWidth().height(pitch + tab)
                        .clip(PuzzleShape(tabPx)).background(pieceColor(drag?.ph ?: 1).copy(alpha = 0.25f)))
                }
                // 每块按原始下标固定身份，拖动中的那块留在原处但隐藏，手势不会断
                sq.forEachIndexed { orig, ph ->
                    key(orig, sq.size) {
                        val g = drag
                        val hidden = g != null && g.from == orig
                        val ri = if (g != null && g.from >= 0 && orig > g.from) orig - 1 else orig
                        val slot = if (target != null && ri >= target) ri + 1 else ri
                        val y by animateDpAsState(pad + pitch * slot, label = "p")
                        Box(
                            Modifier.padding(horizontal = 14.dp).offset(y = y).fillMaxWidth().height(pitch + tab)
                                .alpha(if (hidden) 0f else 1f)
                                .clip(PuzzleShape(tabPx)).background(pieceColor(ph))
                                .pointerInput(enabled, orig, ph, sq) {
                                    if (!enabled) return@pointerInput
                                    detectTapGestures { onSave(sq.toMutableList().also { it[orig] = if (ph == 3) 1 else ph + 1 }) }
                                }
                                .pieceDrag("t$orig", ph, orig),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            Text("${slot + 1}. ${Focus.NAMES[ph]}", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 13.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
            // 右边：拼块仓库
            Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                for (ph in listOf(Focus.WORK, Focus.SHORT, Focus.LONG)) {
                    Box(
                        Modifier.size(62.dp).clip(CircleShape).background(pieceColor(ph).copy(alpha = if (enabled) 1f else 0.4f))
                            .pieceDrag("p$ph", ph, -1),
                        contentAlignment = Alignment.Center
                    ) { Text(Focus.NAMES[ph]!!, fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.SemiBold) }
                }
                Text(tr("按住拖"), fontSize = 11.sp, color = C.Faint)
            }
        }
        // 手指下面跟着走的那块
        drag?.let { g ->
            val w = 96.dp; val h = pitch + tab
            Box(
                Modifier.offset(
                    x = with(d) { (g.pos.x).toDp() } - w / 2,
                    y = with(d) { (g.pos.y).toDp() } - h / 2
                ).size(w, h).clip(PuzzleShape(with(d) { tab.toPx() })).background(pieceColor(g.ph).copy(alpha = 0.92f)),
                contentAlignment = Alignment.TopCenter
            ) { Text(Focus.NAMES[g.ph]!!, fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 13.dp)) }
        }
    }
}
