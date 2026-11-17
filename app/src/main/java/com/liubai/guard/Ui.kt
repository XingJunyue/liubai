package com.liubai.guard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object C {
    val Cream = Color(0xFFFBF6EF)
    val Card = Color(0xFFFFFDF9)
    val Ink = Color(0xFF3D3530)
    val Soft = Color(0xFF8C7E72)
    val Faint = Color(0xFFA39589)
    val Clay = Color(0xFFE38B6D)
    val ClayDeep = Color(0xFFC46F52)
    val ClayPale = Color(0xFFFBEFE6)
    val Track = Color(0xFFF1E8DE)
    val RingTrack = Color(0xFFF3DCCB)
    val Sage = Color(0xFF7FA88B)
    val SageLight = Color(0xFF9DC2A6)
    val SagePale = Color(0xFFEAF2EC)
    val SageInk = Color(0xFF5E8A6B)
    val Sand = Color(0xFFF3ECE3)
}

val LiubaiColors = lightColorScheme(
    primary = C.Clay, onPrimary = Color.White, background = C.Cream, surface = C.Card,
    onBackground = C.Ink, onSurface = C.Ink, secondary = C.Sage
)

@Composable
fun LiubaiTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = LiubaiColors, content = content)

@Composable
fun SoftCard(modifier: Modifier = Modifier, brush: Brush? = null, pad: Dp = 18.dp, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier
            .shadow(10.dp, shape, ambientColor = Color(0x22785A3C), spotColor = Color(0x22785A3C))
            .clip(shape)
            .then(if (brush != null) Modifier.background(brush) else Modifier.background(C.Card))
            .padding(pad),
        content = content
    )
}

@Composable
fun Chip(text: String, warm: Boolean = false) {
    Text(
        text,
        fontSize = 12.sp,
        color = if (warm) C.ClayDeep else C.SageInk,
        modifier = Modifier
            .padding(top = 8.dp, end = 6.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (warm) C.ClayPale else C.SagePale)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
fun SoftBar(frac: Float, done: Boolean) {
    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(8.dp)).background(C.Track)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(frac.coerceIn(0f, 1f)).clip(RoundedCornerShape(8.dp)).background(if (done) C.Sage else C.Clay))
    }
}

@Composable
fun AppIcon(app: AppInfo, size: Dp = 46.dp) {
    val m = Modifier.size(size).clip(RoundedCornerShape(size * 0.3f))
    if (app.icon != null) Image(app.icon, null, m)
    else Box(m.background(C.Sand), contentAlignment = Alignment.Center) { Text(app.label.take(1), color = C.Soft, fontWeight = FontWeight.Bold) }
}

@Composable
fun Ring(frac: Float, size: Dp = 118.dp, stroke: Dp = 11.dp, content: @Composable () -> Unit) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = stroke.toPx()
            val tl = Offset(sw / 2, sw / 2)
            val sz = Size(this.size.width - sw, this.size.height - sw)
            drawArc(C.RingTrack, 0f, 360f, false, tl, sz, style = Stroke(sw))
            if (frac > 0f) drawArc(C.Clay, -90f, 360f * frac.coerceIn(0f, 1f), false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
        }
        content()
    }
}

/** 品牌标记：留了缺口的时间环 + 小芽。 */
@Composable
fun Sprout(size: Dp, ring: Boolean = true, moon: Boolean = false) {
    Canvas(Modifier.size(size)) {
        val u = this.size.width / 108f
        if (ring) {
            val sw = 7f * u
            drawArc(C.Clay, -90f, -(360f - 60f), false, Offset(24f * u, 24f * u), Size(60f * u, 60f * u), style = Stroke(sw, cap = StrokeCap.Round))
        }
        if (moon) {
            drawCircle(Color(0xFFFFF4EA), 46f * u, Offset(54f * u, 54f * u))
            drawCircle(Color(0xFFF2C9A8), 15f * u, Offset(64f * u, 40f * u))
            drawCircle(Color(0xFFFFF4EA), 13f * u, Offset(70f * u, 35f * u))
        }
        val oy = if (moon) 14f * u else 0f
        drawLine(C.Sage, Offset(54f * u, 70f * u + oy), Offset(54f * u, 54f * u + oy), 3.6f * u, StrokeCap.Round)
        val l = Path().apply {
            moveTo(54f * u, 56f * u + oy); cubicTo(51f * u, 47f * u + oy, 44f * u, 43f * u + oy, 37f * u, 45f * u + oy)
            cubicTo(39f * u, 53f * u + oy, 46f * u, 58f * u + oy, 54f * u, 56f * u + oy); close()
        }
        val r = Path().apply {
            moveTo(54f * u, 52f * u + oy); cubicTo(57f * u, 43f * u + oy, 64f * u, 39f * u + oy, 71f * u, 41f * u + oy)
            cubicTo(69f * u, 49f * u + oy, 62f * u, 54f * u + oy, 54f * u, 52f * u + oy); close()
        }
        drawPath(l, C.Sage); drawPath(r, C.SageLight)
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick, modifier.fillMaxWidth().height(56.dp), enabled = enabled,
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(containerColor = C.Clay, contentColor = Color.White, disabledContainerColor = C.Track)
    ) { Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
}

/**
 * 只在界面真正显示在前台时才循环执行：切到后台、锁屏后立即停下，回来再继续。
 * （之前的 while(true) 循环在后台也一直跑，白白耗电。）
 */
@Composable
fun Every(ms: Long, now: Boolean = true, block: () -> Unit) {
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var resumed by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
    }
    androidx.compose.runtime.DisposableEffect(owner) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, _ ->
            resumed = owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    val cb = androidx.compose.runtime.rememberUpdatedState(block)
    androidx.compose.runtime.LaunchedEffect(resumed) {
        if (resumed && now) cb.value()
        while (resumed) { kotlinx.coroutines.delay(ms); cb.value() }
    }
}
