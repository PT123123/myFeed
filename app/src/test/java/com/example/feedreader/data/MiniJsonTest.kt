package com.example.feedreader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 那一份最小 JSON 读取。
 *
 * 用它而不是 `org.json` 是因为后者在本地 JVM 单测里是 stub（一调就抛 `RuntimeException("Stub!")`），
 * 而回包形状属于另一个仓库，恰恰最需要被单测钉住。代价是自己写的解析器得自己测 ——
 * 下面这些用例盖的都是「解析错了不会崩、只会说一句假话」那一类。
 */
class MiniJsonTest {

    @Test
    fun `对象数组嵌套`() {
        val outer = MiniJson.objectOf(MiniJson.parse("""{"a": [1, "two", false, null, {"b": 3}]}"""))!!
        val list = MiniJson.listOf(outer["a"])!!

        assertEquals(5, list.size)
        assertEquals(1.0, list[0])
        assertEquals("two", list[1])
        assertEquals(false, list[2])
        assertNull(list[3])
        assertEquals(3, MiniJson.int(MiniJson.objectOf(list[4])!!, "b"))
    }

    @Test
    fun `空对象与空数组`() {
        assertEquals(emptyMap<String, Any?>(), MiniJson.parse("{}"))
        assertEquals(emptyList<Any?>(), MiniJson.parse("[]"))
        assertEquals(mapOf("k" to emptyMap<String, Any?>()), MiniJson.parse("""{"k":{}}"""))
    }

    /** 对面回的是中文消息；反斜杠转义那几种也要读得对。 */
    @Test
    fun `字符串里的转义`() {
        val map = MiniJson.objectOf(
            MiniJson.parse("""{"msg": "行1\n行2\t引号\"反斜杠\\斜杠\/中"}"""),
        )!!

        assertEquals("行1\n行2\t引号\"反斜杠\\斜杠/中", map["msg"])
        // JSON 的 \\uXXXX 转义：有些代理会把非 ASCII 写成这样，读不出来就是乱码
        // （这里把那个反斜杠拼出来，是为了避开「测试文件自己先被转义一道」的误会）
        val escaped = """{"k": "a""" + "\\" + "u2014" + """"}"""
        assertEquals("a—", MiniJson.string(MiniJson.objectOf(MiniJson.parse(escaped))!!, "k"))
    }

    /** 数字全按 Double 收：对面 `time.time()` 给的是带小数的秒。 */
    @Test
    fun `数字小数负数与指数`() {
        val map = MiniJson.objectOf(
            MiniJson.parse("""{"i": 42, "f": 0.3, "neg": -7.5, "big": 1790531674.3091083}"""),
        )!!

        assertEquals(42, MiniJson.int(map, "i"))
        // 0.3 秒夹成 0：界面上那句「采了 0 秒」是真短，而不是显示成空白
        assertEquals(0, MiniJson.int(map, "f"))
        assertEquals(-7, MiniJson.int(map, "neg"))
        assertEquals(1790531674L, (map["big"] as Double).toLong())
    }

    /**
     * epoch 秒要换成毫秒。
     *
     * 这颗是 control 断言：本 App 全线时间戳是毫秒，对面是 `time.time()`。忘了换算不会崩，
     * 只会把「3 分钟前」说成 1970 年 —— 那种假话必须钉住。
     */
    @Test
    fun `时间戳换成毫秒`() {
        val map = MiniJson.objectOf(
            MiniJson.parse("""{"started_at": 1790531674.3091083, "missing": null}"""),
        )!!

        assertEquals(1790531674309L, MiniJson.millis(map, "started_at"))
        assertNull(MiniJson.millis(map, "missing"))
        assertNull(MiniJson.millis(map, "not_there"))
        assertNull(MiniJson.millis(mapOf("started_at" to "1790531674"), "started_at"))
    }

    /** 字段类型不对时给默认值而不是抛：回包多一两个字段、少一个字段不该让整片界面空白。 */
    @Test
    fun `取字段的兜底`() {
        val map = MiniJson.objectOf(
            MiniJson.parse(
                """{"s": "文本", "b": true, "num": 12, "numStr": "12", "arr": [1], "obj": {"k": 1}}""",
            ),
        )!!

        assertEquals("文本", MiniJson.string(map, "s"))
        assertEquals("", MiniJson.string(map, "absent"))
        assertEquals("缺省", MiniJson.string(map, "absent", "缺省"))
        // 数字字段不硬转成字符串：宁可给空，让调用方走自己的兜底
        assertEquals("", MiniJson.string(map, "num"))
        assertEquals(true, MiniJson.boolean(map, "b"))
        assertEquals(false, MiniJson.boolean(map, "absent"))
        assertEquals(12, MiniJson.int(map, "num"))
        // 加了引号的数字照收：对面 json.dumps 不会这么写，但中间那个代理可能会
        assertEquals(12, MiniJson.int(map, "numStr"))
        assertEquals(0, MiniJson.int(map, "s"))
        assertNull(MiniJson.objectOf(map["arr"]))
        assertNull(MiniJson.listOf(map["obj"]))
        assertNull(MiniJson.objectOf(null))
    }

    /**
     * 读不懂就得抛，不许猜半个。
     *
     * 三种典型：末尾还有东西（说明中间某处停了）、缺引号、数字写歪。调用方统一按
     * 「连上了但读不懂它的回包」报出去，所以这里必须区分得开 —— 静默返回 null 会让
     * 上面那层误报成「这台电脑没在跑」。
     */
    @Test
    fun `形状不对一律抛`() {
        assertThrows(IllegalArgumentException::class.java) { MiniJson.parse("""{"a": 1} trailing""") }
        assertThrows(IllegalArgumentException::class.java) { MiniJson.parse("""{"a": }""") }
        assertThrows(IllegalArgumentException::class.java) { MiniJson.parse("""{"a": 1,}""") }
        assertThrows(IllegalArgumentException::class.java) { MiniJson.parse("""{"a": [1""") }
        assertThrows(IllegalArgumentException::class.java) { MiniJson.parse("nope") }
        assertThrows(IllegalArgumentException::class.java) { MiniJson.parse("") }
        assertTrue(
            "错误信息要带位置或字符，不然线上无从判断",
            runCatching { MiniJson.parse("<html>") }.exceptionOrNull()!!.message!!.isNotEmpty(),
        )
    }

    /** 空白与换行随对面 json.dumps 怎么写，都一样读。 */
    @Test
    fun `缩进无所谓`() {
        val pretty = """
            {
              "ok": true,
              "data": { "plan": [ { "feed": "zhihu-a" } ] }
            }
        """.trimIndent()
        val map = MiniJson.objectOf(MiniJson.parse(pretty))!!

        assertEquals(true, map["ok"])
        val plan = MiniJson.listOf(MiniJson.objectOf(map["data"])!!["plan"])!!
        assertEquals("zhihu-a", MiniJson.string(MiniJson.objectOf(plan.single())!!, "feed"))
    }
}
