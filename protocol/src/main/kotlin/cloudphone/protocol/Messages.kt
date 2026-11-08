package cloudphone.protocol

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * cloudphone 线协议 —— 控制消息定义与编解码。
 *
 * 字节序统一大端（network order）；字符串一律 `u16 长度 + UTF-8 字节`；
 * 字段布局 C 兼容定宽（M1 C++ 化不破坏线上格式，见 docs/protocol.md）。
 *
 * 控制通道记录 = [u8 recordType=0x01][u32 payloadLen][u8 msgType][字段...]，
 * msgType 与字段编解码在本文件单一定义（协议常量唯一真相源）。
 */

/** 协议版本（M0 = 1） */
const val PROTOCOL_VERSION: Int = 1

/** 控制消息类型常量（M1 C++ 化时保持数值不变） */
object MsgType {
    const val HELLO: Int = 0x01
    const val HELLO_ACK: Int = 0x02
    const val START_SESSION: Int = 0x03
    const val STOP_SESSION: Int = 0x04
    const val IDR_REQUEST: Int = 0x05
    const val INPUT_EVENT: Int = 0x06
    const val STATS: Int = 0x07
    const val PING: Int = 0x08
    const val PONG: Int = 0x09
}

/** 观看模式：整屏 / 单应用（指定包名）/ 附着到已挂机会话 */
enum class WatchMode(val id: Int) {
    FULLSCREEN(1), SINGLE_APP(2), ATTACH(3);

    companion object {
        fun from(id: Int): WatchMode? = entries.firstOrNull { it.id == id }
    }
}

/** HELLO_ACK 状态与错误码 */
enum class HelloStatus(val id: Int) {
    ACCEPTED(0), REJECT_BAD_TOKEN(1), REJECT_BUSY(2), REJECT_VERSION(3), REJECT_UNSUPPORTED_MODE(4);

    companion object {
        fun from(id: Int): HelloStatus? = entries.firstOrNull { it.id == id }
    }
}

/** 输入动作：触摸按下/抬起/移动，以及远端 back 键 */
enum class InputAction(val id: Int) {
    DOWN(0), UP(1), MOVE(2), BACK(3);

    companion object {
        fun from(id: Int): InputAction? = entries.firstOrNull { it.id == id }
    }
}

/** 传输路径类型（STATS.transport 字段） */
enum class TransportKind(val id: Int) {
    LAN_DIRECT(0), P2P(1), FRP(2), P2P_FALLBACK_FRP(3);

    companion object {
        fun from(id: Int): TransportKind? = entries.firstOrNull { it.id == id }
    }
}

/** 已挂机（断开未销毁）会话摘要，随 HELLO_ACK 下发供会话抽屉展示 */
data class HungSession(
    val sessionId: Int,
    val mode: WatchMode,
    val packageName: String,
    val width: Int,
    val height: Int,
)

/** 协议解析失败（结构性损坏；未知消息类型不算失败，见 [ControlMessage.Unknown]） */
class ProtocolException(message: String) : Exception(message)

/** 控制消息基类；所有子类字段布局定宽 C 兼容 */
sealed class ControlMessage(val type: Int) {

    /** 客户端 → 源端：握手与配对认证 */
    data class Hello(
        val version: Int,
        val token: String,
        val mode: WatchMode,
        val packageName: String,
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrateKbps: Int,
        val sessionId: Int,
        /** 客户端 SRFLX offer（"ip:port"，p2p 打洞用；空串 = 不打洞） */
        val p2pOffer: String,
    ) : ControlMessage(MsgType.HELLO)

    /** 源端 → 客户端：握手应答（接受携带会话参数，拒绝携带原因） */
    data class HelloAck(
        val status: HelloStatus,
        val reason: String,
        val sessionId: Int,
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrateKbps: Int,
        val encoder: String,
        val displayId: Int,
        /** 源端 SRFLX answer（"ip:port"，空串 = 不打洞） */
        val p2pAnswer: String,
        /** 已挂机会话列表（会话抽屉） */
        val hungSessions: List<HungSession>,
    ) : ControlMessage(MsgType.HELLO_ACK)

    /** 客户端 → 源端：发起新会话或以 sessionId 附着挂机会话 */
    data class StartSession(
        val sessionId: Int,
        val mode: WatchMode,
        val packageName: String,
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrateKbps: Int,
    ) : ControlMessage(MsgType.START_SESSION)

    /** 客户端 → 源端：结束会话（单应用模式销毁虚拟屏与 App） */
    data class StopSession(val sessionId: Int) : ControlMessage(MsgType.STOP_SESSION)

    /** 客户端 → 源端：请求关键帧（重连/持续丢包时） */
    data class IdrRequest(val sessionId: Int) : ControlMessage(MsgType.IDR_REQUEST)

    /** 客户端 → 源端：输入事件（归一化坐标 x/y ∈ [0,65535]） */
    data class InputEvent(
        val sessionId: Int,
        val action: InputAction,
        val pointerId: Int,
        val x: Int,
        val y: Int,
    ) : ControlMessage(MsgType.INPUT_EVENT)

