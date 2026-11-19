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

    /** 专注进行中，且选了「专注时锁住守护中的应用」。 */
    fun locking(c: Context) = phase(c) == WORK && lockApps(c)

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
    var scholar by remember { mutableStateOf(Focus.scholar(c)) }
    // 无敌模式下，专注一旦开始就不能中途暂停、重置或跳过
    val strict = scholar && phase == Focus.WORK
    if (showWl) { WhitelistScreen(onBack = { showWl = false }); return }
    if (showNoise) { NoiseScreen(onBack = { showNoise = false }); return }

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
        Column(Modifier.fillMaxWidth()) {
            Text(tr("‹ 返回"), fontSize = 15.sp, color = C.Soft, modifier = Modifier.padding(top = 14.dp)
                .clip(RoundedCornerShape(10.dp)).clickable { onBack() }.padding(vertical = 4.dp))
            Text(tr("专注"), fontSize = 27.sp, fontWeight = FontWeight.Bold, color = C.Ink, modifier = Modifier.padding(top = 10.dp))
            Text(
                when {
                    scholar -> tr("无敌模式：专注时只开放白名单里的应用")
                    Focus.lockApps(c) -> tr("专注时，守护中的应用都会暂停开放")
                    else -> tr("一次只做一件事")
                },
                fontSize = 14.sp, color = C.Soft, modifier = Modifier.padding(top = 4.dp)
            )
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

        // 主环：细线、圆头，中间大号细体数字
        Box(Modifier.padding(top = 26.dp).size(272.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
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
                Text(mmss(left), fontSize = 60.sp, fontWeight = FontWeight.Light, color = C.Ink,
                    fontFamily = FontFamily.SansSerif, letterSpacing = 1.sp)
                Text(
                    when {
                        phase == Focus.IDLE -> tr("准备好了就开始")
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

        // 时长：直接放在主页，点数字就能改
        SoftCard(Modifier.fillMaxWidth().padding(top = 26.dp)) {
            var lw by remember { mutableStateOf(Focus.len(c, Focus.WORK)) }
            var ls by remember { mutableStateOf(Focus.len(c, Focus.SHORT)) }
            var ll by remember { mutableStateOf(Focus.len(c, Focus.LONG)) }
            Text(tr("时长"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = C.Ink)
            FreeMinutes(tr("专注"), lw) { lw = it; Focus.setLen(c, Focus.WORK, it); tick++ }
            FreeMinutes(tr("短休"), ls) { ls = it; Focus.setLen(c, Focus.SHORT, it); tick++ }
            FreeMinutes(tr("长休"), ll) { ll = it; Focus.setLen(c, Focus.LONG, it); tick++ }
        }

        // 无敌模式卡片
        SoftCard(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("无敌模式"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = C.Ink)
                    Text(tr("专注时，除了白名单都打不开"), fontSize = 12.sp, color = C.Soft, modifier = Modifier.padding(top = 2.dp))
                }
                androidx.compose.material3.Switch(
                    checked = scholar, enabled = !strict,
                    onCheckedChange = { scholar = it; Focus.setScholar(c, it) },
                    colors = androidx.compose.material3.SwitchDefaults.colors(
                        checkedThumbColor = Color.White, checkedTrackColor = C.Clay,
                        uncheckedThumbColor = Color.White, uncheckedTrackColor = C.Track, uncheckedBorderColor = C.Track)
                )
            }
            val wl = remember(showWl, tick / 20) { Focus.whitelist(c) }
            Row(
                Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Sand)
                    .clickable(enabled = !strict) { showWl = true }.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(tr("白名单"), fontSize = 14.sp, color = C.Ink, modifier = Modifier.weight(1f))
                Text(if (wl.isEmpty()) tr("还没选") else tr("{0} 个应用", wl.size), fontSize = 13.sp, color = C.Soft)
                Text("  ›", fontSize = 16.sp, color = C.Faint)
            }
            if (strict) Text(tr("专注中不能改，休息时再调整"), fontSize = 12.sp, color = C.Faint, modifier = Modifier.padding(top = 8.dp))
        }

        // 白噪音：一行入口，点开单独选
        val nk = remember(showNoise, tick / 4) { Noise.kind(c) }
        Row(
            Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.Card)
                .clickable { showNoise = true }.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(tr("白噪音"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = C.Ink)
                Text(if (Noise.inBreak(c)) tr("专注和休息时播放") else tr("仅专注时播放"), fontSize = 12.sp, color = C.Soft,
                    modifier = Modifier.padding(top = 2.dp))
            }
            Text(Noise.label(nk), fontSize = 14.sp, color = if (nk == "off") C.Faint else accent, fontWeight = FontWeight.Medium)
            Text("  ›", fontSize = 16.sp, color = C.Faint)
        }

        // 今日
        val cnt = remember(tick / 8) { Focus.todayCount(c) }
        val mins = remember(tick / 4) { Focus.todayMin(c) }
        Row(Modifier.padding(top = 36.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SoftCard(Modifier.weight(1f)) {
                Text(tr("今天的番茄"), fontSize = 12.sp, color = C.Soft)
                Text("$cnt", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = C.Ink, modifier = Modifier.padding(top = 2.dp))
            }
            SoftCard(Modifier.weight(1f)) {
                Text(tr("专注时长"), fontSize = 12.sp, color = C.Soft)
                Text(if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins}m", fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold, color = C.Ink, modifier = Modifier.padding(top = 2.dp))
            }
        }

        // 设置：默认收起
        Text(
            if (showSet) tr("收起设置") else tr("设置"), fontSize = 13.sp, color = C.Soft,
            modifier = Modifier.padding(top = 18.dp).clip(RoundedCornerShape(10.dp)).clickable { showSet = !showSet }
                .padding(horizontal = 14.dp, vertical = 8.dp)
        )
        AnimatedVisibility(showSet) {
            SoftCard(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                var lock by remember { mutableStateOf(Focus.lockApps(c)) }
                var auto by remember { mutableStateOf(Focus.autoNext(c)) }
                SeqEditor(enabled = phase == Focus.IDLE) { tick++ }
                Toggle(tr("专注时锁住守护中的应用"), tr("专注期间打开它们会被送回桌面"), lock) { lock = it; Focus.setLockApps(c, it) }
                Toggle(tr("自动开始下一段"), tr("专注完自动进入休息，休息完自动开始专注"), auto) { auto = it; Focus.setAutoNext(c, it) }
                HushToggle(tr("专注时收起通知"), tr("白名单和电话照常，其他的专注结束后放回来"), { Hush.on(it) }, { x, v -> Hush.setOn(x, v) })
                PlanEditor()
            }
        }
        Spacer(Modifier.height(36.dp))
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
    androidx.activity.compose.BackHandler { Focus.setWhitelist(c, sel.toSet()); onBack() }
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
        Text(tr("‹ 完成"), fontSize = 15.sp, color = C.Soft, modifier = Modifier.padding(top = 14.dp)
            .clip(RoundedCornerShape(10.dp)).clickable { Focus.setWhitelist(c, sel.toSet()); onBack() }.padding(vertical = 4.dp))
        Text(tr("白名单"), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = C.Ink, modifier = Modifier.padding(top = 10.dp))
        Text(tr("无敌模式下只有这些能打开。电话、短信、输入法已默认放行。"), fontSize = 14.sp, color = C.Soft,
            lineHeight = 21.sp, modifier = Modifier.padding(top = 4.dp))
        val list = apps
        if (list == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(color = C.Clay)
            }
        } else {
            val shown = list.filter { it.pkg !in ess }.sortedBy { if (it.pkg in sel) 0 else 1 }
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
