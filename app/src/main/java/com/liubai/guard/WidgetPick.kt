package com.liubai.guard

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 选小部件里展示哪几个受控应用。勾选顺序就是显示顺序；一个都不选 = 自动显示全部。 */
@Composable
fun WidgetScreen(onDone: (() -> Unit)? = null) {
    val c = LocalContext.current
    val apps = remember { Store.active(c).keys.map { Apps.info(c, it) } }
    val sel = remember { mutableStateListOf<String>().apply { addAll(WidgetSel.get(c).filter { p -> apps.any { it.pkg == p } }) } }
    fun save() { WidgetSel.set(c, sel.toList()); Widgets.updateAll(c, force = true) }
    Column(Modifier.fillMaxSize()) {
        Text(tr("小部件显示"), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = C.Ink)
        Text(
            tr("勾选的顺序就是桌面上的顺序：图标 4×1 放前 4 个，4×2 放前 8 个，进度列表放前 3 个，剩余圆环只算勾选的。一个都不选就自动显示全部。"),
            fontSize = 13.sp, color = C.Soft, lineHeight = 20.sp, modifier = Modifier.padding(top = 6.dp)
        )
        if (apps.isEmpty()) {
            Text(tr("还没有在守护的应用，先在「今天」里添加吧。"), fontSize = 14.sp, color = C.Faint, modifier = Modifier.padding(top = 24.dp))
        }
        LazyColumn(Modifier.weight(1f).padding(top = 10.dp)) {
            items(apps, key = { it.pkg }) { a ->
                val idx = sel.indexOf(a.pkg)
                Row(
                    Modifier.fillMaxWidth().clickable { if (idx >= 0) sel.remove(a.pkg) else sel.add(a.pkg); save() }
                        .padding(vertical = 12.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppIcon(a)
                    Spacer(Modifier.width(14.dp))
                    Text(a.label, fontSize = 16.sp, color = C.Ink, modifier = Modifier.weight(1f))
                    Box(
                        Modifier.size(26.dp).clip(CircleShape)
                            .then(if (idx >= 0) Modifier.background(C.Clay) else Modifier.border(2.dp, Color(0xFFDCCFC2), CircleShape)),
                        contentAlignment = Alignment.Center
                    ) { if (idx >= 0) Text("${idx + 1}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                }
                HorizontalDivider(color = C.Track)
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (sel.isEmpty()) tr("现在：自动显示全部") else tr("已选 {0} 个", sel.size), fontSize = 13.sp, color = C.Faint, modifier = Modifier.weight(1f))
            if (sel.isNotEmpty()) Text(tr("清空"), fontSize = 14.sp, color = C.ClayDeep,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { sel.clear(); save() }.padding(horizontal = 10.dp, vertical = 6.dp))
        }
        if (onDone != null) PrimaryButton(tr("好了"), Modifier.padding(bottom = 16.dp)) { save(); onDone() }
    }
}

/** 添加小部件 / 长按小部件「设置」时打开。 */
class WidgetPickActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val id = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        val ok = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        setResult(Activity.RESULT_OK, ok)   // 直接返回也算添加成功
        setContent {
            LiubaiTheme {
                Box(Modifier.fillMaxSize().background(C.Cream).systemBarsPadding().padding(horizontal = 22.dp, vertical = 14.dp)) {
                    WidgetScreen { setResult(Activity.RESULT_OK, ok); finish() }
                }
            }
        }
    }
}
