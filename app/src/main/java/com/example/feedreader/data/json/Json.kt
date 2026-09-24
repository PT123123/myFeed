package com.example.feedreader.data.json

import java.io.IOException

/**
 * JSON 值。
 */
sealed interface JsonValue {
    data class Obj(val entries: Map<String, JsonValue>) : JsonValue
    data class Arr(val items: List<JsonValue>) : JsonValue
    data class Str(val value: String) : JsonValue
    data class Num(val value: Double) : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    data object Null : JsonValue
}

/**
 * 极简 JSON 解析器。
 *
 * 为什么要自己写 —— 项目里有两个硬约束：
 *  1. `org.json` 在**本地 JVM 单测里是 stub**，调它就抛 "Stub!"。召回通道的解析逻辑
 *     恰恰是最容易写错的地方（字段名写错、类型不一致、缺字段），必须能单测。
 *  2. 引 gson / kotlinx.serialization 为这三个接口的十几个字段不值得。
 *
 * 只实现解析，不做序列化（不需要）。行为上够用即可：
 *  - 支持对象 / 数组 / 字符串（含 `\uXXXX` 转义）/ 数字 / true / false / null
 *  - 数字统一读成 Double，取值时按需转 Long（时间戳都是秒级，Double 精确到 2^53 够用）
 *  - 不校验重复键（后者覆盖前者）、不保证键顺序
 */
object Json {

    fun parse(text: String): JsonValue {
        val reader = Reader(text)
        reader.skipWhitespace()
        val value = reader.readValue()
        reader.skipWhitespace()
        if (!reader.atEnd()) throw IOException("JSON 末尾有多余内容，位置 ${reader.position}")
        return value
    }

    /** 解析失败时返回 null，让调用方决定是记为一次失败还是静默跳过。 */
    fun parseOrNull(text: String): JsonValue? = try {
        parse(text)
    } catch (_: Exception) {
        null
    }

    private class Reader(private val text: String) {
        var position = 0
            private set

        fun atEnd(): Boolean = position >= text.length

        fun skipWhitespace() {
            while (position < text.length && text[position].isWhitespace()) position++
        }

        fun readValue(): JsonValue {
            if (atEnd()) throw IOException("JSON 意外结束")
            return when (val c = text[position]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> JsonValue.Str(readString())
                't', 'f' -> readBoolean()
                'n' -> readNull()
                else -> if (c == '-' || c in '0'..'9') readNumber() else {
                    throw IOException("位置 $position 出现意外字符 '$c'")
                }
            }
        }

        private fun readObject(): JsonValue.Obj {
            expect('{')
            val entries = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                position++
                return JsonValue.Obj(entries)
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                entries[key] = readValue()
                skipWhitespace()
                when (val c = next()) {
                    ',' -> Unit
                    '}' -> return JsonValue.Obj(entries)
                    else -> throw IOException("位置 ${position - 1} 期望 ',' 或 '}'，实际 '$c'")
                }
            }
        }

        private fun readArray(): JsonValue.Arr {
            expect('[')
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                position++
                return JsonValue.Arr(items)
            }
            while (true) {
                skipWhitespace()
                items += readValue()
                skipWhitespace()
                when (val c = next()) {
                    ',' -> Unit
                    ']' -> return JsonValue.Arr(items)
                    else -> throw IOException("位置 ${position - 1} 期望 ',' 或 ']'，实际 '$c'")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw IOException("字符串未闭合")
                when (val c = text[position++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) throw IOException("转义序列未完成")
                        when (val esc = text[position++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (position + 4 > text.length) throw IOException("\\u 转义不完整")
                                val hex = text.substring(position, position + 4)
                                position += 4
                                val code = hex.toIntOrNull(16)
                                    ?: throw IOException("非法的 \\u 转义：$hex")
                                sb.append(code.toChar())
                            }
                            else -> throw IOException("未知转义 \\$esc")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): JsonValue.Num {
            val start = position
            if (peek() == '-') position++
            while (position < text.length && text[position] in '0'..'9') position++
            if (position < text.length && text[position] == '.') {
                position++
                while (position < text.length && text[position] in '0'..'9') position++
            }
            if (position < text.length && (text[position] == 'e' || text[position] == 'E')) {
                position++
                if (position < text.length && (text[position] == '+' || text[position] == '-')) position++
                while (position < text.length && text[position] in '0'..'9') position++
            }
            val raw = text.substring(start, position)
            val value = raw.toDoubleOrNull() ?: throw IOException("非法数字：$raw")
            return JsonValue.Num(value)
        }

        private fun readBoolean(): JsonValue.Bool = when {
            text.startsWith("true", position) -> {
                position += 4
                JsonValue.Bool(true)
            }
            text.startsWith("false", position) -> {
                position += 5
                JsonValue.Bool(false)
            }
            else -> throw IOException("位置 $position 期望 true/false")
        }

        private fun readNull(): JsonValue {
            if (!text.startsWith("null", position)) throw IOException("位置 $position 期望 null")
            position += 4
            return JsonValue.Null
        }

        private fun expect(c: Char) {
            if (atEnd() || text[position] != c) {
                throw IOException("位置 $position 期望 '$c'")
            }
            position++
        }

        private fun peek(): Char? = if (atEnd()) null else text[position]

        private fun next(): Char =
            if (atEnd()) throw IOException("JSON 意外结束") else text[position++]
    }
}

// —— 取值辅助。全部返回默认值而不是抛异常：接口字段缺失/类型不符是常态 ——

fun JsonValue?.objOrEmpty(): Map<String, JsonValue> =
    (this as? JsonValue.Obj)?.entries ?: emptyMap()

fun JsonValue?.arrOrEmpty(): List<JsonValue> =
    (this as? JsonValue.Arr)?.items ?: emptyList()

fun JsonValue?.stringOrNull(): String? = when (this) {
    is JsonValue.Str -> value.takeIf { it.isNotBlank() }
    is JsonValue.Num -> formatNumber(value)
    is JsonValue.Bool -> value.toString()
    else -> null
}

fun JsonValue?.stringOr(default: String): String = stringOrNull() ?: default

/** 时间戳等整数字段。浮点也接受，截断取整。 */
fun JsonValue?.longOrNull(): Long? = when (this) {
    is JsonValue.Num -> value.toLong()
    is JsonValue.Str -> value.toLongOrNull()
    else -> null
}

fun JsonValue?.longOr(default: Long): Long = longOrNull() ?: default

fun JsonValue?.intOr(default: Int): Int = longOrNull()?.toInt() ?: default

fun JsonValue?.doubleOr(default: Double): Double =
    (this as? JsonValue.Num)?.value ?: default

private fun formatNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
