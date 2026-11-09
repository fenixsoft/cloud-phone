package cloudphone.client

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 源端档案（client-ux-and-input-latency 1.1）：连接成功自动入库的源端连接信息。
 *
 * - 存储：SharedPreferences(MODE_PRIVATE) 内 JSON 数组（org.json，Android 自带；
 *   依赖最小化，数据量 ≤ 数十条，不用 Room/DataStore，design D1）；
 * - upsert 键 = host + tcpPort + backend（同键更新，不产生重复档案）；
 * - token 属敏感信息：仅存应用私有目录，本类任何路径（含 toString/日志）MUST NOT
 *   输出 token 明文（spec source-profiles「档案数据边界」）。
 */
data class SourceProfile(
    val name: String,
    val host: String,
    val tcpPort: Int,
    val udpPort: Int,
    val backend: String,
    val token: String,
    val lastConnectedAt: Long,
    /** 端到端加密意图（p2p-crossnet-traversal 3.2，末位追加带缺省——老档案 JSON 无此键自动 true） */
    val encryptOn: Boolean = true,
    /**
     * 手动来源标记（client-ui-redesign 2.4）："manual"=手输/表单保存，"auto"=连接成功自动入库。
     * 末位追加带缺省——老档案 JSON 无此键读作「手输」（与 encryptOn 兼容先例一致）。
     */
    val origin: String = ORIGIN_MANUAL,
) {
    /** 唯一键：host+tcpPort+backend */
    val key: String get() = keyOf(host, tcpPort, backend)

    /** 来源展示名（「手输」/「自动」） */
    val originLabel: String get() = if (origin == ORIGIN_AUTO) "自动" else "手输"

    /** 下拉展示名（不含 token） */
    val displayName: String
        get() = if (name.isBlank()) "$host:$tcpPort（$backend）" else "$name（$host:$tcpPort·$backend）"

    companion object {
        /** 手动来源标记值（client-ui-redesign 2.4）：手输建档 */
        const val ORIGIN_MANUAL = "manual"
        /** 手动来源标记值：连接成功自动入库 */
        const val ORIGIN_AUTO = "auto"
        /** 老档案缺 udpPort 键时的读取兜底值（与既有表单默认一致） */
        const val DEFAULT_UDP_PORT = 22334

        fun keyOf(host: String, tcpPort: Int, backend: String): String = "$host:$tcpPort:$backend"

        internal fun fromJson(o: JSONObject): SourceProfile = SourceProfile(
            name = o.optString("name"),
            host = o.getString("host"),
            tcpPort = o.getInt("tcpPort"),
            // 老档案缺 udpPort 键时兜底 22334（client-ui-redesign 2.4，不迁移存储格式）
            udpPort = o.optInt("udpPort", DEFAULT_UDP_PORT),
            backend = o.getString("backend"),
            token = o.getString("token"),
            lastConnectedAt = o.getLong("lastConnectedAt"),
            encryptOn = o.optBoolean("encryptOn", true), // 老档案无此键 → 缺省开（向后兼容）
            origin = o.optString("origin", ORIGIN_MANUAL), // 老档案无此键 → 缺省「手输」
        )

        internal fun toJson(p: SourceProfile): JSONObject = JSONObject().apply {
            put("name", p.name)
            put("host", p.host)
            put("tcpPort", p.tcpPort)
            put("udpPort", p.udpPort)
            put("backend", p.backend)
            put("token", p.token)
            put("lastConnectedAt", p.lastConnectedAt)
            put("encryptOn", p.encryptOn)
            put("origin", p.origin)
        }
    }

    /** 日志安全形态：host/端口/后端/来源/时间可见，token 恒不出现 */
    override fun toString(): String =
        "SourceProfile(${displayName}, udp=$udpPort, origin=$originLabel, lastConnectedAt=$lastConnectedAt)"
}

/**
 * 档案存取器（进程内可多实例，SharedPreferences 保证落盘一致）。
 * 线程约束：upsert/delete/list 均在调用线程执行 SharedPreferences 同步读写，
 * 连接页统一在工作线程调用（与连接流程同一线程上下文）。
 */
class SourceProfiles(context: Context) {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 全量档案（按 lastConnectedAt 倒序，最近使用在前） */
    fun list(): List<SourceProfile> {
        val raw = prefs.getString(KEY_LIST, null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrElse { return emptyList() }
        val out = ArrayList<SourceProfile>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            runCatching { SourceProfile.fromJson(o) }.getOrNull()?.let { out.add(it) }
        }
        out.sortByDescending { it.lastConnectedAt }
        return out
    }

    /** 按 host+tcpPort+backend 插入或更新（存在则整条更新 + 新 lastConnectedAt） */
    fun upsert(profile: SourceProfile): SourceProfile {
        val current = list().filterNot { it.key == profile.key }
        val next = listOf(profile) + current
        persist(next)
        return profile
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
     * 按 host+TCP 端口匹配档案（不限后端；client-ui-redesign 2.2/2.4）：
     * 首页发现卡与既有档案匹配标「已保存」即用它；取最近使用的匹配项。
     */
    fun findByHostPort(host: String, tcpPort: Int): SourceProfile? =
        list().firstOrNull { it.host == host && it.tcpPort == tcpPort }

    /** 暴露列表增删改查（client-ui-redesign 2.4）：新建表单保存即 upsert、卡片删除即 delete */
    fun save(profile: SourceProfile): SourceProfile = upsert(profile)

    private fun persist(profiles: List<SourceProfile>) {
        val arr = JSONArray()
        profiles.forEach { arr.put(SourceProfile.toJson(it)) }
        prefs.edit().putString(KEY_LIST, arr.toString()).apply()
    }

    companion object {
        private const val FILE = "cloudphone_source_profiles"
        private const val KEY_LIST = "profiles"
    }
}
