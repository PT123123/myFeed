package com.example.feedreader.recommend

import com.example.feedreader.data.Article
import com.example.feedreader.data.Interest
import com.example.feedreader.data.SynonymDict
import io.github.pt123123.semantic.TextEncoder
import io.github.pt123123.semantic.Vectors
import kotlin.math.abs
import kotlin.math.exp

/**
 * 三项的配比。
 *
 * 语义占大头但不独占 —— 实测纯语义会漏掉两处：
 *  - 「前端开发」召不到标题里没写「前端」的文章
 *  - 「量化交易」被同形的「量化」(quantization) 抢走
 * 这两处只有词法项能补，所以给了 0.25 的权重。
 *
 * 时间项只用来让新鲜内容上浮，权重不宜高，否则会盖掉长尾的相关内容。
 */
data class RankWeights(
    val semantic: Float = 0.60f,
    val lexical: Float = 0.25f,
    val recency: Float = 0.15f,
)

/**
 * 一条被排序过的文章，附带各项分数和推荐理由。
 * 分数拆开保留是为了在 UI 上说清「为什么推这条」，也方便调参时定位是哪一项在起作用。
 */
data class ScoredArticle(
    val article: Article,
    val score: Float,
    val semantic: Float,
    val lexical: Float,
    val recency: Float,
    /** 贡献最大的兴趣词，UI 显示成「因为你关注 X」。 */
    val topInterest: String,
    /** 所有命中过的兴趣词，按贡献降序。 */
    val matchedInterests: List<String>,
)

/**
 * 兴趣词与文章的匹配打分。
 *
 * 设计上有一处硬约束：**必须能用假的 [TextEncoder] 跑完整单测**。
 * 所以这里只依赖 `TextEncoder` 接口，不碰 ONNX。
 */
