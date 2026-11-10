package cloudphone.client.ui

import android.app.Dialog
import android.app.DialogFragment
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import cloudphone.client.R
import cloudphone.client.SettingsPrefs
import cloudphone.client.SourceProfile
import cloudphone.client.SourceProfiles

/**
 * 新建连接底部弹层表单（client-ui-redesign 2.3，design D3）：
 * android.app.DialogFragment + 平台 API（窗口底部对齐 + 底部入位动画，不引入 material）。
 *
 * 字段与默认值按 spec：
 * 名称可选 / 主机地址独占一行 / TCP+UDP 同行默认 22333/22334 / 后端三选 lan-direct 默认 /
 * token 密码态 / 高级折叠（分辨率跟随本机、帧率 60、码率 8 Mbps、观看模式整屏镜像——
 * 高级默认值读设置页「串流档位默认值」偏好，4.3）。
 *
 * 保存校验主机地址非空（空则「请填写主机地址」提示且不保存），成功写入
 * SourceProfiles（origin=manual）并经 [onSaved] 回调即时回显档案卡。
 */
class ManualConnectSheet : DialogFragment() {

    /** 保存成功回调（宿主 Activity 据此即时刷新档案列表） */
    var onSaved: ((SourceProfile) -> Unit)? = null

    private val backends = arrayOf("lan-direct", "p2p", "frp")
    private val fpsValues = intArrayOf(30, 60)
    private val bitrateValues = intArrayOf(4, 8, 16, 20)
    private val modeNames = arrayOf("FULLSCREEN", "SINGLE_APP")

    private var backendIdx = 0
    private var resolutionIdx = 0
    private var fpsIdx = 1
    private var bitrateIdx = 1
    private var modeIdx = 0

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = activity ?: return super.onCreateDialog(savedInstanceState)
        val dialog = Dialog(ctx, R.style.Theme_CloudPhone_Sheet)
        val view = LayoutInflater.from(ctx).inflate(R.layout.sheet_manual_connect, null)
        dialog.setContentView(view)
        dialog.window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setWindowAnimations(R.style.sheet_bottom_anim)
        }
        wire(ctx, view)
        return dialog
    }

    private fun wire(ctx: android.content.Context, view: View) {
        val hostInput = view.findViewById<EditText>(R.id.host_input)
        val tcpInput = view.findViewById<EditText>(R.id.tcp_input)
        val udpInput = view.findViewById<EditText>(R.id.udp_input)
        val nameInput = view.findViewById<EditText>(R.id.name_input)
        val tokenInput = view.findViewById<EditText>(R.id.token_input)

        // 端口默认值读设置页「传输偏好」（4.3）；档位默认值读「串流档位默认值」
        tcpInput.setText(SettingsPrefs.defaultTcpPort(ctx).toString())
        udpInput.setText(SettingsPrefs.defaultUdpPort(ctx).toString())
        resolutionIdx = SettingsPrefs.resolutionTier(ctx)
        fpsIdx = if (SettingsPrefs.fps(ctx) == 30) 0 else 1
        bitrateIdx = bitrateValues.indexOf(SettingsPrefs.bitrateMbps(ctx)).coerceAtLeast(0)

        val backendSeg = view.findViewById<LinearLayout>(R.id.backend_seg)
        val resSeg = view.findViewById<LinearLayout>(R.id.resolution_seg)
        val fpsSeg = view.findViewById<LinearLayout>(R.id.fps_seg)
        val brSeg = view.findViewById<LinearLayout>(R.id.bitrate_seg)
        val modeSeg = view.findViewById<LinearLayout>(R.id.mode_seg)

        wireSeg(backendSeg) { backendIdx = it }
        wireSeg(resSeg) { resolutionIdx = it }
        wireSeg(fpsSeg) { fpsIdx = it }
        wireSeg(brSeg) { bitrateIdx = it }
        wireSeg(modeSeg) { modeIdx = it }
        // 初始选中态（seg 选中即回调，顺带赋值）
        selectSeg(backendSeg, backendIdx)
        selectSeg(resSeg, resolutionIdx)
        selectSeg(fpsSeg, fpsIdx)
        selectSeg(brSeg, bitrateIdx)
        selectSeg(modeSeg, modeIdx)

        // 高级选项折叠（默认收起）
        val advancedArea = view.findViewById<LinearLayout>(R.id.advanced_area)
        val advancedToggle = view.findViewById<TextView>(R.id.advanced_toggle)
        advancedToggle.setOnClickListener {
            val open = advancedArea.visibility == View.GONE
            advancedArea.visibility = if (open) View.VISIBLE else View.GONE
            advancedToggle.text = if (open) "高级选项 ▾" else "高级选项 ▸"
        }

        view.findViewById<TextView>(R.id.btn_cancel).setOnClickListener { dismiss() }
        view.findViewById<TextView>(R.id.btn_save).setOnClickListener {
            val host = hostInput.text.toString().trim()
            if (host.isEmpty()) {
                hostInput.error = "请填写主机地址"
                Toast.makeText(ctx, "请填写主机地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val tcp = tcpInput.text.toString().toIntOrNull() ?: SettingsPrefs.defaultTcpPort(ctx)
            val udp = udpInput.text.toString().toIntOrNull() ?: SourceProfile.DEFAULT_UDP_PORT
            val profile = SourceProfile(
                name = nameInput.text.toString().trim(),
                host = host,
                tcpPort = tcp,
                udpPort = udp,
                backend = backends[backendIdx],
                token = tokenInput.text.toString(),
                lastConnectedAt = System.currentTimeMillis(),
                encryptOn = true,
                origin = SourceProfile.ORIGIN_MANUAL,
            )
            SourceProfiles(ctx).save(profile)
            Toast.makeText(ctx, "已保存：${if (profile.name.isBlank()) host else profile.name}", Toast.LENGTH_SHORT).show()
            onSaved?.invoke(profile)
            dismiss()
        }
    }

    /** 分段控件选中态切换（零依赖：背景/文字色按选中位切换；跳过 Space 分隔） */
    private fun wireSeg(container: LinearLayout, onPick: (Int) -> Unit) {
        val items = (0 until container.childCount).map { container.getChildAt(it) }.filterIsInstance<TextView>()
        items.forEachIndexed { idx, _ ->
            container.getChildAt(container.indexOfChild(items[idx])).setOnClickListener {
                items.forEachIndexed { j, c ->
                    val selected = j == idx
                    c.setBackgroundResource(
                        if (selected) R.drawable.bg_seg_selected else R.drawable.bg_seg_normal,
                    )
                    c.setTextColor(
                        if (selected) 0xFF04101F.toInt() else 0xFF94A3B8.toInt(),
                    )
                }
                onPick(idx)
            }
        }
    }

    private fun selectSeg(container: LinearLayout, index: Int) {
        (0 until container.childCount).map { container.getChildAt(it) }
            .filterIsInstance<TextView>()
            .getOrNull(index)
            ?.performClick()
    }
}
