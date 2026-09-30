package com.example.feedreader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置里那几个 0 的语义（0 天 = 永久、0 MB = 不限）如果显示错了，
 * 用户会以为缓存不会被清。这里钉住。
 */
class SettingsLabelsTest {

    @Test
    fun `保留天数 0 显示成永久保留`() {
        assertEquals("永久保留", SettingsStore.retentionLabel(0))
        assertEquals("7 天", SettingsStore.retentionLabel(7))
    }

    @Test
    fun `容量上限 0 显示成不限制`() {
        assertEquals("不限制", SettingsStore.sizeLabel(0))
        assertEquals("20 MB", SettingsStore.sizeLabel(20))
    }

    @Test
    fun `默认值本身是合法的可选项`() {
        assertTrue(SettingsStore.RETENTION_CHOICES.contains(SettingsStore.DEFAULT_RETENTION_DAYS))
        assertTrue(SettingsStore.SIZE_CHOICES_MB.contains(SettingsStore.DEFAULT_MAX_MB))
    }

    @Test
    fun `选项列表没有重复项`() {
        assertEquals(SettingsStore.RETENTION_CHOICES.distinct(), SettingsStore.RETENTION_CHOICES)
        assertEquals(SettingsStore.SIZE_CHOICES_MB.distinct(), SettingsStore.SIZE_CHOICES_MB)
        assertEquals(WebDarkMode.CHOICES.distinct(), WebDarkMode.CHOICES)
    }

    /**
     * 暗色这一项是**默认开着**的（跟随系统），所以偏好文件里读不出值时必须落在
     * FOLLOW_SYSTEM 上：认不出来就关掉暗色，用户看到的是"升级之后网页变白了"。
     */
    @Test
    fun `网页暗色的默认值是跟随系统`() {
        assertTrue(WebDarkMode.CHOICES.contains(WebDarkMode.DEFAULT))
        assertEquals(WebDarkMode.FOLLOW_SYSTEM, WebDarkMode.DEFAULT)
    }

    @Test
    fun `网页暗色认不出的 id 回到默认而不是抛异常`() {
        assertEquals(WebDarkMode.FOLLOW_SYSTEM, WebDarkMode.of(null))
        assertEquals(WebDarkMode.FOLLOW_SYSTEM, WebDarkMode.of(""))
        assertEquals(WebDarkMode.FOLLOW_SYSTEM, WebDarkMode.of("night_mode_v2"))
        // 控制组：三个合法 id 各自解析回自己
        WebDarkMode.CHOICES.forEach { assertEquals(it, WebDarkMode.of(it.id)) }
    }

    @Test
    fun `网页暗色的 id 和文案都不能空`() {
        WebDarkMode.CHOICES.forEach {
            assertTrue("id 不能空：$it", it.id.isNotBlank())
            assertTrue("文案不能空：$it", it.label.isNotBlank())
        }
    }
}
