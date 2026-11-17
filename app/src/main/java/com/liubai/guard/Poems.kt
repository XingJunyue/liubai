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
        "人间有味是清欢" to "苏轼《浣溪沙》",
        "一蓑烟雨任平生" to "苏轼《定风波》",
        "莫听穿林打叶声，何妨吟啸且徐行" to "苏轼《定风波》",
        "此心安处是吾乡" to "苏轼《定风波》",
        "且将新火试新茶，诗酒趁年华" to "苏轼《望江南》",
        "一年好景君须记，最是橙黄橘绿时" to "苏轼《赠刘景文》",
        "竹外桃花三两枝，春江水暖鸭先知" to "苏轼《惠崇春江晚景》",
        "采菊东篱下，悠然见南山" to "陶渊明《饮酒》",
        "晨兴理荒秽，带月荷锄归" to "陶渊明《归园田居》",
        "行到水穷处，坐看云起时" to "王维《终南别业》",
        "明月松间照，清泉石上流" to "王维《山居秋暝》",
        "草木蔓发，春山可望" to "王维《山中与裴秀才迪书》",
        "绿蚁新醅酒，红泥小火炉" to "白居易《问刘十九》",
        "晚来天欲雪，能饮一杯无" to "白居易《问刘十九》",
        "若无闲事挂心头，便是人间好时节" to "无门慧开《颂古》",
        "沾衣欲湿杏花雨，吹面不寒杨柳风" to "志南《绝句》",
        "等闲识得东风面，万紫千红总是春" to "朱熹《春日》",
        "小楼一夜听春雨，深巷明朝卖杏花" to "陆游《临安春雨初霁》",
        "山重水复疑无路，柳暗花明又一村" to "陆游《游山西村》",
        "长风破浪会有时，直挂云帆济沧海" to "李白《行路难》",
        "天生我材必有用" to "李白《将进酒》",
        "最是一年春好处，绝胜烟柳满皇都" to "韩愈《早春呈水部张十八员外》",
        "海内存知己，天涯若比邻" to "王勃《送杜少府之任蜀州》",
        "莫愁前路无知己，天下谁人不识君" to "高适《别董大》",
        "停车坐爱枫林晚，霜叶红于二月花" to "杜牧《山行》",
        "稻花香里说丰年，听取蛙声一片" to "辛弃疾《西江月》",
        "莫道桑榆晚，为霞尚满天" to "刘禹锡《酬乐天咏老见示》",
        "自古逢秋悲寂寥，我言秋日胜春朝" to "刘禹锡《秋词》",
        "好雨知时节，当春乃发生" to "杜甫《春夜喜雨》",
        "迟日江山丽，春风花草香" to "杜甫《绝句》",
        "万物静观皆自得，四时佳兴与人同" to "程颢《秋日》",
        "春水碧于天，画船听雨眠" to "韦庄《菩萨蛮》",
        "醉后不知天在水，满船清梦压星河" to "唐珙《题龙阳县青草湖》",
        "卧看满天云不动，不知云与我俱东" to "陈与义《襄邑道中》",
        "闲坐小窗读周易，不知春去几多时" to "叶采《暮春即事》",
        "墙角数枝梅，凌寒独自开" to "王安石《梅花》",
        "胜日寻芳泗水滨，无边光景一时新" to "朱熹《春日》"
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
        Text("今天也温柔地对待自己", fontSize = 14.sp, color = C.Soft, modifier = Modifier.padding(top = 4.dp))
        return
    }
    val n = Poems.ALL.size
    var i by remember { mutableStateOf(((System.currentTimeMillis() / 86_400_000L) * 7 % n).toInt()) }
    var bump by remember { mutableStateOf(0) }
    LaunchedEffect(bump) { while (true) { delay(12_000); i = (i + 1) % n } }
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
