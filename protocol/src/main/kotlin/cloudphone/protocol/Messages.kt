package cloudphone.protocol

import cloudphone.transport.CryptoAlgo
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

/** 控制消息类型常量（M1 C++ 化时保持数值不变；0x0C 起为 p2p-crossnet-traversal 扩展） */
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
    const val APP_LIST_REQUEST: Int = 0x0A
    const val APP_LIST: Int = 0x0B

    /** 端点迁移（p2p-crossnet-traversal 2.3）：本端网络切换后向对端推送新候选地址 */
    const val P2P_MIGRATE: Int = 0x0C
}

/** 观看模式：整屏 / 单应用（指定包名）/ 附着到已挂机会话 */
enum class WatchMode(val id: Int) {
    FULLSCREEN(1), SINGLE_APP(2), ATTACH(3);

    companion object {
        fun from(id: Int): WatchMode? = entries.firstOrNull { it.id == id }
    }
}

/** HELLO_ACK 状态与错误码（REJECT_BAD_TOKEN=1：token 不匹配拒绝，加密协商亦复用该拒绝路径） */
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

/** 可启动应用条目（APP_LIST 载荷）：源端 PackageManager 枚举结果 */
data class AppEntry(
    val packageName: String,
    val label: String,
)

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
        /** 加密开关意图（true 时线格式追加 34B crypto 块；false = 不追加，与 v1 现状逐字节一致） */
        val encryptOn: Boolean = false,
        /** 请求的加密算法（协商请求语义：encryptOn=true 恒 AES128GCM） */
        val cryptoAlgo: CryptoAlgo = CryptoAlgo.NONE,
        /** 客户端 32B 随机 nonce（HKDF salt 材料；每次握手重新生成） */
        val cryptoNonce: ByteArray = ByteArray(0),
    ) : ControlMessage(MsgType.HELLO) {
        /** ByteArray 属性需按内容比较（数据类默认引用比较） */
        override fun equals(other: Any?): Boolean =
            other is Hello && other.version == version && other.token == token && other.mode == mode &&
                other.packageName == packageName && other.width == width && other.height == height &&
                other.fps == fps && other.bitrateKbps == bitrateKbps && other.sessionId == sessionId &&
                other.p2pOffer == p2pOffer && other.encryptOn == encryptOn && other.cryptoAlgo == cryptoAlgo &&
                other.cryptoNonce.contentEquals(cryptoNonce)
        override fun hashCode(): Int {
            var h = version
            h = h * 31 + token.hashCode()
            h = h * 31 + mode.hashCode()
            h = h * 31 + packageName.hashCode()
            h = h * 31 + width
            h = h * 31 + height
            h = h * 31 + fps
            h = h * 31 + bitrateKbps
            h = h * 31 + sessionId
            h = h * 31 + p2pOffer.hashCode()
            h = h * 31 + encryptOn.hashCode()
            h = h * 31 + cryptoAlgo.hashCode()
            h = h * 31 + cryptoNonce.contentHashCode()
            return h
        }
    }

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
        /** 源端加密开关意图（老端无此块 = false） */
        val encryptOn: Boolean = false,
        /** 协商结果：AES128GCM = 双端都开且都支持；NONE = 未加密（老端不支持/任一侧关闭） */
        val cryptoAlgo: CryptoAlgo = CryptoAlgo.NONE,
        /** 源端 32B 随机 nonce（协商结果 AES128GCM 时携带） */
        val cryptoNonce: ByteArray = ByteArray(0),
    ) : ControlMessage(MsgType.HELLO_ACK) {
        /** ByteArray 属性需按内容比较（数据类默认引用比较） */
        override fun equals(other: Any?): Boolean =
            other is HelloAck && other.status == status && other.reason == reason && other.sessionId == sessionId &&
                other.width == width && other.height == height && other.fps == fps &&
                other.bitrateKbps == bitrateKbps && other.encoder == encoder && other.displayId == displayId &&
                other.p2pAnswer == p2pAnswer && other.hungSessions == hungSessions &&
                other.encryptOn == encryptOn && other.cryptoAlgo == cryptoAlgo &&
                other.cryptoNonce.contentEquals(cryptoNonce)
        override fun hashCode(): Int {
            var h = status.hashCode()
            h = h * 31 + reason.hashCode()
            h = h * 31 + sessionId
            h = h * 31 + width
            h = h * 31 + height
            h = h * 31 + fps
            h = h * 31 + bitrateKbps
            h = h * 31 + encoder.hashCode()
            h = h * 31 + displayId
            h = h * 31 + p2pAnswer.hashCode()
            h = h * 31 + hungSessions.hashCode()
            h = h * 31 + encryptOn.hashCode()
            h = h * 31 + cryptoAlgo.hashCode()
            h = h * 31 + cryptoNonce.contentHashCode()
            return h
        }
    }

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

    /**
     * 双向：端点迁移地址更新（p2p-crossnet-traversal 2.3）。本端网络切换（出口 IP 变化）后
     * 经信令通道把新候选地址推给对端；对端显式 setRemote 更新打洞目标，sequence=0 探针
     * 重连复用，会话与统计均不重置。载荷候选文本格式与 p2pOffer 一致（多候选 + ";nat=sym"）。
     */
    data class P2pMigrate(
        val candidates: String,
    ) : ControlMessage(MsgType.P2P_MIGRATE)

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

    /** 客户端 → 源端：请求枚举源端可启动应用（请求-应答制；空载荷） */
    data class AppListRequest(val requestId: Int) : ControlMessage(MsgType.APP_LIST_REQUEST)

    /** 源端 → 客户端：可启动应用列表（排除源端 App 自身；AppEntry{packageName,label}） */
    data class AppListMessage(
        val requestId: Int,
        val apps: List<AppEntry>,
    ) : ControlMessage(MsgType.APP_LIST)

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

