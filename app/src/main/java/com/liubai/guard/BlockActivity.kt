package com.liubai.guard

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.foundation.clickable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class BlockActivity : ComponentActivity() {
    private fun home() {
        val w = intent.getStringExtra("why")
        if ((w == "focus" || w == "scholar" || w == "class") && Focus.phase(this) == Focus.WORK)
            startActivity(Intent(this, MainActivity::class.java).putExtra("open", "focus")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        else
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    override fun onResume() { super.onResume(); GuardService.instance?.uncoverAfterDraw(this) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = home() })
        val pkg = intent.getStringExtra("pkg") ?: ""
        val name = Apps.info(this, pkg).label
        val why = intent.getStringExtra("why") ?: "daily"
        val x = Store.extraActive(this, pkg)
        val bed = Store.bedNow(this) ?: Store.bedsActive(this).first()
        val (title, sub) = when {
            why == "scholar" -> tr("无敌模式") to tr("专注还剩 {0} 分钟，这会儿只开放白名单里的应用", (Focus.leftMs(this) / 60_000L + 1))
            why == "focus" -> tr("正在专注") to tr("这个番茄还剩 {0} 分钟，先把手上的事做完", (Focus.leftMs(this) / 60_000L + 1))
            why == "bed" && bed.id == Bed.MAIN -> tr("该睡觉啦") to tr("睡前锁定到 {0}，{1} 明早再见", Rules.clock(bed.to), name)
            why == "bed" -> tr("{0}时间到啦", bedLabel(bed)) to tr("{0}锁定到 {1}，{2} 到时候再见", bedLabel(bed), Rules.clock(bed.to), name)
            why == "class" || why == "scene" -> {
                val s = Scenes.now(this)
                (if (why == "class") tr("正在上课") else "${s?.name ?: tr("这段时间")}") to tr("{0} {1} 结束，{2} 到时候再见", s?.name ?: "", Rules.clock(s?.to ?: 0), name).trim()
            }
            why == "opens" -> tr("今天打开{0}的次数用完啦", name) to tr("一共 {0} 次，明天 0:00 再见", x.opens)
            why == "hard" -> tr("{0}今天不开放", name) to tr("这是你给自己定的，明天 0:00 才能改回来")
            why == "window" -> tr("现在不是{0}的时间", name) to tr("它只在 {0}–{1} 开放", Rules.clock(x.from), Rules.clock(x.to))
            why == "cool" -> {
                val m = ((Store.coolUntil(this, pkg) - System.currentTimeMillis()) / 60_000L + 1).coerceAtLeast(1)
                tr("这一轮刷够啦") to tr("单次 {0} 分钟已满，歇 {1} 分钟再来", x.session, m)
            }
            why.startsWith("group:") -> {
                val g = Store.groupsActive(this).firstOrNull { it.id == why.removePrefix("group:") }
                tr("「{0}」今天的时间用完啦", g?.name ?: tr("分组")) to tr("明天 0:00 再见 · 还有 {0}", Stats.untilMidnight())
            }
            else -> tr("今天的{0}时间用完啦", name) to tr("明天 0:00 再见 · 还有 {0}", Stats.untilMidnight())
        }
        val tips = listOf(
            Color(0xFFFBE3D7) to tr("去倒杯温水"),
            Color(0xFFEAF2EC) to tr("站起来伸个懒腰"),
            Color(0xFFEFE7F6) to tr("看看窗外，让眼睛歇一会儿"),
            Color(0xFFFFF1D6) to tr("给想念的人发条消息"),
            Color(0xFFE6EEF6) to tr("早点睡，明天的你会感谢现在的你")
        ).shuffled().take(3)
        setContent {
            LiubaiTheme {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0xFFF8E6D8), 0.55f to C.Cream))) {
                    Column(
                        Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(Modifier.height(56.dp))
                        Sprout(150.dp, ring = false, moon = true)
                        Spacer(Modifier.height(18.dp))
                        Text(title, fontSize = 25.sp, fontWeight = FontWeight.Bold, color = C.Ink, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(10.dp))
                        Text(sub, fontSize = 15.sp, color = C.Soft, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(26.dp))
                        for ((col, t) in tips) {
                            SoftCard(Modifier.fillMaxWidth().padding(bottom = 10.dp), pad = 14.dp) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(col))
                                    Spacer(Modifier.width(12.dp))
                                    Text(t, fontSize = 15.sp, color = C.Ink)
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        // 紧急用一下：每周几次，等 20 秒，放行 5 分钟；专注、无敌模式、重要课不行
                        val canSos = why !in setOf("focus", "scholar", "class") && Store.sosMax(this@BlockActivity) > 0
                        var sos by remember { mutableStateOf(-1) }
                        val left = remember { Store.sosLeft(this@BlockActivity) }
                        if (sos > 0) LaunchedEffect(sos) { kotlinx.coroutines.delay(1000); sos-- }
                        if (sos == 0) LaunchedEffect(Unit) {
                            Store.useSos(this@BlockActivity, pkg, 5)
                            GuardService.instance?.pass(pkg)
                            finish()
                            packageManager.getLaunchIntentForPackage(Virt.real(pkg))?.let { try { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {} }
                        }
                        if (canSos) Text(
                            when { sos > 0 -> tr("{0} 秒后放行 5 分钟…", sos); left > 0 -> tr("有急事？紧急用 5 分钟 · 本周剩 {0} 次", left); else -> tr("本周的紧急次数用完了") },
                            fontSize = 13.sp, color = C.Faint, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 10.dp).clip(RoundedCornerShape(12.dp))
                                .clickable(enabled = left > 0 && sos < 0) { sos = 20 }.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                        PrimaryButton(if (why == "focus" || why == "scholar" || (why == "class" && Focus.phase(this@BlockActivity) == Focus.WORK)) tr("回到专注") else tr("回到桌面"), Modifier.padding(bottom = 24.dp)) { home() }
                    }
                }
            }
        }
    }
}
