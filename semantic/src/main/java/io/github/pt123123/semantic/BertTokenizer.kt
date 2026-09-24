package io.github.pt123123.semantic

/**
 * BERT WordPiece 分词器（bge-small-zh-v1.5 用）。
 *
 * 输出与 huggingface `tokenizers`（Python 侧参考实现）逐 token 对齐，
 * 但有一处**有意偏离默认值**：
 *
 * 原始 `tokenizer.json` 的 BertNormalizer 是 `lowercase=false`，官方
 * `tokenizer_config.json` 也写着 `do_lower_case: false`。结果是 OpenAI / LLM /
 * React / CSS / Rust 这类**大写开头的英文词整词变 `[UNK]`**（词表里只有 `hello`
 * 没有 `Hello`，只有 `open`+`##ai` 没有 `openai`）。
 *
 * 实测主动小写归一化后：R@5 0.733→0.800、P@1 0.800→1.000、MRR 0.900→1.000、
 * 平均 `[UNK]` 数 0.7→0.0。所以端侧开启 [lowercase]，并关闭 [stripAccents]
 * （保持 `café`/`naïve` 原样）。
 *
 * 管线（严格按此顺序）：cleanText → 中文两侧补空格 → 小写 → 剥重音 →
 * 按空白切分 → 标点隔离 → WordPiece 贪心最长匹配。
 */
