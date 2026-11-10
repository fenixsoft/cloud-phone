package cloudphone.desktop.profiles

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 源端档案（Android SourceProfiles 桌面移植）：
 * 连接成功自动入库的源端连接信息 + 档位偏好。
 *
 * - 存储：`~/.cloudphone/profiles.json` JSON 数组（kotlinx-serialization；
 *   数据量 ≤ 数十条，不用数据库，design D1 依赖最小化）；
 * - upsert 键 = host + tcpPort + backend（同键更新，不产生重复档案）；
 * - token 属敏感信息：仅存本机用户目录文件，本类任何展示路径（toString/displayName/日志）
 *   MUST NOT 输出 token 明文（spec source-profiles「档案数据边界」）。
 */
@Serializable
data class SourceProfile(
    val name: String = "",
    val host: String,
    val tcpPort: Int,
    /** 老档案缺 udpPort 键时兜底 22334（不迁移存储格式） */
    val udpPort: Int = DEFAULT_UDP_PORT,
    val backend: String,
    val token: String,
    val lastConnectedAt: Long,
    /** 端到端加密意图（缺省开——老档案 JSON 无此键自动 true） */
    val encryptOn: Boolean = true,
    /** 来源标记："manual"=手输/表单保存，"auto"=连接成功自动入库（缺省手输） */
    val origin: String = ORIGIN_MANUAL,
    /** 档位偏好（档位循环/自定义档记录；空=未设置） */
    val presetLabel: String = "",
) {
    /** 唯一键：host+tcpPort+backend */
    val key: String get() = keyOf(host, tcpPort, backend)

    /** 来源展示名（「手输」/「自动」） */
    val originLabel: String get() = if (origin == ORIGIN_AUTO) "自动" else "手输"

    /** 展示名（不含 token） */
    val displayName: String
        get() = if (name.isBlank()) "$host:$tcpPort（$backend）" else "$name（$host:$tcpPort·$backend）"

    /** 日志安全形态：host/端口/后端/来源/档位/时间可见，token 恒不出现 */
    override fun toString(): String =
        "SourceProfile(${displayName}, udp=$udpPort, origin=$originLabel, preset=$presetLabel, lastConnectedAt=$lastConnectedAt)"

    companion object {
        const val ORIGIN_MANUAL = "manual"
        const val ORIGIN_AUTO = "auto"
        const val DEFAULT_UDP_PORT = 22334

        fun keyOf(host: String, tcpPort: Int, backend: String): String = "$host:$tcpPort:$backend"
    }
}

/**
 * 档案存取器（进程内可多实例；每次读写全量落盘，调用线程执行文件 IO，
 * 连接流程统一在工作线程调用）。
 *
 * @param file 存储文件（可注入；默认 ~/.cloudphone/profiles.json）
 */
class DesktopSourceProfiles(
    private val file: Path = defaultFile(),
) {

    private val json = Json {
        ignoreUnknownKeys = true // 新增键向后兼容旧读取方
        encodeDefaults = true
    }

    /** 全量档案（按 lastConnectedAt 倒序，最近使用在前） */
    fun list(): List<SourceProfile> {
        if (!Files.exists(file)) return emptyList()
        val raw = try {
            Files.readString(file)
        } catch (_: Exception) {
            return emptyList()
        }
        val out = try {
            json.decodeFromString(ListSerializer(SourceProfile.serializer()), raw)
        } catch (_: Exception) {
            // 损坏文件：视为空档案（不抛出、不崩溃；首次 upsert 即重写修复）
            emptyList()
        }
        return out.sortedByDescending { it.lastConnectedAt }
    }

    /** 按 host+tcpPort+backend 插入或更新（存在则整条更新 + 新 lastConnectedAt 语义由调用方给出） */
    fun upsert(profile: SourceProfile): SourceProfile {
        val current = list().filterNot { it.key == profile.key }
        val next = listOf(profile) + current
        persist(next)
        return profile
    }

    /** 连接成功自动入库（origin=auto + 最近时间；档位偏好保留原值——按键合并） */
    fun recordConnected(
        host: String,
        tcpPort: Int,
        udpPort: Int,
        backend: String,
        token: String,
        encryptOn: Boolean = true,
        atMs: Long = System.currentTimeMillis(),
    ): SourceProfile {
        val existing = findByKey(host, tcpPort, backend)
        return upsert(
            SourceProfile(
                name = existing?.name.orEmpty(),
                host = host,
                tcpPort = tcpPort,
                udpPort = udpPort,
                backend = backend,
                token = token,
                lastConnectedAt = atMs,
                encryptOn = encryptOn,
                origin = SourceProfile.ORIGIN_AUTO,
                presetLabel = existing?.presetLabel.orEmpty(),
            ),
        )
    }

    /** 档位偏好更新（按键定位；仅改 presetLabel 与 lastConnectedAt 不动其他字段） */
    fun savePresetPreference(host: String, tcpPort: Int, backend: String, presetLabel: String): Boolean {
        val current = findByKey(host, tcpPort, backend) ?: return false
        upsert(current.copy(presetLabel = presetLabel))
        return true
    }

    /** 删除指定键档案；返回是否确实删除了一条 */
    fun delete(host: String, tcpPort: Int, backend: String): Boolean {
        val key = SourceProfile.keyOf(host, tcpPort, backend)
        val current = list()
        val next = current.filterNot { it.key == key }
        if (next.size == current.size) return false
        persist(next)
        return true
    }

    fun count(): Int = list().size

    fun findByKey(host: String, tcpPort: Int, backend: String): SourceProfile? =
        list().firstOrNull { it.key == SourceProfile.keyOf(host, tcpPort, backend) }

    /**
     * 按 host+TCP 端口匹配档案（不限后端）：首页发现卡与既有档案匹配标「已保存」即用它；
     * 取最近使用的匹配项。
     */
    fun findByHostPort(host: String, tcpPort: Int): SourceProfile? =
        list().firstOrNull { it.host == host && it.tcpPort == tcpPort }

    /** 暴露列表增删改查：新建表单保存即 save、卡片删除即 delete */
    fun save(profile: SourceProfile): SourceProfile = upsert(profile)

    private fun persist(profiles: List<SourceProfile>) {
        Files.createDirectories(file.parent)
        Files.writeString(file, json.encodeToString(ListSerializer(SourceProfile.serializer()), profiles))
    }

    companion object {
        /** 默认存储路径：~/.cloudphone/profiles.json */
        fun defaultFile(): Path {
            val home = System.getProperty("user.home") ?: "."
            return Paths.get(home, ".cloudphone", "profiles.json")
        }
    }
}
