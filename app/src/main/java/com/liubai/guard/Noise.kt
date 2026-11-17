package com.liubai.guard

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** 番茄钟白噪音：全部实时合成，不占安装包体积。 */
object Noise {
    val KINDS = listOf(
        "off" to "关", "white" to "白噪音", "pink" to "粉噪音", "brown" to "低频",
        "rain" to "雨声", "storm" to "雷雨", "wave" to "海浪", "stream" to "溪流",
        "wind" to "风声", "fire" to "篝火", "night" to "夏夜", "fan" to "风扇", "clock" to "钟摆"
    )
    private fun p(c: Context) = c.getSharedPreferences("focus", Context.MODE_PRIVATE)
    fun kind(c: Context) = p(c).getString("noise", "off")!!
    fun setKind(c: Context, k: String) { p(c).edit().putString("noise", k).apply(); sync(c) }
    fun inBreak(c: Context) = p(c).getBoolean("noiseBreak", false)
    fun setInBreak(c: Context, v: Boolean) { p(c).edit().putBoolean("noiseBreak", v).apply(); sync(c) }
    fun volume(c: Context) = p(c).getInt("noiseVol", 60)
    fun setVolume(c: Context, v: Int) { p(c).edit().putInt("noiseVol", v).apply(); vol = v / 100f }

    @Volatile private var playing: String? = null
    @Volatile private var vol = 0.6f
    private var thread: Thread? = null

    /** 由 Focus.tick 定期调用：专注进行中（可选休息中）才出声。 */
    @Synchronized
    fun sync(c: Context) {
        vol = volume(c) / 100f
        val ph = Focus.phase(c)
        val want = kind(c).takeIf {
            it != "off" && Focus.running(c) && (ph == Focus.WORK || (inBreak(c) && ph != Focus.IDLE))
        }
        if (want == playing) return
        stop()
        if (want != null) play(want)
    }

    private fun stop() {
        playing = null
        thread?.join(400)
        thread = null
    }

    private fun play(k: String) {
        playing = k
        thread = Thread {
            val rate = 22050
            val buf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(buf * 4).build()
            track.play()
            val out = ShortArray(1024)
            val r = Random(System.nanoTime())
            var b0 = 0f; var b1 = 0f; var b2 = 0f; var brown = 0f; var lp = 0f
            var t = 0L; var g = 0f; var drop = 0f
            var bp1 = 0f; var bp2 = 0f; var wl = 0f; var crack = 0f; var thunder = 0f; var thLp = 0f
            var nextThunder = rate * (8L + r.nextInt(12))
            while (playing == k) {
                for (i in out.indices) {
                    val w = r.nextFloat() * 2f - 1f
                    // 粉红噪声（Paul Kellet 近似）
                    b0 = 0.99765f * b0 + w * 0.0990460f
                    b1 = 0.96300f * b1 + w * 0.2965164f
                    b2 = 0.57000f * b2 + w * 1.0526913f
                    val pink = (b0 + b1 + b2 + w * 0.1848f) * 0.18f
                    brown = (brown + w * 0.02f).coerceIn(-1f, 1f) * 0.998f
                    val s = when (k) {
                        "white" -> { lp += (w - lp) * 0.55f; lp * 0.35f }
                        "brown" -> brown * 2.4f
                        "pink" -> pink * 0.9f
                        "storm" -> {
                            if (r.nextFloat() < 0.0014f) drop = 0.5f + r.nextFloat() * 0.5f
                            drop *= 0.992f
                            if (t >= nextThunder) { thunder = 1f; nextThunder = t + rate * (10L + r.nextInt(18)) }
                            thunder *= 0.99993f
                            thLp += (brown - thLp) * 0.02f
                            pink * 0.9f + w * drop * 0.35f + thLp * thunder * 9f
                        }
                        "stream" -> {
                            bp1 += (w - bp1) * 0.35f; bp2 += (bp1 - bp2) * 0.35f
                            val sec = t / rate.toFloat()
                            val bub = 0.7f + 0.3f * sin(2 * PI * sec * 0.7).toFloat() * sin(2 * PI * sec * 2.3).toFloat()
                            (bp1 - bp2) * 1.8f * bub + pink * 0.25f
                        }
                        "wind" -> {
                            val sec = t / rate.toFloat()
                            val gust = 0.15f + 0.85f * (0.5f + 0.5f * sin(2 * PI * sec / 7.0).toFloat() * sin(2 * PI * sec / 2.9 + 1).toFloat())
                            val k2 = 0.01f + 0.05f * gust
                            wl += (w - wl) * k2
                            wl * 5f * gust
                        }
                        "fire" -> {
                            if (r.nextFloat() < 0.0025f) crack = 0.4f + r.nextFloat() * 0.6f
                            crack *= 0.93f
                            brown * 1.5f + w * crack * 0.9f
                        }
                        "night" -> {
                            val sec = t / rate.toFloat()
                            val chirpOn = ((sec * 3.2) % 1.0) < 0.35 && ((sec / 1.6) % 1.0) < 0.7
                            val tone = if (chirpOn) sin(2 * PI * 4300.0 * sec).toFloat() * (0.5f + 0.5f * sin(2 * PI * 45.0 * sec).toFloat()) else 0f
                            tone * 0.06f + brown * 0.8f
                        }
                        "fan" -> {
                            val sec = t / rate.toFloat()
                            lp += (w - lp) * 0.08f
                            lp * 1.1f + sin(2 * PI * 118.0 * sec).toFloat() * 0.03f
                        }
                        "clock" -> {
                            val sec = t / rate.toFloat()
                            val f = sec % 1.0
                            val tk = if (f < 0.012) (1 - f / 0.012).toFloat() * sin(2 * PI * 2200.0 * sec).toFloat() else 0f
                            tk * 0.5f + brown * 0.4f
                        }
                        "wave" -> {
                            val sec = t / rate.toFloat()
                            val swell = 0.25f + 0.75f * ((sin(2 * PI * sec / 9.0) * 0.5 + 0.5).toFloat().let { it * it })
                            (brown * 1.6f + pink * 0.6f) * swell
                        }
                        else -> { // rain：粉红底噪 + 稀疏雨滴
                            if (r.nextFloat() < 0.0009f) drop = 0.5f + r.nextFloat() * 0.5f
                            drop *= 0.992f
                            pink * 0.8f + w * drop * 0.35f
                        }
                    }
                    g += ((vol) - g) * 0.0005f // 平滑淡入/音量变化
                    out[i] = (s * g * 22000f).coerceIn(-32000f, 32000f).toInt().toShort()
                    t++
                }
                track.write(out, 0, out.size)
            }
            track.stop(); track.release()
        }.apply { isDaemon = true; start() }
    }
}
