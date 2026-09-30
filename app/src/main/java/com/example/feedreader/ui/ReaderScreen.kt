package com.example.feedreader.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.feedreader.data.Article
import com.example.feedreader.data.ReaderRouting
import com.example.feedreader.data.WebDarkMode
import com.example.feedreader.ui.web.DarkPageInjector
import com.example.feedreader.ui.web.OfflinePage
import kotlinx.coroutines.launch

/**
 * 内置浏览器：正文在应用内读完，不再往外跳。
 *
 * 站内导航（http/https）一律留在 WebView 里；只有 mailto:/tel:/intent: 这种
 * WebView 处理不了的协议才交给系统。顶栏溢出菜单里留了一个显式的
 * 「在浏览器中打开」出口，想用外部浏览器时走它。
 *
 * **需要登录态的站点（知乎、小红书、X）走那个出口，不在这里解决。** WebView 的
 * cookie 存在本应用自己的沙箱里，读不到别的浏览器的登录态；而这几家的接口除了
 * cookie 还要站点侧签名，把 cookie 搬过来也换不来能用的请求。见
 * [docs/crawlbase-relay.md](../../../../../../../docs/crawlbase-relay.md) 里那几组实测。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    article: Article,
    darkMode: WebDarkMode,
    onOpenExternal: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var progress by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var loadTick by remember { mutableIntStateOf(0) }
    // 换文章要从「只看全文」退回默认路由，否则上一篇点的「看原页」会跟到下一篇
    var wantSite by remember(article.id) { mutableStateOf(false) }
    val readOffline = ReaderRouting.readOffline(article) && !wantSite

    val darkPages = when (darkMode) {
        WebDarkMode.ALWAYS -> true
        WebDarkMode.OFF -> false
        WebDarkMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
    }

    // WebView 是重量级对象：跟着这个页面建一次、销毁一次
    val webView = remember {
        WebView(context).apply {
            configureForReading(darkPages)
            if (darkPages) DarkPageInjector.attach(this)
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    error = null
                    if (darkPages && url.isWebUrl()) view?.let(DarkPageInjector::onPageStarted)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    progress = 100
                    if (darkPages && url.isWebUrl()) view?.let(DarkPageInjector::onPageFinished)
                }

                /**
                 * 框架只在主框架报错时才回调这个旧签名（新签名的默认实现会转发过来），
                 * 所以图片 404 之类不会误报成整页失败。
                 */
                @Deprecated("Deprecated in Java")
                @Suppress("DEPRECATION")
                override fun onReceivedError(
                    view: WebView?,
                    errorCode: Int,
                    description: String?,
                    failingUrl: String?,
                ) {
                    error = description?.takeIf { it.isNotBlank() } ?: "错误码 $errorCode"
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    val url = request?.url?.toString() ?: return false
                    if (url.startsWith("http://") || url.startsWith("https://")) return false
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progress = newProgress
                }
            }
        }
    }

    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            // 先摘下来再销毁：销毁顺序和 Compose 移除视图的顺序不保证，
            // 挂在树上直接 destroy() 在部分 ROM 上会出问题
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
    }

    // tick 0 = 首次加载；点刷新时 tick 自增，重新走一遍
    LaunchedEffect(loadTick, readOffline) {
        error = null
        progress = 0
        if (readOffline) {
            webView.loadDataWithBaseURL(null, OfflinePage.html(article, darkPages), "text/html", "utf-8", null)
        } else {
            webView.loadUrl(article.link)
        }
    }

    // 返回键先在网页里回退，退到头才关掉页面
    BackHandler(enabled = true) {
        if (webView.canGoBack()) webView.goBack() else onBack()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = article.title.ifBlank { article.sourceName },
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = when {
                                    !readOffline -> "${article.sourceName} · ${hostOf(article.link)}"
                                    // 摘要长度就说是摘要：让人知道下面这几段不是整篇，原页才是
                                    ReaderRouting.isExcerptOnly(article) ->
                                        "${article.sourceName} · 订阅里只有摘要，原页要登录"
                                    else -> "${article.sourceName} · 全文来自订阅，原页要登录"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        // 全文和原页是两种东西：全文能离线读、原页可能要登录才能看评论。
                        // 出口摆在栏上而不是只塞进三点菜单，否则没人找得到。
                        if (ReaderRouting.readOffline(article)) {
                            TextButton(onClick = { wantSite = !wantSite }) {
                                Text(if (readOffline) "看原页" else "回到全文")
                            }
                        }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("刷新") },
                                onClick = {
                                    menuOpen = false
                                    loadTick++
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("在浏览器中打开") },
                                enabled = article.link.isNotBlank(),
                                onClick = {
                                    menuOpen = false
                                    onOpenExternal(article.link)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("复制链接") },
                                enabled = article.link.isNotBlank(),
                                onClick = {
                                    menuOpen = false
                                    copyLink(context, article.link)
                                    scope.launch { snackbar.showSnackbar("链接已复制") }
                                },
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
                if (progress in 1..99) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
    ) { insets ->
        Box(modifier = Modifier.fillMaxSize().padding(insets)) {
            AndroidView(
                factory = { webView },
                modifier = Modifier.fillMaxSize(),
            )

            error?.let { message ->
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("页面打不开", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { loadTick++ }) { Text("重试") }
                        if (article.link.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(onClick = { onOpenExternal(article.link) }) {
                                Text("改用系统浏览器")
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * WebView 自己的底色：离线全文那一页 [OfflinePage.html] 用的是同一个颜色，
 * 于是加载期间露出来的就是最终背景，不会在暗色界面中间白一块。
 *
 * 这里刻意**不**开 algorithmic darkening：暗色是 [DarkPageInjector] 注入 CSS 做的，
 * 两套一起上会变成「反相之上再反相」。
 */
private fun WebView.configureForReading(dark: Boolean) {
    setBackgroundColor(if (dark) OfflinePage.DARK_BACKGROUND else OfflinePage.LIGHT_BACKGROUND)
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        loadWithOverviewMode = true
        useWideViewPort = true
        // 混合内容放行：不少中文站点正文仍走 http 资源，卡住会整页白屏
        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        cacheMode = WebSettings.LOAD_DEFAULT
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
    }
}

/** 清掉内置浏览器的网页缓存与 Cookie。必须在主线程调用。 */
fun clearWebViewCache(context: Context) {
    runCatching {
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        val probe = WebView(context)
        probe.clearCache(true)
        probe.destroy()
    }
}

private fun copyLink(context: Context, url: String) {
    if (url.isBlank()) return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("链接", url))
}

private fun hostOf(url: String): String =
    runCatching { Uri.parse(url).host }.getOrNull()?.removePrefix("www.") ?: "本地摘要"

/** 只有真正的网页才配被注入暗色：自己渲染的那页正文走 [OfflinePage]，不经过注入脚本。 */
private fun String?.isWebUrl(): Boolean =
    this != null && (startsWith("http://") || startsWith("https://"))
