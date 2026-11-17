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
    val KINDS = listOf("off" to "关", "white" to "白噪音", "rain" to "雨声", "wave" to "海浪", "brown" to "低频")
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
