package com.example.feedreader.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * 一轮局域网搜索的结果。
 *
 * `unresolved` 单独摆出来是因为「搜到了但叫不开」和「一台都没搜到」是两件不同的事：
 * 前者说明广播通了、那台机器在但不肯报地址（多半是它自己出了岔子），后者通常是不在
 * 同一个 WiFi、或者电脑那边 5353 的入站被防火墙挡了。把两种混成一句「没找到」，
 * 用户就会去重试第三条路。
 */
data class LanScan(
    val endpoints: List<LanEndpoint>,
    val unresolved: Int,
    val note: String? = null,
)

/**
 * 用系统 NSD（mDNS/DNS-SD）找局域网里的阅流电脑端与采集端。
 *
 * 走系统框架而不是自己发 5353 组播包：安卓的多播成员关系由系统统一管，第三方 socket
 * 想收 5353 在部分机型上直接被内核丢掉，而那种失败在手机上是「搜不到」四个字，查不到根因。
 *
 * 一轮 [search] 的节拍是刻意分段的 —— **先只收公告，收完再逐个解析**：
 * `NsdManager` 在浏览还挂着的时候发起解析，在一些机型上会以 11
 * （INVALID_LISTENER_OPERATION）失败，报错时机又刚好落在用户唯一能看到结果的那一行字上。
 * 广播窗口因此是固定的 [LanRules.BROADCAST_MS]，而不是一直搜到有人点停止。
 */
@Suppress("DEPRECATION")  // 带 Executor 的新重载是 API 33，minSdk 21 只能用旧的那套
class LanDiscovery(context: Context) {

    private val appContext = context.applicationContext
    private val nsd: NsdManager? = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifi: WifiManager? = runCatching {
        appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }.getOrNull()

    /**
     * 搜一次：广播窗口内记下谁在应答，然后停止浏览、逐个解析成能连的基地址。
     *
     * 挂起函数，调用方负责给超时与取消；[types] 参数只为测试留口，正式调用都用默认两类。
     */
    suspend fun search(types: List<String> = LanRules.SERVICE_TYPES): LanScan {
        val manager = nsd
            ?: return LanScan(emptyList(), 0, "这台设备没有系统 mDNS，只能手填地址或扫码")
        return withContext(Dispatchers.IO) {
            // 引用计数式多播锁：NSD 自己会持有，但这台设备上的 WiFi 省电策略不一定放过
            // 我们的窗口；没有 WiFi 接口（网线、模拟器）时拿不到锁，也不能让搜索失败。
            val lock = runCatching {
                wifi?.createMulticastLock("yueliu-lan")?.also {
                    it.setReferenceCounted(true)
                    it.acquire()
                }
            }.getOrNull()
            try {
                broadcast(manager, types)
            } finally {
                runCatching { lock?.release() }
            }
        }
    }

    private suspend fun broadcast(manager: NsdManager, types: List<String>): LanScan {
        val found = ConcurrentHashMap.newKeySet<String>()
        // 一个监听器对象只能注册一次，所以每个类型各配一个（同一个实例注册两次会抛异常）
        val collectors = types.associateWith { Collector(it, found) }
        var started = 0
        collectors.forEach { (type, listener) ->
            runCatching { manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
                .onSuccess { started++ }
                .onFailure { Log.i(TAG, "浏览 $type 起不来：${it.message}") }
        }
        if (started == 0) {
            return LanScan(emptyList(), 0, "系统没让手机在局域网里广播搜索，只能手填地址或扫码")
        }
        delay(LanRules.BROADCAST_MS)
        collectors.values.forEach { listener ->
            runCatching { manager.stopServiceDiscovery(listener) }
                .onFailure { Log.i(TAG, "停止浏览没干净：${it.message}") }
        }
        return resolve(manager, found.toList())
    }

    private suspend fun resolve(manager: NsdManager, targets: List<String>): LanScan {
        val attempts = targets.take(LanRules.MAX_RESOLVE)
        val endpoints = attempts.mapNotNull { key ->
            val (type, name) = key.split(SEPARATOR, limit = 2)
            val info = resolveOne(manager, name, type) ?: return@mapNotNull null
            LanRules.endpoint(
                role = LanRules.roleOf(type) ?: return@mapNotNull null,
                name = LanRules.instanceName(name, type),
                host = info.host?.hostAddress,
                port = info.port,
            )
        }.distinctBy { it.role to it.baseUrl }
        return LanScan(
            // 先按角色分组、组内按机器名：两个入口各筛一类，同类的多台电脑得比得出先后
            endpoints = endpoints.sortedWith(compareBy({ it.role.ordinal }, { it.name })),
            unresolved = attempts.size - endpoints.size,
        )
    }

    /** 单个实例的解析：超时或系统报错都只丢掉这一条，不影响别人。 */
    private suspend fun resolveOne(manager: NsdManager, name: String, type: String): NsdServiceInfo? =
        withTimeoutOrNull(LanRules.RESOLVE_MS) {
            val query = NsdServiceInfo().apply {
                serviceName = name
                serviceType = type
            }
            suspendCancellableCoroutine { continuation ->
                val answered = AtomicBoolean(false)
                fun settle(result: NsdServiceInfo?) {
                    if (answered.compareAndSet(false, true)) continuation.resume(result)
                }
                val listener = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) {
                        Log.i(TAG, "解析 $service 失败：code=$errorCode")
                        settle(null)
                    }

                    override fun onServiceResolved(service: NsdServiceInfo) = settle(service)
                }
                // 解析监听器同样每次都得新建；注册失败当作它不在了
                runCatching { manager.resolveService(query, listener) }.onFailure { settle(null) }
            }
        }

    /**
     * 一类服务的公告收集器。
     *
     * 类型名由构造参数带着，而不是从回调里的 `NsdServiceInfo.serviceType` 读：旧重载
     * （API 33 之前那个）的 `onServiceFound` 只有一个入参，机型上那个字段可能是空的，
     * 而空类型会让 [LanRules.roleOf] 返回 null，这条公告就凭空消失了。
     */
    private class Collector(
        private val serviceType: String,
        private val found: MutableSet<String>,
    ) : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(regType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.i(TAG, "浏览 $serviceType 失败：code=$errorCode")
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

        override fun onServiceFound(service: NsdServiceInfo) {
            // 只记名字不就地解析：见类文档里那个「浏览中解析会被某些机型拒掉」的坑
            val name = service.serviceName
            if (name != null) found += "$serviceType$SEPARATOR$name"
        }

        override fun onServiceLost(service: NsdServiceInfo) = Unit
    }

    private companion object {
        const val TAG = "LanDiscovery"

        /** 集合里一条记录的 type 与 name 之间的分隔符；mDNS 名字里不会出现 `@`。 */
        const val SEPARATOR = "@"
    }
}
