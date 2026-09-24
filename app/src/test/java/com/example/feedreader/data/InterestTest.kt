package com.example.feedreader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InterestsTest {

    @Test
    fun sanitizeCollapsesWhitespace() {
        assertEquals("AI 大模型", Interests.sanitize("  AI \t 大模型  "))
        assertEquals("a b", Interests.sanitize("a\u3000\u00a0\tb"))
        assertEquals("", Interests.sanitize("   \t\n "))
    }

    /**
     * 关键词里混进制表符会破坏持久化格式（用 `\t` 分隔字段），
     * 所以 sanitize 必须把它清掉。
     */
    @Test
    fun sanitizeRemovesFieldSeparators() {
        val cleaned = Interests.sanitize("前端\t开发")
        assertFalse("不应残留制表符：$cleaned", cleaned.contains('\t'))
        assertFalse("不应残留换行", Interests.sanitize("前端\n开发").contains('\n'))
        assertEquals("前端 开发", cleaned)
    }

    @Test
    fun sanitizeTruncatesOverlongKeyword() {
        val long = "词".repeat(100)
        assertEquals(Interests.MAX_KEYWORD_LENGTH, Interests.sanitize(long).length)
    }

    @Test
    fun idIsCaseInsensitive() {
        assertEquals(Interests.normalizeId("Rust"), Interests.normalizeId("  RUST "))
        assertEquals(Interests.normalizeId("AI 大模型"), Interests.normalizeId("ai 大模型"))
    }

    @Test
    fun weightSnapsToNearestStep() {
        assertEquals(0.5f, Interests.snapWeight(0.55f), 0f)
        assertEquals(1f, Interests.snapWeight(1.2f), 0f)
        assertEquals(1.5f, Interests.snapWeight(1.6f), 0f)
        assertEquals(2f, Interests.snapWeight(1.9f), 0f)
        assertEquals(2f, Interests.snapWeight(99f), 0f)
    }

    @Test
    fun defaultInterestsAreValidAndUnique() {
        val defaults = Interests.DEFAULT
        assertTrue(defaults.isNotEmpty())
        assertEquals(
            "默认兴趣不该有重复",
            defaults.size,
            defaults.map { it.id }.distinct().size,
        )
        defaults.forEach {
            assertTrue("关键词不应为空", it.keyword.isNotBlank())
            assertTrue("关键词长度应合规", it.keyword.length <= Interests.MAX_KEYWORD_LENGTH)
            assertTrue("权重应在范围内", it.weight in Interests.MIN_WEIGHT..Interests.MAX_WEIGHT)
        }
    }

    /**
     * 钉住 2026-09-25 重排后的默认形状：[EFFECTIVE_COUNT] 个启用 + 4 个关着备用 = [MAX_COUNT]。
     * 改默认兴趣时这个测试会提醒你别把启用数弄超（超了首页有人在 EFFECTIVE_COUNT 之外被静默忽略）。
     */
    @Test
    fun defaultInterestShape() {
        val defaults = Interests.DEFAULT
        assertEquals("总数应刚好等于 MAX_COUNT", Interests.MAX_COUNT, defaults.size)
        assertEquals("启用的应刚好等于 EFFECTIVE_COUNT", Interests.EFFECTIVE_COUNT, defaults.count { it.enabled })
        assertEquals(
            "启用的不该有重复 id",
            defaults.filter { it.enabled }.size,
            defaults.filter { it.enabled }.map { it.id }.distinct().size,
        )
        // 新旧默认必须不同，否则 InterestStore 的迁移判断（是否还停在旧默认）会永远为真
        assertNotEquals("新旧默认不能相同", Interests.OLD_DEFAULT, defaults)
    }

    // ------------------------------------------------------------------ 添加校验

    @Test
    fun blankKeywordIsRejected() {
        assertNotEquals(null, Interests.addError("", emptyList()))
        assertNotEquals(null, Interests.addError("   \t ", emptyList()))
    }

    /**
     * 纯标点要挡住：它切不出任何词项、也编码不出有意义的向量，
     * 加进去只会变成一个永远匹配不到东西的死兴趣。
     */
    @Test
    fun punctuationOnlyKeywordIsRejected() {
        assertNotEquals(null, Interests.addError("...", emptyList()))
        assertNotEquals(null, Interests.addError("。。！", emptyList()))
        // 中英混排的「C++」有字母，必须放行
        assertEquals(null, Interests.addError("C++", emptyList()))
    }

    /** 「Rust」和「 rust 」是同一条 —— 去重按归一化后的 id，不能按原始字符串。 */
    @Test
    fun duplicateIsRejectedIgnoringCaseAndWhitespace() {
        val existing = listOf(Interest("Rust"))
        assertNotEquals(null, Interests.addError("rust", existing))
        assertNotEquals(null, Interests.addError("  RUST  ", existing))
        assertEquals(null, Interests.addError("Rustlang", existing))
    }

    @Test
    fun tooManyInterestsIsRejected() {
        val full = List(Interests.MAX_COUNT) { Interest("词$it") }
        assertNotEquals(null, Interests.addError("再加一个", full))

        // 删掉一个之后就该能加了 —— 上限是按当前条数算的，不是一次性的
        assertEquals(null, Interests.addError("再加一个", full.drop(1)))
    }

    /** 超长关键词会被截断而不是拒绝：用户意图是清楚的，没必要让他重打。 */
    @Test
    fun overlongKeywordIsTruncatedNotRejected() {
        assertEquals(null, Interests.addError("词".repeat(100), emptyList()))
        assertEquals(
            Interests.MAX_KEYWORD_LENGTH,
            Interests.sanitize("词".repeat(100)).length,
        )
    }

    @Test
    fun validKeywordIsAccepted() {
        assertEquals(null, Interests.addError("向量数据库", emptyList()))
        assertEquals(null, Interests.addError("  向量数据库  ", emptyList()))
    }
}

