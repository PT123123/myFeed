package io.github.pt123123.semantic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 分词器单测。
 *
 * 主力用例是 [matchesPythonReference]：拿 Python 侧（huggingface `tokenizers`）生成的
 * golden 逐 token 比对。分词错一位，后面的向量就全是错的，所以这条必须严。
 *
 * 其余用例把从参考实现反推出来的**具体规则**钉住，防止以后"顺手优化"改坏。
 */
class BertTokenizerTest {

    private lateinit var tokenizer: BertTokenizer
    private lateinit var idToToken: Map<Int, String>

    @Before
    fun setUp() {
        // JVM 单测的工作目录是模块目录（feedreader/app），直接读源码树里的 asset
        val candidates = listOf(
            File("src/main/assets/semantic/vocab.txt"),
            File("app/src/main/assets/semantic/vocab.txt"),
        )
        val vocabFile = candidates.firstOrNull { it.isFile }
            ?: error(
                "找不到 vocab.txt。试过：\n" +
                    candidates.joinToString("\n") { "  ${it.absolutePath}" } +
                    "\n工作目录：${File("").absolutePath}"
            )

        val lines = vocabFile.readLines(Charsets.UTF_8)
        // 注意 readLines() **会丢掉末尾的空行**，所以是 21128 条而不是 21129 行
        assertEquals("词表 entry 数应为 21128", 21128, lines.count { it.isNotEmpty() })
        idToToken = lines.mapIndexedNotNull { index, token ->
            if (token.isNotEmpty()) index to token else null
        }.toMap()
        tokenizer = BertTokenizer.fromLines(lines.asSequence())
    }

    // ------------------------------------------------------------------ 主用例

    @Test
    fun matchesPythonReference() {
        val mismatches = StringBuilder()
        var failed = 0

        for (case in Golden.TOKEN_CASES) {
            val actual = tokenizer.encode(case.text, Golden.MAX_LENGTH).toList()
            if (actual != case.ids) {
                failed++
                mismatches.append('\n')
                mismatches.append("  原文: ").append(quote(case.text)).append('\n')
                mismatches.append("  期望: ").append(render(case.ids)).append('\n')
                mismatches.append("  实际: ").append(render(actual)).append('\n')
            }
        }

        assertEquals(
            "与 Python 参考实现不一致的用例 $failed / ${Golden.TOKEN_CASES.size} 个：$mismatches",
            0,
            failed,
        )
    }

    // ------------------------------------------------------------------ 具体规则

    /**
     * 这条钉住的是**有意偏离**：原始 tokenizer.json 是 `lowercase=false`，
     * 会让 OpenAI / LLM / React / CSS / Rust 整词变 [UNK]。实测小写归一化后
     * R@5 0.733→0.800、P@1 0.800→1.000、[UNK] 0.7→0.0。
     */
    @Test
    fun lowercaseNormalisationRemovesUnknownTokens() {
        for (word in listOf("OpenAI", "LLM", "React", "CSS", "Rust", "Hello", "GitHub")) {
            val ids = tokenizer.encode(word, Golden.MAX_LENGTH).toList()
            assertTrue(
                "$word 不应该整词变 [UNK]，实际 ${render(ids)}",
                BertTokenizer.UNK_ID !in ids,
            )
        }

        // 大小写不同、归一化后应完全一致
        assertEquals(
            tokenizer.encode("OpenAI 发布模型", Golden.MAX_LENGTH).toList(),
            tokenizer.encode("openai 发布模型", Golden.MAX_LENGTH).toList(),
        )
    }

    @Test
    fun chineseIdeographsBecomeOneTokenEach() {
        assertEquals(
            listOf("大", "语", "言", "模", "型"),
            tokenizer.tokenize("大语言模型"),
        )
    }

    /**
     * 标点隔离的判定是 **ASCII 标点 ∪ Unicode 的 P\\* 类**，不是「中文标点」。
     *
     * 反推依据：`，`(U+FF0C, Po) 被切开，而 `～`(U+FF5E, Sm) 与 `￥`(U+FFE5, Sc)
     * **不**被切开 —— 所以 `¥1,234.56` 会切成 `¥1` + `,` + `234` + `.` + `56`。
     * 如果哪天有人把判定改成「按码点区间」，这条会立刻红。
     */
    @Test
    fun punctuationSplitFollowsUnicodeCategoryNotBlock() {
        val pieces = tokenizer.preTokenize(tokenizer.normalize("¥1,234.56"))
        assertEquals(listOf("¥1", ",", "234", ".", "56"), pieces)

        // `～` 是 Sm、`￥` 是 Sc，都不属于 P*，所以粘在一起；`…` 是 Po，被切开
        val mixed = tokenizer.preTokenize(tokenizer.normalize("…～￥"))
        assertEquals(listOf("…", "～￥"), mixed)

        // ASCII 标点全部单字切开（$ + < = > ^ ` | ~ 属于 Sm/Sc/Sk，靠 ASCII 分支兜住）
        val ascii = tokenizer.preTokenize(tokenizer.normalize("a\$b<c>d^e|f~g"))
        assertEquals(
            listOf("a", "$", "b", "<", "c", ">", "d", "^", "e", "|", "f", "~", "g"),
            ascii,
        )
    }

