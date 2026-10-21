package com.example.feedreader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 局域网发现（mDNS/NSD）里不碰框架的那半。
 *
 * 这组用例存在的主要理由是**跨仓库契约**：`_yueliu-relay._tcp` 与
 * `_yueliu-desktop._tcp` 这两个字面量，电脑端分别写在 crawlbase 的 `harvest/mdns.py`
 * 和阅流电脑端的 `app/services/mdns.py` 里。抄错一个字符的后果不是报错，而是手机上
 * 「搜不到电脑」—— 那是最难查的一类 bug，所以三边各钉一个测试。
 *
 * 另一半要紧的是「搜到的地址必须和手填的地址同形」：见 [同一个地址两种填法得到同一个基址]。
 */
class LanRulesTest {

    // ---------------------------------------------------------------- 契约字面量

    @Test
    fun `服务类型名与电脑端公告的一致`() {
        assertEquals("_yueliu-relay._tcp.", LanRules.RELAY_SERVICE_TYPE)
        assertEquals("_yueliu-desktop._tcp.", LanRules.DESKTOP_SERVICE_TYPE)
        assertEquals(listOf(LanRules.RELAY_SERVICE_TYPE, LanRules.DESKTOP_SERVICE_TYPE), LanRules.SERVICE_TYPES)
    }

    @Test
    fun `按类型认得出角色，别人家的设备一律不认`() {
        assertEquals(LanRole.RELAY, LanRules.roleOf(LanRules.RELAY_SERVICE_TYPE))
        // 系统回调给的是完整 FQDN，末尾点和不带 .local 的两种写法都会遇到
        assertEquals(LanRole.RELAY, LanRules.roleOf("_yueliu-relay._tcp.local."))
        assertEquals(LanRole.DESKTOP, LanRules.roleOf("_yueliu-desktop._tcp.local"))
        assertNull(LanRules.roleOf("_airplay._tcp.local."))
        assertNull(LanRules.roleOf(null))
    }

    @Test
    fun `实例名剪掉类型后只剩主机名`() {
        assertEquals(
            "DESKTOP-READER",
            LanRules.instanceName("DESKTOP-READER._yueliu-relay._tcp.local.", LanRules.RELAY_SERVICE_TYPE),
        )
        assertEquals(
            "DESKTOP-READER",
            LanRules.instanceName("DESKTOP-READER._yueliu-desktop._tcp.local.", LanRules.DESKTOP_SERVICE_TYPE),
        )
        // 带空格的显示器名也得留下人认得的那截
        assertEquals("Living Room TV", LanRules.instanceName("Living Room TV._yueliu-relay._tcp.", "_yueliu-relay._tcp."))
        // 认不出的写法不该把整条 FQDN 甩到手机屏幕上
        assertEquals("局域网设备", LanRules.instanceName("_yueliu-relay._tcp.local.", "_yueliu-relay._tcp."))
    }

    // ---------------------------------------------------------------- 地址可用性

    @Test
    fun `回环_通配_链路本地都不算能连的地址`() {
        listOf(
            "127.0.0.1", "127.5.5.5", "0.0.0.0", "::1", "[::1]",
            "169.254.12.9", "fe80::1", "", "  ", "abc", "256.1.1.1",
        ).forEach { assertFalse("$it 不该被当成可用地址", LanRules.usableHost(it)) }

        listOf("192.168.0.2", "10.0.2.2", "172.16.0.9", "fd00::1234").forEach {
            assertTrue("$it 是局域网里真用得上的写法", LanRules.usableHost(it))
        }
    }

    @Test
    fun `拼基址要端口和地址都对`() {
        val relay = LanRules.endpoint(LanRole.RELAY, "DESKTOP-A", "192.168.0.2", 8099)
        assertEquals("http://192.168.0.2:8099", relay?.baseUrl)
        assertEquals("DESKTOP-A", relay?.name)
        assertEquals(LanRole.RELAY, relay?.role)

        assertNull(LanRules.endpoint(LanRole.RELAY, "x", null, 8099))
        assertNull(LanRules.endpoint(LanRole.RELAY, "x", "127.0.0.1", 8099))
        assertNull(LanRules.endpoint(LanRole.RELAY, "x", "192.168.0.2", 0))
        assertNull(LanRules.endpoint(LanRole.RELAY, "x", "192.168.0.2", 70000))
    }

    @Test
    fun `同一个地址两种填法得到同一个基址`() {
        // 搜索这条路必须和手填走同一个规整函数，否则换 IP 时两条路的差别的会表现成
        // 「订阅源对不上」那类老毛病
        val fromDiscovery = LanRules.endpoint(LanRole.RELAY, "PC", "192.168.0.2", 8099)?.baseUrl
        assertEquals(RelayRules.normalizeBase("192.168.0.2:8099"), fromDiscovery)
        assertEquals(RelayRules.normalizeBase("192.168.0.2"), fromDiscovery)
    }

    @Test
    fun `IPv6 字面量补方括号再交给规整`() {
        val endpoint = LanRules.endpoint(LanRole.DESKTOP, "PC", "fd00::1234", 8000)
        assertEquals("http://[fd00::1234]:8000", endpoint?.baseUrl)
    }

    @Test
    fun `角色标签是给手机看的中文`() {
        assertEquals("电脑端采集", LanRole.RELAY.label)
        assertEquals("阅流电脑端", LanRole.DESKTOP.label)
    }
}