    /** 双向：≥1Hz 统计（驱动统计显示与验证矩阵采集） */
    data class StatsMessage(
        val sessionId: Int,
        val seq: Int,
        val fps: Float,
        val bitrateKbps: Float,
        val rttMs: Float,
        val lossPct: Float,
        val transport: TransportKind,
    ) : ControlMessage(MsgType.STATS)

    /** 客户端 → 源端：RTT与时钟偏移探测（nonce + 发送侧单调时钟微秒） */
    data class Ping(val nonce: Long, val senderUs: Long) : ControlMessage(MsgType.PING)

    /** 源端 → 客户端：回显 nonce/senderUs 并附响应侧时钟 */
    data class Pong(val nonce: Long, val senderUs: Long, val echoUs: Long) : ControlMessage(MsgType.PONG)

    /** 未知类型消息：接收方 MUST 忽略且不中断会话 */
    data class Unknown(val unknownType: Int, val payload: ByteArray) : ControlMessage(unknownType) {
        override fun equals(other: Any?): Boolean =
            other is Unknown && other.unknownType == unknownType && other.payload.contentEquals(payload)
        override fun hashCode(): Int = unknownType * 31 + payload.contentHashCode()
    }
}

// ── 线格式基础原语（u16 定宽 + UTF-8 字符串） ──

private fun ByteBuffer.putU16(v: Int) {
    require(v in 0..0xFFFF) { "u16 out of range: $v" }
    put((v ushr 8).toByte())
    put(v.toByte())
}

private fun ByteBuffer.getU16(): Int {
    val hi = get().toInt() and 0xFF
    return (hi shl 8) or (get().toInt() and 0xFF)
}

private fun ByteBuffer.putU16String(s: String) {
    val bytes = s.toByteArray(StandardCharsets.UTF_8)
    putU16(bytes.size)
    put(bytes)
}

private fun ByteBuffer.getU16String(): String {
    val len = getU16()
    val bytes = ByteArray(len)
    get(bytes)
    return String(bytes, StandardCharsets.UTF_8)
}

/** 控制消息 → 线载荷（不含 record 头，首字节为 msgType） */
fun encodeControlMessage(msg: ControlMessage): ByteArray {
    val body = when (msg) {
        is ControlMessage.Hello -> ByteBuffer.allocate(
            2 + 2 + msg.token.toByteArray(StandardCharsets.UTF_8).size + 1 + 2 + msg.packageName.toByteArray(StandardCharsets.UTF_8).size + 2 + 2 + 1 + 4 + 4 + 2 + msg.p2pOffer.toByteArray(StandardCharsets.UTF_8).size
        ).apply {
            putShort(msg.version.toShort())
            putU16String(msg.token)
            put(msg.mode.id.toByte())
            putU16String(msg.packageName)
            putShort(msg.width.toShort())
            putShort(msg.height.toShort())
            put(msg.fps.toByte())
            putInt(msg.bitrateKbps)
            putInt(msg.sessionId)
            putU16String(msg.p2pOffer)
        }
        is ControlMessage.HelloAck -> {
            var cap = 1 + 2 + msg.reason.toByteArray(StandardCharsets.UTF_8).size + 4 + 2 + 2 + 1 + 4 + 2 + msg.encoder.toByteArray(StandardCharsets.UTF_8).size + 4 + 2 + msg.p2pAnswer.toByteArray(StandardCharsets.UTF_8).size + 2
            for (h in msg.hungSessions) cap += 4 + 1 + 2 + h.packageName.toByteArray(StandardCharsets.UTF_8).size + 2 + 2
            ByteBuffer.allocate(cap).apply {
                put(msg.status.id.toByte())
                putU16String(msg.reason)
                putInt(msg.sessionId)
                putShort(msg.width.toShort())
                putShort(msg.height.toShort())
                put(msg.fps.toByte())
                putInt(msg.bitrateKbps)
                putU16String(msg.encoder)
                putInt(msg.displayId)
                putU16String(msg.p2pAnswer)
                putU16(msg.hungSessions.size)
                for (h in msg.hungSessions) {
                    putInt(h.sessionId)
                    put(h.mode.id.toByte())
                    putU16String(h.packageName)
                    putShort(h.width.toShort())
                    putShort(h.height.toShort())
                }
            }
        }
        is ControlMessage.StartSession -> ByteBuffer.allocate(4 + 1 + 2 + msg.packageName.toByteArray(StandardCharsets.UTF_8).size + 2 + 2 + 1 + 4).apply {
            putInt(msg.sessionId)
            put(msg.mode.id.toByte())
            putU16String(msg.packageName)
            putShort(msg.width.toShort())
            putShort(msg.height.toShort())
            put(msg.fps.toByte())
            putInt(msg.bitrateKbps)
        }
        is ControlMessage.StopSession -> ByteBuffer.allocate(4).apply { putInt(msg.sessionId) }
        is ControlMessage.IdrRequest -> ByteBuffer.allocate(4).apply { putInt(msg.sessionId) }
        is ControlMessage.InputEvent -> ByteBuffer.allocate(4 + 1 + 1 + 2 + 2).apply {
            putInt(msg.sessionId)
            put(msg.action.id.toByte())
            put(msg.pointerId.toByte())
            // 归一化坐标 u16（[0,65535]），按无符号 16 位写入
            putShort((msg.x and 0xFFFF).toShort())
            putShort((msg.y and 0xFFFF).toShort())
        }
        is ControlMessage.StatsMessage -> ByteBuffer.allocate(4 + 4 + 4 + 4 + 4 + 4 + 1).apply {
            putInt(msg.sessionId)
            putInt(msg.seq)
            putFloat(msg.fps)
            putFloat(msg.bitrateKbps)
            putFloat(msg.rttMs)
            putFloat(msg.lossPct)
            put(msg.transport.id.toByte())
        }
        is ControlMessage.Ping -> ByteBuffer.allocate(8 + 8).apply {
            putLong(msg.nonce)
            putLong(msg.senderUs)
        }
        is ControlMessage.Pong -> ByteBuffer.allocate(8 + 8 + 8).apply {
            putLong(msg.nonce)
            putLong(msg.senderUs)
            putLong(msg.echoUs)
        }
        is ControlMessage.Unknown -> throw ProtocolException("cannot encode unknown message type ${msg.unknownType}")
    }
    body.flip()
    val out = ByteArray(1 + body.remaining())
    out[0] = msg.type.toByte()
    body.get(out, 1, body.remaining())
    return out
}

