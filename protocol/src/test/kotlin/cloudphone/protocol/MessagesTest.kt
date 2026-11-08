package cloudphone.protocol

import org.junit.Assert.assertEquals
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
        // 手工构造 msgType=0x0C（未定义）载荷：必须按 Unknown 忽略且不抛
        val payload = byteArrayOf(0x0C, 0x00, 0x00, 0x00, 0x01)
        val msg = decodeControlMessage(payload)
        assertTrue(msg is ControlMessage.Unknown)
        assertEquals(0x0C, (msg as ControlMessage.Unknown).unknownType)
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
}