class Ranker(
    private val encoder: TextEncoder,
    private val synonyms: SynonymDict = SynonymDict.DEFAULT,
    private val weights: RankWeights = RankWeights(),
    /** 时间衰减常数：约 3 天。越大越不看重时效。 */
    private val recencyTauMs: Double = 3.0 * 24 * 3600 * 1000,
) {

    /** 关键词 -> 已归一化的兴趣向量。编码是这里唯一的重活，按词缓存。 */
    private val interestVectorCache = HashMap<String, FloatArray>(32)

    private val lexicalScorer = LexicalScorer(synonyms)

    /**
     * 给一批文章打分排序。
     *
     * @param articleVectors 文章 id -> **已归一化**的句向量。缺向量的文章不会丢，
     *   只是语义项记 0、靠词法项参与排序（模型还没准备好或编码失败时的降级路径）。
     * @param feedback 兴趣 id -> 归一化的反馈重心（用户点击累积出来的）。冷启动时为空。
     * @param feedbackStrength 反馈重心介入的强度，0 表示纯用关键词本身。
     */
    fun rank(
        interests: List<Interest>,
        articles: List<Article>,
        articleVectors: Map<String, FloatArray>,
        feedback: Map<String, FloatArray> = emptyMap(),
        feedbackStrength: Float = DEFAULT_FEEDBACK_STRENGTH,
        now: Long = System.currentTimeMillis(),
        limit: Int = Int.MAX_VALUE,
    ): List<ScoredArticle> {
        val active = interests.filter { it.enabled && it.keyword.isNotBlank() }

        // 一条兴趣都没有：退化成纯时间序，不要返回空列表（空首页比乱序更糟）
        if (active.isEmpty()) {
            return articles.sortedByDescending { it.publishedAt }
                .take(limit)
                .map {
                    ScoredArticle(
                        article = it,
                        score = recencyOf(it, now),
                        semantic = 0f,
                        lexical = 0f,
                        recency = recencyOf(it, now),
                        topInterest = "",
                        matchedInterests = emptyList(),
                    )
                }
        }

        val profiles = buildProfiles(active, feedback, feedbackStrength)

        val scored = articles.map { article ->
            val vector = articleVectors[article.id]
            val titleLower = article.title.lowercase()
            val excerptLower = article.excerpt.lowercase()

            var bestSemantic = 0f
            var bestLexical = 0f
            var topInterest = ""
            var bestRelevance = 0f
            val matched = ArrayList<Pair<String, Float>>(4)

            for (profile in profiles) {
                // 维度不符就按「没有向量」处理。VectorStore 已经拦了一层，
                // 这里再拦一次是因为 Vectors.dot 不带长度校验，越界会直接崩在刷新流程里。
                val semantic = if (vector == null || vector.size != profile.vector.size) {
                    0f
                } else {
                    Vectors.dot(profile.vector, vector)
                }
                val lexical = lexicalScorer.score(profile.terms, titleLower, excerptLower)

                val relevance = weights.semantic * semantic + weights.lexical * lexical
                // 兴趣权重只缩放「这条兴趣和这篇文章有多相关」，**不缩放时间项** ——
                // 时间是文章自身的属性，跟用户有多在意某个兴趣无关。
                // 取「加权后的最大相关度」，而不是把语义项和词法项各自取最大再相加：
                // 后者会让两个不同兴趣的最高分拼成一个分数，权重就没法正确体现强弱了。
                val weighted = profile.weight * relevance

                if (weighted > bestRelevance) {
                    bestRelevance = weighted
                    bestSemantic = semantic
                    bestLexical = lexical
                    topInterest = profile.keyword
                }
                if (semantic >= MATCH_SEMANTIC_FLOOR || lexical > 0f) {
                    matched += profile.keyword to relevance
                }
            }

            val recency = recencyOf(article, now)
            ScoredArticle(
                article = article,
                score = bestRelevance + weights.recency * recency,
                semantic = bestSemantic,
                lexical = bestLexical,
                recency = recency,
                topInterest = topInterest,
                matchedInterests = matched.sortedByDescending { it.second }.map { it.first },
            )
        }

        // 同分时用发布时间兜底，保证顺序稳定（否则同一批数据每次刷新顺序都在抖）
        return scored.sortedWith(
            compareByDescending<ScoredArticle> { it.score }
                .thenByDescending { it.article.publishedAt }
                .thenBy { it.article.id }
        ).take(limit)
    }

    /** 取某个关键词的兴趣向量（带缓存）。UI 想预览「这个词会推什么」时用得上。 */
    fun interestVector(keyword: String): FloatArray =
        interestVectorCache.getOrPut(keyword) { encoder.encode(keyword) }

    // ------------------------------------------------------------------ 内部

    private class InterestProfile(
        val keyword: String,
        /** 归一化后的权重（已除以最大权重），范围 (0, 1]。 */
        val weight: Float,
        val vector: FloatArray,
        val terms: LexicalScorer.Terms,
    )

    /**
     * 把每条兴趣算成一个「画像」：归一化的权重 × 向量 + 词项表。
     *
     * 权重的处理方式是**除以最大权重**，而不是除以权重之和。这样加权重只影响
     * 兴趣之间的相对强弱，不会因为多加了几个兴趣词就把整体分数压下去。
     */
    private fun buildProfiles(
        active: List<Interest>,
        feedback: Map<String, FloatArray>,
        feedbackStrength: Float,
    ): List<InterestProfile> {
        val maxWeight = active.maxOf { it.weight }.coerceAtLeast(1e-3f)

        return active.map { interest ->
            val base = interestVector(interest.keyword)
            val centroid = feedback[interest.id]
            val blended = if (centroid == null || feedbackStrength <= 0f) {
                base
            } else {
                // Rocchio 式加权：向量重心往用户点击过的方向偏
                Vectors.l2Normalize(
                    FloatArray(base.size) { i -> base[i] + feedbackStrength * centroid[i] }
                )
            }
            InterestProfile(
                keyword = interest.keyword,
                weight = interest.weight / maxWeight,
                vector = blended,
                terms = lexicalScorer.termsOf(interest.keyword),
            )
        }
    }

    /**
     * 时间项：指数衰减到 [0, 1]。
     * 没有发布时间的文章给中性值 0.5 —— 既不能当新内容推上去，也不该被当成陈年旧闻埋掉。
     */
    private fun recencyOf(article: Article, now: Long): Float {
        if (article.publishedAt <= 0L) return NEUTRAL_RECENCY
        val age = (now - article.publishedAt).coerceAtLeast(0L).toDouble()
        return exp(-age / recencyTauMs).toFloat()
    }

    companion object {
        const val DEFAULT_FEEDBACK_STRENGTH = 0.35f
        private const val NEUTRAL_RECENCY = 0.5f

        /** 语义余弦超过这个值才算「命中」，用于生成推荐理由。 */
        private const val MATCH_SEMANTIC_FLOOR = 0.28f
    }
}

