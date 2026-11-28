package com.liubai.guard

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
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
        Text(tr("设置"), fontSize = 27.sp, fontWeight = FontWeight.Bold, color = C.Ink, modifier = Modifier.padding(top = 14.dp))
        Text(tr("权限和开关都在这里，绿色的是已经就绪的"), fontSize = 14.sp, color = C.Soft, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))

        SectionTitle(tr("语言") + if (Tr.lang == "en") "" else " · Language")
        SoftCard(Modifier.fillMaxWidth()) {
            val cur = Tr.chosen(c)
            FlowRowLite {
                LangChip(tr("跟随系统"), cur.isEmpty()) { if (cur.isNotEmpty()) Tr.set(c, "") }
                for ((code, name) in Tr.NAMES) LangChip(name, cur == code) { if (cur != code) Tr.set(c, code) }
            }
            Text(tr("切换后留白会重新打开一次，守护设置都不变"), fontSize = 12.sp, color = C.Faint, modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(18.dp))

        SectionTitle(tr("新手教程"))
        SoftCard(Modifier.fillMaxWidth()) {
            StatusRow(tr("新手教程"), tr("再看一遍选应用、专注、睡眠守护这些怎么用"), null, tr("打开 ›")) { Tour.show.value = "first" }
            StatusRow(tr("这个版本的新功能"), tr("看看这次更新加了什么"), null, tr("打开 ›")) { Tour.show.value = "new" }
        }
        Spacer(Modifier.height(18.dp))

        SectionTitle(tr("守护需要的权限"))
        SoftCard(Modifier.fillMaxWidth()) {
            StatusRow(tr("无障碍"), tr("知道你此刻在用什么，到点才能拦住"), acc) { Consent.openA11y(c) }
            StatusRow(tr("使用情况访问"), tr("读取每个应用今天真实用了多久"), usage) {
                open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${c.packageName}")), Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
            StatusRow(tr("通知"), tr("专注倒计时、配对码输入都靠它"), androidx.core.app.NotificationManagerCompat.from(c).areNotificationsEnabled().also { tick }) {
                open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName), appInfo)
            }
            StatusRow(tr("自启动、后台运行、电池不限制"), tr("在应用信息里设置，系统就不容易把留白关掉"), null) { open(appInfo) }
        }

        if (!BuildConfig.PLAY) {
            Spacer(Modifier.height(18.dp))
            SectionTitle(tr("卸载锁"))
            if (lock) SoftCard(Modifier.fillMaxWidth()) {
                StatusRow(tr("卸载锁"), tr("长按图标、应用信息里都卸不掉。在留白里连点 10 次「确定」会自动解开；今天没有守护中的应用时也会自动解开"), true, null)
            } else LockTip(force = true) { tick++ }

            Spacer(Modifier.height(18.dp))
            SectionTitle(tr("被关掉后自动重启"))
            if (heal) SoftCard(Modifier.fillMaxWidth()) {
                StatusRow(tr("无线调试授权"), tr("已授权。无障碍被系统关掉时，留白会自己重新打开"), true, null)
            } else KeepTip(force = true)
        }

        Spacer(Modifier.height(18.dp))
        SectionTitle(tr("紧急用一下"), tr("被拦住时可以等 20 秒、放行 5 分钟；专注、无敌模式和重要课里不能用"))
        SoftCard(Modifier.fillMaxWidth()) {
            var sos by remember { mutableStateOf(Store.sosMax(c)) }
            val pend = remember(sos) { Store.sosPending(c) }
            Text(tr("每周最多"), fontSize = 13.sp, color = C.Soft)
            Spacer(Modifier.height(6.dp))
            Segments(listOf(tr("关掉"), tr("1 次"), tr("2 次"), tr("3 次")), (pend ?: sos).coerceIn(0, 3)) { Store.setSosMax(c, it); sos = Store.sosMax(c) }
            Text(if (pend != null && pend > sos) tr("改多了要等下周生效，这周仍是 {0} 次", sos) else tr("这周还剩 {0} 次 · 改少马上生效，改多下周生效", Store.sosLeft(c)),
                fontSize = 12.sp, color = C.Faint, modifier = Modifier.padding(top = 8.dp))
        }

        Spacer(Modifier.height(18.dp))
        SectionTitle(tr("首页"))
        SoftCard(Modifier.fillMaxWidth()) {
            Toggle(tr("首页诗句"), tr("轮换温暖的诗词，点一下换一句"), poemsOn, onPoems)
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun StatusRow(label: String, sub: String, ok: Boolean?, act: String? = null, onClick: (() -> Unit)? = null) {
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
            true -> Text(tr("已开启"), fontSize = 13.sp, color = C.SageInk, fontWeight = FontWeight.SemiBold)
            false -> Text(tr("去开启 ›"), fontSize = 13.sp, color = C.ClayDeep, fontWeight = FontWeight.SemiBold)
            null -> Text(act ?: tr("去设置 ›"), fontSize = 13.sp, color = C.Soft)
        }
    }
}

@Composable
private fun LangChip(t: String, on: Boolean, onClick: () -> Unit) {
    Text(t, fontSize = 14.sp, color = if (on) androidx.compose.ui.graphics.Color.White else C.Ink,
        modifier = Modifier.padding(4.dp).clip(RoundedCornerShape(14.dp))
            .background(if (on) C.Clay else C.Sand).clickable { onClick() }.padding(horizontal = 14.dp, vertical = 8.dp))
}
