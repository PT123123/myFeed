package com.example.feedreader.data

import android.content.Context

/**
 * 源的构建、校验与 id 生成。纯逻辑，无 Android 依赖，可直接单测。
 *
 * 和 [Interests] 一个路子：这类校验的坑全在边界上（地址带空格、http 漏了 `//`、
 * 同一个 feed 末尾多个斜杠），放在这里才能一条条钉住。
 */
object SourceRules {

    /** 自建源数量上限。比 [Interests.MAX_COUNT] 宽松得多 —— 源不参与每轮请求数的乘法。 */
    const val MAX_COUNT = 60

    const val MAX_NAME_LENGTH = 40

    /** 用户在 OPML 里没写分类、或者手动添加时留空时的兜底分类。 */
    const val FALLBACK_CATEGORY = "自建"

    fun normalizeUrl(url: String): String = url.trim()

    /**
     * 看起来像个地址。
     *
     * **不强制 https**：内置源全是 https，但用户自建的老 feed 有不少只有 http，
     * 一刀切会把它们挡在外面。这里只要求带上协议头 —— 不带协议用户以为能填
     * `example.com/feed`，那样 [FeedRepository] 拉的时候只会得到一个含糊的
     * 「Cleartext HTTP traffic not permitted」或者干脆解析失败。
     */
    fun looksLikeUrl(url: String): Boolean =
        url.startsWith("http://") || url.startsWith("https://")

    /**
     * 清掉名称/分类里的制表符和换行。
     *
     * 这不是洁癖：持久化格式拿 `\t` 当分隔符，用户往名称里粘一个制表符就能让
     * 整行错位、读回来时地址变成名称的一部分。和 [Interests.sanitize] 是同一个理由。
     */
    fun sanitizeName(raw: String): String {
        val collapsed = raw.replace(WHITESPACE, " ").trim()
        return if (collapsed.length <= MAX_NAME_LENGTH) {
            collapsed
        } else {
            collapsed.substring(0, MAX_NAME_LENGTH).trim()
        }
    }

    /** 探不出标题、用户也没填名字时，拿域名当名字 —— 比「未命名」信息量大。 */
    fun fallbackName(url: String): String {
        val host = url.substringAfter("://", "").substringBefore('/').substringBefore('?')
        return host.ifBlank { "自建源" }
    }

    /**
     * 自建源的 id，由地址派生。
     *
     * 派生而不是随机生成/自增，换来两件事：同一个 feed 不管加几次都得到同一个 id
     * （OPML 反复导入不会攒出一堆副本），以及持久化时不用存 id 这个字段
     * （文件被手改过也不会出现 id 和地址对不上的状态）。
     *
     * 取 32 位哈希的十六进制、抹掉符号位。哈希碰撞要现有几十个源才会变得可讨论，
     * 而且 [addError] 会先按**地址**判重，所以真撞了也只是 id 重复而非误判重复。
     */
    fun customId(url: String): String =
        "c" + (normalizeUrl(url).hashCode().toLong() and 0xFFFFFFFFL).toString(16)

    /**
     * 校验「这个地址能不能加进列表」。返回 null 表示可以，否则是给用户看的原因。
     *
     * @param existing 传**全部**源（内置 + 自建）—— 已经内置了的源不该再被加一遍。
     */
    fun addError(rawUrl: String, existing: List<FeedSource>): String? {
        val url = normalizeUrl(rawUrl)
        val customCount = existing.count { it.custom }
        return when {
            url.isEmpty() -> "请输入订阅源地址"
            !looksLikeUrl(url) -> "地址要以 http:// 或 https:// 开头"
            customCount >= MAX_COUNT -> "最多 $MAX_COUNT 个自建源，先删掉几个再加"
            existing.any { normalizeUrl(it.url) == url } -> "这个地址已经在列表里了"
            else -> null
        }
    }

    private val WHITESPACE = Regex("[\\s\\u3000\\u00a0]+")
}

/**
 * 自建源的文本编解码。
 *
 * 每行一条：`名称 \t 分类 \t 地址`。地址放最后一列 —— 它是唯一保证不含制表符和
 * 换行的字段，所以即使前面的字段被手改乱了，也总能从行尾把地址捞回来。
 *
 * 用文本而不是 JSON，沿用 [InterestCodec] 的既定做法：`org.json` 在本地 JVM 单测里
 * 是 stub，而手写一份 JSON 又得多维护一套解析和转义。
 */
object SourceCodec {

    fun encode(sources: List<FeedSource>): String =
        sources.joinToString("\n") { source ->
            "${SourceRules.sanitizeName(source.name)}" +
                "\t${SourceRules.sanitizeName(source.category)}" +
                "\t${source.url}"
        }

