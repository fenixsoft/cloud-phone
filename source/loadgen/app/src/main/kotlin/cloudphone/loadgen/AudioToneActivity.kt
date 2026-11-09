package cloudphone.loadgen

import android.app.Activity
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 音频负载页（m3-audio-clipboard-textinput 验证基建）：
 * 经 AudioTrack 以 USAGE_MEDIA 播放正弦波——M3 音频捕获通道（AudioPlaybackCapture 匹配
 * usage GAME/MEDIA）的应用侧可捕获负载源。shell uid（app_process/root）播放的音轨在部分
 * 平台上被 playback-capture 策略豁免，无法被捕获；应用 uid（loadgen）播放为对照组。
 *
 * 用法：
 *   am start -n cloudphone.loadgen/.AudioToneActivity --ei hz 440 --ei seconds 20
 *   （seconds ≤ 0 = 播放到 Activity 销毁；默认 20s 后自动结束）
 *
 * overlay 显示当前 hz / 已播帧数，验证「负载在播」；播放线程独立于 UI 线程。
 */
class AudioToneActivity : Activity() {

    companion object {
        const val EXTRA_HZ = "hz"
        const val EXTRA_SECONDS = "seconds"
        const val SAMPLE_RATE = 48000
        private const val TAG = "cloudphone-loadgen"
    }

    @Volatile private var running = false
    private var playedFrames = 0L
    private lateinit var status: TextView
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val hz = intent.getIntExtra(EXTRA_HZ, 440)
        val seconds = intent.getIntExtra(EXTRA_SECONDS, 20)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(this).apply { text = "音频负载（USAGE_MEDIA）" }
        status = TextView(this)
        root.addView(title); root.addView(status)
        setContentView(root)

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val bufFrames = SAMPLE_RATE / 10 // 100ms 双声道
        val track = AudioTrack(attrs, fmt, bufFrames * 4, AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE)
        running = true
        Thread({
            val buf = ShortArray(bufFrames * 2)
            var phase = 0.0
            track.play()
            val startAt = SystemClock.elapsedRealtime()
            while (running) {
                for (i in 0 until bufFrames) {
                    val s = (Math.sin(2 * Math.PI * hz * phase / SAMPLE_RATE) * 12000).toInt().toShort()
                    buf[2 * i] = s; buf[2 * i + 1] = s
                    phase += 1.0
                }
                track.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
                playedFrames += bufFrames
                if (playedFrames % (SAMPLE_RATE * 2) == 0L) {
                    ui.post { status.text = "hz=$hz frames=$playedFrames" }
                    Log.i(TAG, "tone hz=$hz frames=$playedFrames")
                }
                if (seconds > 0 && SystemClock.elapsedRealtime() - startAt > seconds * 1000L) break
            }
            track.stop(); track.release()
            ui.post { status.text = "已结束 frames=$playedFrames" }
        }, "loadgen-tone").start()
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }
}
