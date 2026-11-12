package cloudphone.source

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机自启（capture-lifecycle）：BOOT_COMPLETED → 拉起 MainActivity(AUTO_RECOVER) →
 * onResume 消费 captureLost 自动弹系统授权框 → 设备侧 UIBTN 自愈点「立即开始」→ 管线就绪。
 *
 * 不直接 startForegroundService(StreamService)：API 34 后台起 mediaProjection 型 FGS 受限，
 * 经前台 MainActivity 起服务与常规授权共用顺序铁律（先 FGS 再 getMediaProjection），单一代码路径。
 *
 * 前置：SYSTEM_ALERT_WINDOW appops=allow（后台起 Activity 的 BAL 豁免；bootstrap_adb.sh 登记，
 * 跨重启持久）；自愈点弹窗依赖 root 助手（重启后需 `bootstrap_adb.sh helper` 拉起，或宿主 watch 兜底）。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(StreamService.EXTRA_AUTO_RECOVER, true),
            )
            android.util.Log.i("cloudphone-source", "boot: 自动恢复入口已拉起（MainActivity AUTO_RECOVER）")
        } catch (e: Exception) {
            // BAL 被拒（appops 未登记等）：记录明确原因，宿主 bootstrap/watch 仍是兜底路径
            android.util.Log.w("cloudphone-source", "boot: 拉起失败（SAW 未授权？）: ${e.message}")
        }
    }
}