/**
 * 词法匹配打分。
 *
 * 两类词分开处理，因为匹配语义不同：
 * - **CJK 词**走子串匹配。「大模型」出现在「大模型的参数规模」里就是命中，没有词边界概念。
 * - **拉丁词**一律卡词边界（`(?<![a-z0-9])…(?![a-z0-9])`）。不能用子串：`rust` 会命中
 *   `trust`。代价是 `OpenAI` 里的 `ai` 不算词法命中，但那种情况语义项通常已经有分。
 *
 * 打分用 **max + 次优加分**，不用「命中数 / 词项数」那种平均式。原因是平均式有两个坑：
 *  1. 同义词扩得越多、分母越大，命中反而被稀释，扩展变成负作用；
 *  2. 关键词切出来的泛词会盖过真正有区分度的信号。
 *  实例：兴趣词「前端开发」切出泛词「开发」，它会命中「独立开发者的定价策略」，
 *  而真正该命中的「CSS 容器查询」只能靠同义词 `css` 命中。
 *  按平均式两条各得 0.5 / 0.35，泛词那条反而更高；换成 max 之后
 *  `css`(0.85) 稳压泛词(0.35)，排序就对了。
 */
class LexicalScorer(private val synonyms: SynonymDict) {

    /**
     * 一个关键词拆成的三档词项。
     * 分档是因为它们的证据强度差得远，不能混在一起算。
     */
    data class Terms(
        /** 完整关键词（归一化后）。整串命中是最强证据。 */
        val phrase: String,
        /** 按 CJK/ASCII 切出来的片段。泛词多，证据弱。 */
        val parts: List<String>,
        /** 同义词表扩展出来的词。人工筛过，证据较强。 */
        val expanded: List<String>,
    ) {
        val isEmpty: Boolean get() = phrase.isEmpty() && parts.isEmpty() && expanded.isEmpty()
    }

    private val regexCache = HashMap<String, Regex>(64)

    fun termsOf(keyword: String): Terms {
        val parts = splitTerms(keyword)
        if (parts.isEmpty()) return Terms("", emptyList(), emptyList())

        val expanded = ArrayList<String>(8)
        val seen = HashSet<String>(16)
        for (part in parts) {
            for (candidate in synonyms.expand(part)) {
                val normalized = SynonymDict.normalize(candidate)
                if (normalized.isNotEmpty() && seen.add(normalized)) expanded += normalized
            }
        }
        return Terms(phrase = parts.joinToString(" "), parts = parts, expanded = expanded)
    }

