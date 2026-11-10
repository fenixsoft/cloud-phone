package cloudphone.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloudphone.desktop.session.SessionController
import cloudphone.protocol.AppEntry
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 单应用列表（design D9，spec：源端目标 1s 内回传；客户端 3s 无应答判定失败并显示
 * 重试入口，对齐 Android SourceAppsActivity 既有规则）：
 * 磁贴网格（图标占位+应用名，不显示包名）、名称搜索、单应用徽章与退出、
 * 磁贴固化为桌面快捷方式（.lnk 经 PowerShell WScript.Shell 写用户桌面目录）。
 * 列表状态机/搜索过滤/快捷方式命令构造均为纯函数（headless 单测覆盖）。
 */

// ═══════════════ 纯逻辑（headless 可测） ═══════════════

/** 单应用列表状态机 */
object AppsLogic {
    enum class Phase { IDLE, LOADING, LOADED, TIMEOUT }

    /** 列表状态投影（[reason] 在 TIMEOUT 时携带失败说明） */
    data class ListState(
        val phase: Phase = Phase.IDLE,
        val apps: List<AppEntry> = emptyList(),
        val reason: String = "",
    ) {
        /** 加载中（APP_LIST 请求已发出，未到 3s 判定期） */
        val loading: Boolean get() = phase == Phase.LOADING

        /** 超时 → 显示重试入口（spec：3s 无应答判定失败并显示重试入口） */
        val showRetry: Boolean get() = phase == Phase.TIMEOUT
    }

    /** 名称搜索（label 大小写不敏感包含匹配；空查询=全量） */
    fun filterApps(apps: List<AppEntry>, query: String): List<AppEntry> {
        val q = query.trim()
        if (q.isEmpty()) return apps
        return apps.filter { it.label.contains(q, ignoreCase = true) }
    }

    /** 磁贴展示名（不显示包名——spec 明确） */
    fun tileName(entry: AppEntry): String = entry.label.ifBlank { entry.packageName }
}

/** 桌面快捷方式固化（.lnk 经 PowerShell WScript.Shell 写用户桌面目录） */
object DesktopShortcut {
    /** Windows 文件名非法字符（\ / : * ? " < > |） */
    private val ILLEGAL = Regex("""[\\/:*?"<>|]""")

    /** 磁贴名 → 合法文件名（非法字符替换为下划线；空白去除） */
    fun sanitizeFileName(label: String): String =
        ILLEGAL.replace(label.trim(), "_").ifBlank { "app" }

    /** .lnk 文件名 */
    fun lnkFileName(label: String): String = "${sanitizeFileName(label)}.lnk"

    /** 用户桌面目录（user.home/Desktop） */
    fun desktopDir(): Path {
        val home = System.getProperty("user.home") ?: "."
        return Paths.get(home, "Desktop")
    }

    /** 是否 Windows 宿主（非 Windows 不执行 PowerShell 落盘） */
    fun isWindows(): Boolean =
        System.getProperty("os.name")?.lowercase()?.contains("windows") == true

    /**
     * 构造创建 .lnk 的 PowerShell 命令（WScript.Shell COM；参数经引号转义）。
     * - [targetExe]：快捷方式目标（本客户端 exe / 启动脚本）；
     * - [lnkPath]：桌面 .lnk 绝对路径；
     * - [arguments]：直达单应用模式参数（如 `--app <packageName>`）。
     */
    fun buildPowerShellLnkCommand(targetExe: String, lnkPath: String, arguments: String, workingDir: String): String {
        fun q(s: String) = "\"" + s.replace("\"", "\\\"") + "\""
        return listOf(
            "\$ws = New-Object -ComObject WScript.Shell",
            "\$sc = \$ws.CreateShortcut(${q(lnkPath)})",
            "\$sc.TargetPath = ${q(targetExe)}",
            "\$sc.Arguments = ${q(arguments)}",
            "\$sc.WorkingDirectory = ${q(workingDir)}",
            "\$sc.Description = ${q("CloudPhone 单应用直达")}",
            "\$sc.Save()",
        ).joinToString("; ")
    }

    /** 直达单应用模式的启动参数 */
    fun singleAppArguments(packageName: String): String = "--app $packageName"

