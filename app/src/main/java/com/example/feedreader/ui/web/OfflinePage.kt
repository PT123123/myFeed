package com.example.feedreader.ui.web

import com.example.feedreader.data.Article
import com.example.feedreader.data.DateParser
import com.example.feedreader.data.ReaderRouting

/**
 * 应用自己渲染的那一页正文：知乎这类登录墙站点的离线全文，和没有链接时的兜底。
 *
 * 配色由调用方把 `dark` 传进来**直接写死**，不留 `@media (prefers-color-scheme: dark)`。
 * 原因：这一页是 `loadDataWithBaseURL` 喂进去的，媒体查询问的是 WebView 自己的夜间判定
 * （Android 13 起的 algorithmic darkening，跟着 App 声明的主题资源走），
 * 而 Compose 判定系统夜里就暗是另一回事 —— 两个判定不同源，结果就是 App 界面是暗的、
 * 这一页白着脸。能自己决定的东西不交给 ROM。
 *
 * 段落取 [Article.body]（`TextCleaner.body` 用空行分段），没有全文才退回摘要。
 */
object OfflinePage {

    /** 暗色那份底色与 [android.webkit.WebView.setBackgroundColor] 共用，免得接缝处闪白。 */
    const val DARK_BACKGROUND = 0xFF121212.toInt()
    const val LIGHT_BACKGROUND = 0xFFFFFFFF.toInt()

    fun html(article: Article, dark: Boolean): String {
        val palette = if (dark) {
            Palette(background = "#121212", text = "#dcdcdc", faint = "#93989f")
        } else {
            Palette(background = "#ffffff", text = "#1c1b1f", faint = "#888888")
        }
        val paragraphs = article.body.ifBlank { article.excerpt }
            .split("\n\n")
            .filter { it.isNotBlank() }
            .joinToString("\n      ") { "<p>${escape(it)}</p>" }
            .ifBlank { "<p>这条订阅源没有提供正文，也没有原文链接。</p>" }

        // 摘要长度的正文要在文末说清楚，否则用户读完两三百字会以为这篇就这么短，
        // 而「看原页」在顶栏上，不会主动回头去找。
        val notice = if (ReaderRouting.isExcerptOnly(article)) {
            "\n      <div class=\"meta\">以上只是订阅带回来的摘要，整篇在原页 —— 顶栏「看原页」，或从浏览器打开。</div>"
        } else {
            ""
        }

        return """
        <!doctype html>
        <html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          body { font-family: sans-serif; line-height: 1.7; padding: 20px;
                 background: ${palette.background}; color: ${palette.text}; }
          /* 平板上是整屏宽，不限行宽的话一行能有 120 个汉字，读不下去。 */
          h1, .meta, p { max-width: 42rem; }
          h1 { font-size: 1.3rem; }
          .meta { color: ${palette.faint}; font-size: .8rem; margin-bottom: 1.2em; }
          p { margin: 0 0 1em; white-space: pre-wrap; }
        </style></head>
        <body>
          <h1>${escape(article.title)}</h1>
          <div class="meta">${escape(article.sourceName)} · ${escape(DateParser.display(article))}</div>
          $paragraphs$notice
        </body></html>
    """.trimIndent()
    }

    /** 只用于把正文里的尖括号与 & 变成文本，别让订阅正文当成标记来解析。 */
    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private data class Palette(val background: String, val text: String, val faint: String)
}
