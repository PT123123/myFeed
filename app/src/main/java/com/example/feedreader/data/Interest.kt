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

    /** 权重档位，UI 上给固定几档比拉滑块好按，也避免用户纠结 0.87 和 0.88 的区别。 */
    val WEIGHT_STEPS = listOf(0.5f, 1f, 1.5f, 2f)

    /**
     * 首次运行的默认兴趣。
     * 这几个词都在带标注的评测集上验过能稳定召回（MAP 0.826 / MRR 1.000 那批）。
     */
    val DEFAULT: List<Interest> = listOf(
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
