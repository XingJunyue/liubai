package com.liubai.guard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.android.AdbMdns
import java.io.File
import java.net.InetAddress
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 内置无线调试：留白自己当一回「电脑」，通过手机上的无线调试连到本机的 adbd，
 * 给自己授予 WRITE_SECURE_SETTINGS。思路同 Shizuku / App Manager，不用另装应用。
 * 配对码从通知栏输入（配对弹窗开着时，应用切走弹窗会关）。
 */
class LiubaiAdb private constructor(c: Context) : AbsAdbConnectionManager() {
    private val key: PrivateKey
    private val cert: Certificate

    init {
        api = Build.VERSION.SDK_INT
        val kf = File(c.filesDir, "adb.key")
        val cf = File(c.filesDir, "adb.crt")
        var k: PrivateKey? = null
        var ct: Certificate? = null
        try {
            if (kf.exists() && cf.exists()) {
                k = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(kf.readBytes()))
                ct = cf.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
            }
        } catch (x: Exception) { k = null; ct = null }
        if (k == null || ct == null) {
            val g = KeyPairGenerator.getInstance("RSA")
            g.initialize(2048, SecureRandom())
            val kp = g.generateKeyPair()
            k = kp.private
            val alg = "SHA512withRSA"
            val ext = android.sun.security.x509.CertificateExtensions()
            ext.set("SubjectKeyIdentifier", android.sun.security.x509.SubjectKeyIdentifierExtension(
                android.sun.security.x509.KeyIdentifier(kp.public).identifier))
            val name = android.sun.security.x509.X500Name("CN=liubai")
            val from = Date()
            val to = Date(System.currentTimeMillis() + 3650L * 86_400_000L)
            ext.set("PrivateKeyUsage", android.sun.security.x509.PrivateKeyUsageExtension(from, to))
            val info = android.sun.security.x509.X509CertInfo()
            info.set("version", android.sun.security.x509.CertificateVersion(2))
            info.set("serialNumber", android.sun.security.x509.CertificateSerialNumber(Random().nextInt() and Int.MAX_VALUE))
            info.set("algorithmID", android.sun.security.x509.CertificateAlgorithmId(android.sun.security.x509.AlgorithmId.get(alg)))
            info.set("subject", android.sun.security.x509.CertificateSubjectName(name))
            info.set("key", android.sun.security.x509.CertificateX509Key(kp.public))
            info.set("validity", android.sun.security.x509.CertificateValidity(from, to))
            info.set("issuer", android.sun.security.x509.CertificateIssuerName(name))
            info.set("extensions", ext)
            val x = android.sun.security.x509.X509CertImpl(info)
            x.sign(k, alg)
            ct = x
            kf.writeBytes(k!!.encoded)
            cf.writeBytes(x.encoded)
        }
        key = k!!; cert = ct!!
    }

    override fun getPrivateKey(): PrivateKey = key
    override fun getCertificate(): Certificate = cert
    override fun getDeviceName(): String = tr("留白")

    companion object {
        @Volatile private var one: LiubaiAdb? = null
        fun get(c: Context): LiubaiAdb = one ?: synchronized(this) { one ?: LiubaiAdb(c.applicationContext).also { one = it } }
    }
}

object WAdb {
    const val CH = "pair"
    const val NID = 79
    const val KEY = "code"

    @Volatile var status = ""          // 给首页卡片看的进度
    @Volatile private var waitSince = 0L
    @Volatile private var pairHost: InetAddress? = null
    @Volatile private var pairPort = -1
    private var mdns: AdbMdns? = null
    private val h = Handler(Looper.getMainLooper())

    fun supported() = Build.VERSION.SDK_INT >= 30

