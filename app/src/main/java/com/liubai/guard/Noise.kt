package com.liubai.guard

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.random.Random

/** 番茄钟白噪音：自然声用真实录音循环播放；纯噪音预先算好一段再循环。 */
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
    fun setVolume(c: Context, v: Int) { p(c).edit().putInt("noiseVol", v).apply(); vol = v / 100f; player?.setVolume(vol, vol); try { track?.setVolume(vol) } catch (x: Exception) {} }

    @Volatile private var playing: String? = null
    /** 选择界面里的试听：不论是否在专注，都先放这个。 */
    @Volatile var preview: String? = null
    fun label(k: String) = KINDS.firstOrNull { it.first == k }?.second ?: "关"
    @Volatile private var vol = 0.6f
    private var thread: Thread? = null

    /** 由 Focus.tick 定期调用：专注进行中（可选休息中）才出声。 */
    @Synchronized
    fun sync(c: Context) {
        vol = volume(c) / 100f
        val ph = Focus.phase(c)
        val want = preview?.takeIf { it != "off" } ?: kind(c).takeIf {
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
        try { track?.stop(); track?.release() } catch (x: Exception) {}
        track = null
    }

    private var track: AudioTrack? = null
    private val bufCache = HashMap<String, ShortArray>()

    /** 纯噪音：只算一次 6 秒的片段（首尾交叉淡化，无接缝），交给系统循环播放，播放时 CPU 几乎不用干活。 */
    private fun clip(k: String): ShortArray = bufCache.getOrPut(k) {
        val rate = 22050
        val n = rate * 6; val fade = rate / 2
        val raw = FloatArray(n + fade)
        val r = Random(42)
        var b0 = 0f; var b1 = 0f; var b2 = 0f; var brown = 0f; var lp = 0f
        for (i in raw.indices) {
            val w = r.nextFloat() * 2f - 1f
            b0 = 0.99765f * b0 + w * 0.0990460f
            b1 = 0.96300f * b1 + w * 0.2965164f
            b2 = 0.57000f * b2 + w * 1.0526913f
            val pink = (b0 + b1 + b2 + w * 0.1848f) * 0.18f
            brown = (brown + w * 0.02f).coerceIn(-1f, 1f) * 0.998f
            raw[i] = when (k) {
                "white" -> { lp += (w - lp) * 0.55f; lp * 0.63f }
                "brown" -> brown * 1.2f
                else -> pink * 0.72f
            }
        }
        // 尾部多算的半秒淡入到开头，循环时听不出接缝
        for (i in 0 until fade) { val a = i / fade.toFloat(); raw[i] = raw[i] * a + raw[n + i] * (1f - a) }
        ShortArray(n) { i -> val v = raw[i]; ((v / (1f + kotlin.math.abs(v) * 0.5f)) * 26000f).coerceIn(-32000f, 32000f).toInt().toShort() }
    }

    private fun play(k: String) {
        playing = k
        thread = Thread {
            try {
                val data = clip(k)
                val t = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(22050)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(data.size * 2).build()
                t.write(data, 0, data.size)
                t.setLoopPoints(0, data.size, -1)
                t.setVolume(vol)
                if (playing == k) { t.play(); track = t } else t.release()
            } catch (x: Exception) {}
        }.apply { isDaemon = true; start() }
    }
}
