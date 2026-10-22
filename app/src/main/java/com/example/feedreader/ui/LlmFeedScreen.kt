package com.example.feedreader.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.example.feedreader.data.DesktopFeedCache
import com.example.feedreader.data.LanDiscovery
import com.example.feedreader.data.LanRole
import com.example.feedreader.data.ReadStateStore
import com.example.feedreader.data.SettingsStore
import com.example.feedreader.data.LlmServicePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

private const val LLM_ORIGIN = "https://appassets.androidplatform.net"
private const val MAX_MESSAGE = 16L * 1024 * 1024
private data class BackupExport(val name: String, val contents: String, val reply: (JSONObject) -> Unit)

/** Bundled LLMFeed UI. IndexedDB retains its own feed, settings and sync outbox offline. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LlmFeedScreen(savedState: Bundle, modifier: Modifier = Modifier, active: Boolean = true) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var chooser by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var export by remember { mutableStateOf<BackupExport?>(null) }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        chooser?.onReceiveValue(uri?.let { arrayOf(it) })
        chooser = null
    }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val pending = export
        export = null
        if (pending != null) scope.launch {
            val error = if (uri == null) "已取消保存" else withContext(Dispatchers.IO) {
                runCatching {
                    val stream = context.contentResolver.openOutputStream(uri) ?: error("无法写入所选文件")
                    stream.use { it.write(pending.contents.toByteArray(Charsets.UTF_8)) }
                }.exceptionOrNull()?.message
            }
            pending.reply(if (error == null) JSONObject().put("value", true) else JSONObject().put("error", error))
        }
    }
    BackHandler(enabled = active && canGoBack) { webView?.goBack() }
    DisposableEffect(Unit) {
        onDispose {
            chooser?.onReceiveValue(null)
            webView?.let { it.saveState(savedState); it.destroy() }
        }
    }

    if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
        if (active) Text("请先更新 Android System WebView，再打开 LLM Feed。订阅阅读仍可使用。", modifier)
        return
    }
    AndroidView(modifier = modifier, factory = {
        val preferences = SettingsStore(context)
        val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).followRedirects(false).build()
        val assets = WebViewAssetLoader.AssetsPathHandler(context)
        val loader = WebViewAssetLoader.Builder().addPathHandler("/") { path ->
            if (path == "llm/index.html") {
                // Runtime-only connection data; escape HTML delimiters before embedding JSON.
                //
                // 这一页现在只管生产：内容和已读都归原生那条流了，所以地址、令牌由原生
                // 递进去（`desktopFeedBaseUrl`/`desktopFeedToken`），网页里那份只是显示与回填。
                // `manage` 告诉 llm-ui 把 Feed / Saved / Discover 这些阅读侧的路由收起来。
                val host = JSONObject()
                    .put("baseUrl", preferences.desktopFeedBaseUrl)
                    .put("token", preferences.desktopFeedToken)
                    .put("manage", true)
                    .toString()
                    .replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")
                val html = context.assets.open("llm/index.html").bufferedReader().use { it.readText() }
                    .replace("</head>", "<script>window.__YUELIU_HOST__=$host;</script></head>")
                WebResourceResponse("text/html", "UTF-8", html.byteInputStream(Charsets.UTF_8))
            } else assets.handle(path)
        }.build()
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = true // Only files explicitly chosen with the system picker.
            settings.setSupportMultipleWindows(false)
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    return loader.shouldInterceptRequest(request.url)
                        ?: WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), "".byteInputStream())
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    if (uri.scheme == "https" && uri.host == "appassets.androidplatform.net" && uri.path.orEmpty().startsWith("/llm/")) return false
                    if (request.isForMainFrame && uri.scheme in setOf("http", "https")) {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    }
                    return true
                }
                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    canGoBack = view.canGoBack()
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                    chooser?.onReceiveValue(null)
                    chooser = callback
                    openFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                    return true
                }
            }
            WebViewCompat.addWebMessageListener(this, "llmTransport", setOf(LLM_ORIGIN)) { _, message, _, mainFrame, proxy ->
                if (!mainFrame) return@addWebMessageListener
                val data = runCatching { JSONObject(message.data.orEmpty()) }.getOrNull() ?: return@addWebMessageListener
                val id = data.optString("id")
                val reply: (JSONObject) -> Unit = { result ->
                    runCatching { proxy.postMessage(result.put("id", id).toString()) }
                }
                when (data.optString("action")) {
                    "http" -> scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { mobileHttp(client, preferences, data) }
                                .getOrElse { JSONObject().put("error", it.message ?: "电脑连接失败") }
                        }
                        reply(result)
                    }
                    "connection" -> {
                        val url = runCatching { LlmServicePolicy.base(data.optString("baseUrl")) }.getOrNull()
                        if (url == null) {
                            reply(JSONObject().put("error", "请填写电脑服务的根地址，例如 http://192.168.0.2:8000"))
                        } else {
                            preferences.desktopFeedBaseUrl = url.toString().trimEnd('/')
                            // 令牌同一个动作带回来：网页里那格填完就该是本机唯一的凭据，
                            // 存两处迟早对不上（原生同步用的是它，网页请求用的也是它）。
                            val token = data.optString("token").trim()
                            if (token.isNotEmpty()) preferences.desktopFeedToken = token
                            reply(JSONObject().put("value", true))
                        }
                    }
                    "legacy" -> scope.launch {
                        reply(JSONObject().put("value", withContext(Dispatchers.IO) { legacyPosts(context) }))
                    }
                    // 局域网搜索：WebView 自己发不了 mDNS 组播，只能由原生这一侧搜完把清单递回去。
                    // 30 秒是自己上的保险：前端那条通道 35 秒就报超时，那时用户只看到一句
                    // 「电脑服务未响应」，分不清是搜索慢还是这台设备压根没有 NSD。
                    "discovery" -> scope.launch {
                        val role = data.optString("role")
                        val scan = withContext(Dispatchers.IO) {
                            runCatching { withTimeoutOrNull(30_000L) { LanDiscovery(context).search() } }.getOrNull()
                        }
                        if (scan == null) {
                            reply(JSONObject().put("error", "局域网搜索没在这台设备上跑起来，先手填电脑地址"))
                        } else {
                            val wanted = LanRole.entries.firstOrNull { it.name.equals(role, ignoreCase = true) }
                            val items = JSONArray()
                            scan.endpoints
                                .filter { wanted == null || it.role == wanted }
                                .forEach { items.put(JSONObject().put("name", it.name).put("baseUrl", it.baseUrl).put("role", it.role.name.lowercase())) }
                            val value = JSONObject().put("endpoints", items).put("unresolved", scan.unresolved)
                            reply(JSONObject().put("value", scan.note?.let { value.put("note", it) } ?: value))
                        }
                    }
                    "export" -> {
                        if (export != null || data.optString("contents").length > MAX_MESSAGE) {
                            reply(JSONObject().put("error", "正在保存另一份备份，或备份文件过大"))
                        } else {
                            val name = data.optString("name", "llm-feed-backup.json").substringAfterLast('/').substringAfterLast('\\')
                            export = BackupExport(name, data.optString("contents"), reply)
                            saveFile.launch(name)
                        }
                    }
                    else -> reply(JSONObject().put("error", "不支持的应用操作"))
                }
            }
            webView = this
            if (savedState.isEmpty || restoreState(savedState) == null) {
                val path = if (preferences.desktopFeedBaseUrl.isBlank()) "/settings" else "/"
                loadUrl("$LLM_ORIGIN/llm/index.html#$path")
            }
        }
    }, update = { it.visibility = if (active) android.view.View.VISIBLE else android.view.View.GONE })
}

private fun mobileHttp(client: OkHttpClient, settings: SettingsStore, data: JSONObject): JSONObject {
    val url = LlmServicePolicy.request(settings.desktopFeedBaseUrl, data.optString("url"))
    val method = data.optString("method", "GET")
    require(method in setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD"))
    val text = if (data.isNull("body")) "" else data.optString("body")
    require(text.length <= MAX_MESSAGE) { "请求过大" }
    val body = if (method in setOf("GET", "HEAD")) null else text.toRequestBody("application/json".toMediaType())
    val request = Request.Builder().url(url).method(method, body)
    val headers = data.optJSONObject("headers") ?: JSONObject()
    headers.keys().forEach { key ->
        if (key.lowercase(Locale.ROOT) in setOf("authorization", "content-type", "idempotency-key")) request.header(key, headers.getString(key))
    }
    return client.newCall(request.build()).execute().use { response ->
        val source = response.body?.source()
        source?.request(MAX_MESSAGE + 1)
        require((source?.buffer?.size ?: 0) <= MAX_MESSAGE) { "同步数据过大，请先在电脑端整理内容" }
        JSONObject().put("status", response.code).put("body", source?.readUtf8().orEmpty())
    }
}

private fun legacyPosts(context: android.content.Context): JSONArray {
    val readAt = ReadStateStore.forApp(context).load()
    val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    return JSONArray().apply {
        DesktopFeedCache.forApp(context).read().forEach { post ->
            val id = post.remotePostId ?: return@forEach
            put(JSONObject().put("id", id).put("title", post.title).put("body", post.body)
                .put("summary", post.excerpt).put("kind", post.remoteKind)
                .put("sourceUrl", post.link).put("sourceTitle", post.sourceName)
                .put("createdAt", format.format(Date(post.publishedAt)))
                .put("lastReadAt", readAt[post.id]?.let { format.format(Date(it)) } ?: JSONObject.NULL))
        }
    }
}
