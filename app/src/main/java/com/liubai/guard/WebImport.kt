package com.liubai.guard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 从教务网站导入：先选学校 / 教务类型，再在内置浏览器里登录，点「导入课表」。
 * 抓取在网页里用 JS 做（带着登录后的 Cookie，从页面自己的域名发请求），结果交给 Jw 里的解析函数。
 */
private const val NEU = "https://jwxt.neu.edu.cn/"
private const val NEU_VPN = "https://webvpn.neu.edu.cn/"

private fun prefs(c: Context) = c.getSharedPreferences("jwimport", Context.MODE_PRIVATE)

@Volatile private var schoolCache: List<School>? = null
/** assets/schools.tsv（开源学校列表，见 Schools.kt / THIRD_PARTY_NOTICES.md）。 */
fun schoolsOf(c: Context): List<School> = schoolCache ?: try {
    Schools.parse(c.assets.open("schools.tsv").bufferedReader().use { it.readText() }).also { schoolCache = it }
} catch (x: Exception) { emptyList() }

private class Bridge(val cb: (String) -> Unit) {
    @JavascriptInterface fun post(s: String) { Handler(Looper.getMainLooper()).post { cb(s) } }
}

@Composable
fun JwImportScreen(onClose: () -> Unit, onParsed: (List<JwCourse>) -> Unit) {
    val c = LocalContext.current
    // kind：neu / zf / qz …；null = 还在选
    var kind by remember { mutableStateOf(TimetableTest.webKind) }
    var url by remember { mutableStateOf(TimetableTest.webUrl ?: "") }
    val k0 = kind
    if (k0 == null) {
        JwPicker(onClose) { k, u -> kind = k; url = u }
    } else {
        BackHandler { kind = null; TimetableTest.webKind = null; TimetableTest.webUrl = null }
        JwWeb(k0, url, onBack = { kind = null; TimetableTest.webKind = null; TimetableTest.webUrl = null }, onParsed = onParsed,
            onKind = { kind = it })
    }
}

