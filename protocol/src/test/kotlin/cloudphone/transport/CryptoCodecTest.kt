package cloudphone.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CryptoCodec 单测（p2p-crossnet-traversal 1.2，stream-encryption Requirement）：
 * 加解密往返（空片/最大片/counter 边界）、AAD 篡改拒绝、方向子钥隔离、
 * 512 窗口重放位图（重复/窗口外旧 counter 丢弃、正常序列不受影响）、
 * 开销断言 ≤明文+18B/片、双端会话往返（client/source 协商派生）。
 *
 * 会话编解码器方向语义：client 只解 s2c 方向、source 只解 c2s 方向——
 * 所有「往返」用例均经 client/source 配对（与本类 direction keys isolated 一致），
 * 自发自收走低层 aeadEncrypt/aeadDecrypt（同钥）覆盖。
 */
class CryptoCodecTest {

    private val token = "cloudphone-m0"
    private val clientNonce = byteArrayOf(
        0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
        0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F,
        0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17,
        0x18, 0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F,
    )
    private val sourceNonce = ByteArray(32) { (0x80 + it).toByte() }

    private fun clientCodec(): CryptoCodec = CryptoCodec.negotiate(Role.CLIENT, token, clientNonce, sourceNonce)
    private fun sourceCodec(): CryptoCodec = CryptoCodec.negotiate(Role.SOURCE, token, clientNonce, sourceNonce)

    // ── 加解密往返 ──

    @Test
    fun `video roundtrip empty chunk`() {
        val client = clientCodec()
        val source = sourceCodec()
        val sealed = client.encryptVideo(sequence = 7, keyframe = false, fragmentIndex = 0, plaintext = ByteArray(0))
        assertEquals(CryptoCodec.TAG_BYTES, sealed.size) // 空片密文 = 仅 tag
        val plain = source.decryptVideo(sequence = 7, keyframe = false, fragmentIndex = 0, sealed = sealed)
        assertNotNull(plain)
        assertEquals(0, plain!!.size)
        // 低层同钥空明文往返
        val key = ByteArray(16) { 7 }
        val nonce = CryptoCodec.buildNonce(CryptoCodec.DIR_S2C, 42)
        val self = CryptoCodec.aeadEncrypt(key, nonce, CryptoCodec.videoAad(7, false), ByteArray(0))
        assertEquals(CryptoCodec.TAG_BYTES, self.size)
        assertEquals(0, CryptoCodec.aeadDecrypt(key, nonce, CryptoCodec.videoAad(7, false), self).size)
    }

    @Test
    fun `video roundtrip max chunk`() {
        val client = clientCodec()
        val source = sourceCodec()
        val data = ByteArray(1200) { (it * 31).toByte() } // 典型 UDP 分片上限量级
        val sealed = client.encryptVideo(1024 * 1024, true, 3, data)
        assertEquals(data.size + CryptoCodec.TAG_BYTES, sealed.size)
        assertArrayEquals(data, source.decryptVideo(1024 * 1024, true, 3, sealed))
    }

    @Test
    fun `video roundtrip counter boundary`() {
        val client = clientCodec()
        val source = sourceCodec()
        val data = "boundary".toByteArray()
        // 48bit 视频 counter 空间边界：sequence 接近 u32 上限（逐对加密解密，单调推进窗口）
        for (seq in longArrayOf(1L, 0xFFFFL, 0x10000L, 0x7FFFFFFFL, 0xFFFFFFFFL)) {
            val sealed = client.encryptVideo(seq, false, 0xFFFF, data)
            assertArrayEquals("seq=$seq", data, source.decryptVideo(seq, false, 0xFFFF, sealed))
        }
        // 低层显式 counter 边界往返（2^62 控制基址、u64 近顶）
        val key = ByteArray(16) { 3 }
        val aad = CryptoCodec.controlAad(9)
        for (counter in longArrayOf(1L, 0x4000000000000000L, Long.MAX_VALUE - 1)) {
            val nonce = CryptoCodec.buildNonce(CryptoCodec.DIR_C2S, counter)
            val sealedLow = CryptoCodec.aeadEncrypt(key, nonce, aad, data)
            assertArrayEquals("counter=$counter", data, CryptoCodec.aeadDecrypt(key, nonce, aad, sealedLow))
        }
        // nonce 布局抽查：u32 方向 + u64 counter
        val nonce = CryptoCodec.buildNonce(CryptoCodec.DIR_C2S, 0x0102030405060708L)
        assertEquals(12, nonce.size)
        assertEquals(1, nonce[3].toInt())
        assertEquals(0x08, nonce[11].toInt() and 0xFF)
        assertEquals(0x0102030405060708L, java.nio.ByteBuffer.wrap(nonce, 4, 8).long)
    }

