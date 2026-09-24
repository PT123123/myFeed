package com.example.feedreader.recommend

import com.example.feedreader.data.Article
import com.example.feedreader.data.Interest
import com.example.feedreader.data.SynonymDict
import io.github.pt123123.semantic.TextEncoder
import io.github.pt123123.semantic.Vectors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 把文本映射成「主题维」上的稀疏向量的假编码器。
 *
 * 用它替代 ONNX 的好处是**可解释、可复现**：给定主题表，两个文本的余弦是多少能直接算出来，
 * 不像真模型那样只能接受一个黑箱数字。排序逻辑（权重组合、降级、反馈偏移）才是这里要测的，
 * 真模型的质量已经由 `Golden` 那批端到端用例把关了。
 */
private class FakeEncoder : TextEncoder {
    private val topics = listOf(
        listOf("ai", "大模型", "推理", "模型", "openai", "llm", "机器学习"),
        listOf("css", "react", "浏览器", "组件", "前端", "javascript", "布局"),
        listOf("开发", "app", "独立", "副业"),
        listOf("加密", "隐私", "指纹", "安全", "端到端"),
        listOf("红烧肉", "菜谱", "做法", "美食"),
    )

    override val dimension: Int get() = topics.size

    override fun encode(text: String): FloatArray {
        val lower = text.lowercase()
        val vector = FloatArray(topics.size)
        for (i in topics.indices) {
            for (trigger in topics[i]) {
                if (lower.contains(trigger)) vector[i] += 1f
            }
        }
        return Vectors.l2Normalize(vector)
    }
}

class LexicalScorerTest {

    private val scorer = LexicalScorer(SynonymDict.DEFAULT)

    private fun score(keyword: String, title: String, excerpt: String = "") =
        scorer.score(scorer.termsOf(keyword), title.lowercase(), excerpt.lowercase())

    /**
     * 这条是本模块存在的理由。
     *
     * 实测的缺口：兴趣词「前端开发」在纯语义下把「独立开发者的定价策略」排在了
     * 「CSS 容器查询」前面 —— 因为标题里根本没有「前端」二字，而「开发」这个
     * 泛词又恰好出现在「独立开发」里。
     *
     * 词法项必须让 CSS 那条拿到更高分，靠的是同义词 `css`（0.85）压过泛词
     * 「开发」（0.35）。如果哪天有人把两部分权重调成一样，这条会立刻红。
     */
    @Test
    fun synonymBeatsGenericPartialMatch() {
        val css = score("前端开发", "CSS 容器查询终于全平台支持了")
        val indie = score("前端开发", "独立开发者的定价策略：免费增值还是买断")

        assertTrue(
            "「CSS 容器查询」($css) 应当高于「独立开发者的定价策略」($indie)",
            css > indie,
        )
        assertTrue("同义词命中要真的给到分", css > 0.5f)
    }

    /** 「量化交易」被同形的「量化」抢词面 —— 补的是金融侧术语。 */
    @Test
    fun financeTermsDistinguishSameSurfaceWord() {
        val trading = score("量化交易", "如何用量化模型做 A 股择时")
        assertTrue("A 股择时应当命中", trading > 0.5f)
    }

    @Test
    fun unrelatedArticleScoresZero() {
        assertEquals(0f, score("前端开发", "家常红烧肉做法：记住这三步，肥而不腻"), 0f)
        assertEquals(0f, score("AI 大模型", "国庆假期全国铁路预计发送旅客 1.7 亿人次"), 0f)
    }

    @Test
    fun titleHitBeatsExcerptOnlyHit() {
        val inTitle = score("Rust", "Rust 异步运行时 tokio 发布 1.40 版本")
        val inExcerpt = score("Rust", "每周精选", "本期聊聊 Rust 的所有权模型")
        assertTrue("标题命中($inTitle) 应高于仅摘要命中($inExcerpt)", inTitle > inExcerpt)
        // 不钉死等于 EXCERPT_WEIGHT：摘要命中往往同时命中多个词项，
        // 最终值是「最优项 + 次优项 × 0.25」的合成，比单项权重高一点。
        // 这里钉的是有意义的边界 —— 仅命中摘要拿不到标题级的分。
        assertTrue("摘要命中至少要给到基础权重：$inExcerpt", inExcerpt >= LexicalScorer.EXCERPT_WEIGHT)
        assertTrue("仅摘要命中不该够到 0.5：$inExcerpt", inExcerpt < 0.5f)
    }

    @Test
    fun phraseHitIsTheStrongest() {
        val phrase = score("Rust", "Rust 1.40 发布")
        val partOnly = score("异步 Rust", "Rust 1.40 发布")
        assertTrue("整串命中($phrase) 应高于只命中片段($partOnly)", phrase > partOnly)
    }