// ── 加密协商块（p2p-crossnet-traversal 1.3，可选尾部字段；不 bump PROTOCOL_VERSION）──

/** [u8 cryptoAlgo][u8 encryptOn][32B nonce] = 34B；老端无此块，新端按 remaining() 判定有无 */
const val CRYPTO_BLOCK_BYTES: Int = 1 + 1 + 32

private fun ByteBuffer.putCryptoBlock(algo: CryptoAlgo, encryptOn: Boolean, nonce: ByteArray) {
    put(algo.id.toByte())
    put((if (encryptOn) 1 else 0).toByte())
    val n = ByteArray(32) // 不足 32B 左侧零填充（nonce 恒 32B，防御编码半成品）
    nonce.copyInto(n, (32 - nonce.size).coerceAtLeast(0).coerceAtMost(32))
    put(n)
}

/** 读可选 crypto 块；缓冲不足（老端消息）返回 null → 协商回落 none */
private fun ByteBuffer.getCryptoBlockOpt(): Triple<CryptoAlgo, Boolean, ByteArray>? {
    if (remaining() < CRYPTO_BLOCK_BYTES) return null
    val algo = CryptoAlgo.from(get().toInt() and 0xFF) ?: CryptoAlgo.NONE
    val on = (get().toInt() and 0xFF) != 0
    val nonce = ByteArray(32)
    get(nonce)
    return Triple(algo, on, nonce)
}

/** 控制消息 → 线载荷（不含 record 头，首字节为 msgType） */
fun encodeControlMessage(msg: ControlMessage): ByteArray {
    val body = when (msg) {
        is ControlMessage.Hello -> ByteBuffer.allocate(
            2 + 2 + msg.token.toByteArray(StandardCharsets.UTF_8).size + 1 + 2 + msg.packageName.toByteArray(StandardCharsets.UTF_8).size + 2 + 2 + 1 + 4 + 4 + 2 + msg.p2pOffer.toByteArray(StandardCharsets.UTF_8).size +
                (if (msg.encryptOn) CRYPTO_BLOCK_BYTES else 0)
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
            if (msg.encryptOn) putCryptoBlock(msg.cryptoAlgo, msg.encryptOn, msg.cryptoNonce)
        }
        is ControlMessage.HelloAck -> {
            val cryptoBlock = msg.encryptOn || msg.cryptoAlgo != CryptoAlgo.NONE
            var cap = 1 + 2 + msg.reason.toByteArray(StandardCharsets.UTF_8).size + 4 + 2 + 2 + 1 + 4 + 2 + msg.encoder.toByteArray(StandardCharsets.UTF_8).size + 4 + 2 + msg.p2pAnswer.toByteArray(StandardCharsets.UTF_8).size + 2 +
                (if (cryptoBlock) CRYPTO_BLOCK_BYTES else 0)
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
                if (cryptoBlock) putCryptoBlock(msg.cryptoAlgo, msg.encryptOn, msg.cryptoNonce)
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
        is ControlMessage.P2pMigrate -> ByteBuffer.allocate(2 + msg.candidates.toByteArray(StandardCharsets.UTF_8).size).apply {
            putU16String(msg.candidates)
        }
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
        is ControlMessage.AppListRequest -> ByteBuffer.allocate(4).apply { putInt(msg.requestId) }
        is ControlMessage.AppListMessage -> {
            var cap = 4 + 2
            for (a in msg.apps) {
                cap += 2 + a.packageName.toByteArray(StandardCharsets.UTF_8).size +
                    2 + a.label.toByteArray(StandardCharsets.UTF_8).size
            }
            ByteBuffer.allocate(cap).apply {
                putInt(msg.requestId)
                putU16(msg.apps.size)
                for (a in msg.apps) {
                    putU16String(a.packageName)
                    putU16String(a.label)
                }
            }
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
        MsgType.HELLO -> {
            val hello = ControlMessage.Hello(
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
            // 可选 crypto 块（老端无尾部字节 → 保持默认 none/false，兼容回落）
            val crypto = buf.getCryptoBlockOpt() ?: return hello
            hello.copy(encryptOn = crypto.second, cryptoAlgo = crypto.first, cryptoNonce = crypto.third)
        }
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
            val ack = ControlMessage.HelloAck(
                status, reason, sessionId, width, height, fps, bitrateKbps,
                encoder, displayId, p2pAnswer, hung,
            )
            // 可选 crypto 块（老端无尾部字节 → 协商结果 none）
            val crypto = buf.getCryptoBlockOpt() ?: return ack
            ack.copy(encryptOn = crypto.second, cryptoAlgo = crypto.first, cryptoNonce = crypto.third)
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
        MsgType.P2P_MIGRATE -> ControlMessage.P2pMigrate(candidates = buf.getU16String())
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
        MsgType.APP_LIST_REQUEST -> ControlMessage.AppListRequest(requestId = buf.int)
        MsgType.APP_LIST -> {
            val requestId = buf.int
            val count = buf.getU16()
            val apps = ArrayList<AppEntry>(count)
            repeat(count) {
                val pkg = buf.getU16String()
                apps.add(AppEntry(pkg, buf.getU16String()))
            }
            ControlMessage.AppListMessage(requestId, apps)
        }
        else -> {
            val rest = ByteArray(buf.remaining())
            buf.get(rest)
            ControlMessage.Unknown(type, rest)
        }
    }
}
