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
        "off" to "关", "rain" to "雨声", "storm" to "雷雨", "waves" to "海浪", "stream" to "溪流",
        "wind" to "风声", "fireplace" to "篝火", "summer_night" to "夏夜", "birds" to "鸟鸣",
        "coffee_shop" to "咖啡馆", "city" to "城市", "train" to "火车", "boat" to "小船",
        "white" to "白噪音", "pink" to "粉噪音", "brown" to "棕噪音"
    )
    /** 真实录音（来自 Blanket 项目的 CC0 / CC BY / 公有领域素材），其余为实时合成。 */
    private val RAW = mapOf(
        "rain" to R.raw.snd_rain, "storm" to R.raw.snd_storm, "waves" to R.raw.snd_waves, "stream" to R.raw.snd_stream,
        "wind" to R.raw.snd_wind, "fireplace" to R.raw.snd_fireplace, "summer_night" to R.raw.snd_summer_night,
        "birds" to R.raw.snd_birds, "coffee_shop" to R.raw.snd_coffee_shop, "city" to R.raw.snd_city,
        "train" to R.raw.snd_train, "boat" to R.raw.snd_boat
    )
    private var player: android.media.MediaPlayer? = null
    private fun p(c: Context) = c.getSharedPreferences("focus", Context.MODE_PRIVATE)
    fun kind(c: Context) = p(c).getString("noise", "off")!!.takeIf { k -> KINDS.any { it.first == k } } ?: "off"
    fun setKind(c: Context, k: String) { p(c).edit().putString("noise", k).apply(); sync(c) }
    fun inBreak(c: Context) = p(c).getBoolean("noiseBreak", false)
    fun setInBreak(c: Context, v: Boolean) { p(c).edit().putBoolean("noiseBreak", v).apply(); sync(c) }
    fun volume(c: Context) = p(c).getInt("noiseVol", 60)
    fun setVolume(c: Context, v: Int) { p(c).edit().putInt("noiseVol", v).apply(); vol = v / 100f; player?.setVolume(vol, vol) }

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
        if (want == null) return
        val res = RAW[want]
        if (res != null) {
            playing = want
            try {
                player = android.media.MediaPlayer.create(c.applicationContext, res)?.apply {
                    isLooping = true; setVolume(vol, vol); start()
                }
            } catch (x: Exception) { player = null }
        } else play(want)
    }

    private fun stop() {
        playing = null
        try { player?.stop(); player?.release() } catch (x: Exception) {}
        player = null
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
            var bp1 = 0f; var bp2 = 0f; var wl = 0f; var crack = 0f
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
                        "white" -> { lp += (w - lp) * 0.55f; lp * 0.63f }
                        "brown" -> brown * 1.2f
                        "pink" -> pink * 0.72f
                        "wind" -> {
                            val sec = t / rate.toFloat()
                            val gust = 0.15f + 0.85f * (0.5f + 0.5f * sin(2 * PI * sec / 7.0).toFloat() * sin(2 * PI * sec / 2.9 + 1).toFloat())
                            val k2 = 0.01f + 0.05f * gust
                            wl += (w - wl) * k2
                            wl * 3.75f * gust
                        }
                        "fire" -> {
                            // 篝火：低沉的燃烧声 + 偶尔成串的噼啪
                            if (r.nextFloat() < 0.0012f || (crack > 0.05f && r.nextFloat() < 0.002f)) crack = 0.3f + r.nextFloat() * 0.7f
                            crack *= 0.9f
                            (brown * 1.1f + pink * 0.2f + w * crack * 0.7f) * 0.9f
                        }
                        "wave" -> {
                            val sec = t / rate.toFloat()
                            val swell = 0.25f + 0.75f * ((sin(2 * PI * sec / 9.0) * 0.5 + 0.5).toFloat().let { it * it })
                            (brown * 1.6f + pink * 0.6f) * swell * 0.8f
                        }
                        else -> { // rain：粉红底噪 + 稀疏雨滴
                            // 细密的雨：高频偏重的底噪 + 很多轻小的雨滴
                            bp1 += (w - bp1) * 0.6f; bp2 += (bp1 - bp2) * 0.05f
                            if (r.nextFloat() < 0.004f) drop = 0.2f + r.nextFloat() * 0.4f
                            drop *= 0.97f
                            ((bp1 - bp2) * 0.55f + pink * 0.25f + w * drop * 0.3f) * 0.9f
                        }
                    }
                    g += ((vol) - g) * 0.0005f // 平滑淡入/音量变化
                    val soft = s / (1f + kotlin.math.abs(s) * 0.5f) // 柔和限幅，避免爆音
                    out[i] = (soft * g * 26000f).coerceIn(-32000f, 32000f).toInt().toShort()
                    t++
                }
                track.write(out, 0, out.size)
            }
            track.stop(); track.release()
        }.apply { isDaemon = true; start() }
    }
}