    @Test
    fun `video roundtrip same frame different fragments`() {
        // 同帧多片：AAD 相同（sequence/flags），nonce counter 随 fragIdx 推移——各片独立可解
        val client = clientCodec()
        val source = sourceCodec()
        val data = ByteArray(100) { it.toByte() }
        for (frag in 0 until 4) {
            val sealed = client.encryptVideo(9, true, frag, data)
            assertArrayEquals("frag=$frag", data, source.decryptVideo(9, true, frag, sealed))
        }
    }

    @Test
    fun `video roundtrip out-of-order frames survive replay guard`() {
        // UDP 常态乱序：后发帧先到，先发帧迟到一个 sequence 仍在 512 窗口内放行
        val client = clientCodec()
        val source = sourceCodec()
        fun seal(seq: Long, frag: Int) = client.encryptVideo(seq, false, frag, ByteArray(16) { seq.toByte() })
        assertNotNull(source.decryptVideo(2001, false, 0, seal(2001, 0)))
        assertNotNull("乱序帧（-1 seq）必须放行", source.decryptVideo(2000, false, 0, seal(2000, 0)))
        assertNotNull("乱序帧（-5 seq）必须放行", source.decryptVideo(1996, false, 0, seal(1996, 0)))
    }

    @Test
    fun `control roundtrip ordered records`() {
        val client = clientCodec()
        val source = sourceCodec()
        val msg1 = "hello-ctl-1".toByteArray()
        val msg2 = ByteArray(4096) { (it % 251).toByte() }
        val sealed1 = client.encryptControl(msg1)
        val sealed2 = client.encryptControl(msg2)
        // 可靠有序流：按发送顺序重建 counter
        assertArrayEquals(msg1, source.decryptControl(sealed1))
        assertArrayEquals(msg2, source.decryptControl(sealed2))
        // 反向：source → client
        val back = source.encryptControl("ack".toByteArray())
        assertArrayEquals("ack".toByteArray(), client.decryptControl(back))
    }

    // ── AAD 篡改拒绝 ──

    @Test
    fun `aad tamper rejected`() {
        val client = clientCodec()
        val source = sourceCodec()
        val data = ByteArray(64) { 0x55 }
        val sealed = client.encryptVideo(sequence = 5, keyframe = false, fragmentIndex = 1, plaintext = data)
        // sequence 改动（重排/重贴标签）→ 认证拒绝
        assertNull(source.decryptVideo(sequence = 6, keyframe = false, fragmentIndex = 1, sealed = sealed))
        // keyframe 标志改动 → 认证拒绝
        assertNull(source.decryptVideo(sequence = 5, keyframe = true, fragmentIndex = 1, sealed = sealed))
        // ciphertext 篡改 → 认证拒绝
        val tampered = sealed.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        assertNull(source.decryptVideo(5, false, 1, tampered))
        // 原文仍可解（认证失败不占重放窗口）
        assertArrayEquals(data, source.decryptVideo(5, false, 1, sealed))
    }

    // ── 方向子钥隔离 ──

