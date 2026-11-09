package cloudphone.transport

import cloudphone.protocol.Framing
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 流加密协商枚举（Hello.cryptoAlgo / HelloAck.cryptoAlgo 线上取值，p2p-crossnet-traversal 1.3）。
 * 0=none（明文，与协议 v1 现状逐字节一致）；1=AES128GCM（片级 AEAD）。
 */
enum class CryptoAlgo(val id: Int) {
    NONE(0),
    AES128_GCM(1);

    companion object {
        fun from(id: Int): CryptoAlgo? = entries.firstOrNull { it.id == id }
    }
}

/**
 * RFC 5869 HKDF（extract + expand），基于 HmacSHA256。
 * 用途：从配对 token（IKM）+ 双方 32B nonce XOR（salt）派生每方向 128bit 子钥。
 */
object Hkdf {
    private const val HASH_LEN = 32
    private const val HMAC_ALGO = "HmacSHA256"

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGO)
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(HASH_LEN) else key, HMAC_ALGO))
        return mac.doFinal(data)
    }

    /** HKDF-Extract(salt, ikm) → PRK */
    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray = hmacSha256(salt, ikm)

    /** HKDF-Expand(prk, info, length) → OKM（length ≤ 255*32） */
    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length <= 255 * HASH_LEN) { "HKDF output too long: $length" }
        val okm = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            val buf = ByteBuffer.allocate(t.size + info.size + 1)
            buf.put(t)
            buf.put(info)
            buf.put(counter.toByte())
            t = hmacSha256(prk, buf.array())
            val n = minOf(HASH_LEN, length - pos)
            System.arraycopy(t, 0, okm, pos, n)
            pos += n
            counter++
        }
        return okm
    }

    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray =
        expand(extract(salt, ikm), info, length)
}

/**
 * 片级 AES-128-GCM 流加密编解码器（p2p-crossnet-traversal 1.1，design D3）。
 *
 * 密钥：HKDF-SHA256(ikm=配对 token, salt=双方 32B nonce 逐字节 XOR, info=方向) →
 * 每方向独立 128bit 子钥（c2s="c2s"、s2c="s2c"）。
 * nonce：12B = [u32 方向标识][u64 counter]（大端）；方向 c2s=1、s2c=2。
 *   - 视频片 counter 由帧头确定性推导：counter = sequence*65536 + fragmentIndex
 *     （sequence u32 × fragIdx u16 → 48bit 空间，双端无需传 counter 即可重建 nonce，
 *     同帧多片各占独立 counter，杜绝 GCM nonce 重用）；
 *   - 控制记录 counter = 2^62 起的单调序号（可靠有序流上按到达顺序重建，与视频
 *     counter 空间隔离）。AAD：
 *   - 视频片 AAD = [u32 sequence][u8 flags]（帧头 sequence/keyframe 绑定防重排篡改）；
 *   - 控制记录 AAD = [u64 counter]。
 * tag：128bit（+16B/片）。密文输出 = ciphertext || tag，无独立头（开销恒 +16B）。
 * 重放防护：512 窗口滑动位图，窗口外旧 counter 丢弃。
 * 探针帧（sequence=0）恒明文——由调用方短路，本类不做特判（见 UdpVideoChannel）。
 *
 * 线程安全：发送 counter 原子自增；AES Cipher 走 ThreadLocal；重放位图单锁。
 */
