package com.liubai.guard

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * 外观：背景、所有按键的位置 / 文字 / 颜色 / 大小。按键做什么不能改。
 * 改动立刻生效（读 Look.v 的界面会自动刷新）。
 */
object Look {
    var v by mutableStateOf(0)
        private set
    private var sp: android.content.SharedPreferences? = null
    private var bmp: ImageBitmap? = null
    private var bmpVer = -1

    fun init(c: Context) { if (sp == null) sp = c.applicationContext.getSharedPreferences("look", Context.MODE_PRIVATE) }
    private fun p() = sp

    fun str(k: String, d: String): String { v; return p()?.getString(k, null) ?: d }
    fun num(k: String, d: Float): Float { v; return p()?.getFloat(k, d) ?: d }
    fun col(k: String, d: Color): Color { v; val x = p()?.getLong(k, -1L) ?: -1L; return if (x < 0) d else Color(x.toInt()) }
    fun has(k: String): Boolean { v; return p()?.contains(k) == true }

    fun put(k: String, s: String?) { p()?.edit()?.apply { if (s == null) remove(k) else putString(k, s) }?.apply(); v++ }
    fun putNum(k: String, f: Float) { p()?.edit()?.putFloat(k, f)?.apply(); v++ }
    fun putCol(k: String, c: Color?) {
        p()?.edit()?.apply { if (c == null) remove(k) else putLong(k, (argb(c).toLong() and 0xFFFFFFFFL)) }?.apply(); v++
    }
    fun resetAll(c: Context) { p()?.edit()?.clear()?.apply(); File(c.filesDir, "bg.jpg").delete(); bmp = null; v++ }

    fun argb(c: Color): Int = android.graphics.Color.argb((c.alpha * 255).toInt(), (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())

    /** 按键上的字：用户改过就用改过的。 */
    fun label(key: String, d: String) = str("t_$key", d).ifBlank { d }

    // ---- 背景 ----
    fun bgMode() = str("bg", "none")          // none / color / image
    fun bgColor() = col("bgc", C.Cream)
    fun dim() = num("dim", 0.25f)             // 图片上盖一层淡色，字才看得清
    fun cardAlpha() = num("card", 1f)         // 卡片不透明度

    fun bgImage(c: Context): ImageBitmap? {
        v
        if (bmp != null && bmpVer == v) return bmp
        val f = File(c.filesDir, "bg.jpg")
        bmp = if (f.exists()) try { BitmapFactory.decodeFile(f.path)?.asImageBitmap() } catch (x: Throwable) { null } else null
        bmpVer = v
        return bmp
    }

    fun saveImage(c: Context, uri: Uri): Boolean = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1
        while (o.outWidth / (s * 2) >= 1440 || o.outHeight / (s * 2) >= 2560) s *= 2
        val b = c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) }
        if (b == null) false else {
            File(c.filesDir, "bg.jpg").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
            put("bg", "image"); true
        }
    } catch (x: Throwable) { false }

    // ---- 首页三个按键 ----
    val CORNERS = listOf("tl" to tr("左上"), "tr" to tr("右上"), "bl" to tr("左下"), "br" to tr("右下"))
    fun pos(key: String) = str("p_$key", when (key) { "menu" -> "tl"; "focus" -> "tr"; else -> "br" })
    fun scale(key: String) = num("s_$key", 1f)
    fun keyBg(key: String) = col("b_$key", if (key == "add") C.Clay else C.Card)
    fun keyFg(key: String) = col("f_$key", if (key == "add") Color.White else C.Ink)
    fun hidden(key: String) = str("h_$key", "0") == "1"

    // ---- 所有大按钮 ----
    fun btnBg() = col("btn_bg", C.Clay)
    fun btnFg() = col("btn_fg", Color.White)
    fun btnRadius() = num("btn_r", 20f)
    fun btnHeight() = num("btn_h", 56f)
    fun btnFont() = num("btn_font", 16f)

    /** 可以改字的按键：key 到默认文字。 */
    val NAMES = listOf(
        "menu_home" to tr("今天"), "menu_groups" to tr("分组额度"), "menu_time" to tr("时段"), "menu_data" to tr("数据"),
        "menu_widget" to tr("小部件"), "menu_guide" to tr("使用说明"), "menu_update" to tr("检查更新"),
        "focus" to tr("专注"), "add" to tr("＋ 添加应用"),
        "pb_回到桌面" to tr("回到桌面"), "pb_算了，不刷了" to tr("算了，不刷了"), "pb_算了，继续守护" to tr("算了，继续守护"),
        "pb_去卸载留白" to tr("去卸载留白"), "pb_只是关一下守护，回桌面" to tr("只是关一下守护，回桌面"),
        "pb_开始守护" to tr("开始守护"), "pb_保存" to tr("保存"), "pb_去开启" to tr("去开启"), "pb_去打开" to tr("去打开"),
        "pb_开启卸载锁" to tr("开启卸载锁"), "pb_我知道了，开始" to tr("我知道了，开始"), "pb_检查更新" to tr("检查更新"),
        "pb_下载并安装" to tr("下载并安装"), "pb_安装" to tr("安装"), "pb_复制诊断信息" to tr("复制诊断信息")
    )

    val SWATCH = listOf(
        Color(0xFFFBF6EF), Color(0xFFFFFFFF), Color(0xFFF3ECE3), Color(0xFFEAF2EC), Color(0xFFE8EEF6), Color(0xFFF6E8EE),
        Color(0xFFE38B6D), Color(0xFFC46F52), Color(0xFF7FA88B), Color(0xFF5E8A6B), Color(0xFF6B8DB5), Color(0xFF9A7BB0),
        Color(0xFF3D3530), Color(0xFF8C7E72), Color(0xFF1F2226), Color(0xFF000000)
    )
}