    fun score(terms: Terms, titleLower: String, excerptLower: String): Float {
        if (terms.isEmpty) return 0f

        var best = 0f
        var second = 0f

        fun consider(term: String, weight: Float) {
            val strength = hitStrength(term, titleLower, excerptLower)
            if (strength <= 0f) return
            val value = weight * strength
            if (value > best) {
                second = best
                best = value
            } else if (value > second) {
                second = value
            }
        }

        if (terms.phrase.isNotEmpty()) consider(terms.phrase, PHRASE_WEIGHT)
        terms.parts.forEach { consider(it, PART_WEIGHT) }
        terms.expanded.forEach { consider(it, SYNONYM_WEIGHT) }

        // 多重命中额外加分，但加得克制：避免堆同义词刷分
        return (best + SECOND_BEST_BONUS * second).coerceAtMost(1f)
    }

    /** 标题命中记 1.0，只在摘要里命中记 0.4。 */
    private fun hitStrength(term: String, titleLower: String, excerptLower: String): Float = when {
        contains(titleLower, term) -> TITLE_WEIGHT
        contains(excerptLower, term) -> EXCERPT_WEIGHT
        else -> 0f
    }

    private fun contains(haystackLower: String, term: String): Boolean {
        if (haystackLower.isEmpty()) return false
        // CJK 词走子串：中文没有词边界概念，「大模型」出现在「大模型的参数规模」里就是命中
        if (term.any { isCjk(it) }) return haystackLower.contains(term)

        // 拉丁词一律卡词边界。不按长度区分 —— `rust` 用子串会命中 `trust`，
        // 这类假阳性比漏掉 `OpenAI` 里的 `ai` 更伤（后者语义项通常已经拿到分）。
        // 边界只认 [a-z0-9]，所以 CJK 字符天然构成边界：「用react写」能命中 `react`。
        val regex = regexCache.getOrPut(term) {
            Regex("(?<![a-z0-9])" + Regex.escape(term) + "(?![a-z0-9])")
        }
        return regex.containsMatchIn(haystackLower)
    }

    companion object {
        /** 标题是编辑挑出来的核心信息，命中它比命中摘要强得多。 */
        const val TITLE_WEIGHT = 1f
        const val EXCERPT_WEIGHT = 0.4f

        /** 完整关键词命中。 */
        const val PHRASE_WEIGHT = 1f
        /** 切分词命中：泛词多，权重压低，避免「开发」压过「css」。 */
        const val PART_WEIGHT = 0.35f
        /** 同义词命中：人工筛过，权重接近短语。 */
        const val SYNONYM_WEIGHT = 0.85f

        /** 次优命中只加两成，够体现「多处命中更可信」，又不至于堆词刷分。 */
        const val SECOND_BEST_BONUS = 0.25f

        /**
         * 把关键词切成词项：ASCII 字母数字连成一段，CJK 连续段也算一段
         * （中文不做分词，整段当词用 —— 关键词本身就是短查询，够用且不会切错）。
         */
        fun splitTerms(keyword: String): List<String> {
            val out = ArrayList<String>(4)
            val sb = StringBuilder()

            fun flush() {
                if (sb.isNotEmpty()) {
                    out += sb.toString().lowercase()
                    sb.clear()
                }
            }

            var i = 0
            var lastWasCjk = false
            while (i < keyword.length) {
                val cp = Character.codePointAt(keyword, i)
                i += Character.charCount(cp)
                val cjk = isCjk(cp)
                val alnum = !cjk && Character.isLetterOrDigit(cp)

                when {
                    cjk -> {
                        if (!lastWasCjk) flush()
                        sb.append(Character.toChars(cp))
                        lastWasCjk = true
                    }
                    alnum -> {
                        if (lastWasCjk) flush()
                        sb.append(Character.toChars(cp))
                        lastWasCjk = false
                    }
                    else -> {
                        flush()
                        lastWasCjk = false
                    }
                }
            }
            flush()
            return out.filter { it.isNotEmpty() }
        }

        private fun isCjk(cp: Int): Boolean =
            cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF ||
                cp in 0xF900..0xFAFF || cp in 0x3000..0x303F

        private fun isCjk(ch: Char): Boolean = isCjk(ch.code)
    }
}