@Composable
private fun JwPicker(onClose: () -> Unit, onPick: (String, String) -> Unit) {
    val c = LocalContext.current
    var q by remember { mutableStateOf(TimetableTest.query ?: "") }
    val schools = remember { schoolsOf(c) }
    val found = remember(q) { Schools.search(schools, q) }
    Column(Modifier.fillMaxSize().background(C.Cream)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", fontSize = 28.sp, color = C.Ink, modifier = Modifier.clip(CircleShape).clickable { onClose() }.padding(horizontal = 14.dp))
            Text(tr("从教务导入"), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = C.Ink)
        }
        Box(Modifier.padding(horizontal = 18.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.Card).padding(horizontal = 16.dp, vertical = 13.dp)) {
            if (q.isEmpty()) Text(tr("搜索学校或教务类型"), fontSize = 14.sp, color = C.Faint)
            BasicTextField(q, { q = it }, singleLine = true, textStyle = TextStyle(fontSize = 14.sp, color = C.Ink), modifier = Modifier.fillMaxWidth())
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            val t = q.trim().lowercase()
            fun hit(vararg s: String) = t.isEmpty() || s.any { it.lowercase().contains(t) }
            if (hit("东北大学", "neu", "东大", "金智")) {
                Text(tr("学校"), fontSize = 13.sp, color = C.Faint, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp, start = 4.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.Card).padding(16.dp)) {
                    Text(tr("东北大学"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = C.Ink)
                    Text(tr("金智新教务 · jwxt.neu.edu.cn · 统一身份认证登录"), fontSize = 12.sp, color = C.Faint, modifier = Modifier.padding(top = 2.dp))
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill(tr("校园网直连"), true, Modifier.weight(1f)) { onPick("neu", NEU) }
                        Pill(tr("校外 WebVPN"), false, Modifier.weight(1f)) { onPick("neu", NEU_VPN) }
                    }
                }
            }
            if (t.isEmpty() && schools.isNotEmpty()) Text(tr("输入学校名搜索，已收录 {0} 所学校的教务入口", schools.map { it.name }.distinct().size), fontSize = 12.sp, color = C.Faint,
                modifier = Modifier.padding(top = 12.dp, start = 4.dp))
            if (found.isNotEmpty()) {
                Text(tr("搜到的学校"), fontSize = 13.sp, color = C.Faint, modifier = Modifier.padding(top = 18.dp, bottom = 2.dp, start = 4.dp))
                Text(tr("学校列表来自开源项目，网址和类型不一定准；进去后可以改网址、点上方类型名换解析方式。"), fontSize = 11.sp, color = C.Faint, lineHeight = 16.sp,
                    modifier = Modifier.padding(bottom = 6.dp, start = 4.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.Card)) {
                    found.forEachIndexed { i, sc ->
                        if (i > 0) Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(C.Track.copy(alpha = 0.7f)))
                        val kk = Schools.kindOf(sc)
                        val kn = Jw.KINDS.firstOrNull { it.id == kk }?.name ?: kk
                        Row(Modifier.fillMaxWidth().clickable { onPick(kk, sc.url) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(sc.name, fontSize = 15.sp, color = C.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    if (sc.src == "s") Text(tr("有人适配过"), fontSize = 10.sp, color = C.SageInk,
                                        modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(6.dp)).background(C.Sand).padding(horizontal = 5.dp, vertical = 1.dp))
                                }
                                Text(tr(kn) + " · " + (android.net.Uri.parse(sc.url).host ?: sc.url), fontSize = 11.sp, color = C.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text("›", fontSize = 18.sp, color = C.Faint)
                        }
                    }
                }
            }
            val kinds = Jw.KINDS.filter { hit(it.name, it.note, it.id) }
            if (kinds.isNotEmpty()) {
                Text(tr("通用教务类型"), fontSize = 13.sp, color = C.Faint, modifier = Modifier.padding(top = 18.dp, bottom = 2.dp, start = 4.dp))
                Text(tr("学校不在上面？选你们教务系统的类型，再输入教务网址。登录页底部或网址里一般能看出是哪家。"), fontSize = 11.sp, color = C.Faint, lineHeight = 16.sp,
                    modifier = Modifier.padding(bottom = 6.dp, start = 4.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.Card)) {
                    kinds.forEachIndexed { i, k ->
                        if (i > 0) Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(C.Track.copy(alpha = 0.7f)))
                        Row(Modifier.fillMaxWidth().clickable { onPick(k.id, prefs(c).getString("url_" + k.id, "") ?: "") }.padding(horizontal = 16.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(tr(k.name), fontSize = 15.sp, color = C.Ink)
                                    if (k.exp) Text(tr("实验性"), fontSize = 10.sp, color = C.Soft,
                                        modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(6.dp)).background(C.Sand).padding(horizontal = 5.dp, vertical = 1.dp))
                                }
                                if (k.note.isNotEmpty()) Text(tr(k.note), fontSize = 11.sp, color = C.Faint)
                            }
                            Text("›", fontSize = 18.sp, color = C.Faint)
                        }
                    }
                }
            }
            Text(tr("账号密码只在学校的网页里输入，留白不保存也不上传；课表在手机上解析。"), fontSize = 11.sp, color = C.Faint, lineHeight = 16.sp,
                modifier = Modifier.padding(top = 14.dp, bottom = 30.dp, start = 4.dp))
        }
    }
}