    /** 短拉丁词必须卡词边界，否则 `ai` 会在 `said`/`email` 里乱命中。 */
    @Test
    fun shortLatinTermsRequireWordBoundary() {
        assertEquals(0f, score("ai", "He said the email detail is fine"), 0f)
        assertTrue("独立成词时应命中", score("ai", "AI 应用落地") > 0f)
    }

    /** 长拉丁词也不能用子串，否则 `rust` 会命中 `trust`。 */
    @Test
    fun latinTermsDoNotMatchInsideLongerWords() {
        assertEquals(0f, score("rust", "How to build trust in a team"), 0f)
        assertEquals(0f, score("go", "Google 发布新模型"), 0f)
    }

    /** CJK 字符天然构成词边界，所以夹在中文里的英文词应当命中。 */
    @Test
    fun latinTermSurroundedByChineseStillMatches() {
        assertTrue(score("react", "用react写的一个小工具") > 0f)
    }

    @Test
    fun splitTermsHandlesMixedScriptAndPunctuation() {
        assertEquals(listOf("ai", "大模型"), LexicalScorer.splitTerms("AI 大模型"))
        assertEquals(listOf("rust", "异步"), LexicalScorer.splitTerms("Rust-异步"))
        assertEquals(listOf("量化交易"), LexicalScorer.splitTerms("量化交易"))
        assertEquals(emptyList<String>(), LexicalScorer.splitTerms("  ——  "))
        assertEquals(listOf("c", "sharp"), LexicalScorer.splitTerms("C# sharp"))
    }

    @Test
    fun termsCarrySynonymExpansion() {
        val terms = scorer.termsOf("前端开发")
        // 中文**不切分**（见 splitTermsHandlingMixedScriptAndPunctuation 里
        // 「量化交易」不切开的约定）—— 整段当词项，靠同义词表补缺口
        assertEquals(listOf("前端开发"), terms.parts)
        assertTrue("应扩展到 css", terms.expanded.contains("css"))
        assertTrue("应扩展到 react", terms.expanded.contains("react"))
        assertFalse("不该把自身当扩展词", terms.expanded.contains("前端"))
    }

    @Test
    fun unknownKeywordStillWorksWithPartsAlone() {
        val terms = scorer.termsOf("一个没收录的词")
        assertEquals(emptyList<String>(), terms.expanded)
        assertTrue(terms.parts.isNotEmpty())
    }
}

class RankerTest {

    private val encoder = FakeEncoder()
    private val ranker = Ranker(encoder, SynonymDict.DEFAULT)

    private val now = 1_760_000_000_000L
    private val day = 24L * 3600 * 1000

    private fun article(
        id: String,
        title: String,
        excerpt: String = "",
        ageDays: Long = 0,
        source: String = "hn",
    ) = Article(
        id = id,
        title = title,
        excerpt = excerpt,
        link = "https://example.com/$id",
        author = "",
        sourceId = source,
        sourceName = source,
        category = "科技",
        publishedAt = now - ageDays * day,
    )

    private fun vectorsFor(articles: List<Article>): Map<String, FloatArray> =
        articles.associate { it.id to encoder.encode("${it.title} ${it.excerpt}") }

    private val pool = listOf(
        article("a1", "OpenAI 发布新一代推理模型，数学能力大幅提升"),
        article("a2", "LLM 推理加速：KV Cache 量化的三种做法"),
        article("a3", "CSS 容器查询终于全平台支持了"),
        article("a4", "React 19 正式发布：编译器与 Server Actions"),
        article("a5", "独立开发者的定价策略：免费增值还是买断"),
        article("a6", "端到端加密最常见的五种实现错误"),
        article("a7", "家常红烧肉做法：记住这三步，肥而不腻"),
    )

    @Test
    fun ranksRelevantAboveIrrelevant() {
        val ranked = ranker.rank(
            interests = listOf(Interest("AI 大模型")),
            articles = pool,
            articleVectors = vectorsFor(pool),
            now = now,
        )
        assertEquals("最相关的应该是两条 AI 文章", setOf("a1", "a2"), ranked.take(2).map { it.article.id }.toSet())
        assertEquals("最不相关的应该是红烧肉", "a7", ranked.last().article.id)
        assertTrue("分数应单调不增", ranked.zipWithNext().all { it.first.score >= it.second.score })
    }

    @Test
    fun respectsWeightBetweenInterests() {
        val interests = listOf(Interest("AI 大模型", 2f), Interest("隐私安全", 0.5f))
        val ranked = ranker.rank(interests, pool, vectorsFor(pool), now = now)
        val ai = ranked.first { it.article.id == "a1" }
        val privacy = ranked.first { it.article.id == "a6" }
        assertTrue(
            "权重 2f 的 AI 应压过权重 0.5f 的隐私（$ai vs $privacy）",
            ai.score > privacy.score,
        )
    }