class CryptoCodec private constructor(
    private val role: Role,
    c2sKey: ByteArray,
    s2cKey: ByteArray,
) {

    val algo: CryptoAlgo get() = CryptoAlgo.AES128_GCM

    /** 本端发送方向（client→source = c2s；source→client = s2c） */
    private val sendDirection: Int = if (role == Role.CLIENT) DIR_C2S else DIR_S2C
    /** 本端接收方向 */
    private val recvDirection: Int = if (role == Role.CLIENT) DIR_S2C else DIR_C2S

    private val sendKey: ByteArray = if (role == Role.CLIENT) c2sKey else s2cKey
    private val recvKey: ByteArray = if (role == Role.CLIENT) s2cKey else c2sKey

    private val recvVideoGuard = FragReplayGuard(REPLAY_WINDOW_BITS)
    private val recvControlWindow = ReplayWindow(REPLAY_WINDOW_BITS)

    /** 控制记录发送序号（可靠有序流；nonce 空间与视频 counter 隔离） */
    private val controlSendCounter = java.util.concurrent.atomic.AtomicLong(CONTROL_COUNTER_BASE)

    private val cipherTl = object : ThreadLocal<Cipher>() {
        override fun initialValue(): Cipher = Cipher.getInstance(TRANSFORM)
    }

    // ── 视频片（AAD=sequence/flags；counter=sequence*65536+fragIdx）──

    /** 加密一个视频分片载荷（明文任意长度，含 0B），返回 ciphertext||tag */
    fun encryptVideo(sequence: Long, keyframe: Boolean, fragmentIndex: Int, plaintext: ByteArray): ByteArray {
        val counter = videoCounter(sequence, fragmentIndex)
        return aeadEncrypt(sendKey, buildNonce(sendDirection, counter), videoAad(sequence, keyframe), plaintext, cipherTl.get())
    }

    /**
     * 解密一个视频分片载荷；认证失败或重放（重复/窗口外旧 counter）返回 null
     * （调用方按丢弃计数处理，不下发解码）。
     * 顺序纪律：先 GCM 认证后占重放窗口——认证失败（传输损坏）不占窗口，
     * 该分片的干净重传仍可入窗；有效认证的重放才被位图拦截。
     * 重放窗口以「counter=(sequence,fragmentIndex)」为粒度、512 个 sequence 宽度滑动
     * （nonce counter = sequence*65536+fragIdx 的网格系数是 65536，若按原始 counter
     * 数值开 512 窗，任何一帧乱序即整体出窗——故窗口按 sequence 维度滑动，
     * 帧内片去重由 (sequence,fragIdx) 位图承担）。
     */
    fun decryptVideo(sequence: Long, keyframe: Boolean, fragmentIndex: Int, sealed: ByteArray): ByteArray? {
        val plain = try {
            aeadDecrypt(recvKey, buildNonce(recvDirection, videoCounter(sequence, fragmentIndex)), videoAad(sequence, keyframe), sealed, cipherTl.get())
        } catch (_: Exception) {
            return null // GCM 认证拒绝（AEADBadTagException）：密文/AAD/密钥任一不匹配
        }
        if (!recvVideoGuard.checkAndMark(sequence, fragmentIndex)) return null // 有效认证的重放：丢弃
        return plain
    }

    // ── 控制记录（AAD=counter；counter=2^62+单调序号，按到达顺序重建）──

    fun encryptControl(plaintext: ByteArray): ByteArray {
        val counter = controlSendCounter.getAndIncrement()
        return aeadEncrypt(sendKey, buildNonce(sendDirection, counter), controlAad(counter), plaintext, cipherTl.get())
    }

    /** 解密一条控制记录；认证失败/重放返回 null（调用方按流错误处理） */
    fun decryptControl(sealed: ByteArray): ByteArray? {
        val counter = recvControlCounter.getAndIncrement()
        if (!recvControlWindow.checkAndMark(counter)) return null
        return try {
            aeadDecrypt(recvKey, buildNonce(recvDirection, counter), controlAad(counter), sealed, cipherTl.get())
        } catch (_: Exception) {
            null
        }
    }

    private val recvControlCounter = java.util.concurrent.atomic.AtomicLong(CONTROL_COUNTER_BASE)

    /** 会话身份描述（日志/诊断用，不含密钥材料） */
    override fun toString(): String = "CryptoCodec(algo=AES128_GCM, role=$role)"

    companion object {
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val TAG_BYTES = 16
        const val NONCE_BYTES = 12
        const val REPLAY_WINDOW_BITS = 512

        /** nonce 方向标识（32bit）：c2s=1、s2c=2 */
        const val DIR_C2S: Int = 1
        const val DIR_S2C: Int = 2

        /** 视频片 counter = sequence*65536 + fragIdx 的 fragIdx 位移 */
        const val FRAG_IDX_SHIFT = 16

        /** 控制记录 counter 基址（2^62，与视频 counter（<2^48）空间隔离） */
        const val CONTROL_COUNTER_BASE = 1L shl 62

        /** 双方 nonce 字节数（Hello.cryptoNonce / HelloAck.cryptoNonce） */
        const val HANDSHAKE_NONCE_BYTES = 32

        private val RANDOM = SecureRandom()

        /** 生成 32B 握手随机 nonce（SecureRandom；两端各一，落地 Hello/HelloAck） */
        fun generateHandshakeNonce(): ByteArray = ByteArray(HANDSHAKE_NONCE_BYTES).also { RANDOM.nextBytes(it) }

        /** 逐字节 XOR 两 nonce → HKDF salt（长度不一致按较短截断，正常路径两侧恒 32B） */
        fun xorNonces(a: ByteArray, b: ByteArray): ByteArray {
            val n = minOf(a.size, b.size)
            val out = ByteArray(n)
            for (i in 0 until n) out[i] = (a[i].toInt() xor b[i].toInt()).toByte()
            return out
        }

        /** 派生方向子钥：c2s="c2s" info、s2c="s2c" info，各 16B */
        fun deriveKeys(token: String, clientNonce: ByteArray, sourceNonce: ByteArray): Pair<ByteArray, ByteArray> {
            val salt = xorNonces(clientNonce, sourceNonce)
            val ikm = token.toByteArray(Charsets.UTF_8)
            val c2s = Hkdf.derive(ikm, salt, "c2s".toByteArray(Charsets.UTF_8), 16)
            val s2c = Hkdf.derive(ikm, salt, "s2c".toByteArray(Charsets.UTF_8), 16)
            return c2s to s2c
        }

        /** 由握手材料构造会话编解码器（协商结果=AES128GCM 时调用） */
        fun negotiate(role: Role, token: String, clientNonce: ByteArray, sourceNonce: ByteArray): CryptoCodec {
            val (c2s, s2c) = deriveKeys(token, clientNonce, sourceNonce)
            return CryptoCodec(role, c2s, s2c)
        }

        /** 12B nonce = [u32 方向][u64 counter]（大端） */
        fun buildNonce(direction: Int, counter: Long): ByteArray {
            val out = ByteArray(NONCE_BYTES)
            out[0] = (direction ushr 24).toByte()
            out[1] = (direction ushr 16).toByte()
            out[2] = (direction ushr 8).toByte()
            out[3] = direction.toByte()
            for (i in 0 until 8) out[4 + i] = (counter ushr (8 * (7 - i))).toByte()
            return out
        }

        /** 视频 counter 确定性推导：sequence*65536 + fragmentIndex（u32×u16 → 48bit） */
        fun videoCounter(sequence: Long, fragmentIndex: Int): Long =
            (sequence shl FRAG_IDX_SHIFT) or (fragmentIndex.toLong() and 0xFFFFL)

        /** 视频片 AAD = [u32 sequence][u8 flags]（flags bit0=keyframe，与帧头一致） */
        fun videoAad(sequence: Long, keyframe: Boolean): ByteArray {
            val aad = ByteArray(5)
            aad[0] = (sequence ushr 24).toByte()
            aad[1] = (sequence ushr 16).toByte()
            aad[2] = (sequence ushr 8).toByte()
            aad[3] = sequence.toByte()
            aad[4] = if (keyframe) Framing.FLAG_KEYFRAME.toByte() else 0
            return aad
        }

        /** 控制记录 AAD = [u64 counter] */
        fun controlAad(counter: Long): ByteArray {
            val aad = ByteArray(8)
            for (i in 0 until 8) aad[i] = (counter ushr (8 * (7 - i))).toByte()
            return aad
        }

        /** AES-128-GCM 加密（低层：key 16B、nonce 12B），返回 ciphertext||tag */
        fun aeadEncrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray, cipher: Cipher? = null): ByteArray {
            val c = cipher ?: Cipher.getInstance(TRANSFORM)
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            if (aad.isNotEmpty()) c.updateAAD(aad)
            return c.doFinal(plaintext) // GCM doFinal 输出 = ciphertext + tag（+16B）
        }

        /** AES-128-GCM 解密（低层）；认证失败抛 AEADBadTagException，调用方决定处置 */
        fun aeadDecrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray, cipher: Cipher? = null): ByteArray {
            val c = cipher ?: Cipher.getInstance(TRANSFORM)
            require(sealed.size >= TAG_BYTES) { "ciphertext shorter than GCM tag" }
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            if (aad.isNotEmpty()) c.updateAAD(aad)
            return c.doFinal(sealed)
        }
    }
}

