package cloudphone.desktop.session

import cloudphone.protocol.ControlMessage
import cloudphone.protocol.InputAction
import kotlin.math.roundToInt

/**
 * 输入映射（design D4，纯函数，可 headless 单测）：
 * 桌面视口坐标 → 云手机虚拟屏归一化坐标（x/y ∈ [0, 65535]），letterbox 居中、
 * 黑边防护（内容区外事件直接忽略，防误触）、左键 DOWN/MOVE/UP、拖动=滑动、
 * 滚轮=小步滑动、右键=BACK 注入。
 */
object InputMapper {
    /** 协议归一化坐标满量程 */
    const val NORM_MAX = 65535

    /** 鼠标触摸 pointerId（桌面单鼠标固定 0） */
    const val POINTER_MOUSE = 0

    /** 滚轮单格滑动步长（视口像素） */
    const val WHEEL_STEP_PX = 24

    /** letterbox 内容矩形（视口内居中、保持视频纵横比） */
    data class ContentRect(val x: Int, val y: Int, val width: Int, val height: Int) {
        fun contains(px: Double, py: Double): Boolean =
            px >= x && px < x + width && py >= y && py < y + height
    }

    /** 视口→视频坐标系的 letterbox 几何 */
    data class Viewport(val videoWidth: Int, val videoHeight: Int, val viewWidth: Int, val viewHeight: Int) {
        val content: ContentRect by lazy {
            val vw = videoWidth.coerceAtLeast(1)
            val vh = videoHeight.coerceAtLeast(1)
            val scale = minOf(viewWidth.toDouble() / vw, viewHeight.toDouble() / vh)
            val w = (vw * scale).roundToInt().coerceIn(1, viewWidth)
            val h = (vh * scale).roundToInt().coerceIn(1, viewHeight)
            ContentRect((viewWidth - w) / 2, (viewHeight - h) / 2, w, h)
        }
    }

    /** 归一化坐标点 */
    data class NormPoint(val x: Int, val y: Int)

    /**
     * 视口点 → 归一化视频坐标；内容区之外（黑边）返回 null（防护：不注入事件）。
     * 映射：[(px - contentX) / (contentW - 1)] × 65535，左上 (0,0)，右下 (65535, 65535)。
     */
    fun mapToVideo(viewport: Viewport, viewX: Double, viewY: Double): NormPoint? {
        val c = viewport.content
        if (!c.contains(viewX, viewY)) return null
        return NormPoint(
            normalize(viewX - c.x, c.width),
            normalize(viewY - c.y, c.height),
        )
    }

    private fun normalize(offset: Double, span: Int): Int {
        if (span <= 1) return 0
        return (offset / (span - 1) * NORM_MAX).roundToInt().coerceIn(0, NORM_MAX)
    }

    /** 左键按下（拖动起点）；黑边返回 null */
    fun pointerDown(viewport: Viewport, viewX: Double, viewY: Double, sessionId: Int): ControlMessage.InputEvent? =
        mapToVideo(viewport, viewX, viewY)?.toEvent(sessionId, InputAction.DOWN)

    /** 按住拖动 → 滑动（MOVE 流）；黑边返回 null（拖出内容区时事件被忽略，回到内容区继续） */
    fun pointerMove(viewport: Viewport, viewX: Double, viewY: Double, sessionId: Int): ControlMessage.InputEvent? =
        mapToVideo(viewport, viewX, viewY)?.toEvent(sessionId, InputAction.MOVE)

    /** 左键抬起 */
    fun pointerUp(viewport: Viewport, viewX: Double, viewY: Double, sessionId: Int): ControlMessage.InputEvent? =
        mapToVideo(viewport, viewX, viewY)?.toEvent(sessionId, InputAction.UP)

    /**
     * 滚轮一格 → 小步滑动（DOWN → MOVE → UP 三元组，[WHEEL_STEP_PX] 视口像素）。
     * [notches] > 0（滚轮向下）→ 内容上滑（手指上滑，y 递减）；< 0 → 下滑。
     * 任一端点落在黑边 → 返回空列表（不注入半截手势）。
     */
    fun wheelStep(viewport: Viewport, viewX: Double, viewY: Double, notches: Int, sessionId: Int): List<ControlMessage.InputEvent> {
        if (notches == 0) return emptyList()
        val dy = if (notches > 0) -WHEEL_STEP_PX.toDouble() else WHEEL_STEP_PX.toDouble()
        val start = mapToVideo(viewport, viewX, viewY) ?: return emptyList()
        val end = mapToVideo(viewport, viewX, viewY + dy) ?: return emptyList()
        return listOf(
            start.toEvent(sessionId, InputAction.DOWN),
            end.toEvent(sessionId, InputAction.MOVE),
            end.toEvent(sessionId, InputAction.UP),
        )
    }

    /** 右键 = BACK 键注入（坐标由源端忽略） */
    fun back(sessionId: Int): ControlMessage.InputEvent =
        ControlMessage.InputEvent(sessionId, InputAction.BACK, POINTER_MOUSE, 0, 0)

    private fun NormPoint.toEvent(sessionId: Int, action: InputAction) =
        ControlMessage.InputEvent(sessionId, action, POINTER_MOUSE, x, y)
}
