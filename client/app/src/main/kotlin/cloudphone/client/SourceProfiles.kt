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
) {
    /** 唯一键：host+tcpPort+backend */
    val key: String get() = keyOf(host, tcpPort, backend)

    /** 下拉展示名（不含 token） */
    val displayName: String
        get() = if (name.isBlank()) "$host:$tcpPort（$backend）" else "$name（$host:$tcpPort·$backend）"

    companion object {
        fun keyOf(host: String, tcpPort: Int, backend: String): String = "$host:$tcpPort:$backend"

        internal fun fromJson(o: JSONObject): SourceProfile = SourceProfile(
            name = o.optString("name"),
            host = o.getString("host"),
            tcpPort = o.getInt("tcpPort"),
            udpPort = o.getInt("udpPort"),
            backend = o.getString("backend"),
            token = o.getString("token"),
            lastConnectedAt = o.getLong("lastConnectedAt"),
        )

        internal fun toJson(p: SourceProfile): JSONObject = JSONObject().apply {
            put("name", p.name)
            put("host", p.host)
            put("tcpPort", p.tcpPort)
            put("udpPort", p.udpPort)
            put("backend", p.backend)
            put("token", p.token)
            put("lastConnectedAt", p.lastConnectedAt)
        }
    }

    /** 日志安全形态：host/端口/后端/时间可见，token 恒不出现 */
    override fun toString(): String =
        "SourceProfile(${displayName}, udp=$udpPort, lastConnectedAt=$lastConnectedAt)"
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
