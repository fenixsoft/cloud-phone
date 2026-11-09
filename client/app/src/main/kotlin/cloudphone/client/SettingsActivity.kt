package cloudphone.client

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 设置偏好存取器（client-ui-redesign 4.3）：
 * SharedPreferences 持久化，新建连接表单高级默认值与 LanScanner 探测端口读取。
 */
object SettingsPrefs {
    private const val FILE = "cloudphone_settings"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 分辨率默认档：0=跟随本机（默认）/ 1=720p / 2=1080p */
    fun resolutionTier(ctx: Context): Int = prefs(ctx).getInt("resolutionTier", 0)
    fun setResolutionTier(ctx: Context, v: Int) {
        prefs(ctx).edit().putInt("resolutionTier", v).apply()
    }

    /** 帧率默认值（30/60，缺省 60） */
    fun fps(ctx: Context): Int = prefs(ctx).getInt("fps", 60)
    fun setFps(ctx: Context, v: Int) {
        prefs(ctx).edit().putInt("fps", v).apply()
    }

    /** 码率默认值 Mbps（4/8/16/20，缺省 8） */
    fun bitrateMbps(ctx: Context): Int = prefs(ctx).getInt("bitrateMbps", 8)
    fun setBitrateMbps(ctx: Context, v: Int) {
        prefs(ctx).edit().putInt("bitrateMbps", v).apply()
    }

    /** 默认 TCP 端口（缺省 22333）：新建连接表单与 LanScanner 探测共用 */
    fun defaultTcpPort(ctx: Context): Int = prefs(ctx).getInt("defaultTcpPort", 22333)
    fun setDefaultTcpPort(ctx: Context, v: Int) {
        prefs(ctx).edit().putInt("defaultTcpPort", v).apply()
    }

    /** 默认 UDP 端口（缺省 22334） */
    fun defaultUdpPort(ctx: Context): Int = prefs(ctx).getInt("defaultUdpPort", 22334)
    fun setDefaultUdpPort(ctx: Context, v: Int) {
        prefs(ctx).edit().putInt("defaultUdpPort", v).apply()
    }
}

/**
 * 设置页（client-ui-redesign 4.3）：六分组（外观占位 / 串流档位默认值 / 传输偏好 /
 * 触控输入占位 / 安全预留 / 关于版本号）。未实现行统一「预留」徽章且未挂点击监听
 * （点击无效，spec「不得伪装为可用功能」）；设置项变更即时写 SharedPreferences，
 * 下次会话生效（会话中项于下次连接生效）。
 */
class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<TextView>(R.id.btn_back).setOnClickListener { finish() }

        // ── 串流档位默认值 ──
        val resSeg = findViewById<LinearLayout>(R.id.resolution_seg)
        wireSeg(resSeg) { SettingsPrefs.setResolutionTier(this, it) }
        selectSeg(resSeg, SettingsPrefs.resolutionTier(this).coerceIn(0, 2))

        val fpsSeg = findViewById<LinearLayout>(R.id.fps_seg)
        wireSeg(fpsSeg) { SettingsPrefs.setFps(this, FPS_VALUES[it]) }
        selectSeg(fpsSeg, FPS_VALUES.indexOf(SettingsPrefs.fps(this)).coerceAtLeast(0))

        val brSeg = findViewById<LinearLayout>(R.id.bitrate_seg)
        wireSeg(brSeg) { SettingsPrefs.setBitrateMbps(this, BITRATE_VALUES[it]) }
        selectSeg(brSeg, BITRATE_VALUES.indexOf(SettingsPrefs.bitrateMbps(this)).coerceAtLeast(0))

        // ── 传输偏好 ──
        wirePortInput(findViewById(R.id.tcp_port_input), SettingsPrefs.defaultTcpPort(this)) {
            SettingsPrefs.setDefaultTcpPort(this, it)
        }
        wirePortInput(findViewById(R.id.udp_port_input), SettingsPrefs.defaultUdpPort(this)) {
            SettingsPrefs.setDefaultUdpPort(this, it)
        }

        // ── 关于 ──
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "—"
        findViewById<TextView>(R.id.version_value).text = version

        // 外观/触控输入/安全/检查更新各行为预留行：XML 已含「预留」徽章，不挂点击监听（点击无效）
    }

    /** 端口输入框：失焦时合法值落盘，非法值回填旧值 */
    private fun wirePortInput(input: EditText, current: Int, save: (Int) -> Unit) {
        input.setText(current.toString())
        input.onFocusChangeListener = android.view.View.OnFocusChangeListener { v, hasFocus ->
            if (!hasFocus) {
                val parsed = input.text.toString().toIntOrNull()
                if (parsed != null && parsed in 1..65535) {
                    save(parsed)
                } else {
                    input.setText(current.toString())
                }
            }
        }
    }

    /** 分段控件选中态切换（与 ManualConnectSheet 同构，零依赖实现） */
    private fun wireSeg(container: LinearLayout, onPick: (Int) -> Unit) {
        for (i in 0 until container.childCount) {
            val idx = i
            container.getChildAt(i).setOnClickListener {
                for (j in 0 until container.childCount) {
                    val c = container.getChildAt(j)
                    val selected = j == idx
                    c.setBackgroundResource(
                        if (selected) R.drawable.bg_seg_selected else R.drawable.bg_seg_normal,
                    )
                    (c as TextView).setTextColor(
                        if (selected) 0xFF04101F.toInt() else 0xFF94A3B8.toInt(),
                    )
                }
                onPick(idx)
            }
        }
    }

    private fun selectSeg(container: LinearLayout, index: Int) {
        container.getChildAt(index).performClick()
    }

    companion object {
        private val FPS_VALUES = intArrayOf(30, 60)
        private val BITRATE_VALUES = intArrayOf(4, 8, 16, 20)
    }
}