    /** 解析不出来的行跳过，不整份丢掉 —— 一行坏了不该让用户自加的源全被清空。 */
    fun decode(text: String): List<FeedSource> {
        if (text.isBlank()) return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<FeedSource>()

        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            val parts = line.split('\t')
            if (parts.size < 3) continue

            val url = SourceRules.normalizeUrl(parts.last())
            if (!SourceRules.looksLikeUrl(url)) continue
            if (!seen.add(SourceRules.customId(url))) continue

            out += FeedSource(
                id = SourceRules.customId(url),
                name = SourceRules.sanitizeName(parts[0]).ifEmpty { SourceRules.fallbackName(url) },
                url = url,
                category = SourceRules.sanitizeName(parts[1]).ifEmpty { SourceRules.FALLBACK_CATEGORY },
                custom = true,
            )
        }
        return out
    }
}

/**
 * 订阅源的持久化：用户自建的那份清单，以及「哪些源被关掉了」。
 *
 * ### 为什么存「关掉的」而不是「开着的」
 *
 * 出厂 17 个源里有 8 个默认不拉（见 [FeedSources.DEFAULT] 的说明）。如果存「开着的」，
 * 那两个后果都很难看：**改默认值对已有用户完全无效**（他们磁盘上躺着「全开」的快照），
 * 而且**以后新增的源对老用户不会自动打开**（快照里根本没有那个 id）。
 * 存「关掉的」两个问题一起消失：空集合天然表示「一个都没关」，
 * 新源默认就是开的。`InterestStore.disabledRecallChannels` 已经是这个写法，跟它对齐。
 *
 * ### 为什么和 [SettingsStore] 共用一个 prefs 文件
 *
 * 历史上班主任 —— 源的开关原来存在 `myfeed_settings` 的 `enabled_sources` 里，
 * 格式是「开着的集合」。迁移得原地读那个 key，所以这里继续用同一个文件，
 * 而不是另起一个干净的文件再想办法跨文件读。
 */
class SourceStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(SettingsStore.FILE, Context.MODE_PRIVATE)

    init {
        migrate()
    }

    /**
     * 用户自己添加的源。
     *
     * **空列表是合法状态**，所以用 `contains` 区分「从没写过」和「写过但是空的」——
     * 不能写成 `decode(...).ifEmpty { ... }`，那样用户删光之后重启会冒出东西来。
     * （内置源不在这个 key 里，它们在代码里。）
     */
    var customSources: List<FeedSource>
        get() = if (prefs.contains(KEY_CUSTOM)) {
            SourceCodec.decode(prefs.getString(KEY_CUSTOM, "").orEmpty())
        } else {
            emptyList()
        }
        set(value) {
            prefs.edit().putString(KEY_CUSTOM, SourceCodec.encode(distinctByUrl(value))).apply()
        }

    /** 内置 + 自建，内置在前（保持出厂顺序），自建在后。设置页和抓取都按这个顺序。 */
    val allSources: List<FeedSource>
        get() = FeedSources.DEFAULT + customSources

    /** 被关掉的源 id。默认值 = 出厂就标记了不默认拉的那批。 */
    var disabledIds: Set<String>
        get() = prefs.getStringSet(KEY_DISABLED, null)?.toSet() ?: FeedSources.defaultDisabledIds
        set(value) {
            prefs.edit().putStringSet(KEY_DISABLED, value).apply()
        }

    fun isEnabled(id: String): Boolean = id !in disabledIds

    fun enabledSources(): List<FeedSource> = allSources.filter { isEnabled(it.id) }

    fun setEnabled(id: String, enabled: Boolean) {
        val next = disabledIds.toMutableSet()
        if (enabled) next.remove(id) else next.add(id)
        disabledIds = next
    }

    fun findByUrl(url: String): FeedSource? {
        val normalized = SourceRules.normalizeUrl(url)
        return allSources.firstOrNull { SourceRules.normalizeUrl(it.url) == normalized }
    }

    fun findById(id: String): FeedSource? = allSources.firstOrNull { it.id == id }

    /**
     * 加入一个自建源，返回落库后的对象（id 由地址派生，调用方不用自己造）。
     *
     * 调用方应当先过 [SourceRules.addError]；这里只做幂等合并，不做校验 ——
     * 校验失败的原因是要给用户看的文案，那是 UI 层的事。
     */
    fun addCustom(url: String, name: String, category: String): FeedSource {
        val normalized = SourceRules.normalizeUrl(url)
        val source = FeedSource(
            id = SourceRules.customId(normalized),
            name = SourceRules.sanitizeName(name).ifEmpty { SourceRules.fallbackName(normalized) },
            url = normalized,
            category = SourceRules.sanitizeName(category).ifEmpty { SourceRules.FALLBACK_CATEGORY },
            custom = true,
        )
        customSources = customSources + source
        return findById(source.id) ?: source
    }

    /**
     * 删掉一个自建源。
     *
     * 顺手把它从 [disabledIds] 里摘掉 —— 否则每删一个源就留一个永远用不上的 id
     * 在偏好文件里，删删加加几十次之后那里面全是垃圾。
     */
    fun removeCustom(id: String) {
        // 只能在自建源里找：内置源不归这里管（它只该被关，不该被删）。这样即使界面层
        // 传错一个内置源的 id 进来，也只是原地返回，不会把出厂列表抹掉一项。
        val target = customSources.firstOrNull { it.id == id } ?: return
        customSources = customSources.filterNot { it.id == target.id }
        if (id in disabledIds) disabledIds = disabledIds - id
    }

    /**
     * 批量导入（OPML）。返回真正新增的条数。
     *
     * **导入不逐个探测**：OPML 是用户从别的阅读器整体搬过来的清单，本来就带着
     * 一份可用性预期；几十个源逐个 probe 就是几十次网络往返，导入按钮点下去要等半分钟。
     * 真拉不动的源会在下次刷新时进失败横幅 —— 和内置源走同一套反馈，不另造一套。
     * 手动单个添加才 probe：那是随手粘一个地址，得立刻告诉他这个地址对不对。
     */
    fun importSources(incoming: List<FeedSource>): Int {
        val known = allSources.map { SourceRules.normalizeUrl(it.url) }.toMutableSet()
        val fresh = ArrayList<FeedSource>()
        // 上限在这里也守一道：手动添加时 [SourceRules.addError] 会拦住，批量导入没有
        // 那个入口，不守的话一个几百行的 OPML 能直接把列表灌爆
        var room = (SourceRules.MAX_COUNT - customSources.size).coerceAtLeast(0)

        for (source in incoming) {
            if (room <= 0) break
            val url = SourceRules.normalizeUrl(source.url)
            if (!SourceRules.looksLikeUrl(url)) continue
            // known 里已经含内置源，所以从本应用导出的 OPML 再导回来不会把内置源复制一份
            if (!known.add(url)) continue
            fresh += FeedSource(
                id = SourceRules.customId(url),
                name = SourceRules.sanitizeName(source.name).ifEmpty { SourceRules.fallbackName(url) },
                url = url,
                category = SourceRules.sanitizeName(source.category)
                    .ifEmpty { SourceRules.FALLBACK_CATEGORY },
                custom = true,
            )
            room--
        }

        if (fresh.isNotEmpty()) customSources = customSources + fresh
        return fresh.size
    }

    /**
     * 出厂默认值改版时的迁移。
     *
     * 为什么需要版本号：**「默认关掉某个源」只会在第一次生效**。一旦 [disabledIds]
     * 被写过（用户动过任何开关），之后改 `defaultEnabled` 就再也影响不到他了。
     * 版本号一变，就把当前所有 `defaultEnabled = false` 的内置源补进禁用集合。
     *
     * 顺带在这一步把老格式（`enabled_sources`，存的是「开着的」集合）取反迁过来。
     *
     * **已知取舍**：用户如果手动打开过某个默认关闭的源，版本号一涨会被重新关掉。
     * 这个代价是有意付出的 —— 「默认别给我推营销号」比「记住我上次手动开过爱范儿」重要。
     */
    private fun migrate() {
        if (prefs.getInt(KEY_DEFAULTS_VERSION, 0) >= DEFAULTS_VERSION) return

        val legacyEnabled = prefs.getStringSet(KEY_LEGACY_ENABLED, null)
        val next = when {
            // 老格式存的是「开着的」，取反；再并上这次改版新纳入默认关闭的那批
            legacyEnabled != null -> FeedSources.DEFAULT
                .map { it.id }
                .filterNot { it in legacyEnabled }
                .toSet() + FeedSources.defaultDisabledIds

            // 全新安装：getter 的默认值已经是对的，不必写盘
            !prefs.contains(KEY_DISABLED) -> FeedSources.defaultDisabledIds

            // 以后再改默认值：把新增的默认关闭项补进去，用户自己关掉的保持关着
            else -> disabledIds + FeedSources.defaultDisabledIds
        }

        prefs.edit()
            .putStringSet(KEY_DISABLED, next)
            .putInt(KEY_DEFAULTS_VERSION, DEFAULTS_VERSION)
            .remove(KEY_LEGACY_ENABLED)
            .apply()
    }

    /** 按地址去重，保留先出现的。地址是源的真正身份，id 只是它的函数。 */
    private fun distinctByUrl(sources: List<FeedSource>): List<FeedSource> {
        val seen = HashSet<String>()
        return sources.filter { seen.add(SourceRules.normalizeUrl(it.url)) }
    }

    companion object {
        /**
         * 出厂默认值的版本号。**改任何源的 `defaultEnabled` 就要把它加一**，
         * 否则改动对已经用过应用的人无效（原因见 [migrate]）。
         */
        private const val DEFAULTS_VERSION = 1

        private const val KEY_CUSTOM = "custom_sources"
        private const val KEY_DISABLED = "disabled_sources"
        private const val KEY_DEFAULTS_VERSION = "source_defaults_version"

        /** 老格式的 key：存的是「**开着的**源 id」。只用于一次性迁移。 */
        private const val KEY_LEGACY_ENABLED = "enabled_sources"
    }
}
