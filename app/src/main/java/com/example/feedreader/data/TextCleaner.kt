package com.example.feedreader.data

/**
 * 把 RSS 里夹着 HTML 的文本压成列表能直接显示的一行纯文本。
 *
 * 顺序很重要：先剥标签、再解实体。反过来的话 `&lt;div&gt;` 会被当成真标签再剥一次。
 */
object TextCleaner {

    private val SCRIPT_STYLE = Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>")
    private val TAG = Regex("(?s)<[^>]*>")
    private val WHITESPACE = Regex("[\\s\\u00A0\\u3000]+")
    private val DEC_ENTITY = Regex("&#(\\d{1,7});")
    private val HEX_ENTITY = Regex("&#[xX]([0-9a-fA-F]{1,6});")

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "ensp" to " ", "emsp" to " ",
        "mdash" to "—", "ndash" to "–", "hellip" to "…", "middot" to "·",
        "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’",
        "copy" to "©", "reg" to "®", "trade" to "™", "deg" to "°",
        "times" to "×", "divide" to "÷", "laquo" to "«", "raquo" to "»",
    )

    /**
     * @param maxLength 截断上限，超出补省略号。列表里不需要全文。
     */
    fun plain(raw: String?, maxLength: Int = Int.MAX_VALUE): String {
        if (raw.isNullOrBlank()) return ""

        var text = SCRIPT_STYLE.replace(raw, " ")
        text = TAG.replace(text, " ")
        text = decodeEntities(text)
        text = WHITESPACE.replace(text, " ").trim()

        if (text.length > maxLength) {
            text = text.take(maxLength).trimEnd() + "…"
        }
        return text
    }

    private fun decodeEntities(input: String): String {
        if (!input.contains('&')) return input
        var text = DEC_ENTITY.replace(input) { match -> codePoint(match.groupValues[1].toIntOrNull(10)) }
        text = HEX_ENTITY.replace(text) { match -> codePoint(match.groupValues[1].toIntOrNull(16)) }
        return NAMED.entries.fold(text) { acc, (name, value) ->
            acc.replace("&$name;", value)
        }
    }

    private fun codePoint(value: Int?): String {
        if (value == null || value <= 0 || value > 0x10FFFF) return ""
        return try {
            String(Character.toChars(value))
        } catch (_: IllegalArgumentException) {
            ""
        }
    }
}
