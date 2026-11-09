package cloudphone.client

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.os.SystemClock
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import android.widget.LinearLayout
import cloudphone.protocol.ControlMessage
import cloudphone.protocol.encodeControlMessage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 硬解码渲染 Activity（4.1）：
 * - 客户端硬解 H.264 → SurfaceView 渲染（MediaCodec 硬件解码器优选）；
 * - 首帧白闪测量：连接后表面先铺白，首帧渲染时刻 - 连接建立时刻 = 白闪时长（spec：白闪 MUST 可测量）；
 * - 帧级延迟：本地渲染时刻 - 源端 PTS（elapsedRealtimeNanos 同源假设，见 docs/validation-report）；
 * - 丢帧/黑屏恢复：>1s 无输出帧 → 发 IDR_REQUEST（≤200ms 关键帧恢复）；
 * - 首帧等待：白闪界面（白底 + "等待画面…" 提示）。
 */
class DecodeRenderActivity : android.app.Activity() {

    private lateinit var surfaceView: SurfaceView
    private var decoder: MediaCodec? = null
    private val running = AtomicBoolean(false)
    // 深度 8（≈133ms@60fps）：解码慢于收流时丢旧帧保延迟上界（spec P95≤200ms），保关键帧
    private val frameQueue = LinkedBlockingQueue<ClientSession.VideoFrame>(8)
    private val lastOutputAtMs = AtomicLong(0)
    private val connectedAtMs = AtomicLong(0)
    private val firstFrameRenderedMs = AtomicLong(-1)
    private var lastLostToastAtMs = 0L
    private val idrInFlight = AtomicBoolean(false)
    private var renderThread: Thread? = null
    private var watchThread: Thread? = null

    // ── M3 aux-channels-m3（任务 4.3）：会话控制条 ──
    private lateinit var aux: AuxChannelClient
    private lateinit var statusLine: android.widget.TextView
    private lateinit var btnKeyboard: android.widget.Button
    private lateinit var btnPaste: android.widget.Button
    private lateinit var btnCopy: android.widget.Button
    private lateinit var btnMute: android.widget.Button
    /** 降级原因（AuxNotice 驱动）：输入法未启用→键盘/粘贴/复制灰；无获焦输入框→键盘/复制灰 */
    private var imeReason: String? = null
    private var focusReason: String? = null
    private var audioReason: String? = null
    /** 白闪底视频区引用（首帧渲染撤白） */
    private lateinit var videoArea: FrameLayout

