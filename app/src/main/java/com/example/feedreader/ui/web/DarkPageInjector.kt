package com.example.feedreader.ui.web

import android.util.Log
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * [DarkPageScript] 与 WebView 之间的接线。
 *
 * 三个时机，都是同一份脚本：
 * 1. 建 WebView 时注册成 document-start 脚本 —— 只有它在任何网页内容渲染**之前**执行，
 *    白底闪一下的毛病只能在这里治。老 WebView 没这个能力，退到 2。
 * 2. 每次导航开始：再执行一遍。文档已经换新，`window.__mfDark` 是空的，正好重建；
 *    没有 document-start 能力的设备在这里补上，代价是会闪一下白。
 * 3. 每次导航结束：跑带判定的 `smart(true)`。反相路径在这一步撤掉「页面本来就是暗」
 *    的误伤；站点专项路径重新确认样式在位（SPA 站内换路由不会重建文档，只有回调补得上）。
 *
 * 开关只在 WebView 创建时生效：暗色设置改了要重开阅读页。设置页和阅读页是两个
 * 目的地，改设置时阅读页必然已经销毁，所以没有「读到一半改开关」这条路径。
 */
object DarkPageInjector {

    private const val TAG = "DarkPageInjector"

    private val script: String by lazy { DarkPageScript.build() }

    /** document-start 能力可用时注册脚本。关掉暗色就别调这个。 */
    fun attach(webView: WebView) {
        val supported = runCatching {
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        }.getOrDefault(false)
        if (!supported) {
            Log.i(TAG, "DEVICE_NO_DOCUMENT_START_SCRIPT：退到 onPageStarted 注入，会闪白")
            return
        }
        runCatching { WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*://*/*")) }
            .onFailure { Log.w(TAG, "addDocumentStartJavaScript failed", it) }
    }

    fun onPageStarted(webView: WebView) = bootstrap(webView, "early()")

    fun onPageFinished(webView: WebView) = bootstrap(webView, "smart(true)")

    /** 脚本幂等（开头就 `if (window.__mfDark) return;`），所以这里重复执行是安全的。 */
    private fun bootstrap(webView: WebView, call: String) {
        runCatching {
            webView.evaluateJavascript("$script;window.__mfDark&&window.__mfDark.$call", null)
        }.onFailure { Log.w(TAG, "evaluateJavascript($call) failed", it) }
    }
}
