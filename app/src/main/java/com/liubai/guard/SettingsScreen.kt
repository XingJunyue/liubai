package com.liubai.guard

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 侧栏右上角齿轮：所有权限、开关集中在这里。 */
@Composable
fun SettingsScreen(poemsOn: Boolean, onPoems: (Boolean) -> Unit) {
    val c = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    Every(1500) { tick++ }
    val acc = remember(tick) { serviceOn(c) }
    val usage = remember(tick) { Usage.granted(c) }
    val lock = remember(tick) { Admin.on(c) }
    val heal = remember(tick) { Keep.canHeal(c) || WAdb.adbMode(c) }
    fun open(i: Intent, fallback: Intent? = null) {
        try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (x: Exception) { fallback?.let { try { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (y: Exception) {} } }
    }
    val appInfo = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}"))

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
        Text("设置", fontSize = 27.sp, fontWeight = FontWeight.Bold, color = C.Ink, modifier = Modifier.padding(top = 14.dp))
        Text("权限和开关都在这里，绿色的是已经就绪的", fontSize = 14.sp, color = C.Soft, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))

        SectionTitle("守护需要的权限")
        SoftCard(Modifier.fillMaxWidth()) {
            StatusRow("无障碍", "知道你此刻在用什么，到点才能拦住", acc) { Consent.openA11y(c) }
            StatusRow("使用情况访问", "读取每个应用今天真实用了多久", usage) {
                open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${c.packageName}")), Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
            StatusRow("通知", "专注倒计时、配对码输入都靠它", androidx.core.app.NotificationManagerCompat.from(c).areNotificationsEnabled().also { tick }) {
                open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName), appInfo)
            }
            StatusRow("自启动、后台运行、电池不限制", "在应用信息里设置，系统就不容易把留白关掉", null) { open(appInfo) }
        }

        if (!BuildConfig.PLAY) {
            Spacer(Modifier.height(18.dp))
            SectionTitle("卸载锁")
            if (lock) SoftCard(Modifier.fillMaxWidth()) {
                StatusRow("卸载锁", "长按图标、应用信息里都卸不掉。在留白里连点 10 次「确定」会自动解开；今天没有守护中的应用时也会自动解开", true, null)
            } else LockTip(force = true) { tick++ }

            Spacer(Modifier.height(18.dp))
            SectionTitle("被关掉后自动重启")
            if (heal) SoftCard(Modifier.fillMaxWidth()) {
                StatusRow("无线调试授权", "已授权。无障碍被系统关掉时，留白会自己重新打开", true, null)
            } else KeepTip(force = true)
        }

        Spacer(Modifier.height(18.dp))
        SectionTitle("紧急用一下", "被拦住时可以等 20 秒、放行 5 分钟；专注、无敌模式和重要课里不能用")
        SoftCard(Modifier.fillMaxWidth()) {
            var sos by remember { mutableStateOf(Store.sosMax(c)) }
            val pend = remember(sos) { Store.sosPending(c) }
            Text("每周最多", fontSize = 13.sp, color = C.Soft)
            Spacer(Modifier.height(6.dp))
            Segments(listOf("关掉", "1 次", "2 次", "3 次"), (pend ?: sos).coerceIn(0, 3)) { Store.setSosMax(c, it); sos = Store.sosMax(c) }
            Text(if (pend != null && pend > sos) "改多了要等下周生效，这周仍是 $sos 次" else "这周还剩 ${Store.sosLeft(c)} 次 · 改少马上生效，改多下周生效",
                fontSize = 12.sp, color = C.Faint, modifier = Modifier.padding(top = 8.dp))
        }

        Spacer(Modifier.height(18.dp))
        SectionTitle("首页")
        SoftCard(Modifier.fillMaxWidth()) {
            Toggle("首页诗句", "轮换温暖的诗词，点一下换一句", poemsOn, onPoems)
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun StatusRow(label: String, sub: String, ok: Boolean?, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 10.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = C.Ink)
            Text(sub, fontSize = 12.sp, color = C.Faint, lineHeight = 17.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Spacer(Modifier.width(10.dp))
        when (ok) {
            true -> Text("已开启", fontSize = 13.sp, color = C.SageInk, fontWeight = FontWeight.SemiBold)
            false -> Text("去开启 ›", fontSize = 13.sp, color = C.ClayDeep, fontWeight = FontWeight.SemiBold)
            null -> Text("去设置 ›", fontSize = 13.sp, color = C.Soft)
        }
    }
}
