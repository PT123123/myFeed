package com.example.feedreader.ui.web

/**
 * 网页暗色的两套配色来源。
 *
 * - [INVERT_CSS]：通用方案，整页反相 + 把媒体元素二次反相还原。任何站点都能用，
 *   代价见 [SiteDarkRule] 的注释（`filter` 会打掉站内的 `position:fixed`）。
 * - [WEIBO_CSS]：站点专项方案，直接按实测到的类名刷暗色，不碰 `filter`。
 *
 * 命中 [RULES] 的站点走专项，其余走通用 + [DarkPageScript] 里的亮度判定。
 */
object SiteDarkStyles {

    /** 采样有效点数低于该值视为「页面本来就是暗色」，不反相。 */
    const val MIN_VALID_SAMPLES = 6

    /** 平均背景亮度低于该值视为「本来就是深色」，不再反相。 */
    const val DARK_LUMINANCE_THRESHOLD = 0.2

    /**
     * 通用反相。`img/video/canvas` 再反一次是为了图片视频不串色。
     *
     * **`html` 上有 `filter` 会连带做两件事**：成为 fixed 定位的包含块（吸顶/吸底栏跟着
     * 页面滚走），以及给整棵子树建合成层。所以站内有大量吸顶栏的站点必须走 [WEIBO_CSS]
     * 那种专项配色，不能靠这套。
     */
    const val INVERT_CSS: String =
        "html{background:#121212 !important;filter:invert(1) hue-rotate(180deg) !important;}" +
            "body{background:#121212 !important;}" +
            "img,video,canvas,svg,iframe,embed,object{filter:invert(1) hue-rotate(180deg) !important;}"

    /**
     * 微博的暗色覆盖。
     *
     * 微博 m 站**有**自带的暗色，但要 `@media (prefers-color-scheme: dark)` 和
     * `:root[data-theme=dark]` 两个闸门同时成立才生效（cards.css 48 条、base.css 32 条，
     * 实测全部包在媒体查询里；statusLite.css 自己一条都没有）。媒体查询在 WebView 里
     * 只能由设备夜间模式带出来，JS/CSS 都改不动它，所以设备亮色 + 「总是暗色」时
     * 它那一套完全不会触发 —— 只能自己写。
     *
     * 下面每个选择器和每条颜色值都来自 `m.weibo.cn/status/<mid>`（statusLite 页）的
     * 实测计算样式：卡片 `#fff`/边 `#e6e6e6`、吸顶栏 `#fafafa`/字 `#262626`、
     * 正文 `#333`、次要与时间 `#939393`、链接 `#3c6e9e`、图片占位 `#e6e6e6`、
     * 底部输入框 `#fff`、关注按钮 `#ff8200`。没实测到的类名不写，避免凭印象造规则。
     */
    const val WEIBO_CSS: String =
        ":root{color-scheme:dark}" +
            "html{background:#0f1216 !important;}" +
            "body{color:#d5dae1;}" +
            ".card,.m-panel{background:#181d23 !important;border-color:#2a313b !important;color:#d5dae1 !important;}" +
            ".lite-topbar{background:#12161b !important;border-color:#262d36 !important;color:#e6eaf0 !important;}" +
            ".weibo-top,.weibo-main,.weibo-og,.m-text-box,.m-text-cut,.weibo-text," +
            ".m-auto-list,.m-auto-box3,.card-wrap{color:#d5dae1 !important;}" +
            "a,.surl-text{color:#79aede !important;}" +
            ".from,.time{color:#8a919e !important;}" +
            ".m-img-box{background:#232a33 !important;}" +
            ".m-font,.m-icon{color:#c2c9d4 !important;}" +
            ".m-add-box,.m-followBtn{color:#ffa347 !important;}" +
            ".lite-page-editor{background:#181d23 !important;border-color:#2a313b !important;color:#d5dae1 !important;}"

    /** 专项站点表。host 匹配交给 [DarkPageScript] 生成的脚本，这里只当数据源。 */
    val RULES: List<SiteRule> = listOf(
        // 微博：PC 域名在手机的 WebView 里会 302 到 m.weibo.cn（实测重定向参数里带着
        // 目标地址），两个域都列上是因为站内跳转会回到 weibo.com 那批老链接。
        SiteRule(
            hosts = listOf("m.weibo.cn", "weibo.cn", "weibo.com", "s.weibo.com"),
            css = WEIBO_CSS,
            // 微博暗色的第二个闸门，能拨就拨：设备本身是夜间模式时，它自己那 80 条
            // 暗色规则会叠上来，方向一致。
            rootAttributes = mapOf("data-theme" to "dark"),
        ),
    )
}

/** 一个站点的专项暗色：匹配的 host 列表 + 覆盖用 CSS + 要拨的 `<html>` 属性。 */
data class SiteRule(
    val hosts: List<String>,
    val css: String,
    val rootAttributes: Map<String, String> = emptyMap(),
)
