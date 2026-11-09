package cloudphone.source.shell

import cloudphone.protocol.Framing
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * shell bootstrap 客户端（app 侧）：连接 adb root + app_process 启动的 shell 助手进程
 * （scrcpy 同款范式，"增强级权限"的 M0 替身），执行 privileged 命令：
 * 虚拟屏创建（CREATE）、单应用启动（START）、触摸注入（INJECT）、远端 back（BACK）、
 * 虚拟屏销毁（STOP）、关键帧请求（IDR）。
 *
 * 协议（行式，UTF-8，\n 结尾）：
 *   → PING                          ← PONG <helperVersion>
 *   → CREATE <w> <h> <dpi>          ← OK <displayId> | ERR <reason>
 *   → START <displayId> <package>   ← OK <component>   | ERR <reason>
 *   → STOP  <displayId>             ← OK
 *   → INJECT <displayId> <action0..3> <x> <y>   ← OK（归一化坐标 0..65535）
 *   → IDR <displayId>               ← OK
 *   → CAPS                          ← OK <capsJson>
 *
 * 帧流（二进制）：helper 在 127.0.0.1:22341 以 protocol Framing 记录流发送各虚拟屏 H.264 帧
 * （RECORD_VIDEO + 视频载荷，帧头 displayId 承载于 sequence 高 16 位？——不：M0 单帧流多路
 * 复用经帧头 sequence 保留字段不可行，采用每 display 独立连接：22341 帧流连接首行 = displayId）。
 *
 * 无 root / helper 未运行时 [detect] 返回不可用 + 明确降级原因（M0 模拟器 adb root 可用）。
 */
class ShellBootstrap(
    private val host: String = DEFAULT_HOST,
    private val controlPort: Int = DEFAULT_CONTROL_PORT,
) {
    enum class Status { AVAILABLE, UNAVAILABLE }

    data class DetectResult(val status: Status, val version: String, val reason: String)

    private var controlSocket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private val lock = Any()

    /** 检测 helper 是否就绪；失败返回明确降级原因 */
    fun detect(): DetectResult {
        return try {
            Socket().use { probe ->
                probe.connect(InetSocketAddress(host, controlPort), 800)
                probe.soTimeout = 2000
                val w = BufferedWriter(OutputStreamWriter(probe.getOutputStream()))
                val r = BufferedReader(InputStreamReader(probe.getInputStream()))
                w.write("PING\n")
                w.flush()
                val line = r.readLine() ?: return DetectResult(
                    Status.UNAVAILABLE, "", "helper 无应答（协议不一致）",
                )
                if (!line.startsWith("PONG")) {
                    return DetectResult(Status.UNAVAILABLE, "", "helper 应答异常: $line")
                }
                DetectResult(Status.AVAILABLE, line.removePrefix("PONG ").trim(), "")
            }
        } catch (e: SocketTimeoutException) {
            DetectResult(Status.UNAVAILABLE, "", "helper 连接超时（adb root 与 bootstrap 脚本未就绪？）")
        } catch (e: IOException) {
            DetectResult(
                Status.UNAVAILABLE,
                "",
                "helper 未运行于 $host:$controlPort（需 adb root 后由 bootstrap_adb.sh 以 app_process 启动）",
            )
        }
    }

    private fun ensureConnection() {
        if (controlSocket?.isConnected == true && !controlSocket!!.isClosed) return
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, controlPort), 2000)
        s.soTimeout = 8000
        controlSocket = s
        writer = BufferedWriter(OutputStreamWriter(s.getOutputStream()))
        reader = BufferedReader(InputStreamReader(s.getInputStream()))
    }

    /** 发送命令并读取 OK/ERR 行 */
    private fun command(cmd: String): ShellResult {
        synchronized(lock) {
            try {
                ensureConnection()
                writer!!.write(cmd + "\n")
                writer!!.flush()
                val line = reader!!.readLine() ?: throw IOException("helper 连接中断")
                return when {
                    line.startsWith("OK") -> ShellResult(true, line.removePrefix("OK").trim())
                    line.startsWith("ERR") -> ShellResult(false, line.removePrefix("ERR").trim())
                    else -> ShellResult(false, "helper 应答异常: $line")
                }
            } catch (e: IOException) {
                closeControl()
                return ShellResult(false, "helper 通信失败: ${e.message}")
            }
        }
    }

    data class ShellResult(val ok: Boolean, val value: String)

    fun createDisplay(width: Int, height: Int, dpi: Int): ShellResult =
        command("CREATE $width $height $dpi")

    fun startApp(displayId: Int, packageName: String): ShellResult =
        command("START $displayId $packageName")

    fun stopDisplay(displayId: Int): ShellResult = command("STOP $displayId")

    fun injectTouch(displayId: Int, actionCode: Int, normalizedX: Int, normalizedY: Int): ShellResult =
        command("INJECT $displayId $actionCode $normalizedX $normalizedY")

    fun requestKeyframe(displayId: Int): ShellResult = command("IDR $displayId")

    /**
     * `input text` ASCII 文字注入兜底（M3 aux-channels-m3 任务 3.3）：
     * 文本 base64(UTF-8) 传输规避行协议空格歧义；助手侧转 %s 后执行 `input text`。
     */
    fun inputText(text: String): ShellResult {
        val b64 = java.util.Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
        return command("TEXT $b64")
    }

    fun capabilities(): ShellResult = command("CAPS")

    /** 连接指定 display 的帧流（22341，首行 displayId）；返回记录读取器 */
    fun openFrameStream(displayId: Int): Framing.StreamRecordReader {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, DEFAULT_FRAME_PORT), 2000)
        s.soTimeout = 0
        val w = BufferedWriter(OutputStreamWriter(s.getOutputStream()))
        w.write("$displayId\n")
        w.flush()
        return Framing.StreamRecordReader(s.getInputStream().buffered(64 * 1024)).also {
            // socket 生命周期由调用方经 [closeFrameStream] 关闭
            frameSockets.add(s)
        }
    }

    private val frameSockets = java.util.Collections.synchronizedList(ArrayList<Socket>())

    fun closeFrameStreams() {
        synchronized(frameSockets) {
            frameSockets.forEach { runCatching { it.close() } }
            frameSockets.clear()
        }
    }

    private fun closeControl() {
        runCatching { controlSocket?.close() }
        controlSocket = null
        reader = null
        writer = null
    }

    fun close() {
        closeControl()
        closeFrameStreams()
    }

    companion object {
        /** helper 监听端点（app 内连主机模拟器环回；helper 与 app 同模拟器 → 直接 127.0.0.1） */
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_CONTROL_PORT = 22340
        const val DEFAULT_FRAME_PORT = 22341

        const val HELPER_VERSION = 1
    }
}