    /** 固化入口：写桌面 .lnk（非 Windows 或失败返回 null；不抛出） */
    fun create(label: String, packageName: String, targetExe: String): Path? {
        if (!isWindows()) return null
        return try {
            val dir = desktopDir()
            Files.createDirectories(dir)
            val lnk = dir.resolve(lnkFileName(label))
            val cmd = buildPowerShellLnkCommand(
                targetExe = targetExe,
                lnkPath = lnk.toAbsolutePath().toString(),
                arguments = singleAppArguments(packageName),
                workingDir = Paths.get(targetExe).parent?.toAbsolutePath()?.toString() ?: ".",
            )
            val proc = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", cmd)
                .redirectErrorStream(true)
                .start()
            proc.waitFor()
            if (proc.exitValue() == 0 && Files.exists(lnk)) lnk else null
        } catch (_: Exception) {
            null
        }
    }
}

// ═══════════════ Compose UI ═══════════════

/** 单应用列表根组件 */
@Composable
fun AppsScreen(
    state: AppsLogic.ListState,
    query: String,
    onQueryChange: (String) -> Unit,
    onRetry: () -> Unit,
    onLaunch: (AppEntry) -> Unit,
    onPin: (AppEntry) -> Unit,
    onExitSingleApp: () -> Unit,
    singleAppLabel: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(DesktopBg).padding(16.dp)) {
        // 标题行 + 单应用徽章/退出
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("单应用", color = DesktopText, fontSize = 16.sp)
            Spacer(Modifier.width(10.dp))
            if (singleAppLabel.isNotBlank()) {
                Row(
                    Modifier.background(AccentBlue.copy(alpha = 0.16f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(6.dp).background(AccentCyan, CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text("单应用·${singleAppLabel}", color = AccentCyan, fontSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("退出", color = SemanticWarn, fontSize = 12.sp,
                        modifier = Modifier.clickable(onClickLabel = "退出单应用") { onExitSingleApp() })
                }
            }
            Spacer(Modifier.weight(1f))
            // 名称搜索
            Box(Modifier.width(180.dp).background(DesktopSurfaceHigh, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 5.dp)) {
                androidx.compose.foundation.text.BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    textStyle = androidx.compose.ui.text.TextStyle(color = DesktopText, fontSize = 13.sp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        when {
            state.loading -> Text("正在获取应用列表（源端目标 1s 内回传）…", color = DesktopTextDim, fontSize = 13.sp)
            state.showRetry -> {
                // 3s 无应答 → 重试入口
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("应用列表获取失败${if (state.reason.isNotBlank()) "：${state.reason}" else ""}", color = SemanticWarn, fontSize = 13.sp)
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier.background(AccentBlue, RoundedCornerShape(6.dp))
                            .clickable(onClickLabel = "重试") { onRetry() }
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                    ) { Text("重试", color = DesktopText, fontSize = 12.sp) }
                }
            }
            else -> {
                val apps = AppsLogic.filterApps(state.apps, query)
                if (apps.isEmpty()) {
                    Text(if (state.apps.isEmpty()) "源端未返回可启动应用" else "无匹配应用", color = DesktopTextDim, fontSize = 13.sp)
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        apps.chunked(4).forEach { rowApps ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                rowApps.forEach { entry ->
                                    AppTile(entry = entry, onLaunch = { onLaunch(entry) }, onPin = { onPin(entry) })
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}

/** 应用磁贴：图标占位 + 应用名（不显示包名）+ 连接/固化动作 */
@Composable
private fun AppTile(entry: AppEntry, onLaunch: () -> Unit, onPin: () -> Unit) {
    Row(
        Modifier.width(200.dp).background(DesktopSurface, RoundedCornerShape(8.dp))
            .clickable(onClickLabel = "启动 ${AppsLogic.tileName(entry)}") { onLaunch() }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 图标占位（圆形首字）
        Box(
            Modifier.size(34.dp).background(DesktopOutline, RoundedCornerShape(17.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(AppsLogic.tileName(entry).take(1).uppercase(), color = AccentCyan, fontSize = 14.sp)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(AppsLogic.tileName(entry), color = DesktopText, fontSize = 13.sp, maxLines = 1)
            Text("固化桌面快捷方式", color = DesktopTextDim, fontSize = 11.sp,
                modifier = Modifier.clickable(onClickLabel = "固化 ${AppsLogic.tileName(entry)}") { onPin() })
        }
    }
}
