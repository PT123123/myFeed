package com.example.feedreader.data

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * RSS/Atom 的日期解析与展示。
 *
 * 只用 [SimpleDateFormat]，不碰 java.time —— minSdk 21 上 java.time 需要 core library
 * desugaring 才能在低版本跑，这里没必要引入。
 */
object DateParser {

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    /** 常见格式，按命中概率从高到低。带 'Z' 字面量的按 UTC 解释。 */
    private val PATTERNS: List<Pair<String, Boolean>> = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z" to false,
        "EEE, dd MMM yyyy HH:mm:ss z" to false,
        "EEE, d MMM yyyy HH:mm:ss Z" to false,
        "EEE, d MMM yyyy HH:mm:ss z" to false,
        "yyyy-MM-dd'T'HH:mm:ss.SSSZ" to false,
        "yyyy-MM-dd'T'HH:mm:ssZ" to false,
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'" to true,
        "yyyy-MM-dd'T'HH:mm:ss'Z'" to true,
        "yyyy-MM-dd'T'HH:mm:ss" to false,
        "yyyy-MM-dd HH:mm:ss" to false,
        "yyyy/MM/dd HH:mm:ss" to false,
        "yyyy-MM-dd" to false,
        "yyyy/MM/dd" to false,
    )

    /** 匹配 ISO8601 的 `+08:00` / `-05:00` 尾缀，转成 SimpleDateFormat 认的 `+0800`。 */
    private val ISO_OFFSET = Regex("([+-]\\d{2}):(\\d{2})$")

    /**
     * 小数秒截到 3 位。
     *
     * `SSS` 在 java.text 里是「一个数字」不是「三位小数」，lenient 模式下 Python 的 6 位微秒
     * `.986983` 会被读成 986983 毫秒并累加进时间 —— 实测电脑端快照 345 条里 216 条带 6 位小数，
     * 偏差从几秒到几十分钟不等，而且是把内容排到错误的时间上。
     */
    private val FRACTION = Regex("\\.(\\d+)")

    /** 解析失败返回 0。 */
    fun parse(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val text = FRACTION.replace(raw.trim().replace(ISO_OFFSET, "$1$2")) { match ->
            "." + match.groupValues[1].take(3).padEnd(3, '0')
        }
        // 先要求整串都被消费掉。否则像 "…18:38:59 GMT" 这种，用不含时区的格式
        // 也能"部分匹配"成功，时区就被悄悄丢了，时间会整体偏掉。
        return attempt(text, requireFull = true)
            ?: attempt(text, requireFull = false)
            ?: 0L
    }

    private fun attempt(text: String, requireFull: Boolean): Long? {
        for ((pattern, utc) in PATTERNS) {
            val format = SimpleDateFormat(pattern, Locale.US)
            if (utc) format.timeZone = TimeZone.getTimeZone("UTC")
            val position = ParsePosition(0)
            val date: Date = format.parse(text, position) ?: continue
            if (position.index <= 0) continue
            if (requireFull && position.index < text.length) continue
            return date.time
        }
        return null
    }

    /** 相对时间：刚刚 / N 分钟前 / N 小时前 / N 天前 / yyyy-MM-dd。 */
    fun relative(millis: Long, now: Long = System.currentTimeMillis()): String {
        if (millis <= 0L) return ""
        val delta = now - millis
        return when {
            delta < 0L -> SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis))
            delta < MINUTE -> "刚刚"
            delta < HOUR -> "${delta / MINUTE} 分钟前"
            delta < DAY -> "${delta / HOUR} 小时前"
            delta < 7 * DAY -> "${delta / DAY} 天前"
            else -> SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(millis))
        }
    }

    /** 列表里显示的日期：优先相对时间，解析不出来就退回原始字符串。 */
    fun display(article: Article, now: Long = System.currentTimeMillis()): String {
        val rel = relative(article.publishedAt, now)
        if (rel.isNotEmpty()) return rel
        return article.publishedRaw.trim().take(32).ifEmpty { "时间未知" }
    }
}
