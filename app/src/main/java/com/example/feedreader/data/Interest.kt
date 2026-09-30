package com.example.feedreader.data

/**
 * 一条兴趣。**这是新的「订阅单位」** —— 取代原来按源订阅的粒度。
 *
 * 为什么换成兴趣：RSS 的病根不在协议，在于订阅粒度 = 整站。订阅 Hacker News
 * 就得接受它当天推的一切，源编辑的口味和选题范围双重绑定，所以源质量结构性
 * 不可控 —— 换几个源治不好。搜索引擎的粒度是「查询」而不是「站点」，
 * 所以把组织的轴换成兴趣词。
 *
 * [id] 直接取归一化后的关键词，好处是重复添加天然去重、不用持久化额外字段；
 * 代价是改关键词等于换了一条兴趣（对应的点击反馈也随之重置）—— 这个语义
 * 反而符合直觉：换了词就是要重新学。
 */
data class Interest(
    val keyword: String,
    val weight: Float = Interests.DEFAULT_WEIGHT,
    val enabled: Boolean = true,
) {
    val id: String get() = Interests.normalizeId(keyword)
}

/**
 * 兴趣的构建与规范化。纯逻辑，无 Android 依赖，可直接单测。
 */
object Interests {

    const val DEFAULT_WEIGHT = 1f
    const val MIN_WEIGHT = 0.2f
    const val MAX_WEIGHT = 2f
    const val MAX_KEYWORD_LENGTH = 24

    /**
     * 兴趣词数量上限。
     *
     * 一次刷新最多只会发 [EFFECTIVE_COUNT] 个查询，多出来的词连网络请求都发不出去。
     * 留出余量而不是卡在 [EFFECTIVE_COUNT] 是因为用户常常想临时关掉几个试试、
     * 或者留几个备选轮换 —— 那种操作不该被迫先删词。
     */
    const val MAX_COUNT = 12

    /**
     * 一轮刷新真正会用上的兴趣词数量。**这是召回侧的硬上限**（`RecallService.MAX_QUERIES`
     * 直接引用它，免得两处各写一份数字之后悄悄漂移）。
     *
     * 为什么是 8：请求数 = 兴趣词 × 通道数，线性增长，8 个词 × 6 路 = 48 个请求已经
     * 偏多了。继续加大召回面不如把权重调准 —— 召回的活儿是**保下限**，
     * 排序才是决定首页长什么样的那一步。设置页拿这个数告诉用户「哪几个词这轮排不上」。
     */
    const val EFFECTIVE_COUNT = 8

    /** 权重档位，UI 上给固定几档比拉滑块好按，也避免用户纠结 0.87 和 0.88 的区别。 */
    val WEIGHT_STEPS = listOf(0.5f, 1f, 1.5f, 2f)

    /**
     * 首次运行的默认兴趣（2026-09-25 重排）。
     *
     * 这是按用户真实兴趣压平后的结果——他给的层级树有近 40 个叶子词，但 [EFFECTIVE_COUNT]
     * 一轮只取 8 个，所以用「主题级关键词」压成 12 个：前 8 个启用，后 4 个关着当备用轮换
     * （凑满 [MAX_COUNT]=12）。每个词覆盖他树里的一大簇，而不是把每个叶子都塞进来。
     *
     * 提醒：Pharmacology / Drug Discovery / Hypertrophy / 金融 这几块目前「有词无源」——
     * 内置源里还没有对应领域的 feed（医学/健身/财经源在 [FeedSources.DEFAULT] 一并补）。
     * 先把词给了，让用户在设置页能选上，源那侧补完后这几块才真能出内容。
     */
    val DEFAULT: List<Interest> = listOf(
        Interest("AI Agent", 2f),
        Interest("LLM", 1.5f),
        Interest("Rust", 1.5f),
        Interest("Pharmacology", 1.5f),
        Interest("Drug Discovery", 1f),
        Interest("Hypertrophy", 1.5f),
        Interest("Semiconductor", 1f),
        Interest("金融", 1f),
        // —— 关着备用，轮换用（凑满 MAX_COUNT = 12）——
        Interest("C++", 0.5f, enabled = false),
        Interest("Neuroscience", 0.5f, enabled = false),
        Interest("Strength Training", 0.5f, enabled = false),
        Interest("Japanese", 0.5f, enabled = false),
    )