    @Test
    fun `direction keys isolated`() {
        val (c2s, s2c) = CryptoCodec.deriveKeys(token, clientNonce, sourceNonce)
        // 方向子钥独立且非平凡
        assertFalse(c2s.contentEquals(s2c))
        assertFalse(c2s.all { it == 0.toByte() })
        assertEquals(16, c2s.size)

        // c2s 密钥加密的密文用 s2c 密钥解不开（反之亦然）
        val data = "direction".toByteArray()
        val c2sSealed = CryptoCodec.aeadEncrypt(c2s, CryptoCodec.buildNonce(CryptoCodec.DIR_C2S, 1), CryptoCodec.videoAad(1, false), data)
        val s2cSealed = CryptoCodec.aeadEncrypt(s2c, CryptoCodec.buildNonce(CryptoCodec.DIR_S2C, 1), CryptoCodec.videoAad(1, false), data)
        try {
            CryptoCodec.aeadDecrypt(s2c, CryptoCodec.buildNonce(CryptoCodec.DIR_C2S, 1), CryptoCodec.videoAad(1, false), c2sSealed)
            throw AssertionError("c2s ciphertext must not decrypt under s2c key")
        } catch (_: Exception) {
            // 期望：GCM 认证拒绝
        }
        try {
            CryptoCodec.aeadDecrypt(c2s, CryptoCodec.buildNonce(CryptoCodec.DIR_S2C, 1), CryptoCodec.videoAad(1, false), s2cSealed)
            throw AssertionError("s2c ciphertext must not decrypt under c2s key")
        } catch (_: Exception) {
        }

        // 会话级：client 发（c2s 方向）→ source 解；source 发（s2c 方向）→ client 解；
        // 各自拿对方方向的密文无解
        val client = clientCodec()
        val source = sourceCodec()
        val up = client.encryptVideo(3, false, 0, data)
        val down = source.encryptVideo(3, false, 0, data)
        assertArrayEquals(data, source.decryptVideo(3, false, 0, up))
        assertArrayEquals(data, client.decryptVideo(3, false, 0, down))
        assertNull("client must not decrypt c2s-direction traffic", client.decryptVideo(3, false, 0, up))
        assertNull("source must not decrypt s2c-direction traffic", source.decryptVideo(3, false, 0, down))
    }

    @Test
    fun `different salt changes keys`() {
        val otherNonce = ByteArray(32) { (it + 1).toByte() }
        val (a1, _) = CryptoCodec.deriveKeys(token, clientNonce, sourceNonce)
        val (a2, _) = CryptoCodec.deriveKeys(token, clientNonce, otherNonce)
        assertFalse("nonce 变化必须改变派生钥", a1.contentEquals(a2))
        val (t1, _) = CryptoCodec.deriveKeys("other-token", clientNonce, sourceNonce)
        assertFalse("token 变化必须改变派生钥", a1.contentEquals(t1))
    }

    // ── 512 窗口重放位图（线性 counter 组件，控制通道同款）──

    @Test
    fun `replay window drops duplicate and out-of-window`() {
        val w = ReplayWindow(512)
        assertTrue(w.checkAndMark(100))
        assertFalse("重复 counter 丢弃", w.checkAndMark(100))
        assertTrue(w.checkAndMark(101))
        assertTrue(w.checkAndMark(600)) // 前移窗口（覆盖 [89..600]）
        assertFalse("窗口外旧 counter 丢弃", w.checkAndMark(1))
        assertTrue("窗口内旧但未见 counter 放行（乱序语义）", w.checkAndMark(300))
        assertFalse("乱序放行后再到 = 重复", w.checkAndMark(300))
    }

    @Test
    fun `replay window slides forward`() {
        val w = ReplayWindow(512)
        for (i in 1..1000L) assertTrue("advance=$i", w.checkAndMark(i))
        // 全部滑出窗口：≤1000-512 的 counter 全拒
        assertFalse(w.checkAndMark(488))
        // 滑动后正常序列不受影响
        for (i in 1001..1100L) assertTrue(w.checkAndMark(i))
        assertEquals(1100, w.highWaterMark())
    }

    @Test
    fun `replay window non-contiguous jumps`() {
        val w = ReplayWindow(512)
        assertTrue(w.checkAndMark(10))
        assertTrue(w.checkAndMark(600)) // 大跳直接清窗
        assertFalse("旧高度 10 已在窗口外", w.checkAndMark(10))
        assertTrue(w.checkAndMark(601))
        assertEquals(601, w.highWaterMark())
    }

