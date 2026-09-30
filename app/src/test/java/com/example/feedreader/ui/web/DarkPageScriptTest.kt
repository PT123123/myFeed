package com.example.feedreader.ui.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 暗色注入脚本的纯字符串部分。
 *
 * 真正"页面上黑不黑"要设备上看，但有三件事在 JVM 里就能钉死，而且错了必然在真机上炸：
 * 转义（CSS 里全是大括号和引号）、占位符（漏替换等于把 `@@RULES@@` 当 JS 执行）、
 * 以及专项样式别再叠一层 `filter`（那会打掉站内的吸顶/吸底栏）。
 */
class DarkPageScriptTest {

    @Test
    fun `控制组：普通字符串原样加引号`() {
        assertEquals("\"html{color:red}\"", DarkPageScript.jsString("html{color:red}"))
    }

    @Test
    fun `引号 反斜杠和换行都被转义`() {
        assertEquals("\"a\\\\b\"", DarkPageScript.jsString("a\\b"))
        assertEquals("\"a\\\"b\"", DarkPageScript.jsString("a\"b"))
        assertEquals("\"a\\nb\"", DarkPageScript.jsString("a\nb"))
    }

    /**
     * U+2028 / U+2029 是 JS 的行终止符：原样写进脚本会把字符串字面量从中间截断，
     * 整份注入脚本变成语法错误 —— 而它只会出现在中文文案里，真机上表现为
     * "暗色根本没生效"，日志里也看不出所以然。
     */
    @Test
    fun `行终止符转义成 u 序列而不是字符本身`() {
        val ls = 0x2028.toChar()
        val ps = 0x2029.toChar()
        val escaped = DarkPageScript.jsString("左${ls}中${ps}右")
        assertEquals("\"左\\u2028中\\u2029右\"", escaped)
        assertFalse(escaped.contains(ls))
        assertFalse(escaped.contains(ps))
    }

    @Test
    fun `生成的脚本里没有未替换的占位符`() {
        val script = DarkPageScript.build()
        assertFalse("占位符漏替换：$script", script.contains("@@"))
    }

    @Test
    fun `站点表和反相样式都进了脚本`() {
        val script = DarkPageScript.build()
        assertTrue(script.contains("m.weibo.cn"))
        assertTrue(script.contains("weibo.com"))
        assertTrue(script.contains("filter:invert(1)"))
        // 专项 CSS 是字符串字面量，选择器里的点号不该被转义掉
        assertTrue(script.contains(".lite-topbar"))
    }

    @Test
    fun `自定义规则表会覆盖默认表`() {
        val script = DarkPageScript.build(
            rules = listOf(SiteRule(hosts = listOf("example.test"), css = "body{background:#000}")),
            invertCss = "html{filter:none}",
            minValidSamples = 3,
            darkLuminanceThreshold = 0.5,
        )
        assertTrue(script.contains("example.test"))
        assertTrue(script.contains("html{filter:none}"))
        assertFalse(script.contains("m.weibo.cn"))
        assertTrue(script.contains("if (cnt < 3) return confident"))
        assertTrue(script.contains("(sum / cnt) < 0.5"))
    }

    /**
     * 微博专项必须是**配色覆盖**，不能带 `filter`：`html` 上有 filter 会成为
     * fixed 定位的包含块，实测 m.weibo.cn 那三个固定元素（吸顶栏 `.lite-topbar`、
     * 播放器 `.video-player.mwb-layer`、底部输入框 `.lite-page-editor`）会跟着页面滚走。
     */
    @Test
    fun `站点专项样式不用反相滤镜`() {
        assertFalse(SiteDarkStyles.WEIBO_CSS.contains("filter"))
        assertTrue(SiteDarkStyles.INVERT_CSS.contains("filter:invert(1)"))
        // 媒体元素反回来这条得有，否则通用路径上图片视频全串色
        assertTrue(SiteDarkStyles.INVERT_CSS.contains("img,video,canvas"))
    }

    @Test
    fun `专项样式里的类名都要有实测出处`() {
        val used = Regex("""\.([a-zA-Z][\w-]*)""").findAll(SiteDarkStyles.WEIBO_CSS)
            .map { it.groupValues[1] }
            .toSet()
        assertTrue("至少得有几条选择器", used.isNotEmpty())
        assertEquals(
            "这些类名和实测集合对不上，别凭印象加规则：",
            MEASURED_WEIBO_CLASSES,
            used,
        )
    }

    @Test
    fun `默认表里的站点都有非空配色和宿主`() {
        assertTrue(SiteDarkStyles.RULES.isNotEmpty())
        for (rule in SiteDarkStyles.RULES) {
            assertTrue(rule.hosts.isNotEmpty())
            assertTrue(rule.css.isNotBlank())
            rule.hosts.forEach {
                assertTrue("宿主该是小写域名：$it", it == it.lowercase() && !it.contains('/'))
            }
        }
    }

    /**
     * 把生成的脚本真交给 node 解析一遍。
     *
     * 转义写坏的后果不是"报错"而是**静默不生效**：`evaluateJavascript` 拿到语法错误的
     * 脚本只会吞掉，页面上表现为暗色没起作用，日志里一行都没有。这里让它在测试里就叫。
     * 没有 node 的环境跳过（本机跑得过就行，这项不是构建依赖）。
     */
    @Test
    fun `生成的脚本能被 node 解析`() {
        val node = sequenceOf("node", "node.exe").firstOrNull { which(it) }
            ?: return org.junit.Assume.assumeTrue("环境里没有 node，跳过语法校验", false)
        val file = File.createTempFile("darkpage", ".js").apply {
            writeText(DarkPageScript.build(), Charsets.UTF_8)
            deleteOnExit()
        }
        val result = ProcessBuilder(node, "--check", file.absolutePath)
            .redirectErrorStream(true)
            .start()
            .apply { outputStream.close() }
            .let { it.waitFor() to it.inputStream.readBytes().toString(Charsets.UTF_8) }
        assertEquals("node --check 失败：${result.second}", 0, result.first)
    }

    private fun which(command: String): Boolean = runCatching {
        ProcessBuilder("where", command).start().waitFor() == 0
    }.getOrDefault(false)
}

/**
 * 从 `m.weibo.cn/status/<mid>`（statusLite 页）的实测算样式里抄下来的类名集合。
 *
 * [DarkPageScriptTest] 拿它当白名单：以后往里加类名之前，得先在真页面上量到，
 * 而不是照着别的站点的命名习惯猜。
 */
private val MEASURED_WEIBO_CLASSES = setOf(
    "card",
    "card-wrap",
    "from",
    "lite-page-editor",
    "lite-topbar",
    "m-add-box",
    "m-auto-box3",
    "m-auto-list",
    "m-font",
    "m-followBtn",
    "m-icon",
    "m-img-box",
    "m-panel",
    "m-text-box",
    "m-text-cut",
    "surl-text",
    "time",
    "weibo-main",
    "weibo-og",
    "weibo-text",
    "weibo-top",
)
