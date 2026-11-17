package com.liubai.guard

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** 打开受守护的应用前，先停几秒、跟着呼吸一下。 */
class PauseActivity : ComponentActivity() {
    private fun home() {
        Store.addWalkedAway(this)
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private fun proceed(pkg: String) {
        GuardService.instance?.pass(pkg)
        finish()
        packageManager.getLaunchIntentForPackage(Virt.real(pkg))?.let {
            try { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (x: Exception) {}
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = home() })
        val pkg = intent.getStringExtra("pkg") ?: ""
        val sec = intent.getIntExtra("sec", 5).coerceIn(1, 60)
        val name = Apps.info(this, pkg).label
        val walked = Store.walkedAway(this)
        setContent {
            LiubaiTheme {
                var left by remember { mutableStateOf(sec) }
                LaunchedEffect(Unit) { while (left > 0) { delay(1000); left-- } }
                val t = rememberInfiniteTransition(label = "breath")
                val s by t.animateFloat(
                    0.72f, 1f,
                    infiniteRepeatable(tween(4000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "s"
                )
                val inhale = s > 0.86f
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to C.SagePale, 0.6f to C.Cream))) {
                    Column(
                        Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 26.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(Modifier.height(70.dp))
                        Text("要打开$name 了", fontSize = 15.sp, color = C.Soft)
                        Text("先停一下", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = C.Ink, modifier = Modifier.padding(top = 6.dp))
                        Spacer(Modifier.height(44.dp))
                        Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(220.dp).scale(s).clip(CircleShape).background(C.SageLight.copy(alpha = 0.35f)))
                            Box(Modifier.size(150.dp).scale(s).clip(CircleShape).background(C.Sage.copy(alpha = 0.55f)))
                            Text(if (inhale) "呼" else "吸", fontSize = 26.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(36.dp))
                        Text(
                            "现在真的想刷吗？\n还是只是手习惯性地点开了",
                            fontSize = 16.sp, color = C.Ink, textAlign = TextAlign.Center, lineHeight = 25.sp
                        )
                        if (walked > 0) Chip("今天已经忍住 $walked 次")
                        Spacer(Modifier.weight(1f))
                        PrimaryButton("算了，不刷了") { home() }
                        Text(
                            if (left > 0) "$left 秒后可以继续" else "继续打开$name",
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            color = if (left > 0) C.Faint else C.Soft,
                            modifier = Modifier.padding(top = 8.dp, bottom = 22.dp).clip(RoundedCornerShape(12.dp))
                                .clickable(enabled = left == 0) { proceed(pkg) }.padding(horizontal = 18.dp, vertical = 12.dp)
                        )
                    }
                }
            }
        }
    }
}
