package cloudphone.desktop.aux

import cloudphone.protocol.ControlMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** 辅助通道 UI 能力状态（AuxNotice 驱动置灰；null=未探测） */
data class AuxUiState(
    val clipboardAvailable: Boolean? = null,
    val textInputAvailable: Boolean? = null,
    val audioAvailable: Boolean? = null,
    val clipboardUnavailableReason: String = "",
    val textInputUnavailableReason: String = "",
    val audioUnavailableReason: String = "",
)

/** 文本注入结果（ACK 对账三态） */
sealed class TextInputResult {
    /** 注入成功 */
    object Ok : TextInputResult()

    /** 源端明确失败（携原因） */
    data class Failed(val reason: String) : TextInputResult()

    /** 5s 内未收到 ACK（状态未知，UI 呈现超时态） */
    object Timeout : TextInputResult()
}

/** 控制消息发送抽象（生产：SessionController.sendControl；测试：mock） */
fun interface AuxTransport {
    fun send(msg: ControlMessage)
}

/**
 * 辅助通道桥（design D5）：
 * - CLIPBOARD_SET / CLIPBOARD_GET / TEXT（10s 超时降级，超时按通道不可用处理并给出原因）；
 * - TEXT_INPUT{seq} ACK 对账（5s 超时，状态未知不误报成败）；
 * - AuxNotice → [AuxUiState]（UI 置灰并显示原因，非静默失败）。
 */
class AuxBridge(
    private val transport: AuxTransport,
    private val clipboardTimeoutMs: Long = DEFAULT_CLIPBOARD_TIMEOUT_MS,
    private val textAckTimeoutMs: Long = DEFAULT_TEXT_ACK_TIMEOUT_MS,
) {
    companion object {
        const val DEFAULT_CLIPBOARD_TIMEOUT_MS = 10_000L
        const val DEFAULT_TEXT_ACK_TIMEOUT_MS = 5_000L
    }

    private val _uiState = MutableStateFlow(AuxUiState())
    val uiState: StateFlow<AuxUiState> = _uiState

    private val mutex = Mutex()
    private var clipboardPending: CompletableDeferred<ClipboardReply>? = null
    private val ackPending = HashMap<Int, CompletableDeferred<ControlMessage.TextInputAck>>()
    private var textSeq = 0

    /** 剪贴板挂起请求的回执类型（区分文本回执 / AuxNotice 不可用 / 真超时） */
    private sealed class ClipboardReply {
        data class Text(val s: String) : ClipboardReply()
        object Unavailable : ClipboardReply()
    }

    /** 源端 → 客户端 消息分发入口（SessionController dispatch 循环调用） */
    fun onControlMessage(msg: ControlMessage) {
        when (msg) {
            is ControlMessage.ClipboardText -> {
                clipboardPending?.complete(ClipboardReply.Text(msg.text))
                // 成功回执即通道可用
                updateState { it.copy(clipboardAvailable = true) }
            }
            is ControlMessage.AuxNotice -> {
                when (msg.channel) {
                    cloudphone.protocol.AuxChannel.CLIPBOARD -> {
                        clipboardPending?.complete(ClipboardReply.Unavailable)
                        updateState { it.copy(clipboardAvailable = false, clipboardUnavailableReason = msg.reason) }
                    }
                    cloudphone.protocol.AuxChannel.TEXT_INPUT -> {
                        updateState { it.copy(textInputAvailable = false, textInputUnavailableReason = msg.reason) }
                    }
                    cloudphone.protocol.AuxChannel.AUDIO -> {
                        updateState { it.copy(audioAvailable = false, audioUnavailableReason = msg.reason) }
                    }
                }
            }
            is ControlMessage.TextInputAck -> {
                ackPending.remove(msg.seq)?.complete(msg)
            }
            else -> Unit // 其余消息不经辅助通道
        }
    }

    /** 剪贴板写入：true=源端确认；false=失败/超时/通道不可用（已降级并给出原因） */
    suspend fun setClipboard(text: String): Boolean {
        return mutex.withLock {
            val deferred = CompletableDeferred<ClipboardReply>()
            clipboardPending = deferred
            transport.send(ControlMessage.ClipboardSet(text))
            val reply = awaitClipboardReply(deferred)
            when (reply) {
                is ClipboardReply.Text -> true
                ClipboardReply.Unavailable -> false // AuxNotice 已给出原因，不覆盖
                null -> {
                    degradeClipboard("剪贴板写入超时（${clipboardTimeoutMs}ms 无回执）")
                    false
                }
            }
        }
    }

    /** 剪贴板读取：文本或 null（失败/超时/通道不可用） */
    suspend fun getClipboard(): String? {
        return mutex.withLock {
            val deferred = CompletableDeferred<ClipboardReply>()
            clipboardPending = deferred
            transport.send(ControlMessage.ClipboardGet())
            when (val reply = awaitClipboardReply(deferred)) {
                is ClipboardReply.Text -> reply.s
                ClipboardReply.Unavailable -> null // AuxNotice 已给出原因
                null -> {
                    degradeClipboard("剪贴板读取超时（${clipboardTimeoutMs}ms 无回执）")
                    null
                }
            }
        }
    }

    /** await + 超时归一（null=超时） */
    private suspend fun awaitClipboardReply(deferred: CompletableDeferred<ClipboardReply>): ClipboardReply? =
        try {
            withTimeout(clipboardTimeoutMs) { deferred.await() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            null
        } finally {
            clipboardPending = null
        }

    /** 组合文本注入：seq 递增对账，5s 无 ACK 呈超时态 */
    suspend fun sendTextInput(text: String): TextInputResult {
        val seq = synchronized(this) { ++textSeq }
        val deferred = CompletableDeferred<ControlMessage.TextInputAck>()
        synchronized(this) { ackPending[seq] = deferred }
        transport.send(ControlMessage.TextInput(seq, text))
        val ack = try {
            withTimeout(textAckTimeoutMs) { deferred.await() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            null
        } finally {
            synchronized(this) { ackPending.remove(seq) }
        }
        return when {
            ack == null -> TextInputResult.Timeout
            ack.ok -> {
                updateState { it.copy(textInputAvailable = true) }
                TextInputResult.Ok
            }
            else -> {
                updateState { it.copy(textInputAvailable = false, textInputUnavailableReason = ack.reason) }
                TextInputResult.Failed(ack.reason)
            }
        }
    }

    /** 会话结束清理（挂起请求以超时语义收尾） */
    fun reset() {
        synchronized(this) {
            clipboardPending?.complete(ClipboardReply.Unavailable)
            clipboardPending = null
            ackPending.values.forEach { it.complete(ControlMessage.TextInputAck(it.hashCode(), false, "会话已结束")) }
            ackPending.clear()
            textSeq = 0
        }
        _uiState.value = AuxUiState()
    }

    private fun degradeClipboard(reason: String) {
        updateState { it.copy(clipboardAvailable = false, clipboardUnavailableReason = reason) }
    }

    private inline fun updateState(transform: (AuxUiState) -> AuxUiState) {
        _uiState.value = transform(_uiState.value)
    }
}