class InterestCodecTest {

    @Test
    fun roundTripsThroughText() {
        val original = listOf(
            Interest("AI 大模型", 2f, true),
            Interest("Rust", 1f, true),
            Interest("量化交易", 0.5f, false),
        )
        assertEquals(original, InterestCodec.decode(InterestCodec.encode(original)))
    }

    @Test
    fun emptyListRoundTrips() {
        assertEquals(emptyList<Interest>(), InterestCodec.decode(InterestCodec.encode(emptyList())))
    }

    /**
     * 一行坏掉不该让整份列表消失 —— 用户的兴趣是手动维护的，丢一次要重打一遍。
     */
    @Test
    fun badLinesAreSkippedNotFatal() {
        val text = """
            1.0	1	Rust
            这不是一行合法数据
            1.5	1	AI 大模型
            abc	1	坏权重
            1.0	1	
        """.trimIndent()

        val decoded = InterestCodec.decode(text)
        assertEquals(2, decoded.size)
        assertEquals(listOf("Rust", "AI 大模型"), decoded.map { it.keyword })
    }

    @Test
    fun duplicatesKeepFirstOccurrence() {
        val text = "2.0\t1\tRust\n0.5\t0\tRUST"
        val decoded = InterestCodec.decode(text)
        assertEquals(1, decoded.size)
        assertEquals(2f, decoded.single().weight, 0f)
        assertTrue(decoded.single().enabled)
    }

    @Test
    fun outOfRangeWeightIsClamped() {
        val decoded = InterestCodec.decode("99\t1\tRust\n0.01\t1\tGo")
        assertEquals(Interests.MAX_WEIGHT, decoded[0].weight, 0f)
        assertEquals(Interests.MIN_WEIGHT, decoded[1].weight, 0f)
    }

    @Test
    fun blankTextDecodesToEmpty() {
        assertEquals(emptyList<Interest>(), InterestCodec.decode(""))
        assertEquals(emptyList<Interest>(), InterestCodec.decode("   \n  "))
    }

    @Test
    fun encodedTextIsHumanReadable() {
        val text = InterestCodec.encode(listOf(Interest("Rust", 1.5f, false)))
        assertEquals("1.5\t0\tRust", text)
    }
}

class SynonymDictTest {

    private val dict = SynonymDict.DEFAULT

    @Test
    fun expandIsCaseInsensitive() {
        assertEquals(dict.expand("css"), dict.expand("CSS"))
        assertEquals(dict.expand("前端"), dict.expand(" 前端 "))
    }

    @Test
    fun unknownTermExpandsToNothing() {
        assertEquals(emptyList<String>(), dict.expand("一个完全没收录的词"))
    }

    /** 「前端」这组是最要紧的 —— 实测纯语义就是在这里漏掉的。 */
    @Test
    fun frontendGroupCoversCommonFrameworks() {
        val expanded = dict.expand("前端")
        assertTrue(expanded.contains("css"))
        assertTrue(expanded.contains("react"))
        assertTrue(expanded.contains("javascript"))
    }

    @Test
    fun quantTradingGroupCoversFinanceTerms() {
        val expanded = dict.expand("量化交易")
        assertTrue(expanded.contains("回测"))
        assertTrue(expanded.contains("因子"))
        assertTrue(expanded.contains("a股"))
    }

    @Test
    fun tableHasNoEmptyOrSelfReferentialEntries() {
        dict.expand("ai").forEach { assertTrue("扩展词不应为空", it.isNotBlank()) }
        // 扩展词不应包含自己
        assertTrue(dict.expand("rust").none { it == "rust" })
        assertTrue(dict.expand("css").none { it == "css" })
    }
}