    /** 延迟采样（PTS 差值，ms）——最近 100 帧滚动窗口，供 StatsView/日志导出 */
    val delaySamplesMs = ArrayDeque<Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 视频区 + 底部控制条（M3 任务 4.3）+ 状态行
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        videoArea = FrameLayout(this)
        surfaceView = AspectFitSurfaceView(this)
        // 触摸挂接（真机修复：此前 TouchChannel 从未接线，全屏触摸不可用）
        surfaceView.setOnTouchListener(
            cloudphone.client.input.TouchChannel(
                sessionIdProvider = { ClientSession.currentSessionId },
                send = { ClientSession.sendControl(it) },
            )
        )
        videoArea.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                android.view.Gravity.CENTER,
            ),
        )
        // 白闪底：首帧渲染前全白（测量基准）
        videoArea.setBackgroundColor(0xFFFFFFFF.toInt())
        root.addView(
            videoArea,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            ),
        )
        buildControlBar(root)
        setContentView(root)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = startPipeline(holder.surface)
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) = stopPipeline()
        })
        ClientSession.onFrame = { frame -> onTransportFrame(frame) }
        ClientSession.onConnectionLost = {
            runOnUiThread {
                videoArea.setBackgroundColor(0xFFFFFFFF.toInt())
                firstFrameRenderedMs.set(-1)
                // 重连退避期间 DISCONNECTED 会多次触发，toast 节流 ≥3s 防止刷屏
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastLostToastAtMs > 3000) {
                    lastLostToastAtMs = now
                    android.widget.Toast.makeText(
                        this, "连接断开，正在重连…", android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
        // M3：辅助通道接线（音频默认开启；静音按钮独立控制）
        aux = AuxChannelClient()
        aux.onUiState = { st -> runOnUiThread { onAuxUiState(st) } }
        ClientSession.onAux = { msg -> aux.handleAux(msg) }
        aux.startAudio()
        // 音频通道活性观测：10s 无任何音频帧且无源端通告 → 提示（源端静音/通道未建立）
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!isFinishing && aux.player.framesReceived.get() == 0L && audioReason == null) {
                audioReason = "音频未收到数据（源端静音、音频通道未建立或源端版本过旧）"
                refreshStatus()
            }
        }, 10_000L)
        connectedAtMs.set(SystemClock.elapsedRealtime())
    }

    // ── 控制条（任务 4.3：键盘/粘贴/复制 + 音频静音；不可用态置灰与原因文案）──

    private fun buildControlBar(root: LinearLayout) {
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnKeyboard = android.widget.Button(this).apply {
            text = "键盘"
            setOnClickListener { showTextInputPanel() }
        }
        btnPaste = android.widget.Button(this).apply {
            text = "粘贴"
            setOnClickListener { onPasteClicked() }
        }
        btnCopy = android.widget.Button(this).apply {
            text = "复制"
            setOnClickListener { onCopyClicked() }
        }
        btnMute = android.widget.Button(this).apply {
            text = "🔊 开"
            setOnClickListener { toggleMute() }
            setOnLongClickListener { showVolumeDialog(); true }
        }
        for (b in arrayOf(btnKeyboard, btnPaste, btnCopy, btnMute)) {
            bar.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        statusLine = android.widget.TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF888888.toInt())
        }
        root.addView(statusLine, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    /** AuxNotice 驱动的可用性更新（原因文案→置灰，禁止静默失败） */
    private fun onAuxUiState(st: AuxChannelClient.UiState) {
        (st.audioUnavailableReason ?: st.clipboardUnavailableReason ?: st.keyboardUnavailableReason)?.let { r ->
            when {
                r.contains("未启用") && st.audioUnavailableReason == null -> imeReason = r
                r.contains("无获焦输入框") -> focusReason = r
                st.audioUnavailableReason != null -> audioReason = r
                else -> if (st.clipboardUnavailableReason != null) focusReason = r
            }
        }
        refreshStatus()
    }

    private fun refreshStatus() {
        statusLine.text = listOfNotNull(
            audioReason?.let { "音频: $it" },
            imeReason?.let { "输入法: $it" },
            focusReason?.let { "焦点: $it" },
        ).joinToString("；").ifBlank { "辅助通道就绪" }
        btnKeyboard.isEnabled = imeReason == null && focusReason == null
        btnPaste.isEnabled = imeReason == null
        btnCopy.isEnabled = imeReason == null && focusReason == null
        btnMute.isEnabled = audioReason == null || aux.player.framesReceived.get() > 0
        btnMute.text = if (aux.player.muted) "🔇 关" else "🔊 开"
    }

    /** 键盘按钮：多行文本面板（TEXT_INPUT 发送，完整 Unicode 走 IME commitText） */
    private fun showTextInputPanel() {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = android.view.Gravity.TOP
            hint = "输入要注入到源端的文本（支持中文）"
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("文字输入（IME 注入主路径）")
            .setView(input)
            .setPositiveButton("发送") { d, _ ->
                val text = input.text.toString()
                d.dismiss()
                if (text.isNotEmpty()) sendTextInput(text)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun sendTextInput(text: String) {
        statusLine.text = "注入中…"
        Thread({
            val (ok, why) = aux.sendText(text)
            runOnUiThread {
                statusLine.text = if (ok) "已注入（${text.length} 字符）✓" else "注入失败：$why"
                if (!ok && why.contains("无获焦输入框")) {
                    focusReason = why
                    refreshStatus()
                }
            }
        }, "aux-text-input").apply { isDaemon = true; start() }
    }

    /** 粘贴按钮：客户端本地剪贴板 → CLIPBOARD_SET → 源端 IME setPrimaryClip */
    private fun onPasteClicked() {
        val text = aux.localClipboardText(this)
        if (text == null) {
            statusLine.text = "本地剪贴板无文本（先在本机复制内容）"
            return
        }
        Thread({
            val (ok, v) = aux.sendClipboardSet(text)
            runOnUiThread {
                statusLine.text = if (ok) "已粘贴到源端（${text.length} 字符）✓" else "粘贴失败：$v"
                if (!ok && v.contains("未启用")) {
                    imeReason = v
                    refreshStatus()
                }
            }
        }, "aux-clipboard-set").apply { isDaemon = true; start() }
    }

    /** 复制按钮：CLIPBOARD_GET → 等待 CLIPBOARD_TEXT 回执（10s 超时降级）→ 写本地剪贴板 */
    private fun onCopyClicked() {
        statusLine.text = "正在读取源端剪贴板…"
        Thread({
            val (ok, v) = aux.requestClipboardGet()
            runOnUiThread {
                if (ok) {
                    aux.setLocalClipboard(this, v)
                    statusLine.text = "已复制到本机（${v.length} 字符）✓"
                } else {
                    statusLine.text = "复制失败：$v"
                    if (v.contains("未启用")) imeReason = v
                    if (v.contains("无获焦输入框")) focusReason = v
                    refreshStatus()
                }
            }
        }, "aux-clipboard-get").apply { isDaemon = true; start() }
    }

    /** 音频静音开关（会话级独立，不联动视频） */
    private fun toggleMute() {
        aux.player.muted = !aux.player.muted
        refreshStatus()
    }

    /** 长按音量按钮：独立音量滑条（不联动视频） */
    private fun showVolumeDialog() {
        val seek = android.widget.SeekBar(this).apply {
            max = 100
            progress = (aux.player.volume * 100).toInt()
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("音频音量（独立于视频）")
            .setView(seek)
            .setPositiveButton("确定", null)
            .show()
        seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                aux.player.volume = progress / 100f
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) = Unit
        })
    }

    // ── 传输帧入口 ──

    private fun onTransportFrame(frame: ClientSession.VideoFrame) {
        // 丢帧保护：队列满（解码背压）时丢最旧的普通帧，保留关键帧
        while (!frameQueue.offer(frame)) {
            val dropped = frameQueue.poll()
            if (dropped?.keyframe == true) frameQueue.offer(dropped)
        }
    }

    // ── 解码管线 ──

    private fun startPipeline(surface: Surface) {
        if (running.get()) return
        running.set(true)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720).apply {
            // 实际分辨率随首帧 SPS 自适应（解码器按流内容解析）
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
        }
        val name = pickDecoder() ?: run {
            android.widget.Toast.makeText(this, "无可用 AVC 解码器", android.widget.Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val codec = MediaCodec.createByCodecName(name)
        try {
            codec.configure(format, surface, null, 0)
        } catch (e: Exception) {
            // 分辨率不匹配等：不带尺寸重配（流内 SPS 优先）
            val retry = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080)
            retry.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            codec.configure(retry, surface, null, 0)
        }
        codec.start()
        decoder = codec
        renderThread = Thread({ decodeLoop(codec) }, "client-decode").apply { isDaemon = true; start() }
        watchThread = Thread({ blackScreenWatch() }, "client-watch").apply { isDaemon = true; start() }
    }

    private fun pickDecoder(): String? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder.not() }
        val avc = list.filter { it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } }
        // goldfish 解码器是模拟器虚拟硬件：吃帧不出帧（stale input buffer 后静默），实测必须避开；
        // 真机无 goldfish，硬解优先不受影响
        val usable = avc.filter { !it.name.contains("goldfish", true) }
        return (usable.firstOrNull {
            val n = it.name
            !n.startsWith("OMX.google") && !n.startsWith("c2.android")
        } ?: usable.firstOrNull() ?: avc.firstOrNull())?.name
    }

    private fun decodeLoop(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running.get()) {
            val frame = frameQueue.poll(50, TimeUnit.MILLISECONDS) ?: continue
            var index = codec.dequeueInputBuffer(20_000)
            if (index >= 0) {
                val buf = codec.getInputBuffer(index) ?: continue
                buf.clear()
                buf.put(frame.data)
                codec.queueInputBuffer(index, 0, frame.data.size, frame.ptsUs, 0)
            } else {
                continue
            }
            index = codec.dequeueOutputBuffer(info, 20_000)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // 真实视频尺寸到达：更新 letterbox 视口（触控归一化基于该视口）
                val f = codec.outputFormat
                val vw = runCatching { f.getInteger(MediaFormat.KEY_WIDTH) }.getOrDefault(0)
                val vh = runCatching { f.getInteger(MediaFormat.KEY_HEIGHT) }.getOrDefault(0)
                if (vw > 0 && vh > 0) {
                    (surfaceView as AspectFitSurfaceView).setVideoSize(vw, vh)
                    android.util.Log.i("cloudphone-client", "video size $vw x $vh")
                }
                continue
            }
            if (index >= 0) {
                // latest-wins 渲染（触摸延迟修复 5.3）：解码照常（保 P 帧参考链完整），
                // 但仅当队列为空（本帧已是最新）或本帧为关键帧时才上屏；
                // 积压的陈旧帧 releaseOutputBuffer(false) 丢弃，避免显示滞后 = 积压深度 × 帧间隔
                val isLatest = frameQueue.isEmpty() || frame.keyframe
                codec.releaseOutputBuffer(index, isLatest) // true=渲染到 Surface
                onFrameRendered(frame, info)
            }
        }
    }

    private fun onFrameRendered(frame: ClientSession.VideoFrame, info: MediaCodec.BufferInfo) {
        lastOutputAtMs.set(SystemClock.elapsedRealtime())
        idrInFlight.set(false)
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        // 收流→渲染管线延迟（客户端本地时钟，跨设备时钟域不可直接相减）
        val delayMs = (nowNanos / 1000 - frame.arrivalUs) / 1000
        if (delayMs in 0..10_000) {
            synchronized(delaySamplesMs) {
                delaySamplesMs.addLast(delayMs)
                while (delaySamplesMs.size > 100) delaySamplesMs.removeFirst()
            }
            android.util.Log.i(
                "cloudphone-latency",
                "frame seq=${frame.sequence} delayMs=$delayMs keyframe=${frame.keyframe}",
            )
        }
        // ⑥ 渲染完成打点（触摸延迟插桩 5.1）：sinceTouchMs = 本帧渲染时刻 − 最近触摸发出时刻
        val sinceTouchMs = SystemClock.elapsedRealtime() - ClientSession.lastInputSentAtMs
        if (ClientSession.lastInputSentAtMs > 0 && sinceTouchMs in 0..5000) {
            android.util.Log.i(
                "cloudphone-touch",
                "t6 render seq=${frame.sequence} sinceTouchMs=$sinceTouchMs keyframe=${frame.keyframe}",
            )
        }
        if (firstFrameRenderedMs.compareAndSet(-1, SystemClock.elapsedRealtime())) {
            val flashMs = firstFrameRenderedMs.get() - connectedAtMs.get()
            android.util.Log.i("cloudphone-latency", "white-flash Ms=$flashMs")
            runOnUiThread {
                // 首帧已渲染：撤白闪（M3 布局：白底在视频区）
                videoArea.setBackgroundColor(0x00000000)
            }
        }
    }

    /** 黑屏看守：>1s 无输出帧 → 请求 IDR（≤200ms 恢复；静屏无输入时每 2s 重试） */
    private fun blackScreenWatch() {
        var lastIdrRequestAtMs = 0L
        while (running.get()) {
            Thread.sleep(300)
            val idleMs = SystemClock.elapsedRealtime() - lastOutputAtMs.get()
            // 源端编码器为 surface 输入：静屏时无新帧进编码器，sync-frame 请求要等下次
            // 重绘才兑现——单次 CAS 只发一次会让白屏等到天荒地老，改为 2s 持续重试
            val now = SystemClock.elapsedRealtime()
            if (idleMs > 1000 && now - lastIdrRequestAtMs > 2000) {
                lastIdrRequestAtMs = now
                ClientSession.sendControl(
                    ControlMessage.IdrRequest(sessionId = ClientSession.currentSessionId),
                )
            }
        }
    }

    /** 请求立即关键帧（UI 手动触发入口） */
    fun requestKeyframe() {
        idrInFlight.set(false)
        ClientSession.sendControl(ControlMessage.IdrRequest(sessionId = ClientSession.currentSessionId))
    }

    private fun stopPipeline() {
        running.set(false)
        renderThread?.join(1000)
        watchThread?.join(300)
        runCatching {
            decoder?.stop()
            decoder?.release()
        }
        decoder = null
        frameQueue.clear()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPipeline()
        ClientSession.onFrame = null
        ClientSession.onConnectionLost = null
        // M3：辅助通道释放
        aux.stopAudio()
        ClientSession.onAux = null
    }

    /** 最近延迟统计（P50/P95，供 StatsView） */
    fun delayStats(): Pair<Long, Long>? {
        synchronized(delaySamplesMs) {
            if (delaySamplesMs.isEmpty()) return null
            val sorted = delaySamplesMs.sorted()
            return sorted[sorted.size / 2] to sorted[((sorted.size - 1) * 95) / 100]
        }
    }

    /**
     * 按视频宽高比 letterbox 的 SurfaceView：onMeasure 使自身 laid-out 尺寸 = 视频显示区
     * （居中于父容器，黑边由父容器白底/黑底承载）；TouchChannel 归一化基于该 View 尺寸，
     * 故视口=触控区，坐标语义与源端真实屏幕一一对应。
     */
    inner class AspectFitSurfaceView(context: android.content.Context) : SurfaceView(context) {
        private var videoW = 0
        private var videoH = 0

        fun setVideoSize(w: Int, h: Int) {
            if (w == videoW && h == videoH) return
            videoW = w
            videoH = h
            post { requestLayout() }
        }

        override fun onMeasure(wSpec: Int, hSpec: Int) {
            if (videoW <= 0 || videoH <= 0) {
                super.onMeasure(wSpec, hSpec)
                return
            }
            val pw = android.view.View.MeasureSpec.getSize(wSpec)
            val ph = android.view.View.MeasureSpec.getSize(hSpec)
            val videoAspect = videoW.toDouble() / videoH
            val parentAspect = pw.toDouble() / ph
            val (w, h) = if (videoAspect > parentAspect) {
                pw to (pw / videoAspect).toInt()
            } else {
                (ph * videoAspect).toInt() to ph
            }
            setMeasuredDimension(w.coerceAtLeast(1), h.coerceAtLeast(1))
        }
    }
}
