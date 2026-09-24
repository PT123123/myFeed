package com.example.feedreader.data.recall

import com.example.feedreader.data.Interest
import com.example.feedreader.data.SynonymDict
import com.example.feedreader.recommend.LexicalScorer

/**
 * 一次关键词召回请求。
 *
 * [keyword] 是用户写的原词，[alternates] 是词表扩出来的相关词。
 * 两者在**召回阶段**和**排序阶段**的用法完全不同，别混：
 *  - 召回阶段只能挑 **一个** 串发出去。每个通道 × 每个变体都是一次网络请求，
 *    兴趣词 5 个 × 变体 10 个 × 通道 6 个 = 300 个请求，手机上直接废掉。
 *  - 排序阶段才把所有变体拿来打分（那边只算字符串匹配，不要钱）。
 *
 * 这里复用 [LexicalScorer.splitTerms] 做切词，是为了让「召回用的变体」和
 * 「排序用的词项」出自同一套切词规则 —— 两处规则不一致的话，会出现
 * 「召回来的文章排序时不认」这种极难查的错位。
 */
data class RecallQuery(
    val keyword: String,
    val alternates: List<String> = emptyList(),
    /** 每条通道最多要几条（通道自己的上限通常更小，取两者中的小值）。 */
    val limit: Int = DEFAULT_LIMIT,
) {

    /**
     * 发往**英文语料**的查询串。
     *
     * 「大模型」直接发给 GitHub 搜索是空手而归（仓库描述里极少出现中文），
     * 而词表扩出来的 `llm` 能召回一堆。这个替换必须发生在**发请求之前** ——
     * 排序阶段的语义扩展只能对已经召回来的东西重排，召不回来就无从谈起。
     *
     * 注意这里挑的是「第一个纯 ASCII 的相关词」，不是翻译。词表是人工对照的
     * 相关词（`大模型 → llm/gpt/transformer/…`），所以召回面会宽一些，
     * 多出来的噪声由排序阶段兜。
     */
    val latin: String
        get() = (listOf(keyword) + alternates).firstOrNull(::isAsciiTerm) ?: keyword

    companion object {
        const val DEFAULT_LIMIT = 20

        fun of(interest: Interest, synonyms: SynonymDict, limit: Int = DEFAULT_LIMIT): RecallQuery =
            of(interest.keyword, synonyms, limit)

        /** 展开成查询串：原词永远排第一，后面是去重后的词表相关词。 */
        fun of(keyword: String, synonyms: SynonymDict, limit: Int = DEFAULT_LIMIT): RecallQuery {
            val trimmed = keyword.trim()
            if (trimmed.isEmpty()) return RecallQuery("", emptyList(), limit)

            val self = SynonymDict.normalize(trimmed)
            val expanded = LinkedHashSet<String>()
            for (part in LexicalScorer.splitTerms(trimmed)) {
                for (candidate in synonyms.expand(part)) {
                    val normalized = SynonymDict.normalize(candidate)
                    // 扩出来的词里可能就有原词本身（比如 "ai" 展开含 "ai" 的近义写法），剔掉
                    if (normalized.isNotEmpty() && normalized != self) expanded += normalized
                }
            }
            return RecallQuery(trimmed, expanded.toList(), limit)
        }

        /**
         * 纯 ASCII 才算「能发给英文语料」。
         *
         * 不能只看 `isAscii()` —— 「a股」这种中英混排的串发过去同样搜不到，
         * 而且它常常排在词表第一位，会让 [latin] 误选。
         */
        private fun isAsciiTerm(term: String): Boolean =
            term.isNotEmpty() && term.all { it.code < 0x80 }
    }
}
