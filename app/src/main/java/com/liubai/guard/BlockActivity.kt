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
        if ((w == "focus" || w == "scholar") && Focus.phase(this) == Focus.WORK)
            startActivity(Intent(this, MainActivity::class.java).putExtra("open", "focus")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        else
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = home() })
        val pkg = intent.getStringExtra("pkg") ?: ""
        val name = Apps.info(this, pkg).label
        val why = intent.getStringExtra("why") ?: "daily"
        val x = Store.extraActive(this, pkg)
        val bed = Store.bedActive(this)
        val (title, sub) = when {
            why == "scholar" -> "无敌模式" to "专注还剩 ${(Focus.leftMs(this) / 60_000L + 1)} 分钟，这会儿只开放白名单里的应用"
            why == "focus" -> "正在专注" to "这个番茄还剩 ${(Focus.leftMs(this) / 60_000L + 1)} 分钟，先把手上的事做完"
            why == "bed" -> "该睡觉啦" to "睡前锁定到 ${Rules.clock(bed.to)}，$name 明早再见"
            why == "hard" -> "${name}今天不开放" to "这是你给自己定的，明天 0:00 才能改回来"
            why == "window" -> "现在不是${name}的时间" to "它只在 ${Rules.clock(x.from)}–${Rules.clock(x.to)} 开放"
            why == "cool" -> {
                val m = ((Store.coolUntil(this, pkg) - System.currentTimeMillis()) / 60_000L + 1).coerceAtLeast(1)
                "这一轮刷够啦" to "单次 ${x.session} 分钟已满，歇 $m 分钟再来"
            }
            why.startsWith("group:") -> {
                val g = Store.groupsActive(this).firstOrNull { it.id == why.removePrefix("group:") }
                "「${g?.name ?: "分组"}」今天的时间用完啦" to "明天 0:00 再见 · 还有 ${Stats.untilMidnight()}"
            }
            else -> "今天的${name}时间用完啦" to "明天 0:00 再见 · 还有 ${Stats.untilMidnight()}"
        }
        val tips = listOf(
            Color(0xFFFBE3D7) to "去倒杯温水",
            Color(0xFFEAF2EC) to "站起来伸个懒腰",
            Color(0xFFEFE7F6) to "看看窗外，让眼睛歇一会儿",
            Color(0xFFFFF1D6) to "给想念的人发条消息",
            Color(0xFFE6EEF6) to "早点睡，明天的你会感谢现在的你"
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
                        PrimaryButton(if (why == "focus" || why == "scholar") "回到专注" else "回到桌面", Modifier.padding(bottom = 24.dp)) { home() }
                    }
                }
            }
        }
    }
}
