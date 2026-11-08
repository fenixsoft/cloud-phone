package cloudphone.source.obs

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * 源端管线可观测性：采集/编码/发送逐级计数、采样帧 dump 开关（写 /sdcard 供诊断）、
 * 坏事件分类（编码失败/采集失败/MediaProjection 授权丢失）。
 * 单例（App 进程内唯一），多线程安全。
 */
object PipelineCounters {

    /** 管线逐级计数 */
    val capturedFrames = AtomicLong()
    val encodedFrames = AtomicLong()
    val sentFrames = AtomicLong()

    /** 丢帧：编码器不可用/背压丢弃 */
    val droppedFrames = AtomicLong()

    /** 坏事件分类 */
    enum class BadEvent { ENCODE_FAILURE, CAPTURE_FAILURE, PROJECTION_LOST, SHELL_HELPER_LOST, DISPLAY_REBUILD }

    private val badEventCounts = java.util.EnumMap<BadEvent, AtomicLong>(BadEvent::class.java).also { m ->
        BadEvent.entries.forEach { m[it] = AtomicLong() }
    }
    private val lastBadEventDetail = java.util.concurrent.ConcurrentHashMap<BadEvent, String>()

    // ── 采样帧 dump 开关 ──

    @Volatile var dumpEnabled: Boolean = false
    @Volatile var dumpEveryN: Int = 60

    private val dumpDir: File by lazy {
        File(
            android.os.Environment.getExternalStorageDirectory()?.absolutePath ?: "/sdcard",
            "cloudphone-dump",
        )
    }

    fun enableDump(enabled: Boolean) {
        dumpEnabled = enabled
        if (enabled) dumpDir.mkdirs()
    }

    /** 采样写帧字节（编码输出帧）到 /sdcard/cloudphone-dump/seq.h264 */
    fun maybeDumpFrame(sequence: Long, keyframe: Boolean, data: ByteArray) {
        if (!dumpEnabled || dumpEveryN <= 0) return
        if (sequence % dumpEveryN.toLong() != 0L && !keyframe) return
        runCatching {
            val ts = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
            val f = File(dumpDir, "frame-$ts-seq$sequence${if (keyframe) "-idr" else ""}.h264")
            f.writeBytes(data)
        }
    }

    fun onBadEvent(event: BadEvent, detail: String = "") {
        badEventCounts[event]?.incrementAndGet()
        if (detail.isNotEmpty()) lastBadEventDetail[event] = detail
    }

    fun snapshot(): Map<String, Long> = buildMap {
        put("captured", capturedFrames.get())
        put("encoded", encodedFrames.get())
        put("sent", sentFrames.get())
        put("dropped", droppedFrames.get())
        BadEvent.entries.forEach { put("bad_${it.name.lowercase()}", badEventCounts[it]!!.get()) }
    }

    fun lastBadDetail(event: BadEvent): String? = lastBadEventDetail[event]

    /** 一行摘要（UI/STATS 附言用） */
    fun summaryLine(): String =
        "cap=${capturedFrames.get()} enc=${encodedFrames.get()} sent=${sentFrames.get()} drop=${droppedFrames.get()}"
}