/** 整个应用的背景：纯色或图片，图片上盖一层淡色。 */
@Composable
fun LookBackground(content: @Composable () -> Unit) {
    val c = LocalContext.current
    remember { Look.init(c); 0 }
    Box(Modifier.fillMaxSize().background(if (Look.bgMode() == "color") Look.bgColor() else C.Cream)) {
        if (Look.bgMode() == "image") {
            Look.bgImage(c)?.let {
                Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(C.Cream.copy(alpha = Look.dim())))
            }
        }
        content()
    }
}

@Composable
private fun LookTitle(t: String) =
    Text(t, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = C.Ink, modifier = Modifier.padding(top = 20.dp, bottom = 10.dp, start = 4.dp))

@Composable
private fun Small(t: String) = Text(t, fontSize = 13.sp, color = C.Soft, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))

@Composable
fun Swatches(cur: Color, onPick: (Color) -> Unit) {
    var hex by remember(cur) { mutableStateOf(String.format("%06X", Look.argb(cur) and 0xFFFFFF)) }
    Column {
        Look.SWATCH.chunked(8).forEach { row ->
            Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { s ->
                    Box(Modifier.size(30.dp).clip(CircleShape).background(s)
                        .border(if (Look.argb(s) == Look.argb(cur)) 3.dp else 1.dp, if (Look.argb(s) == Look.argb(cur)) C.ClayDeep else C.Track, CircleShape)
                        .clickable { onPick(s) })
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Text("#", color = C.Soft, fontSize = 14.sp)
            BasicTextField(hex, { t ->
                hex = t.uppercase().filter { it in "0123456789ABCDEF" }.take(6)
                if (hex.length == 6) onPick(Color(0xFF000000 or hex.toLong(16)))
            }, singleLine = true, textStyle = TextStyle(fontSize = 14.sp, color = C.Ink),
                modifier = Modifier.width(90.dp).clip(RoundedCornerShape(10.dp)).background(C.Sand).padding(horizontal = 10.dp, vertical = 8.dp))
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(24.dp).clip(CircleShape).background(cur).border(1.dp, C.Track, CircleShape))
        }
    }
}

