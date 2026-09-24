package com.example.feedreader.ui

import com.example.feedreader.data.Article
import com.example.feedreader.data.FeedFailure
import com.example.feedreader.recommend.RecommendStats
import com.example.feedreader.recommend.ScoredArticle

/** 首页的全部可变状态。 */
data class FeedUiState(
    /** 首次加载（页面还没有任何内容）时转圈。 */
    val isLoading: Boolean = false,
    /** 已有内容时的刷新，走下拉指示器，不盖住列表。 */
    val isRefreshing: Boolean = false,
    /**
     * 排序后的兴趣流。
     *
     * 这里存 [ScoredArticle] 而不是裸的 [Article]：每一篇都带着「为什么是它」
     * （各项分数 + 命中的兴趣词），列表才能在标题下面挂一句推荐理由。
     * 分数本身不进主界面，但排查「这条为什么排前面」时它是唯一依据。
     */
    val scored: List<ScoredArticle> = emptyList(),
    /** 部分来源失败：列表照常显示，顶部挂一条提示。 */
    val failures: List<FeedFailure> = emptyList(),
    /** 什么都拿不到且失败信息已经存在时，才进整页错误态。 */
    val error: String? = null,
    val lastUpdated: Long = 0L,
    /**
     * 一条内容来源都没有：没有兴趣词，也没有启用的订阅源。
     *
     * **注意「订阅源全关」本身不再是异常状态。** 只要有兴趣词，六路召回照样能
     * 填满首页 —— 这是换成兴趣流之后和「按源浏览」最大的行为差异，
     * 所以判断条件从「启用的源为空」改成了「兴趣和源同时为空」。
     */
    val noContentSource: Boolean = false,
    /**
     * 当前列表是先从磁盘缓存铺出来的，网络结果还没回来。
     * 顶栏据此把时间标成「缓存内容」。
     */
    val showingCache: Boolean = false,
    /** 本轮推荐的统计，用于抽屉里回答「首页为什么长这样」。 */
    val stats: RecommendStats? = null,
) {
    val hasContent: Boolean get() = scored.isNotEmpty()

    /** 只关心文章本身的调用点（本地过滤之外的地方）用它。 */
    val articles: List<Article> get() = scored.map { it.article }
}