    @Test
    fun `frag replay guard dedups fragments and slides`() {
        val g = FragReplayGuard(512)
        assertTrue(g.checkAndMark(500, 0))
        assertFalse("同帧同片重复丢弃", g.checkAndMark(500, 0))
        assertTrue("同帧不同片放行", g.checkAndMark(500, 1))
        assertTrue(g.checkAndMark(501, 0))
        assertTrue(g.checkAndMark(1012, 0)) // 前移（窗口 [501..1012]）
        assertFalse("窗口外旧 sequence 丢弃", g.checkAndMark(500, 2))
        assertFalse("窗口外旧 sequence 丢弃（重复片）", g.checkAndMark(1, 0))
        assertTrue("窗口后沿仍在窗内放行", g.checkAndMark(600, 0))
        assertEquals(1012, g.highWaterMark())
    }

    @Test
    fun `replayed video chunks dropped and normal sequence unaffected`() {
        // 会话级重放场景：注入重复 counter 与窗口外旧 counter 加密片
        val client = clientCodec()
        val source = sourceCodec()
        fun seal(seq: Long, frag: Int, tag: Byte) = client.encryptVideo(seq, false, frag, ByteArray(32) { tag })

        val fresh = seal(500, 0, 0x11)
        val fresh2 = seal(501, 0, 0x22)

        // 接收侧按真实到达序（乱序模拟）：501、500（乱序）、重放 501（丢）、窗口外旧（丢）、正常
        assertNotNull(source.decryptVideo(501, false, 0, fresh2))
        assertNotNull(source.decryptVideo(500, false, 0, fresh))
        assertNull("重放片丢弃", source.decryptVideo(501, false, 0, fresh2))
        // 推高水位至窗口外：1500 落地后 500/501 全部出窗
        assertNotNull(source.decryptVideo(1500, false, 0, seal(1500, 0, 0x44)))
        assertNull("窗口外旧 counter 片丢弃", source.decryptVideo(500, false, 0, fresh))
        assertNull("窗口外旧 counter 片丢弃", source.decryptVideo(1, false, 0, seal(1, 0, 0x33)))
        // 正常序列不受影响
        assertNotNull(source.decryptVideo(1501, false, 0, seal(1501, 0, 0x55)))
    }

    // ── 开销上限：≤ 明文 +18B/片（16B tag + 2B 长度容差）──

    @Test
    fun `overhead at most plaintext plus 18 bytes per chunk`() {
        val c = clientCodec()
        // 1080p 典型片长分布（~1KB 级）+ 空片 + 大片
        val sizes = intArrayOf(0, 64, 256, 512, 1024, 1179, 4096, 65536)
        var totalPlain = 0
        var totalSealed = 0
        var seq = 1L
        for (s in sizes) {
            val data = ByteArray(s) { (it % 7).toByte() }
            val sealed = c.encryptVideo(seq, s % 2 == 0, 0, data)
            assertEquals("size=$s", s + CryptoCodec.TAG_BYTES, sealed.size)
            totalPlain += s
            totalSealed += sealed.size
            seq++
        }
        val perChunkOverhead = (totalSealed - totalPlain) / sizes.size.toDouble()
        assertTrue("每片开销 $perChunkOverhead 必须 ≤18B", perChunkOverhead <= 18.0)
        // 实测 ≈1.6%（1KB 片 +16B tag）
        val pct = 100.0 * (totalSealed - totalPlain) / totalPlain
        assertTrue("总开销百分比 $pct%", pct < 5.0)
    }

    // ── HKDF 参考值（RFC 5869 A.1 SHA-256 test case 1 校验实现正确性）──

    @Test
    fun `hkdf sha256 matches rfc 5869 test vector`() {
        val ikm = ByteArray(22) { 0x0b.toByte() }
        val salt = ByteArray(13) { it.toByte() } // 0x000102...0c
        val info = byteArrayOf(
            0xf0.toByte(), 0xf1.toByte(), 0xf2.toByte(), 0xf3.toByte(),
            0xf4.toByte(), 0xf5.toByte(), 0xf6.toByte(), 0xf7.toByte(),
            0xf8.toByte(), 0xf9.toByte(),
        )
        val okm = Hkdf.derive(ikm, salt, info, 42)
        val expected = hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865")
        assertArrayEquals(expected, okm)
    }

    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "")
        return ByteArray(clean.length / 2) {
            ((Character.digit(clean[it * 2], 16) shl 4) + Character.digit(clean[it * 2 + 1], 16)).toByte()
        }
    }
}
