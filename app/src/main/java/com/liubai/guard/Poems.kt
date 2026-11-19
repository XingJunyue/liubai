package com.liubai.guard

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** 首页轮换的诗句：温暖、热爱生活的那一类。 */
object Poems {
    val ALL = listOf(
        tr("人间有味是清欢") to tr("苏轼《浣溪沙》"),
        tr("一蓑烟雨任平生") to tr("苏轼《定风波》"),
        tr("莫听穿林打叶声，何妨吟啸且徐行") to tr("苏轼《定风波》"),
        tr("此心安处是吾乡") to tr("苏轼《定风波》"),
        tr("且将新火试新茶，诗酒趁年华") to tr("苏轼《望江南》"),
        tr("一年好景君须记，最是橙黄橘绿时") to tr("苏轼《赠刘景文》"),
        tr("竹外桃花三两枝，春江水暖鸭先知") to tr("苏轼《惠崇春江晚景》"),
        tr("采菊东篱下，悠然见南山") to tr("陶渊明《饮酒》"),
        tr("晨兴理荒秽，带月荷锄归") to tr("陶渊明《归园田居》"),
        tr("行到水穷处，坐看云起时") to tr("王维《终南别业》"),
        tr("明月松间照，清泉石上流") to tr("王维《山居秋暝》"),
        tr("草木蔓发，春山可望") to tr("王维《山中与裴秀才迪书》"),
        tr("绿蚁新醅酒，红泥小火炉") to tr("白居易《问刘十九》"),
        tr("晚来天欲雪，能饮一杯无") to tr("白居易《问刘十九》"),
        tr("若无闲事挂心头，便是人间好时节") to tr("无门慧开《颂古》"),
        tr("沾衣欲湿杏花雨，吹面不寒杨柳风") to tr("志南《绝句》"),
        tr("等闲识得东风面，万紫千红总是春") to tr("朱熹《春日》"),
        tr("小楼一夜听春雨，深巷明朝卖杏花") to tr("陆游《临安春雨初霁》"),
        tr("山重水复疑无路，柳暗花明又一村") to tr("陆游《游山西村》"),
        tr("长风破浪会有时，直挂云帆济沧海") to tr("李白《行路难》"),
        tr("天生我材必有用") to tr("李白《将进酒》"),
        tr("最是一年春好处，绝胜烟柳满皇都") to tr("韩愈《早春呈水部张十八员外》"),
        tr("海内存知己，天涯若比邻") to tr("王勃《送杜少府之任蜀州》"),
        tr("莫愁前路无知己，天下谁人不识君") to tr("高适《别董大》"),
        tr("停车坐爱枫林晚，霜叶红于二月花") to tr("杜牧《山行》"),
        tr("稻花香里说丰年，听取蛙声一片") to tr("辛弃疾《西江月》"),
        tr("莫道桑榆晚，为霞尚满天") to tr("刘禹锡《酬乐天咏老见示》"),
        tr("自古逢秋悲寂寥，我言秋日胜春朝") to tr("刘禹锡《秋词》"),
        tr("好雨知时节，当春乃发生") to tr("杜甫《春夜喜雨》"),
        tr("迟日江山丽，春风花草香") to tr("杜甫《绝句》"),
        tr("万物静观皆自得，四时佳兴与人同") to tr("程颢《秋日》"),
        tr("春水碧于天，画船听雨眠") to tr("韦庄《菩萨蛮》"),
        tr("醉后不知天在水，满船清梦压星河") to tr("唐珙《题龙阳县青草湖》"),
        tr("卧看满天云不动，不知云与我俱东") to tr("陈与义《襄邑道中》"),
        tr("闲坐小窗读周易，不知春去几多时") to tr("叶采《暮春即事》"),
        tr("墙角数枝梅，凌寒独自开") to tr("王安石《梅花》"),
        tr("胜日寻芳泗水滨，无边光景一时新") to tr("朱熹《春日》")
    )
    private fun p(c: Context) = c.getSharedPreferences("liubai_ui", Context.MODE_PRIVATE)
    fun on(c: Context) = p(c).getBoolean("poems", true)
    fun setOn(c: Context, v: Boolean) = p(c).edit().putBoolean("poems", v).apply()
}

/** 首页问候区：开着诗句就每 12 秒换一句（点一下也换），关掉就是原来的问候。 */
@Composable
fun GreetingArea(poems: Boolean) {
    if (!poems) {
        Text(Stats.greeting(), fontSize = 27.sp, fontWeight = FontWeight.Bold, color = C.Ink, modifier = Modifier.padding(top = 4.dp))
        Text(tr("今天也温柔地对待自己"), fontSize = 14.sp, color = C.Soft, modifier = Modifier.padding(top = 4.dp))
        return
    }
    val n = Poems.ALL.size
    var i by remember { mutableStateOf(((System.currentTimeMillis() / 86_400_000L) * 7 % n).toInt()) }
    var bump by remember { mutableStateOf(0) }
    key(bump) { Every(12_000, now = false) { i = (i + 1) % n } }
    AnimatedContent(
        targetState = i,
        transitionSpec = { fadeIn(tween(700)) togetherWith fadeOut(tween(500)) },
        label = "poem",
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { i = (i + 1) % n; bump++ }
    ) { k ->
        val (line, src) = Poems.ALL[k]
        Column(Modifier.heightIn(min = 76.dp)) {
            Text(line.replace("，", "，\n"), fontSize = if (line.length > 9) 21.sp else 25.sp, lineHeight = 32.sp,
                fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Serif, color = C.Ink, letterSpacing = 1.sp)
            Text("— $src", fontSize = 12.sp, color = C.Faint, modifier = Modifier.padding(top = 6.dp))
        }
    }
}
