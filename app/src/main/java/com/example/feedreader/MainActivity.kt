package com.example.feedreader

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import com.example.feedreader.data.DeepLink
import com.example.feedreader.ui.theme.FeedReaderTheme

class MainActivity : ComponentActivity() {

    /**
     * 待处理的扫码深链。
     *
     * 存在 Activity 而不是 ViewModel 里：它是一次性的交互意图，不是配置。用户把 App
     * 划掉再进来时不该又弹一次「同步 relay」，而 ViewModel 会跨配置变更活下来。
     */
    private val deepLink = mutableStateOf<DeepLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        takeDeepLink(intent)
        setContent {
            FeedReaderTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    FeedReaderApp(
                        deepLink = deepLink.value,
                        onDeepLinkHandled = { deepLink.value = null },
                    )
                }
            }
        }
    }

    // manifest 里是 singleTask：第二次扫码不会重建 Activity（那会把用户滚好的列表位置冲掉），
    // 而是走这里。
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeDeepLink(intent)
    }

    private fun takeDeepLink(intent: Intent?) {
        // 解析失败（协议不对、host 不认识、缺参数）就什么也不做：
        // 这不是用户主动点的操作，弹个「链接无效」只会莫名其妙。
        DeepLink.parse(intent?.dataString)?.let { deepLink.value = it }
    }
}