@Composable
private fun Seg(opts: List<Pair<String, String>>, cur: String, onPick: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        opts.forEach { (k, t) ->
            Text(t, fontSize = 14.sp, color = if (k == cur) Color.White else C.Ink,
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (k == cur) C.Clay else C.Sand)
                    .clickable { onPick(k) }.padding(horizontal = 14.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun Num(label: String, v: Float, range: ClosedFloatingPointRange<Float>, show: String, onV: (Float) -> Unit) {
    Small("$label  $show")
    Slider(v, onV, valueRange = range, colors = SliderDefaults.colors(thumbColor = C.Clay, activeTrackColor = C.Clay, inactiveTrackColor = C.Track))
}

@Composable
private fun NameField(key: String, d: String) {
    var t by remember(key, Look.v) { mutableStateOf(Look.str("t_$key", "")) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(d, fontSize = 13.sp, color = C.Soft, modifier = Modifier.width(120.dp), maxLines = 2)
        BasicTextField(t, { t = it.take(16); Look.put("t_$key", t.ifBlank { null }) }, singleLine = true,
            textStyle = TextStyle(fontSize = 14.sp, color = C.Ink),
            decorationBox = { inner -> Box { if (t.isEmpty()) Text(d, fontSize = 14.sp, color = C.Faint); inner() } },
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(C.Sand).padding(horizontal = 12.dp, vertical = 9.dp))
    }
}

@Composable
fun LookScreen() {
    val c = LocalContext.current
    remember { Look.init(c); 0 }
    var msg by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null) msg = if (Look.saveImage(c, u)) null else tr("这张图片读不出来，换一张试试")
    }
    var key by remember { mutableStateOf("focus") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
        Text(tr("外观"), fontSize = 27.sp, fontWeight = FontWeight.Bold, color = C.Ink, modifier = Modifier.padding(top = 14.dp))
        Text(tr("背景和按键都能换成你喜欢的样子，按键做的事不变"), fontSize = 14.sp, color = C.Soft, modifier = Modifier.padding(top = 4.dp))

        LookTitle(tr("背景"))
        SoftCard(Modifier.fillMaxWidth()) {
            Seg(listOf("none" to tr("默认"), "color" to tr("纯色"), "image" to tr("图片")), Look.bgMode()) {
                if (it == "image") pick.launch("image/*") else Look.put("bg", it)
            }
            if (Look.bgMode() == "color") { Small(tr("颜色")); Swatches(Look.bgColor()) { Look.putCol("bgc", it) } }
            if (Look.bgMode() == "image") {
                Text(tr("换一张图片"), fontSize = 14.sp, color = C.ClayDeep,
                    modifier = Modifier.padding(top = 10.dp).clip(RoundedCornerShape(12.dp)).clickable { pick.launch("image/*") }.padding(vertical = 6.dp))
                Num(tr("图片上的淡色遮罩"), Look.dim(), 0f..0.9f, "${(Look.dim() * 100).toInt()}%") { Look.putNum("dim", it) }
            }
            msg?.let { Text(it, fontSize = 13.sp, color = C.ClayDeep, modifier = Modifier.padding(top = 8.dp)) }
            Num(tr("卡片不透明度"), Look.cardAlpha(), 0.3f..1f, "${(Look.cardAlpha() * 100).toInt()}%") { Look.putNum("card", it) }
        }

        LookTitle(tr("首页按键"))
        SoftCard(Modifier.fillMaxWidth()) {
            Seg(listOf("menu" to tr("菜单"), "focus" to tr("专注"), "add" to tr("添加应用")), key) { key = it }
            Small(tr("位置"))
            Seg(Look.CORNERS, Look.pos(key)) { Look.put("p_$key", it) }
            if (key != "menu") { Small(tr("文字")); NameField(key, if (key == "focus") tr("专注") else tr("＋ 添加应用")) }
            Num(tr("大小"), Look.scale(key), 0.6f..1.8f, "${(Look.scale(key) * 100).toInt()}%") { Look.putNum("s_$key", it) }
            Small(tr("底色")); Swatches(Look.keyBg(key)) { Look.putCol("b_$key", it) }
            Small(tr("文字 / 图标颜色")); Swatches(Look.keyFg(key)) { Look.putCol("f_$key", it) }
        }

        LookTitle(tr("所有大按钮"))
        SoftCard(Modifier.fillMaxWidth()) {
            PrimaryButton(tr("预览效果")) {}
            Small(tr("底色")); Swatches(Look.btnBg()) { Look.putCol("btn_bg", it) }
            Small(tr("文字颜色")); Swatches(Look.btnFg()) { Look.putCol("btn_fg", it) }
            Num(tr("圆角"), Look.btnRadius(), 0f..32f, "${Look.btnRadius().toInt()}") { Look.putNum("btn_r", it) }
            Num(tr("高度"), Look.btnHeight(), 40f..80f, "${Look.btnHeight().toInt()}") { Look.putNum("btn_h", it) }
            Num(tr("字号"), Look.btnFont(), 12f..24f, "${Look.btnFont().toInt()}") { Look.putNum("btn_font", it) }
        }

        LookTitle(tr("按键文字"))
        SoftCard(Modifier.fillMaxWidth()) {
            Text(tr("留空就是原来的字"), fontSize = 12.sp, color = C.Faint, modifier = Modifier.padding(bottom = 6.dp))
            Look.NAMES.forEach { (k, d) -> NameField(k, d) }
        }

        Text(tr("全部恢复默认"), fontSize = 14.sp, color = C.ClayDeep,
            modifier = Modifier.padding(top = 22.dp, bottom = 40.dp).clip(RoundedCornerShape(12.dp)).clickable { Look.resetAll(c) }.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}

