package cloudphone.client

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import cloudphone.protocol.AuxChannel
import cloudphone.protocol.ControlMessage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 辅助通道客户端（aux-channels-m3 任务 4.2）：
 *
 * - AUDIO_CHUNK 消费/解译：AAC-LC ADTS 解码（MediaCodec，KEY_IS_ADTS 免带外 csd）→
 *   PCM 20ms 帧 → [AudioPlayer] jitter 播放；
 * - TEXT_INPUT 发送与 ACK 对账：seq 自增、阻塞等回执（超时返回明确失败原因）；
 * - CLIPBOARD_SET/GET/TEXT 往返：单飞对账，10s 超时降级（spec：超时降级非静默）；
 * - AuxNotice（源端降级通告）上抛 UI（置灰按钮 + 原因文案）。
 *
 * 生命周期由 DecodeRenderActivity 持有：onCreate 启动音频、onDestroy 释放。
 */
class AuxChannelClient {

    companion object {
        const val CLIPBOARD_TIMEOUT_MS = 10_000L
        const val TEXT_ACK_TIMEOUT_MS = 5_000L

        /** 源端 ADTS 头长度（AudioCaptureService.adtsHeader 固定 7B，protection absent） */
        const val ADTS_HEADER_BYTES = 7
    }

    /** UI 上抛：置灰与原因文案（任务 4.3 消费） */
    data class UiState(
        val clipboardUnavailableReason: String? = null, // 非空=粘贴/复制需置灰提示
        val keyboardUnavailableReason: String? = null,  // 非空=键盘置灰提示
        val audioUnavailableReason: String? = null,     // 非空=音频不可用提示（如 API<29）
    )

    @Volatile var onUiState: ((UiState) -> Unit)? = null

    val player = AudioPlayer()
    private var decoder: MediaCodec? = null
    private var decodeThread: Thread? = null
    private val audioRunning = AtomicBoolean(false)

    // TEXT_INPUT seq → 回执信箱
    private val ackMailboxes = java.util.concurrent.ConcurrentHashMap<Int, SynchronousQueue<ControlMessage.TextInputAck>>()
    private val textSeq = AtomicInteger(0)

    // 剪贴板单飞对账（CLIPBOARD_GET/SET 共用一次在途操作）
    private data class ClipboardResult(val ok: Boolean, val text: String, val reason: String)
    private val clipboardMailbox = LinkedBlockingQueue<ClipboardResult>(1)

    /** 最近源端通告（降级原因文案） */
    @Volatile var lastNotice: String? = null
        private set

    // ── 音频（AUDIO_CHUNK 消费/解译）──