    /** 第一步：开始找配对端口，并在通知栏放一个输入配对码的框。 */
    fun begin(c: Context) {
        val app = c.applicationContext
        status = "waiting"
        waitSince = android.os.SystemClock.elapsedRealtime()
        try {
            mdns?.stop()
            mdns = AdbMdns(app, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { host, port ->
                if (port > 0) { pairHost = host; pairPort = port }
            }.also { it.start() }
            h.postDelayed({ try { mdns?.stop() } catch (x: Exception) {} }, 5 * 60_000L)
        } catch (x: Exception) {}
        notify(app, tr("输入配对码"), tr("在「无线调试」里点「使用配对码配对设备」，把 6 位数字填在这里"), true)
    }

    fun notify(c: Context, title: String, text: String, input: Boolean) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, tr("无线调试配对"), NotificationManager.IMPORTANCE_HIGH))
        val b = NotificationCompat.Builder(c, CH)
            .setSmallIcon(R.drawable.ic_mono)
            .setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
        if (input) {
            val ri = RemoteInput.Builder(KEY).setLabel(tr("6 位配对码")).build()
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(c, 78, Intent(c, PairReceiver::class.java), flags)
            b.addAction(NotificationCompat.Action.Builder(0, tr("输入配对码"), pi).addRemoteInput(ri).build())
            b.setOngoing(true)
        } else b.setAutoCancel(true)
        try { nm.notify(NID, b.build()) } catch (x: SecurityException) {}
    }

    private fun sh(m: LiubaiAdb, cmd: String): String {
        val st = m.openStream("shell:$cmd")
        val out = StringBuilder()
        try {
            val ins = st.openInputStream(); val buf = ByteArray(1024)
            while (true) { val n = ins.read(buf); if (n <= 0) break; out.append(String(buf, 0, n)) }
        } catch (x: Exception) {}
        try { st.close() } catch (x: Exception) {}
        return out.toString()
    }

    fun doneText(c: Context) = if (Keep.canHeal(c)) tr("以后留白被系统关掉，会自己重新打开。无线调试可以关掉了。")
        else tr("以后留白被系统关掉，会自己重新打开。让「无线调试」一直开着就行；重启或换了 Wi-Fi 后，记得再打开它。")

    fun adbMode(c: Context) = c.getSharedPreferences("liubai_ui", Context.MODE_PRIVATE).getBoolean("adbMode", false)

    /** 用无线调试直接把无障碍重新写回去（不需要额外的安全开关）。 */
    private fun reopen(app: Context, m: LiubaiAdb): Boolean {
        val me = "${app.packageName}/${GuardService::class.java.name}"
        val cur = sh(m, "settings get secure enabled_accessibility_services").trim()
        val list = cur.split(':').filter { it.isNotBlank() && it != "null" && !it.startsWith("${app.packageName}/") }
        val r1 = sh(m, "settings put secure enabled_accessibility_services " + (list + me).joinToString(":"))
        sh(m, "settings put secure accessibility_enabled 1")
        return "Exception" !in r1
    }

    /** 连上之后：先试着要永久权限；系统不让的话，改用「无线调试模式」，不用再开任何开关。 */
    private fun grant(app: Context, m: LiubaiAdb): String {
        val o = sh(m, "pm grant ${app.packageName} android.permission.WRITE_SECURE_SETTINGS")
        if (Keep.canHeal(app)) { try { m.disconnect() } catch (x: Exception) {}; Keep.heal(app); return "ok" }
        val ok = try { if (GuardService.running) "Exception" !in sh(m, "settings get secure accessibility_enabled") else reopen(app, m) } catch (x: Exception) { false }
        try { m.disconnect() } catch (x: Exception) {}
        if (ok) {
            app.getSharedPreferences("liubai_ui", Context.MODE_PRIVATE).edit().putBoolean("adbMode", true).apply()
            return "ok"
        }
        throw Exception(o.lineSequence().firstOrNull { it.isNotBlank() }?.take(120) ?: tr("授权没成功"))
    }

    @Volatile private var lastHeal = 0L
    fun healViaAdb(c: Context) {
        val app = c.applicationContext
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastHeal < 60_000L || status == "working") return
        lastHeal = now
        thread {
            try {
                val m = LiubaiAdb.get(app)
                if (m.isConnected || m.connectTls(app, 4_000)) { reopen(app, m); try { m.disconnect() } catch (x: Exception) {} }
            } catch (x: Throwable) {}
        }
    }

    /** 已经配对过的话，不用再输配对码，直接连上授权。 */
    fun retry(c: Context) {
        val app = c.applicationContext
        if (status == "working") return
        status = "working"
        thread {
            val msg = try {
                val m = LiubaiAdb.get(app)
                if (!m.isConnected && !m.connectTls(app, 4_000)) throw Exception("nopair")
                grant(app, m)
            } catch (x: Throwable) { x.message ?: "nopair" }
            when (if ("pairing" in msg.lowercase() || "not paired" in msg.lowercase()) "nopair" else msg) {
                "ok" -> { status = "done"; notify(app, tr("好了"), doneText(app), false) }
                "nopair" -> { status = ""; android.os.Handler(Looper.getMainLooper()).post { begin(app) } }
                else -> status = "error:$msg"
            }
        }
    }

    /** 第二步：拿到配对码 → 配对 → 连接 → 授权。在后台线程跑。 */
    private var lastScan = 0L
    private val reCode = Regex("^\\s*(\\d{6})\\s*$")
    private val reAddr = Regex("(\\d{1,3}(?:\\.\\d{1,3}){3}):(\\d{2,5})")

    /** 无障碍在设置里看到配对弹窗时，直接把配对码和端口读出来，不用手输。 */
    fun scan(c: Context, roots: List<android.view.accessibility.AccessibilityNodeInfo?>) {
        if (status != "waiting") return
        val now = android.os.SystemClock.elapsedRealtime()
        // 10 分钟还没配对就不再读屏幕，省电
        if (now - waitSince > 10 * 60_000L) { status = ""; return }
        if (now - lastScan < 400) return
        lastScan = now
        var code: String? = null; var host: String? = null; var port = -1
        fun walk(n: android.view.accessibility.AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > 30) return
            val t = n.text?.toString()
            if (t != null) {
                reCode.find(t)?.let { if (code == null) code = it.groupValues[1] }
                reAddr.find(t)?.let { host = it.groupValues[1]; port = it.groupValues[2].toIntOrNull() ?: -1 }
            }
            for (i in 0 until n.childCount) walk(try { n.getChild(i) } catch (x: Exception) { null }, d + 1)
        }
        for (r in roots) try { walk(r, 0) } catch (x: Exception) {}
        val cd = code ?: return
        if (port > 0) { try { pairHost = InetAddress.getByName(host) } catch (x: Exception) {}; pairPort = port }
        pairAndGrant(c, cd)
    }

    fun pairAndGrant(c: Context, code: String) {
        if (status == "working") return
        val app = c.applicationContext
        status = "working"
        notify(app, tr("正在配对…"), tr("稍等几秒"), false)
        thread {
            val msg = try {
                val m = LiubaiAdb.get(app)
                // 端口还没找到就再等一会儿
                if (pairPort <= 0) {
                    val l = CountDownLatch(1)
                    val md = AdbMdns(app, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { host, port -> if (port > 0) { pairHost = host; pairPort = port; l.countDown() } }
                    md.start(); l.await(15, TimeUnit.SECONDS); md.stop()
                }
                if (pairPort <= 0) throw Exception(tr("找不到配对端口：请保持「使用配对码配对设备」的弹窗开着再输入"))
                val host = pairHost?.hostAddress ?: "127.0.0.1"
                if (!m.pair(host, pairPort, code.trim())) throw Exception(tr("配对码不对，重新点一次配对再试"))
                if (!m.isConnected && !m.connectTls(app, 15_000)) throw Exception(tr("配对成功，但没连上：确认「无线调试」还开着"))
                grant(app, m)
            } catch (x: Throwable) { x.message ?: x.javaClass.simpleName }
            try { mdns?.stop() } catch (x: Exception) {}
            pairPort = -1
            if (msg == "ok") {
                status = "done"
                notify(app, tr("好了"), doneText(app), false)
            } else {
                val say = if ("pairing" in msg.lowercase()) tr("还没配对：重新点「使用配对码配对设备」再试一次") else msg
                status = "error:$say"
                notify(app, tr("没成功"), say, false)
            }
        }
    }
}

class PairReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val code = RemoteInput.getResultsFromIntent(i)?.getCharSequence(WAdb.KEY)?.toString()?.filter { it.isDigit() } ?: return
        if (code.length < 6) { WAdb.notify(c, tr("配对码是 6 位数字"), tr("再输一次"), true); return }
        WAdb.pairAndGrant(c, code)
    }
}
