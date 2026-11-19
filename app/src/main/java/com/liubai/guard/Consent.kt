package com.liubai.guard

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 打开无障碍前的单独说明（Google Play 要求的「醒目披露」）：说清用它读什么、怎么用，点同意才去设置。
 */
object Consent {
    val show = mutableStateOf(false)
    private fun p(c: Context) = c.getSharedPreferences("liubai_ui", Context.MODE_PRIVATE)
    fun agreed(c: Context) = p(c).getBoolean("a11yOk", false)

    /** 所有「去打开无障碍」都走这里。 */
    fun openA11y(c: Context) {
        if (agreed(c)) go(c) else show.value = true
    }
    fun go(c: Context) {
        try { c.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (x: Exception) {}
    }
    fun agree(c: Context) { p(c).edit().putBoolean("a11yOk", true).apply(); show.value = false; go(c) }
}

@Composable
fun ConsentDialog() {
    if (!Consent.show.value) return
    val c = LocalContext.current
    AlertDialog(
        onDismissRequest = { Consent.show.value = false },
        containerColor = C.Card,
        title = { Text(tr("留白怎么使用无障碍"), fontWeight = FontWeight.Bold, color = C.Ink) },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                Text(
                    tr("留白通过无障碍服务（AccessibilityService）做这几件事：\n\n") +
                        tr("· 读取当前在前台的应用和页面名称，用来计时，到了你设的时间把你送回来\n") +
                        tr("· 在设置、桌面、安装器页面读取屏幕上的文字，用来识别关闭留白、卸载留白的操作并先拦一下\n") +
                        tr("· 在你守护的应用里识别是否进入了短视频那一块（比如视频号）\n") +
                        tr("· 必要时代你按一次「返回」或「回到桌面」\n\n") +
                        tr("这些信息只在手机上即时处理，不保存内容、不上传、不分享给任何人。你可以随时在系统设置里关掉它，关掉后守护会停止。"),
                    fontSize = 14.sp, color = C.Ink, lineHeight = 22.sp
                )
            }
        },
        confirmButton = { TextButton(onClick = { Consent.agree(c) }) { Text(tr("同意并去开启"), color = C.ClayDeep, fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onClick = { Consent.show.value = false }) { Text(tr("不同意"), color = C.Soft) } }
    )
}