    @Test
    fun whitespaceIsCollapsedAndControlCharsDropped() {
        // \t \n \r 算空白 → 归一成空格；\u000c(换页符) 是 Cc → 直接丢弃
        assertEquals("abcdef", tokenizer.normalize("abc\u000cdef"))

        // Zs 类空白（U+00A0 不间断空格、U+3000 全角空格）都归一成半角空格
        assertEquals("a b", tokenizer.normalize("a\u00a0b"))
        assertEquals("a b", tokenizer.normalize("a\u3000b"))

        // 各类空白在分词后都应消失，且不产生空 token
        // （注意 normalize 还会给 CJK 字两侧补空格，所以别直接断言 normalize 的结果）
        assertEquals(
            listOf("多", "个", "空", "格", "制", "表", "符"),
            tokenizer.tokenize("多个\t空格\n制表符"),
        )
        assertEquals(
            listOf("不", "间", "断", "空", "格", "全", "角", "空", "格"),
            tokenizer.tokenize("\u00a0不间断空格\u3000全角空格"),
        )
        assertEquals(
            listOf("a", "b"),
            tokenizer.tokenize("  \t\n a \r\n b  "),
        )
    }

    @Test
    fun overlongWordBecomesSingleUnknown() {
        // 超过 100 码点的连续串整词判未知，不逐段尝试
        val ids = tokenizer.encode("a".repeat(150), Golden.MAX_LENGTH).toList()
        assertEquals(listOf(BertTokenizer.CLS_ID, BertTokenizer.UNK_ID, BertTokenizer.SEP_ID), ids)
    }

    @Test
    fun truncationIncludesSpecialTokens() {
        val ids = tokenizer.encode("深度学习".repeat(40), Golden.MAX_LENGTH)
        assertEquals("总长必须正好等于 maxLength", Golden.MAX_LENGTH, ids.size)
        assertEquals(BertTokenizer.CLS_ID, ids.first())
        assertEquals(BertTokenizer.SEP_ID, ids.last())
        assertEquals("最后的正文 token 之后紧跟 [SEP]", BertTokenizer.SEP_ID, ids[ids.size - 1])

        // 缩小 maxLength 也要遵守
        val short = tokenizer.encode("深度学习".repeat(40), 16)
        assertEquals(16, short.size)
    }

    @Test
    fun emptyAndBlankInputsGiveOnlySpecialTokens() {
        for (text in listOf("", "   ", "\t\n")) {
            assertEquals(
                "输入 ${quote(text)}",
                listOf(BertTokenizer.CLS_ID, BertTokenizer.SEP_ID),
                tokenizer.encode(text, Golden.MAX_LENGTH).toList(),
            )
        }
    }

    @Test
    fun emojiAndRareIdeographsArePreservedAsCodePoints() {
        // emoji 是代理对，按码点处理不能劈成半个字符
        val pieces = tokenizer.preTokenize(tokenizer.normalize("测试🎧🚀生僻"))
        assertEquals(listOf("测", "试", "🎧🚀", "生", "僻"), pieces)
    }

    @Test
    fun vocabLoadsWithExpectedSpecialTokenIds() {
        val vocabFile = listOf(
            File("src/main/assets/semantic/vocab.txt"),
            File("app/src/main/assets/semantic/vocab.txt"),
        ).first { it.isFile }
        val vocab = vocabFile.readLines(Charsets.UTF_8)

        assertEquals("[PAD]", vocab[0])
        assertEquals("[UNK]", vocab[BertTokenizer.UNK_ID])
        assertEquals("[CLS]", vocab[BertTokenizer.CLS_ID])
        assertEquals("[SEP]", vocab[BertTokenizer.SEP_ID])
        assertEquals(21128, vocab.count { it.isNotEmpty() })
    }

    @Test
    fun accentStrippingIsOffByDefault() {
        // stripAccents 默认关闭，café / naïve 原样保留（与参考实现 golden 一致）
        val plain = mapOf("[PAD]" to 0, "[UNK]" to 100, "[CLS]" to 101, "[SEP]" to 102)

        val keep = BertTokenizer(plain, lowercase = true, stripAccents = false)
        assertEquals("café naïve", keep.normalize("café naïve"))

        val strip = BertTokenizer(plain, lowercase = true, stripAccents = true)
        assertEquals("cafe naive", strip.normalize("café naïve"))
        // 注意别用 length 比 —— "café" 和 "cafe" 都是 4 个 UTF-16 单元，比长度会假绿
        assertNotEquals("café", strip.normalize("café"))
    }

    // ------------------------------------------------------------------ 辅助

    private fun render(ids: List<Int>): String =
        ids.joinToString(" ") { idToToken[it] ?: "#$it" }

    private fun quote(text: String): String =
        "\"" + text.replace("\\", "\\\\").replace("\n", "\\n")
            .replace("\r", "\\r").replace("\t", "\\t") + "\""
}