@Composable
private fun Pill(t: String, main: Boolean, m: Modifier, onClick: () -> Unit) {
    Box(m.clip(RoundedCornerShape(12.dp)).background(if (main) C.Clay else C.Sand).clickable { onClick() }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(t, fontSize = 13.sp, color = if (main) Color.White else C.Ink, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun JwWeb(kind: String, start: String, onBack: () -> Unit, onParsed: (List<JwCourse>) -> Unit, onKind: (String) -> Unit = {}) {
    var pickKind by remember { mutableStateOf(false) }
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var addr by remember { mutableStateOf(start) }
    var progress by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var campuses by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var wv by remember { mutableStateOf<WebView?>(null) }
    val kname = if (kind == "neu") tr("东北大学") else Jw.KINDS.firstOrNull { it.id == kind }?.name?.let { tr(it) } ?: kind
    val vpn = addr.contains("webvpn.neu.edu.cn")

    fun go(u0: String) {
        val u = u0.trim().let { if (it.isEmpty() || it.contains("://")) it else "https://$it" }
        if (u.isEmpty()) return
        addr = u; err = null
        if (kind != "neu") prefs(c).edit().putString("url_$kind", u).apply()
        wv?.loadUrl(u)
    }
    fun grab(arg: String?) {
        val w = wv ?: return
        busy = true; err = null
        w.evaluateJavascript(jwScript(if (kind == "neu") "wisedu" else kind, arg), null)
    }
    val handle: (String) -> Unit = { s ->
        val j = try { JSONObject(s) } catch (x: Exception) { JSONObject().put("ok", false).put("err", "bad result") }
        if (!j.optBoolean("ok")) { busy = false; err = friendly(j.optString("err")) }
        else if (j.optString("need") == "campus") {
            busy = false
            val a = j.optJSONArray("campuses")
            campuses = (0 until (a?.length() ?: 0)).map { a!!.getJSONObject(it) }.map { it.optString("id") to it.optString("name") }
        } else scope.launch {
            val r = withContext(Dispatchers.Default) { try { Jw.parse(if (kind == "neu") "wisedu" else kind, s) } catch (x: Exception) { emptyList() } }
            busy = false
            if (r.isEmpty()) err = tr("没在这个页面找到课表。请先登录并打开「我的课表」页面，再点导入；也可以换一个教务类型试试。")
            else { CookieManager.getInstance().flush(); onParsed(r) }
        }
    }
    val onResult = rememberUpdatedState(handle)
    BackHandler { val w = wv; if (w != null && w.canGoBack()) w.goBack() else onBack() }

    Column(Modifier.fillMaxSize().background(C.Cream)) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 10.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", fontSize = 28.sp, color = C.Ink, modifier = Modifier.clip(CircleShape).clickable { onBack() }.padding(horizontal = 14.dp))
            Box(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(C.Card).padding(horizontal = 12.dp, vertical = 10.dp)) {
                if (addr.isEmpty()) Text(tr("输入你们学校的教务网址"), fontSize = 13.sp, color = C.Faint, maxLines = 1)
                BasicTextField(addr, { addr = it }, singleLine = true, textStyle = TextStyle(fontSize = 13.sp, color = C.Ink),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { go(addr) }), modifier = Modifier.fillMaxWidth())
            }
            Text(tr("前往"), fontSize = 13.sp, color = C.ClayDeep, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(10.dp)).clickable { go(addr) }.padding(horizontal = 8.dp, vertical = 8.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(kname + if (kind == "neu") "" else " ▾", fontSize = 12.sp, color = C.Soft, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = kind != "neu") { pickKind = true }.padding(horizontal = 4.dp, vertical = 3.dp))
            if (kind == "neu") {
                Spacer(Modifier.width(8.dp))
                Text(if (vpn) tr("WebVPN 已开") else tr("切到 WebVPN（校外）"), fontSize = 11.sp, color = if (vpn) Color.White else C.Ink, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (vpn) C.Sage else C.Sand).clickable { go(if (vpn) NEU else NEU_VPN) }.padding(horizontal = 8.dp, vertical = 3.dp))
            }
            Spacer(Modifier.weight(1f))
            Text(tr("刷新"), fontSize = 12.sp, color = C.Soft, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { wv?.reload() }.padding(6.dp))
        }
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, Modifier.fillMaxSize(), color = C.Clay, trackColor = Color.Transparent)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.setSupportZoom(true)
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    addJavascriptInterface(Bridge { onResult.value(it) }, "LiubaiJw")
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { addr = url }
                        override fun onPageFinished(view: WebView, url: String) {
                            addr = url; CookieManager.getInstance().flush()
                            if (TimetableTest.autoImport) { TimetableTest.autoImport = false; grab(null) }
                        }
                        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                            val s = req.url.scheme ?: return true
                            return s != "http" && s != "https" && s != "file"
                        }
                    }
                    webChromeClient = object : WebChromeClient() { override fun onProgressChanged(view: WebView, p: Int) { progress = p } }
                    wv = this
                    if (start.isNotBlank()) loadUrl(start)
                }
            }, onRelease = { it.stopLoading(); it.destroy() }, modifier = Modifier.fillMaxSize())
            if (start.isBlank() && progress == 0) Column(Modifier.fillMaxSize().background(C.Cream).padding(28.dp)) {
                Text(tr("在上面输入教务系统的网址"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = C.Ink)
                Text(tr("比如 jwxt.xxx.edu.cn。登录后打开「我的课表」或「学期课表」页面，再点下面的「导入课表」。"), fontSize = 13.sp, color = C.Soft, lineHeight = 20.sp, modifier = Modifier.padding(top = 6.dp))
            }
            // 底部：提示 + 导入按钮
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(14.dp).navigationBarsPadding()) {
                err?.let { e ->
                    Text(e, fontSize = 12.sp, color = C.ClayDeep, lineHeight = 17.sp, modifier = Modifier.fillMaxWidth().shadow(4.dp, RoundedCornerShape(14.dp))
                        .clip(RoundedCornerShape(14.dp)).background(C.Card).clickable { err = null }.padding(12.dp))
                    Spacer(Modifier.height(8.dp))
                }
                Row(Modifier.fillMaxWidth().shadow(8.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp)).background(C.Card).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (busy) tr("正在读取课表…") else tr("登录后打开课表页面，再点导入"), fontSize = 12.sp, color = C.Soft, lineHeight = 16.sp, modifier = Modifier.weight(1f),
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Box(Modifier.clip(RoundedCornerShape(16.dp)).background(if (busy) C.Track else C.Clay).clickable(enabled = !busy) { grab(null) }.padding(horizontal = 18.dp, vertical = 12.dp)) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = C.Clay, strokeWidth = 2.dp)
                        else Text(tr("导入课表"), fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
    if (pickKind) AlertDialog(onDismissRequest = { pickKind = false }, containerColor = C.Card,
        title = { Text(tr("换教务类型"), color = C.Ink) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(tr("导入不出来时换一个试试，网页和登录状态不变"), fontSize = 12.sp, color = C.Faint, modifier = Modifier.padding(bottom = 8.dp))
                for (k in Jw.KINDS) Text(tr(k.name) + if (k.exp) " · " + tr("实验性") else "", fontSize = 15.sp,
                    color = if (k.id == kind) C.ClayDeep else C.Ink, fontWeight = if (k.id == kind) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { pickKind = false; err = null; onKind(k.id) }.padding(vertical = 11.dp, horizontal = 4.dp))
            }
        },
        confirmButton = {}, dismissButton = { TextButton({ pickKind = false }) { Text(tr("取消"), color = C.Soft) } })
    campuses?.let { cs ->
        AlertDialog(onDismissRequest = { campuses = null }, containerColor = C.Card,
            title = { Text(tr("选择校区"), color = C.Ink) },
            text = {
                Column {
                    Text(tr("这学期你在好几个校区有课，选要导入的那个"), fontSize = 12.sp, color = C.Faint, modifier = Modifier.padding(bottom = 8.dp))
                    for ((id, n) in cs) Text(n, fontSize = 15.sp, color = C.Ink, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .clickable { campuses = null; grab(id) }.padding(vertical = 12.dp, horizontal = 4.dp))
                }
            },
            confirmButton = {}, dismissButton = { TextButton({ campuses = null }) { Text(tr("取消"), color = C.Soft) } })
    }
}

