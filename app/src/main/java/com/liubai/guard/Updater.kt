package com.liubai.guard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class Release(val code: Int, val name: String, val notes: String, val url: String)

object Updater {
    const val REPO = "XingJunyue/liubai"

    /** 直连优先，失败再走国内加速镜像（只代理 github.com 的下载链接）。 */
    val MIRRORS = listOf("", "https://ghfast.top/", "https://gh-proxy.com/", "https://ghproxy.net/")
    @Volatile var lastError = ""
    @Volatile private var mirror = ""

    private fun open(url: String, timeout: Int = 8_000): HttpURLConnection {
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = timeout; con.readTimeout = timeout
        con.setRequestProperty("User-Agent", "liubai")
        return con
    }

    /** 各个来源都问一遍，取最大的版本号；镜像会缓存跳转，所以不能拿到第一个就停。 */
    fun latest(): Release? {
        lastError = ""
        val bust = "?t=" + System.currentTimeMillis()
        var best: Release? = null
        var bestMirror = ""
        fun take(r: Release, m: String) { if (best == null || r.code > best!!.code) { best = r; bestMirror = m } }
        // 1) GitHub API：列出最近的发布（有更新说明）
        try {
            val con = open("https://api.github.com/repos/$REPO/releases?per_page=5")
            con.setRequestProperty("Accept", "application/vnd.github+json")
            con.setRequestProperty("Cache-Control", "no-cache")
            val rc = con.responseCode
            if (rc == 404) lastError = "private"
            if (rc == 200) {
                val arr = org.json.JSONArray(con.inputStream.bufferedReader().readText())
                for (k in 0 until arr.length()) {
                    val j = arr.getJSONObject(k)
                    if (j.optBoolean("draft") || j.optBoolean("prerelease")) continue
                    val code = j.optString("tag_name").substringAfterLast('-').toIntOrNull() ?: continue
                    take(Release(code, j.optString("name"), j.optString("body"), apk(code)), "")
                }
            }
        } catch (x: Exception) {}
        // 2) 每个镜像读 releases/latest 的跳转
        for (m in MIRRORS) {
            try {
                val con = open(m + "https://github.com/$REPO/releases/latest" + bust, 6_000)
                con.instanceFollowRedirects = false
                con.setRequestProperty("Cache-Control", "no-cache")
                val rc = con.responseCode
                val loc = con.getHeaderField("Location") ?: ""
                val code = Regex("build-(\\d+)").find(loc)?.groupValues?.get(1)?.toIntOrNull()
                if (rc in 300..399 && code != null) take(Release(code, "留白 1.$code", "", apk(code)), m)
                if (rc == 404 && best == null) lastError = "private"
            } catch (x: Exception) {}
        }
        // 3) 发布的 atom 订阅，再兜底一次
        for (m in MIRRORS.take(2)) {
            try {
                val con = open(m + "https://github.com/$REPO/releases.atom" + bust, 6_000)
                if (con.responseCode == 200) {
                    val txt = con.inputStream.bufferedReader().readText()
                    Regex("build-(\\d+)").findAll(txt).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull()
                        ?.let { take(Release(it, "留白 1.$it", "", apk(it)), m) }
                }
            } catch (x: Exception) {}
        }
        if (best == null && lastError.isEmpty()) lastError = "network"
        if (best != null) mirror = bestMirror
        return best
    }

    private fun apk(code: Int) = "https://github.com/$REPO/releases/download/build-$code/liubai-1.$code.apk"

    fun download(c: Context, url: String, progress: (Float) -> Unit): File {
        val order = listOf(mirror) + MIRRORS.filter { it != mirror }
        var last: Exception? = null
        val want = Regex("build-(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull()
        for (m in order) {
            try {
                val f = fetch(c, m + url, progress)
                // 镜像可能给了旧缓存或残缺文件：核对安装包里的版本号，不对就换下一个
                val got = try { c.packageManager.getPackageArchiveInfo(f.path, 0)?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt() else @Suppress("DEPRECATION") it.versionCode } } catch (x: Exception) { null }
                if (got == null || (want != null && got < want)) { last = Exception("bad apk $got"); continue }
                return f
            } catch (x: Exception) { last = x }
        }
        throw last ?: Exception("download failed")
    }

