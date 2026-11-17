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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.delay

/** 番茄钟：状态全在 SharedPreferences 里，关掉应用也照样走；守护服务负责到点提醒。 */
object Focus {
    const val IDLE = 0; const val WORK = 1; const val SHORT = 2; const val LONG = 3
    val NAMES = mapOf(WORK to "专注", SHORT to "短休", LONG to "长休")
    private fun p(c: Context) = c.getSharedPreferences("focus", Context.MODE_PRIVATE)

    fun phase(c: Context) = p(c).getInt("phase", IDLE)
    fun endAt(c: Context) = p(c).getLong("end", 0L)
    fun pausedLeft(c: Context) = p(c).getLong("paused", -1L)
    fun running(c: Context) = phase(c) != IDLE && pausedLeft(c) < 0
    fun round(c: Context) = p(c).getInt("round", 0)            // 本轮已完成的专注数（0..3）
    fun next(c: Context) = p(c).getInt("next", WORK)            // 空闲时建议的下一段
    fun len(c: Context, ph: Int) = when (ph) { SHORT -> p(c).getInt("ls", 5); LONG -> p(c).getInt("ll", 15); else -> p(c).getInt("lw", 25) }
    fun setLen(c: Context, ph: Int, m: Int) = p(c).edit().putInt(when (ph) { SHORT -> "ls"; LONG -> "ll"; else -> "lw" }, m).apply()
    fun lockApps(c: Context) = p(c).getBoolean("lock", true)
    fun setLockApps(c: Context, v: Boolean) = p(c).edit().putBoolean("lock", v).apply()
    fun autoNext(c: Context) = p(c).getBoolean("auto", false)
    fun setAutoNext(c: Context, v: Boolean) = p(c).edit().putBoolean("auto", v).apply()

    private fun dayFix(c: Context) {
        val t = Store.today()
        if (p(c).getString("day", "") != t) p(c).edit().putString("day", t).putInt("cnt", 0).putInt("min", 0).apply()
    }
    fun todayCount(c: Context): Int { dayFix(c); return p(c).getInt("cnt", 0) }
    fun todayMin(c: Context): Int { dayFix(c); return p(c).getInt("min", 0) }

    fun leftMs(c: Context): Long = when {
        phase(c) == IDLE -> len(c, next(c)) * 60_000L
        pausedLeft(c) >= 0 -> pausedLeft(c)
        else -> (endAt(c) - System.currentTimeMillis()).coerceAtLeast(0)
    }
    fun totalMs(c: Context): Long = len(c, if (phase(c) == IDLE) next(c) else phase(c)) * 60_000L

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
    fun stop(c: Context) { p(c).edit().putInt("phase", IDLE).putLong("paused", -1L).apply() }
    fun skip(c: Context) { finish(c, counted = false) }
    fun choose(c: Context, ph: Int) { if (phase(c) == IDLE) p(c).edit().putInt("next", ph).apply() }

    private fun finish(c: Context, counted: Boolean) {
        val ph = phase(c)
        val e = p(c).edit()
        var nx = WORK
        if (ph == WORK) {
            var r = round(c)
            if (counted) {
                dayFix(c)
                e.putInt("cnt", p(c).getInt("cnt", 0) + 1).putInt("min", p(c).getInt("min", 0) + len(c, WORK))
            }
            r += 1
            nx = if (r >= 4) LONG else SHORT
            e.putInt("round", if (r >= 4) 0 else r)
        }
        e.putInt("phase", IDLE).putInt("next", nx).putLong("paused", -1L).apply()
        if (autoNext(c)) start(c, nx)
    }

    /** 守护服务和界面都会调用：到点就收尾并提醒。 */
    @Synchronized
    fun tick(c: Context) {
        if (!running(c) || System.currentTimeMillis() < endAt(c)) return
        val ph = phase(c)
        finish(c, counted = true)
        notifyDone(c, ph)
    }

    private fun notifyDone(c: Context, ph: Int) {
        try {
            val v = c.getSystemService(Vibrator::class.java)
            v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 120, 120, 120), -1))
        } catch (x: Exception) {}
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel("focus", "番茄钟", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(c, 7, Intent(c, MainActivity::class.java).putExtra("open", "focus")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)
        val (t, s) = if (ph == WORK) "专注完成" to "起来走走，喝口水。" else "休息结束" to "准备好了，就开始下一个番茄。"
        nm.notify(77, NotificationCompat.Builder(c, "focus").setSmallIcon(R.drawable.ic_mono)
            .setContentTitle(t).setContentText(s).setAutoCancel(true).setContentIntent(pi).build())
    }
}

private fun mmss(ms: Long): String {
    val s = (ms + 999) / 1000
    return "%02d:%02d".format(s / 60, s % 60)
}