/** 线载荷 → 控制消息；未知 msgType 返回 [ControlMessage.Unknown]（调用方忽略即可） */
fun decodeControlMessage(payload: ByteArray): ControlMessage {
    require(payload.isNotEmpty()) { "empty control payload" }
    val buf = ByteBuffer.wrap(payload)
    val type = buf.get().toInt() and 0xFF
    return when (type) {
        MsgType.HELLO -> ControlMessage.Hello(
            version = buf.short.toInt(),
            token = buf.getU16String(),
            mode = WatchMode.from(buf.get().toInt())
                ?: throw ProtocolException("bad watch mode"),
            packageName = buf.getU16String(),
            width = buf.short.toInt(),
            height = buf.short.toInt(),
            fps = buf.get().toInt(),
            bitrateKbps = buf.int,
            sessionId = buf.int,
            p2pOffer = buf.getU16String(),
        )
        MsgType.HELLO_ACK -> {
            val status = HelloStatus.from(buf.get().toInt())
                ?: throw ProtocolException("bad hello status")
            val reason = buf.getU16String()
            val sessionId = buf.int
            val width = buf.short.toInt()
            val height = buf.short.toInt()
            val fps = buf.get().toInt()
            val bitrateKbps = buf.int
            val encoder = buf.getU16String()
            val displayId = buf.int
            val p2pAnswer = buf.getU16String()
            val hungCount = buf.getU16()
            val hung = ArrayList<HungSession>(hungCount)
            repeat(hungCount) {
                val hs = buf.int
                val hm = WatchMode.from(buf.get().toInt())
                    ?: throw ProtocolException("bad hung session mode")
                val hp = buf.getU16String()
                val hw = buf.short.toInt()
                val hh = buf.short.toInt()
                hung.add(HungSession(hs, hm, hp, hw, hh))
            }
            ControlMessage.HelloAck(
                status, reason, sessionId, width, height, fps, bitrateKbps,
                encoder, displayId, p2pAnswer, hung,
            )
        }
        MsgType.START_SESSION -> ControlMessage.StartSession(
            sessionId = buf.int,
            mode = WatchMode.from(buf.get().toInt()) ?: throw ProtocolException("bad watch mode"),
            packageName = buf.getU16String(),
            width = buf.short.toInt(),
            height = buf.short.toInt(),
            fps = buf.get().toInt(),
            bitrateKbps = buf.int,
        )
        MsgType.STOP_SESSION -> ControlMessage.StopSession(buf.int)
        MsgType.IDR_REQUEST -> ControlMessage.IdrRequest(buf.int)
        MsgType.INPUT_EVENT -> ControlMessage.InputEvent(
            sessionId = buf.int,
            action = InputAction.from(buf.get().toInt()) ?: throw ProtocolException("bad input action"),
            pointerId = buf.get().toInt(),
            x = buf.short.toInt() and 0xFFFF,
            y = buf.short.toInt() and 0xFFFF,
        )
        MsgType.STATS -> ControlMessage.StatsMessage(
            sessionId = buf.int,
            seq = buf.int,
            fps = buf.float,
            bitrateKbps = buf.float,
            rttMs = buf.float,
            lossPct = buf.float,
            transport = TransportKind.from(buf.get().toInt()) ?: throw ProtocolException("bad transport kind"),
        )
        MsgType.PING -> {
            val nonce = buf.long
            ControlMessage.Ping(nonce, buf.long)
        }
        MsgType.PONG -> {
            val nonce = buf.long
            val senderUs = buf.long
            ControlMessage.Pong(nonce, senderUs, buf.long)
        }
        else -> {
            val rest = ByteArray(buf.remaining())
            buf.get(rest)
            ControlMessage.Unknown(type, rest)
        }
    }
}
