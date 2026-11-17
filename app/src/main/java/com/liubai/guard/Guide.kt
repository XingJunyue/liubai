package com.liubai.guard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val SECTIONS = listOf(
    "留白是做什么的" to "给你想少刷的应用定一个每天的额度。到点后，打开它会被轻轻送回桌面，明天 0:00 自动恢复。",
    "先开这几项权限" to "· 使用情况访问：算每个应用今天用了多久\n· 无障碍：知道你打开了什么，到点才能拦住\n· 小米手机还要在应用设置里打开：自启动、省电策略选「无限制」、后台弹出界面\n少了任何一项，守护都可能不准或失效。",
    "四种守护方式" to "· 每日总量：一天累计用满就休息\n· 限定时段：只在你定的时间段开放\n· 单次限时：每次最多刷几分钟，用完歇一会儿\n· 完全不开：今天整天不开放\n还可以叠加「打开前停一下」（先呼吸几秒再进去）和「剩 5 分钟提醒」。",
    "分组额度与睡前锁定" to "· 分组：几个应用共用一份时间，换着刷也逃不掉\n· 睡前锁定：到点后所有守护中的应用都不开放，第二天早上恢复",
    "番茄钟" to "首页右上角「专注」进入。默认专注 25 分钟、短休 5 分钟，每 4 个番茄长休一次。专注期间守护中的应用会暂停开放（可在设置里关掉）。",
    "白噪音" to "雨声、海浪、篝火等是真实录音，来自开源应用 Blanket 的素材库（作者 alex36917、Luftrum、Digifish music、gezortenplotz、SDLx 等，CC0 / CC BY / 公有领域）；白、粉、棕噪音为实时合成。默认只在专注时播放。",
    "无敌模式" to "在专注页打开，并选好白名单。专注期间除了白名单和电话、短信、输入法，其他应用都打不开；这个番茄也不能中途暂停、重置或跳过。",
    "今天只能改得更严" to "加应用、调低时长、加限制，马上生效。\n调高时长、移出应用、放宽方式、关闭睡前锁定，都要等明天 0:00 才生效。这样冲动的时候改不了规则。",
    "想卸载或关掉守护" to "可以，但不容易：守护生效期间，在系统里点卸载、结束运行、清除数据或关闭无障碍，会先跳到留白，要连点 100 次「确定」。点满后 3 分钟内可以卸载或关闭，中途离开要重新数。\n应用信息页本身随便看，不会拦。",
    "小部件和更新" to "桌面长按添加「留白」小部件：圆形图标外的一圈就是今天用掉的比例，刷的时候会跟着涨。\n有新版本时打开留白会自动提示，可以先选「以后再说」。"
)

@Composable
fun GuideScreen(onDone: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
        Spacer(Modifier.height(8.dp))
        Text("使用说明", fontSize = 27.sp, fontWeight = FontWeight.Bold, color = C.Ink)
        Text("两分钟看完，之后在侧栏随时能找到", fontSize = 14.sp, color = C.Soft, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(16.dp))
        SECTIONS.forEachIndexed { i, (t, body) ->
            SoftCard(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Text("${i + 1}. $t", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (t.startsWith("想卸载")) C.ClayDeep else C.Ink)
                Text(body, fontSize = 14.sp, color = C.Soft, lineHeight = 22.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
        if (onDone != null) PrimaryButton("我知道了，开始", Modifier.padding(top = 6.dp)) { onDone() }
        Spacer(Modifier.height(30.dp))
    }
}