    private fun fetch(c: Context, url: String, progress: (Float) -> Unit): File {
        val dir = File(c.filesDir, "update").apply { mkdirs() }
        val f = File(dir, "liubai.apk")
        var u = URL(url)
        var con: HttpURLConnection
        var hops = 0
        while (true) {
            con = u.openConnection() as HttpURLConnection
            con.instanceFollowRedirects = false
            con.connectTimeout = 10_000; con.readTimeout = 20_000
            con.setRequestProperty("User-Agent", "liubai")
            val rc = con.responseCode
            if (rc in 300..399 && hops++ < 5) { u = URL(u, con.getHeaderField("Location")); continue }
            break
        }
        if (con.responseCode != 200) throw Exception("http ${con.responseCode}")
        val len = con.contentLengthLong
        con.inputStream.use { inp ->
            f.outputStream().use { out ->
                val buf = ByteArray(32 * 1024); var n: Int; var got = 0L
                while (inp.read(buf).also { n = it } > 0) {
                    out.write(buf, 0, n); got += n
                    if (len > 0) progress(got.toFloat() / len)
                }
            }
        }
        return f
    }

    fun canInstall(c: Context) = Build.VERSION.SDK_INT < 26 || c.packageManager.canRequestPackageInstalls()

    fun install(c: Context, f: File) {
        val uri = FileProvider.getUriForFile(c, "${c.packageName}.files", f)
        c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
fun AboutScreen() {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf("idle") } // idle checking latest found downloading ready error noperm
    var rel by remember { mutableStateOf<Release?>(null) }
    var prog by remember { mutableStateOf(0f) }
    var file by remember { mutableStateOf<File?>(null) }

    fun check() {
        state = "checking"
        scope.launch {
            val r = withContext(Dispatchers.IO) { try { Updater.latest() } catch (x: Exception) { null } }
            rel = r
            state = when { r == null -> "error"; r.code > BuildConfig.VERSION_CODE -> "found"; else -> "latest" }
        }
    }
    // 一打开就查，不用再点一次
    LaunchedEffect(Unit) { check() }
    fun get() {
        val r = rel ?: return
        if (!Updater.canInstall(c)) {
            state = "noperm"
            c.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        state = "downloading"; prog = 0f
        scope.launch {
            val f = withContext(Dispatchers.IO) {
                try { Updater.download(c, r.url) { p -> prog = p } } catch (x: Exception) { null }
            }
            if (f == null) state = "error" else { file = f; state = "ready"; Updater.install(c, f) }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
        Spacer(Modifier.height(8.dp))
        Text("关于与更新", fontSize = 27.sp, fontWeight = FontWeight.Bold, color = C.Ink)
        Spacer(Modifier.height(18.dp))
        SoftCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Sprout(72.dp, ring = true)
                Text("留白", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = C.Ink, modifier = Modifier.padding(top = 8.dp))
                Text("版本 ${BuildConfig.VERSION_NAME}", fontSize = 14.sp, color = C.Soft, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(18.dp))
            val r = rel
            when (state) {
                "checking" -> Text("正在看看有没有新版本…", fontSize = 14.sp, color = C.Soft)
                "latest" -> Text("已经是最新版本啦", fontSize = 14.sp, color = C.SageInk)
                "error" -> Text(if (Updater.lastError == "private") "仓库还没公开，暂时拿不到新版本" else "直连和镜像都没连上，换个网络再试试", fontSize = 14.sp, color = C.ClayDeep, lineHeight = 20.sp)
                "noperm" -> Text("请允许留白「安装未知应用」，回来再点一次更新", fontSize = 14.sp, color = C.ClayDeep, lineHeight = 20.sp)
                "found", "downloading", "ready" -> if (r != null) {
                    Text("发现新版本 ${r.name}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = C.Ink)
                    if (r.notes.isNotBlank()) Text(r.notes, fontSize = 13.sp, color = C.Soft, lineHeight = 20.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
            if (state == "downloading") {
                LinearProgressIndicator(progress = prog, color = C.Clay, trackColor = C.Track,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(8.dp))
            }
            Spacer(Modifier.height(14.dp))
            when (state) {
                "found", "noperm" -> PrimaryButton("下载并安装") { get() }
                "downloading" -> PrimaryButton("下载中 ${(prog * 100).toInt()}%", enabled = false) {}
                "ready" -> PrimaryButton("安装") { file?.let { Updater.install(c, it) } }
                "checking" -> PrimaryButton("检查中…", enabled = false) {}
                else -> PrimaryButton("检查更新") { check() }
            }
        }
    }
}

/** 打开留白时自动查一次，有新版本就弹窗；可以「以后再说」，3 小时内不再打扰。 */
@Composable
fun UpdatePrompt() {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val pr = remember { c.getSharedPreferences("upd", Context.MODE_PRIVATE) }
    var rel by remember { mutableStateOf<Release?>(null) }
    var state by remember { mutableStateOf("ask") } // ask downloading error noperm
    var prog by remember { mutableStateOf(0f) }

    // 每次回到留白都查（5 分钟内不重复查），不是只在进程启动时查一次
    var resumes by remember { mutableStateOf(0) }
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumes++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(resumes) {
        if (resumes == 0 || state == "downloading") return@LaunchedEffect
        val now = System.currentTimeMillis()
        if (rel != null || now - pr.getLong("checked", 0L) < 60_000L) return@LaunchedEffect
        pr.edit().putLong("checked", now).apply()
        val r = withContext(Dispatchers.IO) { try { Updater.latest() } catch (x: Exception) { null } }
        if (r == null || r.code <= BuildConfig.VERSION_CODE) return@LaunchedEffect
        // 「以后再说」只压住那一个版本 1 小时；一出更新的版本马上再提示
        val skipped = pr.getInt("skipCode", 0)
        if (r.code <= skipped && now - pr.getLong("later", 0L) < 3600_000L) return@LaunchedEffect
        rel = r; state = "ask"
    }
    val r = rel
    if (r != null) {
    fun later() { pr.edit().putLong("later", System.currentTimeMillis()).putInt("skipCode", r.code).apply(); rel = null }
    fun go() {
        if (!Updater.canInstall(c)) {
            state = "noperm"
            c.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        state = "downloading"; prog = 0f
        scope.launch {
            val f = withContext(Dispatchers.IO) {
                // 点下去那一刻再确认一次最新版，直接下最新的那个
                val fresh = try { Updater.latest() } catch (x: Exception) { null }
                val target = if (fresh != null && fresh.code > r.code) fresh else r
                try { Updater.download(c, target.url) { p -> prog = p } } catch (x: Exception) { null }
            }
            if (f == null) state = "error" else { Updater.install(c, f); rel = null }
        }
    }
    AlertDialog(
        onDismissRequest = { if (state != "downloading") rel = null },
        containerColor = C.Card,
        title = { Text("发现新版本 ${r.name}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = C.Ink) },
        text = {
            Column {
                when (state) {
                    "downloading" -> {
                        Text("下载中 ${(prog * 100).toInt()}%", fontSize = 14.sp, color = C.Soft)
                        LinearProgressIndicator(progress = prog, color = C.Clay, trackColor = C.Track,
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(8.dp))
                    }
                    "error" -> Text("下载没成功，换个网络再试试", fontSize = 14.sp, color = C.ClayDeep)
                    "noperm" -> Text("请允许留白「安装未知应用」，回来再点更新", fontSize = 14.sp, color = C.ClayDeep)
                    else -> Text(if (r.notes.isNotBlank()) r.notes else "守护设置更新后都会保留。", fontSize = 14.sp, color = C.Soft, lineHeight = 21.sp)
                }
            }
        },
        confirmButton = {
            if (state != "downloading") TextButton(onClick = { go() }) {
                Text(if (state == "error") "重试" else "更新", color = C.Clay, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            if (state != "downloading") TextButton(onClick = { later() }) { Text("以后再说", color = C.Soft) }
        }
    )
}
}
