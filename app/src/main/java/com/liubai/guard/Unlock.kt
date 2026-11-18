package com.liubai.guard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Unlock {
    const val NEED = 10
    const val WINDOW_MS = 3 * 60_000L
    private fun p(c: Context) = c.getSharedPreferences("misc", Context.MODE_PRIVATE)
    fun until(c: Context) = p(c).getLong("unlock", 0L)
    fun open(c: Context) = System.currentTimeMillis() < until(c)
    fun grant(c: Context) = p(c).edit().putLong("unlock", System.currentTimeMillis() + WINDOW_MS).apply()
    fun guided(c: Context) = p(c).getBoolean("guided", false)
    fun setGuided(c: Context) = p(c).edit().putBoolean("guided", true).apply()
}

/** 想卸载 / 结束运行 / 关无障碍：连点 10 次「确定」，之后 3 分钟内放行。 */
class UnlockActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContent {
            LiubaiTheme {
                var n by remember { mutableStateOf(0) }
                val done = n >= Unlock.NEED
                val left = Unlock.NEED - n
                val line = when {
                    done -> "好，3 分钟内可以卸载或关闭守护。"
                    n < 20 -> "确定要拆掉留白吗？"
                    n < 50 -> "这么点下去，是真的想好了，还是一时上头？"
                    n < 80 -> "已经过半了。现在放下手机，也来得及。"
                    else -> "快到了。走之前，记得今天也留了一点空白。"
                }
                Column(
                    Modifier.fillMaxSize().background(C.Cream).systemBarsPadding().padding(horizontal = 26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(70.dp))
                    Sprout(72.dp, ring = true)
                    Text(line, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = C.Ink, textAlign = TextAlign.Center,
                        lineHeight = 30.sp, modifier = Modifier.padding(top = 24.dp))
                    Text(
                        if (done) "离开这个页面后计时开始" else "连点 ${Unlock.NEED} 次「确定」才能继续，中途离开要重新数",
                        fontSize = 13.sp, color = C.Soft, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp)
                    )
                    Spacer(Modifier.height(30.dp))
                    Text("$n / ${Unlock.NEED}", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = if (done) C.SageInk else C.Clay)
                    LinearProgressIndicator(
                        progress = n / Unlock.NEED.toFloat(), color = C.Clay, trackColor = C.Track,
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp).height(8.dp).clip(RoundedCornerShape(4.dp))
                    )
                    Spacer(Modifier.weight(1f))
                    if (!done) {
                        PrimaryButton("确定（还差 $left 次）") { n++; if (n >= Unlock.NEED) Unlock.grant(this@UnlockActivity) }
                        PrimaryButton("算了，继续守护", Modifier.padding(top = 10.dp, bottom = 26.dp)) { finish() }
                    } else {
                        PrimaryButton("去卸载留白") {
                            Admin.release(this@UnlockActivity)
                            try {
                                startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            } catch (x: Exception) {
                                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                            finish()
                        }
                        PrimaryButton("只是关一下守护，回桌面", Modifier.padding(top = 10.dp, bottom = 26.dp)) {
                            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            finish()
                        }
                    }
                }
            }
        }
    }
}
