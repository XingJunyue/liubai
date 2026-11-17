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
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = home() })
        val pkg = intent.getStringExtra("pkg") ?: ""
        val name = Apps.info(this, pkg).label
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
                        Text("今天的${name}时间用完啦", fontSize = 25.sp, fontWeight = FontWeight.Bold, color = C.Ink, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(10.dp))
                        Text("明天 0:00 再见 · 还有 ${Stats.untilMidnight()}", fontSize = 15.sp, color = C.Soft)
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
                        PrimaryButton("回到桌面", Modifier.padding(bottom = 24.dp)) { home() }
                    }
                }
            }
        }
    }
}
