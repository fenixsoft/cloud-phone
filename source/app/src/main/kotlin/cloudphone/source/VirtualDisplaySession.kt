package cloudphone.source

import cloudphone.protocol.Framing
import cloudphone.source.shell.ShellBootstrap
import cloudphone.source.shell.ShellBootstrap.ShellResult
import java.io.IOException

/**
 * 单应用虚拟屏会话（shell bootstrap 路径）：
 * 经 ShellBootstrap 命令 helper（app_process，root/shell uid）创建 PUBLIC 虚拟屏，
 * 在其上启动指定包名 App；helper 在屏上编码 H.264，本会话经 22341 帧流读回统一出帧。
 *
 * 挂机语义：[detach] 仅断帧流（屏与 App 保持存活）；[destroy] 才销毁虚拟屏与 App。
 */
class VirtualDisplaySession(
    private val bootstrap: ShellBootstrap,
    private val packageName: String,
    private val width: Int,
    private val height: Int,
    private val dpi: Int = 160,
) : CaptureSource {

    var displayId: Int = -1
        private set
    var component: String = ""
        private set
    private var frameReader: Framing.StreamRecordReader? = null
    private var readerThread: Thread? = null
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var frameSink: ((Long, Long, Boolean, ByteArray) -> Unit)? = null

    override val describe: String
        get() = "virtual-display $packageName ${width}x${height}@${dpi}dpi display=$displayId"

    /** 创建虚拟屏（不出帧）；失败返回降级原因 */
    fun createDisplay(): ShellResult {
        val result = bootstrap.createDisplay(width, height, dpi)
        if (result.ok) {
            displayId = result.value.toIntOrNull() ?: -1
        }
        return result
    }

    /** 在虚拟屏上启动目标 App（am start --display） */
    fun launchApp(): ShellResult {
        if (displayId <= 0) return ShellResult(false, "display not created")
        val result = bootstrap.startApp(displayId, packageName)
        if (result.ok) component = result.value
        return result
    }

    override fun start(onFrame: (sequence: Long, ptsUs: Long, keyframe: Boolean, data: ByteArray) -> Unit): Boolean {
        if (displayId <= 0) {
            val created = createDisplay()
            if (!created.ok) return false
        }
        frameSink = onFrame
        return try {
            frameReader = bootstrap.openFrameStream(displayId)
            running.set(true)
            val reader = frameReader!!
            readerThread = Thread({
                try {
                    while (running.get()) {
                        val record = reader.read() ?: break
                        if (record.type != Framing.RECORD_VIDEO) continue
                        // helper 帧记录 = 单分片视频载荷（布局同 docs/protocol.md §2.2）
                        val frame = cloudphone.transport.TcpStreamChannel.decodeVideoRecord(record.payload)
                            ?: continue
                        onFrame(frame.sequence, frame.ptsUs, frame.keyframe, frame.data)
                    }
                } catch (_: IOException) {
                    // helper 停止/断流：会话侧由 SessionManager 依坏事件处理
                } finally {
                    running.set(false)
                }
            }, "vd-frame-$displayId").apply { isDaemon = true; start() }
            true
        } catch (e: IOException) {
            running.set(false)
            false
        }
    }

    override fun requestIdr() {
        if (displayId > 0) bootstrap.requestKeyframe(displayId)
    }

    /** 挂机：只断帧流，虚拟屏与 App 保留（spec：断开停编码留虚拟屏） */
    fun detach() {
        running.set(false)
        readerThread?.join(1000)
        bootstrap.closeFrameStreams()
        frameReader = null
    }

    /** 销毁虚拟屏与其上的 App（STOP_SESSION 语义） */
    fun destroy() {
        detach()
        if (displayId > 0) {
            bootstrap.stopDisplay(displayId)
            displayId = -1
        }
    }

    override fun stop() = destroy()
}
