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
    override fun getDeviceName(): String = "留白"

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
    @Volatile private var pairHost: InetAddress? = null
    @Volatile private var pairPort = -1
    private var mdns: AdbMdns? = null
    private val h = Handler(Looper.getMainLooper())

    fun supported() = Build.VERSION.SDK_INT >= 30

    /** 第一步：开始找配对端口，并在通知栏放一个输入配对码的框。 */
    fun begin(c: Context) {
        val app = c.applicationContext
        status = "waiting"
        try {
            mdns?.stop()
            mdns = AdbMdns(app, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { host, port ->
                if (port > 0) { pairHost = host; pairPort = port }
            }.also { it.start() }
            h.postDelayed({ try { mdns?.stop() } catch (x: Exception) {} }, 5 * 60_000L)
        } catch (x: Exception) {}
        notify(app, "输入配对码", "在「无线调试」里点「使用配对码配对设备」，把 6 位数字填在这里", true)
    }

    fun notify(c: Context, title: String, text: String, input: Boolean) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "无线调试配对", NotificationManager.IMPORTANCE_HIGH))
        val b = NotificationCompat.Builder(c, CH)
            .setSmallIcon(R.drawable.ic_mono)
            .setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
        if (input) {
            val ri = RemoteInput.Builder(KEY).setLabel("6 位配对码").build()
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(c, 78, Intent(c, PairReceiver::class.java), flags)
            b.addAction(NotificationCompat.Action.Builder(0, "输入配对码", pi).addRemoteInput(ri).build())
            b.setOngoing(true)
        } else b.setAutoCancel(true)
        try { nm.notify(NID, b.build()) } catch (x: SecurityException) {}
    }

    /** 第二步：拿到配对码 → 配对 → 连接 → 授权。在后台线程跑。 */
    private var lastScan = 0L
    private val reCode = Regex("^\\s*(\\d{6})\\s*$")
    private val reAddr = Regex("(\\d{1,3}(?:\\.\\d{1,3}){3}):(\\d{2,5})")

    /** 无障碍在设置里看到配对弹窗时，直接把配对码和端口读出来，不用手输。 */
    fun scan(c: Context, roots: List<android.view.accessibility.AccessibilityNodeInfo?>) {
        if (status != "waiting") return
        val now = android.os.SystemClock.elapsedRealtime()
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
        notify(app, "正在配对…", "稍等几秒", false)
        thread {
            val msg = try {
                val m = LiubaiAdb.get(app)
                // 端口还没找到就再等一会儿
                if (pairPort <= 0) {
                    val l = CountDownLatch(1)
                    val md = AdbMdns(app, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { host, port -> if (port > 0) { pairHost = host; pairPort = port; l.countDown() } }
                    md.start(); l.await(15, TimeUnit.SECONDS); md.stop()
                }
                if (pairPort <= 0) throw Exception("找不到配对端口：请保持「使用配对码配对设备」的弹窗开着再输入")
                val host = pairHost?.hostAddress ?: "127.0.0.1"
                if (!m.pair(host, pairPort, code.trim())) throw Exception("配对码不对，重新点一次配对再试")
                if (!m.isConnected && !m.connectTls(app, 15_000)) throw Exception("配对成功，但没连上：确认「无线调试」还开着")
                val s = m.openStream("shell:pm grant ${app.packageName} android.permission.WRITE_SECURE_SETTINGS")
                val out = StringBuilder()
                try {
                    val ins = s.openInputStream(); val buf = ByteArray(1024)
                    while (true) { val n = ins.read(buf); if (n <= 0) break; out.append(String(buf, 0, n)) }
                } catch (x: Exception) {}
                try { s.close() } catch (x: Exception) {}
                try { m.disconnect() } catch (x: Exception) {}
                if (Keep.canHeal(app)) { Keep.heal(app); "ok" } else throw Exception(out.toString().ifBlank { "授权没成功" })
            } catch (x: Throwable) { x.message ?: x.javaClass.simpleName }
            try { mdns?.stop() } catch (x: Exception) {}
            pairPort = -1
            if (msg == "ok") {
                status = "done"
                notify(app, "好了", "以后留白被系统关掉，会自己重新打开。无线调试可以关掉了。", false)
            } else {
                status = "error:$msg"
                notify(app, "没成功", msg, false)
            }
        }
    }
}

class PairReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val code = RemoteInput.getResultsFromIntent(i)?.getCharSequence(WAdb.KEY)?.toString()?.filter { it.isDigit() } ?: return
        if (code.length < 6) { WAdb.notify(c, "配对码是 6 位数字", "再输一次", true); return }
        WAdb.pairAndGrant(c, code)
    }
}
