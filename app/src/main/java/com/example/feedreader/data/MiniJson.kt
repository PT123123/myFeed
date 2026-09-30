package com.example.feedreader.data

/**
 * 只够读 PC 命令口那份回包的最小 JSON 解析。
 *
 * 为什么不用 `org.json`：它在本地 JVM 单测里是 stub（一调就抛 `RuntimeException("Stub!")`），
 * 而这一层恰恰是最需要被单测钉住的 —— 回包形状来自另一个仓库，改了我这边不知道。
 * 项目里 [InterestCodec] / [SourceCodec] 早就因此改用文本编解码，这里是同一个理由。
 *
 * 支持对象 / 数组 / 字符串 / 数字 / `true` `false` `null`，返回
 * `Map<String, Any?>` / `List<Any?>` / `String` / `Double` / `Boolean` / null。
 * 解析不出来一律抛 [IllegalArgumentException]：调用方按「读不懂这台电脑的回包」处理，
 * 不要猜半个结果给用户看。
 */
object MiniJson {

    fun parse(text: String): Any? {
        val reader = Reader(text)
        reader.skipSpace()
        val value = reader.value()
        reader.skipSpace()
        if (!reader.atEnd) throw IllegalArgumentException("回包末尾还有没读掉的内容")
        return value
    }

    /** 取字符串字段，缺类型不符给默认值：回包多一两个字段不该让整片界面报错。 */
    fun string(map: Map<*, *>, key: String, fallback: String = ""): String =
        (map[key] as? String) ?: fallback

    fun int(map: Map<*, *>, key: String, fallback: Int = 0): Int =
        (map[key] as? Double)?.toInt() ?: (map[key] as? String)?.toIntOrNull() ?: fallback

    fun boolean(map: Map<*, *>, key: String, fallback: Boolean = false): Boolean =
        map[key] as? Boolean ?: fallback

    /**
     * 读一个 epoch 秒字段，返回**毫秒**。
     *
     * 对面写的是 Python `time.time()`（秒，带小数），而本 App 全线时间戳是毫秒 ——
     * 直接搬过去的话「开始于 X 前」会算成 1970 年，且不会崩，只会说一句假话。
     */
    fun millis(map: Map<*, *>, key: String): Long? = (map[key] as? Double)?.let { (it * 1000).toLong() }

    fun objectOf(value: Any?): Map<*, *>? = value as? Map<*, *>

    fun listOf(value: Any?): List<*>? = value as? List<*>

    private class Reader(private val text: String) {
        private var index = 0
        val atEnd: Boolean get() = index >= text.length

        fun skipSpace() {
            while (!atEnd && text[index].isWhitespace()) index++
        }

        fun value(): Any? {
            if (atEnd) throw IllegalArgumentException("读到末尾，但这里还该有一个值")
            return when (val head = text[index]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't', 'f', 'n' -> literal()
                '-', '+', in '0'..'9' -> number()
                else -> throw IllegalArgumentException("不该出现在这里的字符：$head")
            }
        }

        private fun obj(): Map<String, Any?> {
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            skipSpace()
            if (take('}')) return out
            while (true) {
                skipSpace()
                val key = string()
                skipSpace()
                expect(':')
                skipSpace()
                out[key] = value()
                skipSpace()
                if (take(',')) continue
                expect('}')
                return out
            }
        }

        private fun array(): List<Any?> {
            expect('[')
            val out = ArrayList<Any?>()
            skipSpace()
            if (take(']')) return out
            while (true) {
                skipSpace()
                out += value()
                skipSpace()
                if (take(',')) continue
                expect(']')
                return out
            }
        }

        private fun string(): String {
            expect('"')
            val builder = StringBuilder()
            while (true) {
                if (atEnd) throw IllegalArgumentException("字符串没闭合")
                when (val char = text[index++]) {
                    '"' -> return builder.toString()
                    '\\' -> builder.append(escape())
                    else -> builder.append(char)
                }
            }
        }

        private fun escape(): Char = when (val char = text[index++]) {
            'n' -> '\n'
            't' -> '\t'
            'r' -> '\r'
            'b' -> '\b'
            'f' -> '\u000C'
            'u' -> {
                val code = text.substring(index, index + 4).also { index += 4 }
                code.toInt(16).toChar()
            }
            else -> char
        }

        private fun number(): Double {
            val start = index
            if (text[index] == '-' || text[index] == '+') index++
            while (!atEnd && (text[index].isDigit() || text[index] in ".eE+-")) index++
            return text.substring(start, index).toDoubleOrNull()
                ?: throw IllegalArgumentException("不像数字：${text.substring(start, index)}")
        }

        private fun literal(): Any? {
            val word = when {
                text.startsWith("true", index) -> { index += 4; true }
                text.startsWith("false", index) -> { index += 5; false }
                text.startsWith("null", index) -> { index += 4; null }
                else -> throw IllegalArgumentException("认不出的字面量：位置 $index")
            }
            return word
        }

        private fun take(char: Char): Boolean {
            if (!atEnd && text[index] == char) { index++; return true }
            return false
        }

        private fun expect(char: Char) {
            if (!take(char)) {
                throw IllegalArgumentException("位置 $index 想要 '$char'，实际是 ${if (atEnd) "末尾" else "'${text[index]}'"}")
            }
        }
    }
}