    /**
     * 2026-09-25 之前的出厂默认兴趣。只用于 [InterestStore] 的默认值迁移：
     * 判断「用户是不是还停留在旧默认」来避免覆盖个性化列表。不要删除，也不要改。
     */
    @Suppress("unused")
    val OLD_DEFAULT: List<Interest> = listOf(
        Interest("AI 大模型", 2f),
        Interest("开源项目", 1.5f),
        Interest("独立开发", 1.5f),
        Interest("隐私安全", 1f),
        Interest("Rust", 1f),
    )

    /**
     * 规范关键词：合并所有空白（含全角空格）为单个半角空格、去掉首尾空白、
     * 截断到 [MAX_KEYWORD_LENGTH]。
     *
     * 顺带把制表符和换行干掉 —— 持久化格式用 `\t` 分隔字段，关键词里混进
     * 制表符会破坏解析。
     */
    fun sanitize(raw: String): String {
        val collapsed = raw.replace(WHITESPACE, " ").trim()
        return if (collapsed.length <= MAX_KEYWORD_LENGTH) {
            collapsed
        } else {
            collapsed.substring(0, MAX_KEYWORD_LENGTH).trim()
        }
    }

    /** 关键词归一化后作为 id。大小写不敏感，所以 `Rust` 和 `rust` 是同一条兴趣。 */
    fun normalizeId(keyword: String): String = sanitize(keyword).lowercase()

    fun weightOf(value: Float): Float = value.coerceIn(MIN_WEIGHT, MAX_WEIGHT)

    /** 把任意权重吸附到最近的档位，UI 选档和外部数据迁移都用它。 */
    fun snapWeight(value: Float): Float =
        WEIGHT_STEPS.minByOrNull { kotlin.math.abs(it - value) } ?: DEFAULT_WEIGHT

    fun weightLabel(weight: Float): String = when {
        weight >= 2f -> "很高"
        weight >= 1.5f -> "较高"
        weight >= 1f -> "普通"
        else -> "较低"
    }

    /**
     * 校验「这个词能不能加进列表」。返回 null 表示可以，否则是直接给用户看的原因。
     *
     * 放在这里而不是 ViewModel 里，是为了让每一种拒绝理由都能单测 ——
     * 这类校验的坑全在边界上：全角空格、只有标点的串、大小写不同但其实是同一个词。
     */
    fun addError(raw: String, existing: List<Interest>): String? {
        val keyword = sanitize(raw)
        return when {
            keyword.isEmpty() -> "请输入兴趣词"
            keyword.none { it.isLetterOrDigit() } -> "兴趣词里至少要有汉字或字母"
            existing.size >= MAX_COUNT -> "最多 $MAX_COUNT 个兴趣词，先删掉几个再加"
            existing.any { it.id == normalizeId(keyword) } -> "「$keyword」已经在列表里了"
            else -> null
        }
    }

    /** 关键词里的所有空白（半角/全角/制表/换行）统一成半角空格。 */
    private val WHITESPACE = Regex("[\\s\\u3000\\u00a0]+")
}

/**
 * 兴趣列表的文本编解码。
 *
 * 每行一条：`权重 \t 是否启用 \t 关键词`。
 * 用文本而不是 JSON —— 与项目既有约定一致（org.json 在本地 JVM 单测里是 stub），
 * 而且这格式能在终端里直接看，排查问题方便。
 *
 * 关键词里的制表符已被 [Interests.sanitize] 清掉，所以分隔符不会冲突。
 */
object InterestCodec {

    private const val ENABLED = "1"
    private const val DISABLED = "0"

    fun encode(interests: List<Interest>): String =
        interests.joinToString("\n") { interest ->
            "${interest.weight}\t${if (interest.enabled) ENABLED else DISABLED}\t${interest.keyword}"
        }

    /** 解析失败的行直接跳过，不整份丢掉 —— 一行坏了不该让用户的兴趣列表全清空。 */
    fun decode(text: String): List<Interest> {
        if (text.isBlank()) return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<Interest>()

        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            val parts = line.split('\t')
            if (parts.size < 3) continue

            val weight = parts[0].toFloatOrNull() ?: continue
            val keyword = Interests.sanitize(parts.slice(2 until parts.size).joinToString("\t"))
            if (keyword.isEmpty()) continue
            // 同一关键词出现多次时保留第一条
            if (!seen.add(Interests.normalizeId(keyword))) continue

            out += Interest(
                keyword = keyword,
                weight = Interests.weightOf(weight),
                enabled = parts[1] == ENABLED,
            )
        }
        return out
    }
}
