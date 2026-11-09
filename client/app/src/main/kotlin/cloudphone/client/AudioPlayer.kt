package cloudphone.client

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 客户端音频播放器（aux-channels-m3 任务 4.1）：
 *
 * - AudioTrack（MODE_STREAM，48kHz stereo PCM16）+ 120ms 起播水位 jitter buffer：
 *   缓冲攒够 120ms 才起播（吸收网络抖动），不足 skip 并计数（防频繁起停爆音）；
 * - 队列过深（>800ms）时丢弃到水位（防漂移无限增长；源端看门狗丢最旧为主防护）；
 * - 音量/静音独立控制：不联动视频，会话级开关。
 *
 * 喂入接口为解码后的 PCM16 双声道交错 20ms 帧（AuxChannelClient AAC 解码后调用 [enqueue]）。
 */
class AudioPlayer {

    companion object {
        const val SAMPLE_RATE = 48000
        const val CHANNELS = 2
        const val FRAME_MS = 20
        /** 每帧 PCM 字节数（960 samples×2ch×2B，与源端 20ms 帧对齐） */
        const val FRAME_BYTES = SAMPLE_RATE * FRAME_MS / 1000 * CHANNELS * 2
        /** 起播水位（spec：120ms jitter buffer） */
        const val START_WATERMARK_MS = 120
        const val START_WATERMARK_FRAMES = START_WATERMARK_MS / FRAME_MS
        /** 队列深度上限（≈640ms；超出丢弃到水位，防漂移） */
        const val MAX_FRAMES = 32
        const val MAX_DROP_TO_WATERMARK_MS = 800
    }

    private val queue = LinkedBlockingQueue<ByteArray>(MAX_FRAMES)
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var track: AudioTrack? = null

    val skipCount = AtomicLong(0)
    val playedFrames = AtomicLong(0)
    val framesReceived = AtomicLong(0)

    /** 静音开关（独立于视频；实现为 AudioTrack 音量 0，不停止传输与缓冲） */
    @Volatile var muted = false
        set(value) {
            field = value
            applyVolume()
        }

    /** 独立音量 [0f,1f]（不联动视频） */
    @Volatile var volume = 1.0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            applyVolume()
        }

    @Volatile private var buffering = true

    private fun applyVolume() {
        val t = track ?: return
        runCatching { t.setVolume(if (muted) 0f else volume) }
    }

    /** 当前缓冲深度 ms（jitter 状态观测） */
    fun bufferedMs(): Int = queue.size * FRAME_MS

    fun start() {
        if (!running.compareAndSet(false, true)) return
        buffering = true
        queue.clear()
        val minBufBytes = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val t = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build(),
            maxOf(minBufBytes * 2, FRAME_BYTES * MAX_FRAMES),
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        track = t
        t.play()
        applyVolume()
        thread = Thread({ playLoop(t) }, "audio-play").apply { isDaemon = true; start() }
    }

    /** jitter 入队（解码线程回调）：满丢最旧（jitter 上界防护） */
    fun enqueue(pcm: ByteArray) {
        framesReceived.incrementAndGet()
        if (!running.get()) return
        while (!queue.offer(pcm)) {
            queue.poll()
            skipCount.incrementAndGet()
        }
    }

    private fun playLoop(t: AudioTrack) {
        val buf = ByteArray(FRAME_BYTES)
        try {
            while (running.get()) {
                if (buffering) {
                    // 不足 skip：等水位重新起播（spec：缓冲不足 skip 并计数；等待不消费）
                    if (queue.size < START_WATERMARK_FRAMES) {
                        Thread.sleep(20)
                        continue
                    }
                    buffering = false
                }
                // 过深丢弃到水位（防时钟漂移无限增长）
                if (bufferedMs() > MAX_DROP_TO_WATERMARK_MS) {
                    while (queue.size > START_WATERMARK_FRAMES) {
                        queue.poll() ?: break
                        skipCount.incrementAndGet()
                    }
                }
                val frame = queue.poll(100, TimeUnit.MILLISECONDS)
                if (frame == null) {
                    // 欠载：skip 计数并回到缓冲态（防断续爆音）
                    skipCount.incrementAndGet()
                    buffering = true
                    continue
                }
                val n = frame.size.coerceAtMost(buf.size)
                System.arraycopy(frame, 0, buf, 0, n)
                val written = t.write(buf, 0, n, AudioTrack.WRITE_BLOCKING)
                if (written > 0) playedFrames.incrementAndGet()
                // 可观测性（verification 断言「AudioTrack 持续消费」依据）：首帧 + 每 5s 一行
                val total = playedFrames.get()
                if (total == 1L || total % 250 == 0L) {
                    android.util.Log.i(
                        "cloudphone-audio",
                        "play frames=$total skip=${skipCount.get()} bufferedMs=${bufferedMs()}",
                    )
                }
            }
        } catch (_: Exception) {
            // track 已释放（stop 竞态）：退出泵
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        thread?.join(1000)
        thread = null
        runCatching {
            track?.stop()
            track?.release()
        }
        track = null
        queue.clear()
    }
}
