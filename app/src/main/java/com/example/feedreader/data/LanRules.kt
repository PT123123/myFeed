package com.example.feedreader.data

/** 这台电脑在局域网里充当哪个角色：采集端（harvest）还是阅流电脑端（FastAPI）。 */
enum class LanRole {
    RELAY,
    DESKTOP,
    ;

    /** 列表里那行小字：手机搜到的是「一台电脑上的两个不同服务」，不是两台电脑。 */
    val label: String
        get() = when (this) {
            RELAY -> "电脑端采集"
            DESKTOP -> "阅流电脑端"
        }
}

/**
 * 局域网发现里不碰 Android 框架的那半部分：类型名契约、地址可不可用、名字怎么显示。
 *
 * 单独拆出来是因为真正会错的地方都在这里，而不是在 `NsdManager` 的回调编排上：
 * 类型名字面量抄错一个字符，手机就永远搜不到，而那时看不出是拼写问题。
 */
object LanRules {

    /**
     * crawlbase `harvest serve` 广播的类型。
     *
     * 末尾的点属于 FQDN；电脑侧的字面量在 `harvest/mdns.py` 的 `SERVICE_TYPE`，
     * 两边各有一个测试钉着同一个串（`LanRulesTest` / `test_mdns.py`）。
     */
    const val RELAY_SERVICE_TYPE = "_yueliu-relay._tcp."

    /** 阅流电脑端（FastAPI）广播的类型；电脑侧在 `app/services/mdns.py`。 */
    const val DESKTOP_SERVICE_TYPE = "_yueliu-desktop._tcp."

    val SERVICE_TYPES = listOf(RELAY_SERVICE_TYPE, DESKTOP_SERVICE_TYPE)

    /** 广播窗口：这段时间只收「谁在应答」，不去逐个解析。 */
    const val BROADCAST_MS = 5_000L

    /** 单个实例的解析上限；超过就当作它不在了。 */
    const val RESOLVE_MS = 3_000L

    /** 最多解析几个：一屋子设备时不让这一轮搜索无限长下去。 */
    const val MAX_RESOLVE = 8

    /** 按类型认角色；不是我们的两种服务就返回 null（邻居的打印机、电视都会路过这里）。 */
    fun roleOf(serviceType: String?): LanRole? = when (serviceType?.asType()) {
        RELAY_SERVICE_TYPE.asType() -> LanRole.RELAY
        DESKTOP_SERVICE_TYPE.asType() -> LanRole.DESKTOP
        else -> null
    }

    /**
     * 实例名去掉类型后缀，剩下人认得的那截（电脑主机名）。
     *
     * NSD 回调给的 `serviceName` 形如 `DESKTOP-ABC._yueliu-relay._tcp.local.`，
     * 整串摆到手机上等于没摆。
     */
    fun instanceName(serviceName: String, serviceType: String): String {
        val tail = serviceType.asType()
        val head = serviceName.asType()
            .let { if (it.endsWith(tail)) it.dropLast(tail.length).removeSuffix(".") else it }
        return head.ifBlank { "局域网设备" }
    }

    /**
     * 由解析结果拼出可用的基地址；地址不能给手机用就返回 null。
     *
     * `normalizeBase` 在这里复用而不是自己拼字符串：填 IP 那条路走的规整必须和搜索这条路
     * 完全一致（补端口规则、方括号 IPv6、路径剪掉），否则同一台电脑两种方式配出来的
     * 地址不一样，「订阅源换不掉」那类老毛病就会以另一种形式回来。
     */
    fun endpoint(role: LanRole, name: String, host: String?, port: Int): LanEndpoint? {
        if (port !in 1..65535) return null
        val address = host?.takeIf { usableHost(it) } ?: return null
        val authority = if (address.contains(':')) "[$address]:$port" else "$address:$port"
        val base = RelayRules.normalizeBase(authority) ?: return null
        return LanEndpoint(role, name, base)
    }

    /**
     * 这个地址手机点得开吗。
     *
     * 回环不用提（在那台电脑上才打得开）；`169.254`/`fe80` 是链路本地，跨设备要点运气；
     * `0.0.0.0` 是通配地址，写进列表里连不上的一定是它。
     */
    fun usableHost(host: String): Boolean {
        val value = host.trim().removeSurrounding("[", "]")
        if (value.isEmpty()) return false
        if (value == "::1" || value == "0.0.0.0" || value.startsWith("127.")) return false
        if (value.startsWith("169.254.") || value.startsWith("fe80", ignoreCase = true)) return false
        val parts = value.split('.')
        return if (parts.size == 4) parts.all { it.isDigits() && it.toInt() in 0..255 }
        else value.contains(':')
    }

    private fun String.isDigits(): Boolean = isNotEmpty() && all(Char::isDigit) && length <= 3

    /** 类型名和实例名都按 FQDN 写法比：结尾的 `.` 与 `.local` 先统一剪掉。 */
    private fun String.asType(): String =
        trim().removeSuffix(".").removeSuffix(".local").removeSuffix(".")
}

/** 一条搜到的服务：`baseUrl` 是能直接存进设置的那个形态。 */
data class LanEndpoint(
    val role: LanRole,
    val name: String,
    val baseUrl: String,
)