/**
 * 512 窗口滑动位图重放防护（p2p-crossnet-traversal 重放防护 Requirement）。
 * 位图按「age = highWater - counter」相对索引：age=0 为最新；
 * counter 前移时窗口整体右移，移出窗口的旧记录自动失效。
 * checkAndMark 语义：已见 false（重复丢弃）、未见标记并放行 true、
 * 窗口外旧 counter false（丢弃）、新 counter 前移窗口 true。
 */
class ReplayWindow(val bits: Int = CryptoCodec.REPLAY_WINDOW_BITS) {

    private val words = LongArray((bits + 63) / 64)
    private var highWater = -1L

    /** 记录 counter；返回是否放行（true=未见且窗口内/新高度） */
    fun checkAndMark(counter: Long): Boolean {
        if (counter < 0) return false
        synchronized(this) {
            if (highWater < 0) {
                highWater = counter
                setAge(0)
                return true
            }
            if (counter > highWater) {
                val advance = counter - highWater
                shiftRight(if (advance >= bits) bits.toLong() else advance)
                highWater = counter
                setAge(0)
                return true
            }
            val age = (highWater - counter)
            if (age >= bits) return false // 窗口外旧 counter：丢弃
            val r = age.toInt()
            if (bitAt(r)) return false // 窗口内已见：重复丢弃
            setAge(r) // 窗口内未见：标记并放行
            return true
        }
    }

