package cloudphone.protocol

import cloudphone.transport.CryptoAlgo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APP_LIST 消息编解码单测（client-ux-and-input-latency 3.1）：
 * 覆盖请求/应答编解码往返、空列表、多条目、CJK 标签、非法尾部截断容错。
 */
class MessagesTest {

    private fun roundtrip(msg: ControlMessage): ControlMessage =
        decodeControlMessage(encodeControlMessage(msg))

    @Test
    fun `app list request roundtrip`() {
        val msg = ControlMessage.AppListRequest(requestId = 42)
        val out = roundtrip(msg) as ControlMessage.AppListRequest
        assertEquals(42, out.requestId)
    }

    @Test
    fun `app list request zero id roundtrip`() {
        val out = roundtrip(ControlMessage.AppListRequest(0)) as ControlMessage.AppListRequest
        assertEquals(0, out.requestId)
    }

    @Test
    fun `app list empty roundtrip`() {
        val msg = ControlMessage.AppListMessage(requestId = 7, apps = emptyList())
        val out = roundtrip(msg) as ControlMessage.AppListMessage
        assertEquals(7, out.requestId)
        assertTrue(out.apps.isEmpty())
    }

    @Test
    fun `app list multi entries roundtrip`() {
        val apps = listOf(
            AppEntry("cloudphone.loadgen", "Load Generator"),
            AppEntry("com.android.settings", "设置"),
            AppEntry("org.chromium.webview_shell", "WebView Shell"),
        )
        val out = roundtrip(ControlMessage.AppListMessage(requestId = 1, apps = apps)) as ControlMessage.AppListMessage
        assertEquals(1, out.requestId)
        assertEquals(apps, out.apps)
    }

    @Test
    fun `app list cjk and emoji labels survive roundtrip`() {
        val apps = listOf(
            AppEntry("com.example.中文", "中文标签 🎮"),
            AppEntry("com.example.b", "ＡＢＣ全角"),
        )
        val out = roundtrip(ControlMessage.AppListMessage(2, apps)) as ControlMessage.AppListMessage
        assertEquals(apps, out.apps)
    }

    @Test
    fun `unknown type with app list bytes stays unknown`() {
        // 手工构造 msgType=0x7F（未定义）载荷：必须按 Unknown 忽略且不抛
        // （0x0C 已被 p2p-crossnet-traversal 2.3 分配为 P2P_MIGRATE，不再属于未知类型）
        val payload = byteArrayOf(0x7F.toByte(), 0x00, 0x00, 0x00, 0x01)
        val msg = decodeControlMessage(payload)
        assertTrue(msg is ControlMessage.Unknown)
        assertEquals(0x7F, (msg as ControlMessage.Unknown).unknownType)
    }

    @Test
    fun `p2p migrate message roundtrip`() {
        // 端点迁移消息（MsgType 0x0C）：候选文本（多候选 + symmetric 标记）双向可编码
        val text = "2001:db8::1:40000,1.2.3.4:40001,192.168.1.5:41000;nat=sym"
        val out = roundtrip(ControlMessage.P2pMigrate(text)) as ControlMessage.P2pMigrate
        assertEquals(text, out.candidates)
    }

    @Test
    fun `framed app list travels through framing channel`() {
        // 通用记录通道复用：控制记录 [0x01][len][payload] 承载 APP_LIST 往返
        val msg = ControlMessage.AppListMessage(9, listOf(AppEntry("a.b", "Ab")))
        val out = java.io.ByteArrayOutputStream()
        Framing.writeRecord(out, Framing.RECORD_CONTROL, encodeControlMessage(msg))
        val reader = Framing.StreamRecordReader(java.io.ByteArrayInputStream(out.toByteArray()))
        val record = reader.read()!!
        assertEquals(Framing.RECORD_CONTROL, record.type)
        val decoded = decodeControlMessage(record.payload) as ControlMessage.AppListMessage
        assertEquals(9, decoded.requestId)
        assertEquals("a.b", decoded.apps[0].packageName)
    }

    // ── 加密协商扩展（p2p-crossnet-traversal 1.3）──

    private fun legacyHelloBytes(token: String, offer: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(0x01) // msgType HELLO
        out.write(byteArrayOf(0, 1)) // version=1
        writeU16String(out, token)
        out.write(2) // SINGLE_APP
        writeU16String(out, "cloudphone.loadgen")
        out.write(byteArrayOf(0, 4)) // width
        out.write(byteArrayOf(0, 3)) // height
        out.write(60) // fps
        out.write(byteArrayOf(0, 0, 31, 64)) // bitrateKbps=8000
        out.write(byteArrayOf(0, 0, 0, 7)) // sessionId
        writeU16String(out, offer)
        return out.toByteArray()
    }