    /** 没有向量（模型还没就绪 / 编码失败）时必须降级到词法排序，不能整屏空白。 */
    @Test
    fun degradesGracefullyWithoutVectors() {
        val ranked = ranker.rank(listOf(Interest("前端开发")), pool, emptyMap(), now = now)
        assertEquals("一篇都不能丢", pool.size, ranked.size)
        assertTrue("语义项应全为 0", ranked.all { it.semantic == 0f })
        val top = ranked.first()
        assertTrue(
            "应靠词法把 CSS/React 顶上来，实际是 ${top.article.title}",
            top.article.id in setOf("a3", "a4"),
        )
    }

    @Test
    fun emptyInterestsFallBackToTimeOrder() {
        val old = article("old", "很久以前", ageDays = 30)
        val fresh = article("fresh", "刚刚发布", ageDays = 0)
        val ranked = ranker.rank(emptyList(), listOf(old, fresh), vectorsFor(listOf(old, fresh)), now = now)
        assertEquals(listOf("fresh", "old"), ranked.map { it.article.id })
    }

    @Test
    fun recencyLiftsFreshArticlesAmongEquals() {
        val older = article("o", "Rust 1.39 发布", ageDays = 20)
        val newer = article("n", "Rust 1.40 发布", ageDays = 1)
        val ranked = ranker.rank(
            listOf(Interest("Rust")),
            listOf(older, newer),
            vectorsFor(listOf(older, newer)),
            now = now,
        )
        assertTrue("同等相关时新内容应在前", ranked.first().recency > ranked.last().recency)
    }

    @Test
    fun missingPublishedAtGetsNeutralRecency() {
        val undated = Article(
            id = "u", title = "Rust 发布", excerpt = "", link = "", author = "",
            sourceId = "x", sourceName = "x", category = "c", publishedAt = 0L,
        )
        val ranked = ranker.rank(
            listOf(Interest("Rust")),
            listOf(undated),
            vectorsFor(listOf(undated)),
            now = now,
        )
        assertEquals(0.5f, ranked.single().recency, 1e-5f)
    }

    @Test
    fun limitIsRespected() {
        val ranked = ranker.rank(listOf(Interest("AI 大模型")), pool, vectorsFor(pool), now = now, limit = 3)
        assertEquals(3, ranked.size)
    }

    /** 反馈重心（Rocchio）应把兴趣向量往点击过的方向拉。 */
    @Test
    fun feedbackShiftsInterestVector() {
        val base = ranker.interestVector("AI 大模型")
        val articles = listOf(article("p1", "端到端加密与隐私安全实践"))
        val vectors = vectorsFor(articles)
        val privacyCentroid = vectors.getValue("p1")

        val without = ranker.rank(listOf(Interest("AI 大模型")), articles, vectors, now = now)
        val with = ranker.rank(
            interests = listOf(Interest("AI 大模型")),
            articles = articles,
            articleVectors = vectors,
            feedback = mapOf(Interest("AI 大模型").id to privacyCentroid),
            feedbackStrength = 1f,
            now = now,
        )

        assertTrue(
            "加反馈后这条隐私文章的语义分应当升高（${without.single().semantic} -> ${with.single().semantic}）",
            with.single().semantic > without.single().semantic,
        )
        assertTrue("基础向量本身不该被改坏", base.size == encoder.dimension)
    }

    @Test
    fun disabledInterestsAreIgnored() {
        val ranked = ranker.rank(
            listOf(Interest("AI 大模型", enabled = false)),
            pool,
            vectorsFor(pool),
            now = now,
        )
        // 全部兴趣被禁用 = 退化成纯时间序
        assertTrue(ranked.all { it.semantic == 0f && it.topInterest.isEmpty() })
        assertEquals(pool.size, ranked.size)
    }

    @Test
    fun matchedInterestsListedByContribution() {
        val ranked = ranker.rank(
            listOf(Interest("AI 大模型"), Interest("隐私安全")),
            pool,
            vectorsFor(pool),
            now = now,
        )
        val ai = ranked.first { it.article.id == "a1" }
        assertTrue("AI 文章应命中 AI 兴趣", ai.matchedInterests.contains("AI 大模型"))
        assertEquals("贡献最大的应当是 AI", "AI 大模型", ai.topInterest)
    }

    @Test
    fun identicalScoresKeepStableOrder() {
        // 同一批数据重排两次结果必须一致，否则每次刷新顺序都在抖
        val first = ranker.rank(listOf(Interest("AI 大模型")), pool, vectorsFor(pool), now = now)
        val second = ranker.rank(listOf(Interest("AI 大模型")), pool, vectorsFor(pool), now = now)
        assertEquals(first.map { it.article.id }, second.map { it.article.id })
    }
}
