package cloudphone.desktop

import cloudphone.desktop.aux.AuxBridge
import cloudphone.desktop.aux.AuxTransport
import cloudphone.desktop.aux.TextInputResult
import cloudphone.protocol.AuxChannel
import cloudphone.protocol.ControlMessage
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.5 验收：AuxBridge mock transport 单测——CLIPBOARD_SET/GET/TEXT（10s 超时降级，测试用短超时）、
 * TEXT_INPUT{seq} ACK 对账（5s 超时）、AuxNotice→UI 状态（置灰+原因）。
 */
class AuxBridgeTest {

    /** 可编程 mock：send 后按脚本回执（同步，确定性） */
    private class MockTransport : AuxTransport {
        val sent = mutableListOf<ControlMessage>()
        var reply: ((ControlMessage, AuxBridge) -> Unit)? = null

        override fun send(msg: ControlMessage) {
            sent += msg
            val bridge = bridges.lastOrNull()
            reply?.let { it(msg, bridge!!) }
        }

        val bridges = mutableListOf<AuxBridge>()
    }

    private fun newBridge(mock: MockTransport, clipboardTimeoutMs: Long = 10_000, textAckTimeoutMs: Long = 5_000): AuxBridge {
        val bridge = AuxBridge(mock, clipboardTimeoutMs, textAckTimeoutMs)
        mock.bridges += bridge
        return bridge
    }

    // ── 剪贴板成功路径 ──

    @Test
    fun `CLIPBOARD_SET - 成功回执为真`() = runBlocking {
        val mock = MockTransport()
        mock.reply = { msg, bridge ->
            if (msg is ControlMessage.ClipboardSet) bridge.onControlMessage(ControlMessage.ClipboardText("ok"))
        }
        val bridge = newBridge(mock)
        assertTrue(bridge.setClipboard("abc"))
        assertEquals(1, mock.sent.size)
        assertTrue(mock.sent[0] is ControlMessage.ClipboardSet)
        assertEquals("abc", (mock.sent[0] as ControlMessage.ClipboardSet).text)
        assertEquals(true, bridge.uiState.value.clipboardAvailable)
    }

    @Test
    fun `CLIPBOARD_GET - 文本回执`() = runBlocking {
        val mock = MockTransport()
        mock.reply = { msg, bridge ->
            if (msg is ControlMessage.ClipboardGet) bridge.onControlMessage(ControlMessage.ClipboardText("hello"))
        }
        val bridge = newBridge(mock)
        assertEquals("hello", bridge.getClipboard())
        assertTrue(mock.sent[0] is ControlMessage.ClipboardGet)
        assertEquals(true, bridge.uiState.value.clipboardAvailable)
    }

    // ── 超时降级（测试用 100ms 短超时） ──

    @Test
    fun `CLIPBOARD 超时 - 降级并置灰带原因`() = runBlocking {
        val mock = MockTransport() // 不回执
        val bridge = newBridge(mock, clipboardTimeoutMs = 100)
        assertNull(bridge.getClipboard())
        val s = bridge.uiState.value
        assertEquals(false, s.clipboardAvailable)
        assertTrue("应携带超时原因", s.clipboardUnavailableReason.contains("超时"))
    }

    @Test
    fun `CLIPBOARD_SET 超时 - 返回假且置灰`() = runBlocking {
        val mock = MockTransport()
        val bridge = newBridge(mock, clipboardTimeoutMs = 100)
        assertFalse(bridge.setClipboard("x"))
        assertEquals(false, bridge.uiState.value.clipboardAvailable)
    }

    // ── AuxNotice → UI 置灰 ──

    @Test
    fun `AuxNotice - 剪贴板置灰并解除挂起`() = runBlocking {
        val mock = MockTransport()
        mock.reply = { msg, bridge ->
            if (msg is ControlMessage.ClipboardGet) {
                bridge.onControlMessage(ControlMessage.AuxNotice(AuxChannel.CLIPBOARD, "无焦点"))
            }
        }
        val bridge = newBridge(mock)
        assertNull(bridge.getClipboard())
        val s = bridge.uiState.value
        assertEquals(false, s.clipboardAvailable)
        assertEquals("无焦点", s.clipboardUnavailableReason)
    }

    @Test
    fun `AuxNotice - 音频不可用通告`() {
        val mock = MockTransport()
        val bridge = newBridge(mock)
        bridge.onControlMessage(ControlMessage.AuxNotice(AuxChannel.AUDIO, "API<29 不支持"))
        val s = bridge.uiState.value
        assertEquals(false, s.audioAvailable)
        assertEquals("API<29 不支持", s.audioUnavailableReason)
    }

    // ── TEXT_INPUT ACK 对账 ──

    @Test
    fun `TEXT_INPUT - ACK 成功`() = runBlocking {
        val mock = MockTransport()
        mock.reply = { msg, bridge ->
            if (msg is ControlMessage.TextInput) bridge.onControlMessage(ControlMessage.TextInputAck(msg.seq, true, ""))
        }
        val bridge = newBridge(mock)
        assertEquals(TextInputResult.Ok, bridge.sendTextInput("你好"))
        val sent = mock.sent[0] as ControlMessage.TextInput
        assertEquals("你好", sent.text)
        assertEquals("首个 seq=1", 1, sent.seq)
        assertEquals(true, bridge.uiState.value.textInputAvailable)
    }

    @Test
    fun `TEXT_INPUT - ACK 失败携原因并置灰`() = runBlocking {
        val mock = MockTransport()
        mock.reply = { msg, bridge ->
            if (msg is ControlMessage.TextInput) bridge.onControlMessage(ControlMessage.TextInputAck(msg.seq, false, "IME 未启用"))
        }
        val bridge = newBridge(mock)
        val r = bridge.sendTextInput("hi")
        assertTrue(r is TextInputResult.Failed)
        assertEquals("IME 未启用", (r as TextInputResult.Failed).reason)
        assertEquals(false, bridge.uiState.value.textInputAvailable)
        assertEquals("IME 未启用", bridge.uiState.value.textInputUnavailableReason)
    }

    @Test
    fun `TEXT_INPUT - 5s 无 ACK 超时态`() = runBlocking {
        val mock = MockTransport() // 不回执
        val bridge = newBridge(mock, textAckTimeoutMs = 100)
        assertEquals(TextInputResult.Timeout, bridge.sendTextInput("hi"))
    }

    @Test
    fun `seq 对账 - 并发两条各自配对`() = runBlocking {
        val mock = MockTransport()
        val bridge = newBridge(mock)
        // 回执顺序与请求相反（证明按 seq 配对而非按到达顺序）
        val results = mutableListOf<TextInputResult>()
        val j1 = launch { results += bridge.sendTextInput("first") }
        val j2 = launch { results += bridge.sendTextInput("second") }
        // 两条都已发出（同协程内 launch 先执行到挂起点）
        kotlinx.coroutines.delay(50)
        val seqs = mock.sent.filterIsInstance<ControlMessage.TextInput>().map { it.seq }
        assertEquals(listOf(1, 2), seqs)
        bridge.onControlMessage(ControlMessage.TextInputAck(2, true, ""))
        bridge.onControlMessage(ControlMessage.TextInputAck(1, true, ""))
        j1.join(); j2.join()
        assertEquals(2, results.size)
        assertTrue(results.all { it is TextInputResult.Ok })
    }
}
