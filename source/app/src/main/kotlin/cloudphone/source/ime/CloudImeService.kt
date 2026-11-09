package cloudphone.source.ime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 云剪贴板输入法桥（aux-channels-m3 任务 3.1）：
 *
 * StreamService（控制线程）与 CloudImeService（系统实例化的主线程服务）同进程通信桥。
 * Android 10+ 后台剪贴板读写需焦点——IME 天然持焦点，是唯一合法焦点代理（设计 D2）：
 * - CLIPBOARD_SET → [setPrimaryClip]（持焦点合法写入）
 * - CLIPBOARD_GET → [readPrimaryClip]（IME 活跃且源端 App 有焦点时可读）
 * - TEXT_INPUT   → [commitText]（完整 Unicode 注入主路径）
 *
 * ime == null 表示系统未启用/未选中本输入法（引导启用：设置页入口 + bootstrap_adb ime enable/set）。
 * hasInputConnection == false 表示 IME 已选中但源端无文本框获焦（commitText/读剪贴板受限）。
 */
object ImeBridge {

    @Volatile var ime: CloudImeService? = null
        internal set

    /** 最近一次输入会话开始时刻（elapsedRealtime ms；「IME 活跃」判据之一） */
    @Volatile var lastInputStartedAtMs: Long = 0
        internal set

    /** 输入法已被系统启用并实例化（选中状态） */
    val installed: Boolean get() = ime != null

    /** IME 活跃：已实例化且当前有输入连接（源端存在获焦文本框） */
    val active: Boolean get() = ime?.hasInputConnection() == true

    private val main = Handler(Looper.getMainLooper())

    /** IME 方法 MUST 在主线程执行：控制线程阻塞等待结果（超时视为失败） */
    private fun <T> onMainThread(timeoutMs: Long, body: () -> T): T? {
        val imeSvc = ime ?: return null
        if (Looper.myLooper() == Looper.getMainLooper()) return body()
        var result: T? = null
        val latch = CountDownLatch(1)
        main.post {
            try {
                result = body()
            } catch (e: Exception) {
                android.util.Log.w("cloudphone-ime", "ime op failed: ${e.message}")
                result = null
            } finally {
                latch.countDown()
            }
        }
        // 唤醒 IME 服务生命周期等待（防止误判活跃服务死亡）
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
        return result
    }

    /** commitText 注入（完整 Unicode）。true=已提交到获焦输入框 */
    fun commitText(text: String): Boolean {
        val ok = onMainThread(2000) { ime?.injectCommitText(text) == true } ?: false
        return ok
    }

    /** 写剪贴板（IME 持焦点合法；CLIPBOARD_SET 路径）。true=已写入 */
    fun setPrimaryClip(text: String): Boolean {
        val ok = onMainThread(2000) { ime?.writePrimaryClip(text) == true } ?: false
        return ok
    }

    /** 读剪贴板文本（IME 活跃时合法；CLIPBOARD_GET 路径）。null=不可读（未启用/无焦点/非文本） */
    fun readPrimaryClip(): String? = onMainThread(2000) { ime?.readClip() }

    /** 查询系统启用状态（enabled 集合含本 IME 即 true，与是否当前选中无关） */
    fun isEnabled(context: Context): Boolean {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        return (imm.enabledInputMethodList ?: emptyList())
            .any { it.packageName == context.packageName && it.id.contains("CloudImeService") }
    }
}

/**
 * 极简云剪贴板输入法（aux-channels-m3 任务 3.1）：无键盘 UI 的焦点代理。
 *
 * - onCreateInputView 返回零尺寸视图 + onEvaluateInputViewShown=false：不渲染任何键盘面板，
 *   仅作为 Android 10+ 剪贴板/组合文本注入的合法焦点载体；
 * - commitText：向获焦输入框提交完整 Unicode 文本（中文/emoji 主路径）；
 * - setPrimaryClip / readPrimaryClip：剪贴板双向通道的合法读写点。
 */
class CloudImeService : InputMethodService() {

    override fun onCreate() {
        super.onCreate()
        ImeBridge.ime = this
        android.util.Log.i("cloudphone-ime", "CloudImeService created (focus proxy ready)")
    }

    override fun onDestroy() {
        if (ImeBridge.ime === this) ImeBridge.ime = null
        super.onDestroy()
    }

    override fun onStartInput(attribute: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        ImeBridge.lastInputStartedAtMs = SystemClock.elapsedRealtime()
    }

    /** 无键盘 UI：零尺寸视图（任务 3.2 字面要求） */
    override fun onCreateInputView(): View {
        val v = FrameLayout(this)
        v.visibility = View.GONE
        v.layoutParams = ViewGroup.LayoutParams(0, 0)
        return v
    }

    /** 永不弹输入面板：纯焦点代理（设计 Non-Goals：不做 IME 可见键盘 UI） */
    override fun onEvaluateInputViewShown(): Boolean = false

    internal fun hasInputConnection(): Boolean = currentInputConnection != null

    /** 主线程调用：向获焦输入框提交文本 */
    internal fun injectCommitText(text: String): Boolean {
        val ic = currentInputConnection ?: return false
        val ok = ic.commitText(text, 1)
        return ok
    }

    /** 主线程调用：写剪贴板（持焦点合法） */
    internal fun writePrimaryClip(text: String): Boolean = try {
        val cm = applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("cloudphone", text))
        true
    } catch (e: Exception) {
        android.util.Log.w("cloudphone-ime", "setPrimaryClip failed: ${e.message}")
        false
    }

    /** 主线程调用：读剪贴板文本（IME 活跃时合法；非文本内容 coerceToText 兜底） */
    internal fun readClip(): String? = try {
        val cm = applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) null else clip.getItemAt(0).coerceToText(this)?.toString()
    } catch (e: Exception) {
        android.util.Log.w("cloudphone-ime", "getPrimaryClip failed: ${e.message}")
        null
    }
}
