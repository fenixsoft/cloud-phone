package cloudphone.client.input

import android.view.MotionEvent
import android.view.View
import cloudphone.protocol.ControlMessage
import cloudphone.protocol.InputAction

/**
 * 触摸通道（4.2）：客户端渲染区触摸 → INPUT_EVENT 控制消息。
 *
 * - 坐标归一化到 [0,65535]（协议要求；分辨率无关）；
 * - 多点触控：pointerId 逐指追踪，DOWN/UP/MOVE 按指针分发（M0 单指体验 + 多指协议正确）；
 * - 边缘侧滑 → 远端 back：ACTION_DOWN 在左/右缘 24dp 内且水平滑动 ≥80dp 时触发 BACK。
 */
class TouchChannel(
    private val sessionIdProvider: () -> Int,
    private val send: (ControlMessage.InputEvent) -> Unit,
) : View.OnTouchListener {

    /** 归一化上限（协议 [0,65535]） */
    private val normMax = 65535

    /** 每指当前逻辑位置（用于 back 手势判定） */
    private val pointerStartX = mutableMapOf<Int, Float>()

    /** 已出发 back 的手势（抬起前不再重复触发） */
    private val backTriggered = mutableMapOf<Int, Boolean>()

    private fun normalize(v: Float, total: Int): Int =
        (v.coerceIn(0f, total.toFloat()) / total.coerceAtLeast(1) * normMax).toInt()

    private fun sendAction(pointerId: Int, action: InputAction, x: Int, y: Int) {
        // ① 触摸事件产生打点（触摸延迟插桩 5.1）
        android.util.Log.i("cloudphone-touch", "t1 action=${action.id} p=$pointerId x=$x y=$y")
        val t0 = android.os.SystemClock.elapsedRealtime()
        send(
            ControlMessage.InputEvent(
                sessionId = sessionIdProvider(),
                action = action,
                pointerId = pointerId,
                x = x,
                y = y,
            ),
        )
        // 写出耗时（定位控制通道排队段：>50ms 说明发送路径阻塞）
        val dt = android.os.SystemClock.elapsedRealtime() - t0
        if (dt > 20) {
            android.util.Log.w("cloudphone-touch", "t1b send slow dtMs=$dt")
        } else {
            android.util.Log.d("cloudphone-touch", "t1b send ok dtMs=$dt")
        }
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        val edgeDp = 24 * v.resources.displayMetrics.density
        val swipeDp = 80 * v.resources.displayMetrics.density
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                val x = event.getX(idx)
                val y = event.getY(idx)
                pointerStartX[id] = x
                backTriggered[id] = false
                sendAction(id, InputAction.DOWN, normalize(x, v.width), normalize(y, v.height))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    val x = event.getX(i)
                    val y = event.getY(i)
                    val startX = pointerStartX[id]
                    // 边缘侧滑 → 远端 back（一次性）
                    if (startX != null && backTriggered[id] != true &&
                        (startX < edgeDp || startX > v.width - edgeDp) &&
                        kotlin.math.abs(x - startX) > swipeDp
                    ) {
                        backTriggered[id] = true
                        send(
                            ControlMessage.InputEvent(
                                sessionId = sessionIdProvider(),
                                action = InputAction.BACK,
                                pointerId = id,
                                x = 0,
                                y = 0,
                            ),
                        )
                        continue
                    }
                    sendAction(id, InputAction.MOVE, normalize(x, v.width), normalize(y, v.height))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val idx = if (event.actionMasked == MotionEvent.ACTION_UP) 0 else event.actionIndex
                val id = event.getPointerId(idx)
                val x = event.getX(idx)
                val y = event.getY(idx)
                if (backTriggered[id] != true) {
                    sendAction(id, InputAction.UP, normalize(x, v.width), normalize(y, v.height))
                }
                pointerStartX.remove(id)
                backTriggered.remove(id)
            }
            MotionEvent.ACTION_CANCEL -> {
                pointerStartX.clear()
                backTriggered.clear()
            }
        }
        return true
    }
}