private fun friendly(e: String): String = when {
    e.contains("jwapp") -> tr("找不到教务系统的地址。用 WebVPN 时，请先在网页里打开「我的课表」页面再点导入。")
    e.contains("JSON") || e.contains("Unexpected token") || e.contains("term") -> tr("读不到课表，可能还没登录或登录已过期。请先登录，再点导入。")
    e.contains("HTTP") || e.contains("Failed to fetch") -> tr("教务系统没有响应：{0}。登录后再试，或者换个网络。", e.take(80))
    else -> tr("导入失败：{0}", e.take(120))
}

/** 注入网页的脚本：按教务类型抓数据，通过 LiubaiJw.post 回传 JSON。 */
fun jwScript(kind: String, arg: String?): String {
    val k = JSONObject.quote(kind); val a = if (arg == null) "null" else JSONObject.quote(arg)
    return """
(async function(){
const K=$k, ARG=$a;
const P=o=>{try{LiubaiJw.post(JSON.stringify(o))}catch(e){}};
function docs(){const out=[];const seen=new Set();function walk(w,d){if(d>4)return;try{const doc=w.document;if(!doc||seen.has(doc))return;seen.add(doc);out.push(doc);for(const f of doc.querySelectorAll('iframe,frame')){try{if(f.contentWindow)walk(f.contentWindow,d+1)}catch(e){}}}catch(e){}}walk(window,0);try{if(window.top!==window)walk(window.top,0)}catch(e){}return out;}
function htmls(){return docs().map(d=>{try{return d.documentElement.outerHTML}catch(e){return ''}});}
function hrefs(){const out=[];for(const d of docs()){try{out.push(d.location.href)}catch(e){}try{for(const e of d.querySelectorAll('a[href],iframe[src],frame[src]'))out.push(e.href||e.src)}catch(e){}}return out;}
function base(marker){for(const h of hrefs()){const i=h.indexOf(marker);if(i>=0)return h.substring(0,i);}return null;}
async function req(url,body){const o={method:body==null?'GET':'POST',credentials:'include'};if(body!=null){o.headers={'x-requested-with':'XMLHttpRequest','content-type':'application/x-www-form-urlencoded; charset=UTF-8'};o.body=body;}const r=await fetch(url,o);if(!r.ok)throw new Error('HTTP '+r.status);return await r.text();}
async function reqDecoded(url){const r=await fetch(url,{credentials:'include'});if(!r.ok)throw new Error('HTTP '+r.status);const buf=await r.arrayBuffer();const ct=(r.headers.get('content-type')||'').toLowerCase();let cs=(ct.match(/charset=([\w-]+)/)||[])[1];if(!cs){const t=new TextDecoder('utf-8').decode(buf);if(t.indexOf('\ufffd')<0)return t;cs='gbk';}try{return new TextDecoder(cs).decode(buf);}catch(e){return new TextDecoder('utf-8').decode(buf);}}
function enc(o){return Object.keys(o).map(k=>encodeURIComponent(k)+'='+encodeURIComponent(o[k])).join('&');}
function val(id){for(const d of docs()){try{const e=d.getElementById(id);if(e&&e.value)return e.value;}catch(x){}}return null;}
try{
 if(K==='wisedu'){
  let pre=base('/jwapp/');
  if(pre==null){ if(location.host.indexOf('webvpn')>=0) throw new Error('jwapp'); pre=location.origin; }
  const tj=JSON.parse(await req(pre+'/jwapp/sys/jwpubapp/modules/gg/cxmrxnxq.do','CSDM=SYS&ZCSDM=DQXNXQDM&SFSY=1'));
  const row=(((tj.datas||{}).cxmrxnxq||{}).rows||[])[0]; if(!row) throw new Error('term');
  const xn=row.XNXQDM; let campus=ARG;
  if(campus==null){
   let cs=[]; try{const cj=JSON.parse(await req(pre+'/jwapp/sys/kbapp/api/wdkbcx/getMyScheduledCampus.do',enc({XNXQDM:xn})));cs=((cj.datas||{}).getMyScheduledCampus)||[];}catch(e){}
   if(cs.length>1){P({ok:true,need:'campus',term:row.XNXQMC||xn,campuses:cs.map(c=>({id:String(c.id),name:String(c.name)}))});return;}
   campus=cs.length?String(cs[0].id):'';
  }
  const b={XNXQDM:xn}; if(campus) b.XQDM=campus;
  const dj=await req(pre+'/jwapp/sys/kbapp/api/wdkbcx/getMyScheduleDetail.do',enc(b));
  P({ok:true,kind:K,term:row.XNXQMC||xn,termRow:row,json:dj}); return;
 }
 if(K==='zf'){
  let b=base('/jwglxt/'); b=b!=null?b+'/jwglxt':(base('/kbcx/')||base('/xtgl/')||location.origin);
  const now=new Date(); const y=now.getFullYear(), m=now.getMonth()+1;
  const xnm=val('xnm')||String(m>=8?y:y-1); const xqm=val('xqm')||((m>=8||m<=1)?'3':'12');
  const ok=t=>{try{const j=JSON.parse(t);return ((j.kbList||(j.data||{}).kbList)||[]).length>0;}catch(e){return false;}};
  let json=null; try{json=await req(b+'/kbcx/xskbcx_cxXsKb.html?gnmkdm=N2151',enc({xnm:xnm,xqm:xqm,kzlx:'ck'}));}catch(e){}
  // 有的学校只开了另一个接口（baoozak/timetable zhengfang_new.js）
  if(!json||!ok(json)){try{const t=await req(b+'/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N253508',enc({xnm:xnm,xqm:xqm}));if(ok(t))json=t;}catch(e){}}
  P({ok:true,kind:K,json:json,html:htmls()}); return;
 }
 if(K==='cx'){
  // 超星：页面上的 xhid / xqdm / 学期下拉框，没有就去课表页拿，再请求 sdpkkbList（shiguang_warehouse chaoxing.js）
  const o=base('/admin/')||location.origin; const pick=(d,id)=>{try{const e=d.getElementById(id);return (e&&e.value)||null;}catch(x){return null;}};
  let xhid=null,xqdm=null,xnxq=null;
  for(const d of docs()){xhid=xhid||pick(d,'xhid');xqdm=xqdm||pick(d,'xqdm');xnxq=xnxq||pick(d,'xnxq1');}
  if(!xhid||!xqdm){try{const d=new DOMParser().parseFromString(await req(o+'/admin/pkgl/xskb/queryKbForXsd',null),'text/html');xhid=xhid||pick(d,'xhid');xqdm=xqdm||pick(d,'xqdm');xnxq=xnxq||pick(d,'xnxq1');}catch(e){}}
  if(!xnxq){const n=new Date(),y=n.getFullYear(),m=n.getMonth()+1;xnxq=m>=8?y+'-'+(y+1)+'-1':(m<=1?(y-1)+'-'+y+'-1':(y-1)+'-'+y+'-2');}
  let json=null; if(xhid&&xqdm){try{json=await req(o+'/admin/xsd/pkgl/xskb/sdpkkbList?'+enc({xnxq:xnxq,xhid:xhid,xqdm:xqdm,xskbxslx:'0'}),null);}catch(e){}}
  P({ok:true,kind:K,json:json,html:htmls()}); return;
 }
 if(K==='urp'){
  // 旧 URP：当前页没有选课列表就去取「本学期课表」（页面常是 GBK）
  const hs=htmls();
  if(!hs.some(h=>h.indexOf('课程名')>=0)){ const b=base('/xkAction.do')||base('/menu/')||location.origin; try{hs.push(await reqDecoded(b+'/xkAction.do?actionType=6'));}catch(e){} }
  P({ok:true,kind:K,html:hs}); return;
 }
 if(K==='qz'){
  const hs=htmls();
  if(!hs.some(h=>h.indexOf('kbcontent')>=0)){ const b=(base('/jsxsd')||location.origin)+'/jsxsd/xskb/xskb_list.do'; try{hs.push(await req(b,null));}catch(e){} }
  P({ok:true,kind:K,html:hs}); return;
 }
 if(K==='urpnew'){
  const b=base('/student/')||location.origin; let json=null;
  for(const p of ['/student/courseSelect/thisSemesterCurriculum/ajaxStudentSchedule/callback','/student/courseSelect/thisSemesterCurriculum/ajaxStudentSchedule/curr/callback']){
   try{const t=await req(b+p,'');if(t.trim().charAt(0)==='{'){json=t;break;}}catch(e){}
  }
  P({ok:true,kind:K,json:json,html:htmls()}); return;
 }
 P({ok:true,kind:K,html:htmls()});
}catch(e){P({ok:false,err:String(e&&e.message||e)});}
})();
"""
}
