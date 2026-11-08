package cloudphone.source

import android.media.projection.MediaProjection
import cloudphone.protocol.HungSession
import cloudphone.protocol.WatchMode
import cloudphone.source.obs.PipelineCounters
import cloudphone.source.shell.ShellBootstrap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 会话管理器：会话生命周期 > 连接生命周期。
 *
 * - 断开（客户端断连）只停编码/帧流（[detach]），虚拟屏与 App 保留（挂机）；
 * - 相同 token 重连 + ATTACH sessionId → [resume]：≤1s 恢复画面（重建帧流 + IDR 200ms）；
 * - 单应用会话 ≥2 并存：并发注册表支持；STOP_SESSION 才销毁（[destroy]）。
 */
class SessionManager(
    private val context: android.content.Context,
    val token: String,
    private val bootstrap: ShellBootstrap,
) {
    enum class SessionEvent { CREATED, HUNG, RESUMED, DESTROYED, PROJECTION_LOST }

    class Session internal constructor(
        val id: Int,
        val mode: WatchMode,
        val packageName: String,
        val params: EncoderPipeline.SessionParams,
        internal var projection: MediaProjection?,
        internal var source: CaptureSource?,
        internal var vdSession: VirtualDisplaySession?,
    ) {
        @Volatile var hung: Boolean = false
            internal set
        val createdAtMs: Long = System.currentTimeMillis()
        val sentFrames: Long get() = PipelineCounters.sentFrames.get()
    }

    private val sessions = ConcurrentHashMap<Int, Session>()
    private val idGen = AtomicInteger(1)

    @Volatile var lastError: String = ""
        private set

    fun validateToken(candidate: String): Boolean = token == candidate

    fun all(): Collection<Session> = sessions.values

    fun get(id: Int): Session? = sessions[id]

    /** 已挂机会话摘要（HELLO_ACK.hungSessions / 会话抽屉） */
    fun hungSessions(): List<HungSession> = sessions.values.filter { it.hung }.map {
        HungSession(it.id, it.mode, it.packageName, it.params.width, it.params.height)
    }

    /** 整屏会话（共享采集管线）：服务在授权时已建好 VD+编码器（一授权一实例的镜像约束），会话只挂接帧流 */
    fun attachFullscreen(params: EncoderPipeline.SessionParams): Session {
        val session = Session(idGen.getAndIncrement(), WatchMode.FULLSCREEN, "", params, null, null, null)
        sessions[session.id] = session
        return session
    }

    /** 单应用虚拟屏会话：shell helper 创建屏 + 启动 App；helper 不可用时报明确降级原因 */
    fun startSingleApp(
        packageName: String,
        params: EncoderPipeline.SessionParams,
        onFrame: (sequence: Long, ptsUs: Long, keyframe: Boolean, data: ByteArray) -> Unit,
    ): Session? {
        val vd = VirtualDisplaySession(bootstrap, packageName, params.width, params.height)
        val created = vd.createDisplay()
        if (!created.ok) {
            lastError = "虚拟屏创建失败（shell helper 不可用？）: ${created.value}"
            PipelineCounters.onBadEvent(
                PipelineCounters.BadEvent.SHELL_HELPER_LOST, lastError,
            )
            return null
        }
        val launched = vd.launchApp()
        if (!launched.ok) {
            lastError = "App 启动失败: ${launched.value}"
            vd.destroy()
            return null
        }
        val session = Session(
            idGen.getAndIncrement(), WatchMode.SINGLE_APP, packageName, params, null, vd, vd,
        )
        if (!vd.start { seq, pts, kf, data -> onSessionFrame(session, onFrame, seq, pts, kf, data) }) {
            lastError = "虚拟屏帧流连接失败"
            vd.destroy()
            return null
        }
        sessions[session.id] = session
        return session
    }

    /** 挂机：断开帧流/停编码，保留虚拟屏与 App（含整屏会话的投影授权） */
    fun hangup(session: Session) {
        if (session.hung) return
        session.hung = true
        synchronized(session) {
            when (session.mode) {
                WatchMode.FULLSCREEN -> session.source?.stop() // 停编码与镜像，投影对象保留可重建
                WatchMode.SINGLE_APP -> session.vdSession?.detach() // 只断帧流，屏与 App 存活
                else -> Unit
            }
        }
    }

    /**
     * 恢复（同 token 重连 + attach）：≤1s 恢复画面 —— 重建帧源并立即请求 IDR（≤200ms 产关键帧）。
     * 整屏会话恢复需要有效的 MediaProjection（未丢失）；失败返回 null 并写 lastError。
     */
    fun resume(
        session: Session,
        onFrame: (sequence: Long, ptsUs: Long, keyframe: Boolean, data: ByteArray) -> Unit,
        projectionProvider: (() -> MediaProjection?)? = null,
    ): Boolean {
        if (!sessions.containsKey(session.id)) return false
        synchronized(session) {
            when (session.mode) {
                WatchMode.FULLSCREEN -> {
                    val projection = session.projection
                    if (projection == null) {
                        lastError = "投影授权已丢失，整屏会话不可恢复"
                        return false
                    }
                    val capture = ScreenCapture(context, projection, session.params) {
                        PipelineCounters.onBadEvent(PipelineCounters.BadEvent.PROJECTION_LOST, "resumed session lost projection")
                    }
                    session.source = capture
                    val ok = capture.start { seq, pts, kf, data -> onSessionFrame(session, onFrame, seq, pts, kf, data) }
                    if (!ok) {
                        lastError = "整屏采集重建失败"
                        return false
                    }
                    capture.requestIdr()
                }
                WatchMode.SINGLE_APP -> {
                    val vd = session.vdSession ?: return false
                    if (!vd.start { seq, pts, kf, data -> onSessionFrame(session, onFrame, seq, pts, kf, data) }) {
                        lastError = "虚拟屏帧流重连失败"
                        return false
                    }
                    vd.requestIdr() // ≤200ms 关键帧 → 客户端 ≤1s 出画
                }
                else -> return false
            }
            session.hung = false
            return true
        }
    }

    /** 结束会话：销毁虚拟屏与 App、释放采集 */
    fun destroy(session: Session) {
        synchronized(session) {
            session.source?.stop()
            session.vdSession?.destroy()
            session.projection?.stop()
        }
        sessions.remove(session.id)
    }

    fun destroyAll() {
        sessions.values.toList().forEach { destroy(it) }
    }

    private fun onSessionFrame(
        session: Session,
        sink: (sequence: Long, ptsUs: Long, keyframe: Boolean, data: ByteArray) -> Unit,
        seq: Long, pts: Long, kf: Boolean, data: ByteArray,
    ) {
        sink(seq, pts, kf, data)
    }
}