    /** 启动音频解码与播放（默认开启；静音仅关音量不停传输） */
    fun startAudio() {
        if (!audioRunning.compareAndSet(false, true)) return
        player.start()
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AudioPlayer.SAMPLE_RATE, AudioPlayer.CHANNELS)
            // 裸 AAC + 显式 csd-0（AudioSpecificConfig: AOT=2 LC, freqIdx=3=48kHz, chCfg=2）：
            // 不走解码器内建 ADTS 传输解析（部分平台 C2SoftAacDec 对逐帧 ADTS 报 0x1001 无输出），
            // 由 [feedDecoder] 剥离源端 ADTS 7B 头后按裸帧喂入，行为确定、跨平台一致。
            val csd = byteArrayOf(0x11, 0x90.toByte())
            format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(csd))
            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, 0)
            codec.start()
            decoder = codec
            decodeThread = Thread({ decodeLoop(codec) }, "aux-audio-decode").apply { isDaemon = true; start() }
        } catch (e: Exception) {
            android.util.Log.w("cloudphone-client", "audio decoder init failed: ${e.message}")
            audioRunning.set(false)
        }
    }

    /** 解码泵：只抽输出（输入由 handleAux→feedDecoder 按网络到达直喂，速率天然 ≈ 实时） */
    private fun decodeLoop(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var tryAgain = 0L
        while (audioRunning.get()) {
            var outIdx = codec.dequeueOutputBuffer(info, 50_000)
            if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER && ++tryAgain % 40 == 0L) {
                // 每 40 次（≈2s）静默计数：解码器长期无输出的诊断锚点
                android.util.Log.i("cloudphone-client", "audio decode try_again=$tryAgain feed=$feedCount")
            }
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
            while (outIdx >= 0) {
                if (info.size > 0) {
                    val buf = codec.getOutputBuffer(outIdx)
                    if (buf != null) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val pcm = ByteArray(info.size)
                        buf.get(pcm)
                        if (pcmOutCount == 0L) {
                            android.util.Log.i("cloudphone-client", "audio pcm first out=${info.size}")
                        }
                        pcmOutCount++
                        player.enqueue(pcm)
                    }
                }
                codec.releaseOutputBuffer(outIdx, false)
                outIdx = codec.dequeueOutputBuffer(info, 0)
            }
        }
    }

    fun stopAudio() {
        if (!audioRunning.compareAndSet(true, false)) return
        decodeThread?.join(1000)
        decodeThread = null
        runCatching {
            decoder?.stop()
            decoder?.release()
        }
        decoder = null
        player.stop()
    }

    // ── 控制分发入口（ClientSession.onAux）──

    fun handleAux(msg: ControlMessage) {
        when (msg) {
            is ControlMessage.AudioChunk -> feedDecoder(msg.payload)
            is ControlMessage.ClipboardText -> {
                clipboardMailbox.offer(ClipboardResult(ok = true, text = msg.text, reason = ""))
            }
            is ControlMessage.AuxNotice -> onNotice(msg)
            is ControlMessage.TextInputAck -> {
                ackMailboxes[msg.seq]?.offer(msg)
            }
            else -> Unit
        }
    }

    private fun onNotice(msg: ControlMessage.AuxNotice) {
        lastNotice = msg.reason
        android.util.Log.i("cloudphone-client", "aux notice ch=${msg.channel.id}: ${msg.reason}")
        when (msg.channel) {
            AuxChannel.AUDIO -> onUiState?.invoke(UiState(audioUnavailableReason = msg.reason))
            AuxChannel.CLIPBOARD, AuxChannel.TEXT_INPUT -> {
                val imeMissing = msg.reason.contains("未启用")
                val noFocus = msg.reason.contains("无获焦输入框")
                onUiState?.invoke(
                    UiState(
                        clipboardUnavailableReason = if (noFocus && !imeMissing) "复制需源端输入框获焦：${msg.reason}" else msg.reason,
                        keyboardUnavailableReason = if (imeMissing || noFocus) msg.reason else null,
                    ),
                )
            }
        }
    }

    /** AAC ADTS 帧喂解码器（解码线程外调用：直接拿缓冲写输入） */
    private fun feedDecoder(adts: ByteArray) {
        val codec = decoder ?: return
        // 源端帧 = 7B ADTS 头 + AAC 裸载荷；解码器按 csd-0 配置，剥离头后喂裸帧
        if (adts.size <= ADTS_HEADER_BYTES) return
        val idx = codec.dequeueInputBuffer(10_000)
        if (idx < 0) return // 输入背压：丢该帧（jitter 侧 skip 由播放器计数）
        val buf = codec.getInputBuffer(idx) ?: return
        buf.clear()
        buf.put(adts, ADTS_HEADER_BYTES, adts.size - ADTS_HEADER_BYTES)
        codec.queueInputBuffer(idx, 0, adts.size - ADTS_HEADER_BYTES, 0, 0)
        // 可观测性：首帧（头部 hex 校验 ADTS 同步字）+ 每 200 帧（解码输入持续喂入依据）
        feedCount++
        if (feedCount == 1L) {
            val head = adts.take(4).joinToString(" ") { "%02x".format(it) }
            android.util.Log.i("cloudphone-client", "audio feed first n=1 bytes=${adts.size} head=[$head]")
        } else if (feedCount % 200 == 0L) {
            android.util.Log.i("cloudphone-client", "audio feed n=$feedCount bytes=${adts.size}")
        }
    }

    private var feedCount = 0L
    private var pcmOutCount = 0L

    // ── 文字输入（TEXT_INPUT 发送与 ACK 对账）──

    /**
     * 发送组合文本并等待 ACK{seq}（阻塞，UI 须在工作线程调用）。
     * 返回 (ok, 原因文案)：成功 (true, "")；失败/超时 (false, reason)。
     */
    fun sendText(text: String, timeoutMs: Long = TEXT_ACK_TIMEOUT_MS): Pair<Boolean, String> {
        if (!ClientSession.connected) return false to "未连接源端"
        val seq = textSeq.incrementAndGet()
        val mailbox = SynchronousQueue<ControlMessage.TextInputAck>()
        ackMailboxes[seq] = mailbox
        try {
            ClientSession.sendControl(ControlMessage.TextInput(seq = seq, text = text))
            val ack = mailbox.poll(timeoutMs, TimeUnit.MILLISECONDS)
                ?: return false to "无回执（${timeoutMs / 1000}s 超时，源端版本过旧或链路不通）"
            return if (ack.ok) true to "" else false to ack.reason
        } finally {
            ackMailboxes.remove(seq)
        }
    }

    // ── 剪贴板往返（10s 超时降级）──

    /** CLIPBOARD_GET → CLIPBOARD_TEXT。返回 (ok, 成功=文本/失败=原因) */
    fun requestClipboardGet(timeoutMs: Long = CLIPBOARD_TIMEOUT_MS): Pair<Boolean, String> {
        if (!ClientSession.connected) return false to "未连接源端"
        clipboardMailbox.clear()
        ClientSession.sendControl(ControlMessage.ClipboardGet())
        val result = clipboardMailbox.poll(timeoutMs, TimeUnit.MILLISECONDS)
            ?: return false to "源端无响应（10s 超时降级：源端版本过旧或剪贴板通道不可用）"
        return if (result.ok) true to result.text else false to result.reason
    }

    /** CLIPBOARD_SET（本地剪贴板内容 → 源端）。返回 (ok, 失败原因/成功回显文本) */
    fun sendClipboardSet(text: String, timeoutMs: Long = CLIPBOARD_TIMEOUT_MS): Pair<Boolean, String> {
        if (!ClientSession.connected) return false to "未连接源端"
        clipboardMailbox.clear()
        ClientSession.sendControl(ControlMessage.ClipboardSet(text))
        val result = clipboardMailbox.poll(timeoutMs, TimeUnit.MILLISECONDS)
            ?: return false to "源端无响应（10s 超时降级：源端版本过旧或剪贴板通道不可用）"
        return if (result.ok) true to result.text else false to result.reason
    }

    /** 读客户端本地剪贴板文本（「粘贴」按钮数据源）；null=本地剪贴板无文本 */
    fun localClipboardText(context: Context): String? = try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
    } catch (e: Exception) {
        null
    }

    /** 写客户端本地剪贴板（「复制」按钮成功后落本地） */
    fun setLocalClipboard(context: Context, text: String) {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("cloudphone", text))
        } catch (e: Exception) {
            android.util.Log.w("cloudphone-client", "local clipboard set failed: ${e.message}")
        }
    }
}