    private fun writeU16String(out: java.io.ByteArrayOutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        out.write(byteArrayOf((b.size ushr 8).toByte(), b.size.toByte()))
        out.write(b)
    }

    @Test
    fun `old peer hello without crypto block decodes none`() {
        // 老端字节流（无尾部 crypto 块）→ 新端解码：encryptOn=false、algo=none、nonce 空
        val payload = legacyHelloBytes("cloudphone-m0", "1.2.3.4:5000")
        val decoded = decodeControlMessage(payload) as ControlMessage.Hello
        assertEquals("cloudphone-m0", decoded.token)
        assertFalse(decoded.encryptOn)
        assertEquals(CryptoAlgo.NONE, decoded.cryptoAlgo)
        assertTrue(decoded.cryptoNonce.isEmpty())
    }

    @Test
    fun `hello encryptOn off wire identical to v1 baseline`() {
        // encryptOn=false：线字节与加密功能合入前一致（无额外字段）——开关关闭回归保障
        val legacy = legacyHelloBytes("tok", "")
        val newPeer = encodeControlMessage(
            ControlMessage.Hello(
                version = 1, token = "tok", mode = WatchMode.SINGLE_APP,
                packageName = "cloudphone.loadgen", width = 4, height = 3, fps = 60,
                bitrateKbps = 8000, sessionId = 7, p2pOffer = "",
            ),
        )
        assertTrue(legacy.contentEquals(newPeer))
    }

    @Test
    fun `hello crypto negotiation roundtrip`() {
        val nonce = ByteArray(32) { (it + 1).toByte() }
        val msg = ControlMessage.Hello(
            version = 1, token = "t", mode = WatchMode.FULLSCREEN, packageName = "p",
            width = 0, height = 0, fps = 0, bitrateKbps = 0, sessionId = 0, p2pOffer = "",
            encryptOn = true, cryptoAlgo = CryptoAlgo.AES128_GCM, cryptoNonce = nonce,
        )
        val out = roundtrip(msg) as ControlMessage.Hello
        assertTrue(out.encryptOn)
        assertEquals(CryptoAlgo.AES128_GCM, out.cryptoAlgo)
        assertTrue(nonce.contentEquals(out.cryptoNonce))
    }

    @Test
    fun `hello ack crypto negotiation roundtrip and hung sessions`() {
        val nonce = ByteArray(32) { (0xFF - it).toByte() }
        val msg = ControlMessage.HelloAck(
            status = HelloStatus.ACCEPTED, reason = "", sessionId = 5,
            width = 1280, height = 720, fps = 60, bitrateKbps = 8000,
            encoder = "MediaCodec AVC", displayId = 2, p2pAnswer = "9.9.9.9:40000",
            hungSessions = listOf(HungSession(5, WatchMode.SINGLE_APP, "cloudphone.loadgen", 1280, 720)),
            encryptOn = true, cryptoAlgo = CryptoAlgo.AES128_GCM, cryptoNonce = nonce,
        )
        val out = roundtrip(msg) as ControlMessage.HelloAck
        assertEquals(1, out.hungSessions.size)
        assertTrue(out.encryptOn)
        assertEquals(CryptoAlgo.AES128_GCM, out.cryptoAlgo)
        assertTrue(nonce.contentEquals(out.cryptoNonce))
    }

    @Test
    fun `hello ack legacy bytes decode crypto none`() {
        // 老端 HELLO_ACK（无尾部块）：手工构造 accepted 最小载荷
        val out = java.io.ByteArrayOutputStream()
        out.write(0x02) // msgType HELLO_ACK
        out.write(0) // ACCEPTED
        writeU16String(out, "")
        out.write(byteArrayOf(0, 0, 0, 3)) // sessionId
        out.write(byteArrayOf(0, 80)) // width
        out.write(byteArrayOf(1, 96)) // height
        out.write(30) // fps
        out.write(byteArrayOf(0, 0, 15, 64)) // bitrate
        writeU16String(out, "enc")
        out.write(byteArrayOf(0, 0, 0, 1)) // displayId
        writeU16String(out, "") // p2pAnswer
        out.write(byteArrayOf(0, 0)) // hung 0
        val decoded = decodeControlMessage(out.toByteArray()) as ControlMessage.HelloAck
        assertEquals(HelloStatus.ACCEPTED, decoded.status)
        assertEquals(3, decoded.sessionId)
        assertFalse(decoded.encryptOn)
        assertEquals(CryptoAlgo.NONE, decoded.cryptoAlgo)
    }
}
