package com.example.feedreader.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Limits the privileged WebView transport to the computer explicitly saved by the reader. */
object LlmServicePolicy {
    fun base(raw: String): HttpUrl {
        val url = raw.toHttpUrlOrNull() ?: error("请先在 LLM Feed 设置里填写电脑地址")
        require(url.host != "appassets.androidplatform.net" && url.encodedPath == "/" &&
            url.query == null && url.fragment == null && url.username.isEmpty() && url.password.isEmpty()) {
            "请填写电脑服务的根地址，例如 http://192.168.0.2:8000"
        }
        return url
    }

    fun request(savedBase: String, target: String): HttpUrl {
        val base = base(savedBase)
        val url = target.toHttpUrlOrNull() ?: error("电脑地址无效")
        require(url.scheme == base.scheme && url.host == base.host && url.port == base.port &&
            url.username.isEmpty() && url.password.isEmpty() && url.fragment == null &&
            (url.encodedPath == "/health" || url.encodedPath.startsWith("/api/v1/"))) {
            "请先保存该电脑地址，再连接服务"
        }
        return url
    }
}