    /** 窗口当前高水位（测试/诊断用） */
    fun highWaterMark(): Long = synchronized(this) { highWater }

    private fun bitAt(r: Int): Boolean = (words[r / 64] ushr (r % 64)) and 1L == 1L

    private fun setAge(r: Int) {
        words[r / 64] = words[r / 64] or (1L shl (r % 64))
    }

    /** 位图整体右移 s 位（age 全体 +s；移出 [0,bits) 的记录丢弃） */
    private fun shiftRight(s: Long) {
        if (s <= 0L) return
        if (s >= bits) {
            java.util.Arrays.fill(words, 0L)
            return
        }
        val wShift = (s / 64).toInt()
        val bShift = (s % 64).toInt()
        val next = LongArray(words.size)
        for (i in words.indices) {
            var v = 0L
            val src0 = i - wShift
            if (src0 >= 0) v = if (bShift == 0) words[src0] else words[src0] ushr bShift
            val src1 = src0 - 1
            if (bShift != 0 && src1 >= 0) v = v or (words[src1] ushr (64 - bShift))
            next[i] = v
        }
        System.arraycopy(next, 0, words, 0, words.size)
    }
}

/**
 * 片级重放防护（视频通道）：512 个 sequence 宽度的滑动窗口，帧内按 fragmentIndex 位图去重。
 * counter 语义 = (sequence, fragmentIndex)：窗口内已见 (seq,frag) 丢弃、未见标记并放行、
 * 窗口外旧 sequence 丢弃、新 sequence 前移窗口。
 * （nonce counter 网格 = sequence*65536+fragIdx，线性 counter 位图在任一帧乱序时即整体
 * 出窗——UDP 常态乱序/NACK 重传下不可用，故窗口按 sequence 维度滑动。）
 */
class FragReplayGuard(val window: Int = CryptoCodec.REPLAY_WINDOW_BITS) {

    private val marks = HashMap<Long, java.util.BitSet>()
    private var highSeq = -1L

    /** 返回 true=放行（窗口内未见）；false=重放（已见）或窗口外旧 sequence（丢弃） */
    fun checkAndMark(sequence: Long, fragmentIndex: Int): Boolean {
        if (sequence < 0) return false
        synchronized(this) {
            if (highSeq < 0) {
                highSeq = sequence
                return mark(sequence, fragmentIndex)
            }
            if (sequence > highSeq) {
                highSeq = sequence
                evict()
            } else if (highSeq - sequence >= window) {
                return false // 窗口外旧 counter：丢弃
            }
            return mark(sequence, fragmentIndex)
        }
    }

    /** 窗口高水位 sequence（测试/诊断用） */
    fun highWaterMark(): Long = synchronized(this) { highSeq }

    private fun mark(sequence: Long, fragmentIndex: Int): Boolean {
        val bs = marks.getOrPut(sequence) { java.util.BitSet() }
        if (bs.get(fragmentIndex)) return false // 已见（重放片）：丢弃
        bs.set(fragmentIndex)
        return true
    }

    private fun evict() {
        val cutoff = highSeq - window + 1
        marks.entries.removeIf { it.key < cutoff }
    }
}
