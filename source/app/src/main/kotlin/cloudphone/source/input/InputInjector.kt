package cloudphone.source.input

import cloudphone.protocol.InputAction
import cloudphone.source.SessionManager
import cloudphone.source.shell.ShellBootstrap

/**
 * 输入注入器：INPUT_EVENT 归一化坐标（0..65535）按会话分辨率换算后注入对应 display。
 *
 * 路径：全部经 shell helper（app_process root/shell uid）反射 InputManager.injectInputEvent，
 * 显示器由隐藏 API InputEvent.setDisplayId 指定——整屏会话注入 DEFAULT_DISPLAY(0)，
 * 单应用会话注入其虚拟屏 displayId。
 */
class InputInjector(
    private val bootstrap: ShellBootstrap,
    private val sessions: SessionManager,
) {
    /** 归一化坐标上限（协议定义 [0,65535]） */
    private val NORMALIZED_MAX = 65535

    /** 注入一个输入事件；返回错误描述（null=成功） */
    fun inject(sessionId: Int, action: InputAction, pointerId: Int, normalizedX: Int, normalizedY: Int): String? {
        val session = sessions.get(sessionId) ?: return "unknown session $sessionId"
        val displayId = when (session.mode) {
            cloudphone.protocol.WatchMode.SINGLE_APP -> session.vdSession?.displayId ?: return "session $sessionId has no display"
            else -> 0 // 整屏 = 主屏
        }
        val nx = normalizedX.coerceIn(0, NORMALIZED_MAX)
        val ny = normalizedY.coerceIn(0, NORMALIZED_MAX)
        val result = bootstrap.injectTouch(displayId, action.id, nx, ny)
        return if (result.ok) null else result.value
    }

    /** 远端 back（客户端边缘侧滑手势触发） */
    fun injectBack(sessionId: Int): String? {
        val session = sessions.get(sessionId) ?: return "unknown session $sessionId"
        val displayId = when (session.mode) {
            cloudphone.protocol.WatchMode.SINGLE_APP -> session.vdSession?.displayId ?: return "no display"
            else -> 0
        }
        val result = bootstrap.injectTouch(displayId, InputAction.BACK.id, 0, 0)
        return if (result.ok) null else result.value
    }
}