@Composable
fun FocusScreen() {
    val c = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { Focus.tick(c); tick++; delay(250) } }
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
            Spacer(Modifier.height(8.dp))
            Text("专注", fontSize = 27.sp, fontWeight = FontWeight.Bold, color = C.Ink)
            Text(
                if (Focus.lockApps(c)) "专注时，守护中的应用都会暂停开放" else "一次只做一件事",
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
                        phase == Focus.IDLE -> "准备好了就开始"
                        !running -> "已暂停"
                        isBreak -> "放松一下"
                        else -> "正在专注"
                    },
                    fontSize = 13.sp, color = C.Soft, modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        // 本轮四个番茄
        val r = remember(tick) { Focus.round(c) }
        Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(4) { i ->
                val doneDot = i < r
                val cur = i == r && phase == Focus.WORK
                Box(
                    Modifier.size(if (cur) 10.dp else 8.dp).clip(CircleShape)
                        .background(if (doneDot) C.Clay else if (cur) C.ClayPale else C.Track)
                )
            }
        }

        // 主按钮 + 两个轻按钮
        Row(Modifier.padding(top = 30.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "重置", fontSize = 14.sp, color = if (phase != Focus.IDLE) C.Soft else C.Faint,
                modifier = Modifier.width(72.dp).clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = phase != Focus.IDLE) { Focus.stop(c); tick++ }.padding(vertical = 12.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Box(
                Modifier.padding(horizontal = 20.dp).size(76.dp).clip(CircleShape).background(accent)
                    .clickable {
                        when {
                            phase == Focus.IDLE -> startNow(Focus.next(c))
                            running -> { Focus.pause(c); tick++ }
                            else -> { Focus.resume(c); tick++ }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (running) {
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
                "跳过", fontSize = 14.sp, color = if (phase != Focus.IDLE) C.Soft else C.Faint,
                modifier = Modifier.width(72.dp).clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = phase != Focus.IDLE) { Focus.skip(c); tick++ }.padding(vertical = 12.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }

        // 今日
        val cnt = remember(tick / 8) { Focus.todayCount(c) }
        val mins = remember(tick / 8) { Focus.todayMin(c) }
        Row(Modifier.padding(top = 36.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SoftCard(Modifier.weight(1f)) {
                Text("今天的番茄", fontSize = 12.sp, color = C.Soft)
                Text("$cnt", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = C.Ink, modifier = Modifier.padding(top = 2.dp))
            }
            SoftCard(Modifier.weight(1f)) {
                Text("专注时长", fontSize = 12.sp, color = C.Soft)
                Text(if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins}m", fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold, color = C.Ink, modifier = Modifier.padding(top = 2.dp))
            }
        }

        // 设置：默认收起
        Text(
            if (showSet) "收起设置" else "设置", fontSize = 13.sp, color = C.Soft,
            modifier = Modifier.padding(top = 18.dp).clip(RoundedCornerShape(10.dp)).clickable { showSet = !showSet }
                .padding(horizontal = 14.dp, vertical = 8.dp)
        )
        AnimatedVisibility(showSet) {
            SoftCard(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                var lw by remember { mutableStateOf(Focus.len(c, Focus.WORK)) }
                var ls by remember { mutableStateOf(Focus.len(c, Focus.SHORT)) }
                var ll by remember { mutableStateOf(Focus.len(c, Focus.LONG)) }
                var lock by remember { mutableStateOf(Focus.lockApps(c)) }
                var auto by remember { mutableStateOf(Focus.autoNext(c)) }
                StepSlider("专注", lw, 10..90, 5, "分钟") { lw = it; Focus.setLen(c, Focus.WORK, it); tick++ }
                StepSlider("短休", ls, 3..20, 1, "分钟") { ls = it; Focus.setLen(c, Focus.SHORT, it); tick++ }
                StepSlider("长休", ll, 10..40, 5, "分钟") { ll = it; Focus.setLen(c, Focus.LONG, it); tick++ }
                Toggle("专注时锁住守护中的应用", "专注期间打开它们会被送回桌面", lock) { lock = it; Focus.setLockApps(c, it) }
                Toggle("自动开始下一段", "专注完自动进入休息，休息完自动开始专注", auto) { auto = it; Focus.setAutoNext(c, it) }
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
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
    val phase = remember(tick) { Focus.phase(c) }
    val running = remember(tick) { Focus.running(c) }
    val text = when {
        phase == Focus.IDLE -> "开始一个番茄"
        !running -> "${Focus.NAMES[phase]} 已暂停 · ${mmss(Focus.leftMs(c))}"
        else -> "${Focus.NAMES[phase]}中 · ${mmss(Focus.leftMs(c))}"
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