class BertTokenizer(
    private val vocab: Map<String, Int>,
    private val lowercase: Boolean = true,
    private val stripAccents: Boolean = false,
) {

    /**
     * 编码成 token id 序列，含首尾的 `[CLS]` / `[SEP]`，已按 [maxLength] 截断。
     * 截断计入两个特殊符号（即正文最多 [maxLength] - 2 个 token）。
     */
    fun encode(text: String, maxLength: Int = DEFAULT_MAX_LENGTH): IntArray {
        val tokens = tokenize(text, maxLength)
        val ids = IntArray(tokens.size + 2)
        ids[0] = CLS_ID
        for (i in tokens.indices) ids[i + 1] = vocab[tokens[i]] ?: UNK_ID
        ids[tokens.size + 1] = SEP_ID
        return ids
    }

    /** 只做分词，不含特殊符号、不截断。单测用来和参考实现比 token 串。 */
    internal fun tokenize(text: String): List<String> {
        val out = ArrayList<String>()
        for (piece in preTokenize(normalize(text))) {
            out += wordPiece(piece)
        }
        return out
    }

    private fun tokenize(text: String, maxLength: Int): List<String> {
        val budget = (maxLength - 2).coerceAtLeast(0)
        val out = ArrayList<String>(budget.coerceAtMost(64))
        for (piece in preTokenize(normalize(text))) {
            for (token in wordPiece(piece)) {
                if (out.size >= budget) return out
                out += token
            }
        }
        return out
    }

    // ------------------------------------------------------------------ 规范化

    internal fun normalize(text: String): String {
        // 1) cleanText：丢控制字符、把各类空白统一成半角空格
        val cleaned = StringBuilder(text.length + 8)
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            i += Character.charCount(cp)
            when {
                cp == 0 || cp == REPLACEMENT_CHAR -> Unit          // 直接丢弃
                isControl(cp) -> Unit                              // Cc/Cf/Cs/Co/Cn 丢弃
                isWhitespace(cp) -> cleaned.append(' ')
                else -> cleaned.append(Character.toChars(cp))
            }
        }

        // 2) 中日韩表意文字两侧补空格，让它们各自成词
        val padded = StringBuilder(cleaned.length + 16)
        var j = 0
        while (j < cleaned.length) {
            val cp = Character.codePointAt(cleaned, j)
            j += Character.charCount(cp)
            if (isCjkIdeograph(cp)) {
                padded.append(' ')
                padded.append(Character.toChars(cp))
                padded.append(' ')
            } else {
                padded.append(Character.toChars(cp))
            }
        }

        var out = padded.toString()
        if (lowercase) out = out.lowercase()
        if (stripAccents) out = stripAccents(out)
        return out
    }

    // ------------------------------------------------------------------ 预切分

    /**
     * 按空白切分（空白丢弃），再把标点单独切成一个 token。
     *
     * 标点的判定不是「ASCII 标点 ∪ 中文标点」，而是 **ASCII 标点 ∪ Unicode 的 P\* 类**。
     * 这条是从参考实现反推出来的：`，`(U+FF0C, Po) 会被切开，而 `～`(U+FF5E, Sm)
     * 和 `￥`(U+FFE5, Sc) **不会** —— 实测 `…～￥` 切出来是 `…` + `～￥`，
     * 而 `¥1,234.56` 切出来是 `¥1` + `,` + `234` + `.` + `56`。
     */
    internal fun preTokenize(normalized: String): List<String> {
        val out = ArrayList<String>()
        // normalize 已把所有空白归一成半角空格，这里按空格切即可
        for (chunk in normalized.split(' ')) {
            if (chunk.isEmpty()) continue

            var start = 0
            var i = 0
            while (i < chunk.length) {
                val cp = Character.codePointAt(chunk, i)
                val width = Character.charCount(cp)
                if (isPunctuation(cp)) {
                    if (i > start) out += chunk.substring(start, i)
                    out += String(Character.toChars(cp))
                    start = i + width
                }
                i += width
            }
            if (start < chunk.length) out += chunk.substring(start)
        }
        return out
    }

    // ------------------------------------------------------------------ WordPiece

    /**
     * 贪心最长匹配。先按码点拆成单字数组再切，避免在 UTF-16 下标和码点下标之间反复换算
     * （两者在含 emoji 的串上不等长，混用极易越界）。
     */
    private fun wordPiece(piece: String): List<String> {
        val chars = codePointStrings(piece)
        if (chars.size > MAX_INPUT_CHARS_PER_WORD) return listOf(UNK_TOKEN)

        val out = ArrayList<String>(4)
        var start = 0
        while (start < chars.size) {
            var end = chars.size
            var matched: String? = null
            while (start < end) {
                val sub = concat(chars, start, end)
                val candidate = if (start > 0) CONTINUING_PREFIX + sub else sub
                if (vocab.containsKey(candidate)) {
                    matched = candidate
                    break
                }
                end--
            }
            // 一段都匹配不上，整词判为未知
            if (matched == null) return listOf(UNK_TOKEN)
            out += matched
            start = end
        }
        return out
    }

    /** 不用 `String.codePoints()` —— 那是 API 24 才有的，本项目 minSdk 21。 */
    private fun codePointStrings(s: String): Array<String> {
        val parts = ArrayList<String>(s.length)
        var i = 0
        while (i < s.length) {
            val cp = Character.codePointAt(s, i)
            val width = Character.charCount(cp)
            parts += s.substring(i, i + width)
            i += width
        }
        return parts.toTypedArray()
    }

    private fun concat(parts: Array<String>, start: Int, end: Int): String {
        val sb = StringBuilder(end - start)
        for (k in start until end) sb.append(parts[k])
        return sb.toString()
    }

    companion object {
        const val UNK_TOKEN = "[UNK]"
        const val CONTINUING_PREFIX = "##"
        const val UNK_ID = 100
        const val CLS_ID = 101
        const val SEP_ID = 102
        const val PAD_ID = 0

        /** 与模型 `max_position_embeddings` 一致，也是 bge 官方推荐的短文本上限。 */
        const val DEFAULT_MAX_LENGTH = 128

        private const val MAX_INPUT_CHARS_PER_WORD = 100
        private const val REPLACEMENT_CHAR = 0xFFFD

        /**
         * 从 `vocab.txt` 的每一行构建词表：**行号即 token id**。
         * （已核对：21128 行与 tokenizer.json 的 vocab 逐条一致，`[UNK]`=100、`[CLS]`=101、`[SEP]`=102。）
         */
        fun fromLines(lines: Sequence<String>): BertTokenizer {
            val vocab = HashMap<String, Int>(32768)
            lines.forEachIndexed { index, raw ->
                val token = raw.trimEnd('\r', '\n')
                if (token.isNotEmpty()) vocab[token] = index
            }
            return BertTokenizer(vocab)
        }

        /** Unicode 类别以 C 开头（Cc/Cf/Cs/Co/Cn），但制表/换行/回车除外——它们算空白。 */
        private fun isControl(cp: Int): Boolean {
            if (cp == '\t'.code || cp == '\n'.code || cp == '\r'.code) return false
            return when (Character.getType(cp)) {
                Character.CONTROL.toInt(),
                Character.FORMAT.toInt(),
                Character.SURROGATE.toInt(),
                Character.PRIVATE_USE.toInt(),
                Character.UNASSIGNED.toInt(),
                -> true
                else -> false
            }
        }

        /** 半角空格、制表、换行、回车，或 Unicode 类别 Zs。 */
        private fun isWhitespace(cp: Int): Boolean =
            cp == ' '.code || cp == '\t'.code || cp == '\n'.code || cp == '\r'.code ||
                Character.getType(cp) == Character.SPACE_SEPARATOR.toInt()

        /** 中日韩表意文字（含扩展区），这些字要各自成词。 */
        private fun isCjkIdeograph(cp: Int): Boolean =
            cp in 0x4E00..0x9FFF ||
                cp in 0x3400..0x4DBF ||
                cp in 0x20000..0x2A6DF ||
                cp in 0x2A700..0x2B73F ||
                cp in 0x2B740..0x2B81F ||
                cp in 0x2B820..0x2CEAF ||
                cp in 0xF900..0xFAFF ||
                cp in 0x2F800..0x2FA1F

        /** ASCII 标点，或 Unicode 类别 P\*（Po/Ps/Pe/Pd/Pc/Pi/Pf）。 */
        private fun isPunctuation(cp: Int): Boolean {
            if (cp < 0x80) {
                return (cp in 0x21..0x2F) || (cp in 0x3A..0x40) ||
                    (cp in 0x5B..0x60) || (cp in 0x7B..0x7E)
            }
            return when (Character.getType(cp)) {
                Character.CONNECTOR_PUNCTUATION.toInt(),
                Character.DASH_PUNCTUATION.toInt(),
                Character.START_PUNCTUATION.toInt(),
                Character.END_PUNCTUATION.toInt(),
                Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
                Character.FINAL_QUOTE_PUNCTUATION.toInt(),
                Character.OTHER_PUNCTUATION.toInt(),
                -> true
                else -> false
            }
        }

        /**
         * 剥重音：先分解成 NFD，再丢掉非间距记号（类别 Mn）。
         * 端侧默认不启用（[stripAccents] = false），保留原文更稳。
         */
        private fun stripAccents(text: String): String {
            val decomposed = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
            val sb = StringBuilder(decomposed.length)
            var i = 0
            while (i < decomposed.length) {
                val cp = Character.codePointAt(decomposed, i)
                i += Character.charCount(cp)
                if (Character.getType(cp) != Character.NON_SPACING_MARK.toInt()) {
                    sb.append(Character.toChars(cp))
                }
            }
            return sb.toString()
        }
    }
}
