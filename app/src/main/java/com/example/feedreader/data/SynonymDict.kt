package com.example.feedreader.data

/**
 * 关键词的同义 / 相关词扩展表。
 *
 * 为什么需要它 —— 实测出来的两个词法缺口，纯向量模型补不上：
 *
 * 1. 兴趣词「前端开发」召不到标题里根本没写「前端」的文章（CSS 容器查询、React 19），
 *    语义余弦把「独立开发」「端到端加密」排在了它们前面。
 * 2. 兴趣词「量化交易」被「LLM 推理加速：KV Cache **量化**的三种做法」抢走词面
 *    （两处「量化」同形不同义），而「量化」是核心词没法直接去掉。
 *
 * 这类问题靠**加大模型**解决不划算（体积翻 20 倍、延迟翻 50 倍），
 * 靠一张几十 KB 的对照表就能补住。
 *
 * 另一条已被否决的路：用模型自己做「关键词 → 词表近邻」扩张。实测「大模型」的
 * top-5 近邻是 `估值模型 / 设计模式 / 重构 / 分布式系统`，P@5 = 0.00 ——
 * 孤立短词对 4 层小模型没有判别力，会退化成字面匹配。所以这里用人工对照表。
 */
class SynonymDict(private val table: Map<String, List<String>>) {

    /** 返回该词的相关词；没有收录就返回空表（不是返回原词）。 */
    fun expand(term: String): List<String> = table[normalize(term)].orEmpty()

    /** 表里收录了多少个词条，设置页可以显示一下。 */
    val size: Int get() = table.size

    companion object {
        /**
         * 归一化：小写、去首尾空白。表里的键和查询都走同一套，避免大小写不一致查不到。
         */
        fun normalize(term: String): String = term.trim().lowercase()

        /**
         * 内置对照表。刻意保持精简 —— 覆盖常见技术兴趣即可，
         * 词条太多会让词法项被稀释，反而拉低排序质量。
         */
        val DEFAULT: SynonymDict = SynonymDict(
            mapOf(
                // —— AI ——
                "ai" to listOf("人工智能", "机器学习", "深度学习", "神经网络", "llm", "大模型", "gpt", "transformer", "推理", "embedding", "agent"),
                "人工智能" to listOf("ai", "机器学习", "深度学习", "神经网络", "大模型", "llm"),
                "大模型" to listOf("llm", "gpt", "chatgpt", "transformer", "推理模型", "参数规模", "微调", "fine-tuning", "agent"),
                "机器学习" to listOf("ai", "深度学习", "神经网络", "训练", "数据集", "模型"),
                "深度学习" to listOf("机器学习", "神经网络", "ai", "训练", "反向传播"),
                "神经网络" to listOf("深度学习", "机器学习", "transformer", "注意力机制"),

                // —— 前端 ——（缺口最大的一组：「前端」这个词很少出现在标题里）
                "前端" to listOf("css", "javascript", "typescript", "react", "vue", "svelte", "浏览器", "dom", "组件", "npm", "vite", "webpack", "渲染"),
                "前端开发" to listOf("css", "javascript", "typescript", "react", "vue", "浏览器", "组件", "npm", "vite", "构建"),
                "javascript" to listOf("typescript", "node", "前端", "npm", "ecmascript"),
                "typescript" to listOf("javascript", "类型", "前端", "ts"),
                "css" to listOf("样式", "布局", "前端", "容器查询", "flex", "grid"),
                "react" to listOf("前端", "组件", "hooks", "jsx", "vue"),
                "vue" to listOf("前端", "组件", "react", "组合式"),

                // —— 后端 / 开发 ——
                "后端" to listOf("服务端", "数据库", "api", "微服务", "接口", "服务器"),
                "数据库" to listOf("sql", "postgres", "mysql", "索引", "事务", "存储", "查询优化"),
                "微服务" to listOf("分布式", "服务治理", "容器化", "kubernetes", "网关"),
                "开源" to listOf("github", "开源项目", "贡献", "许可证", "star", "社区"),
                "开源项目" to listOf("github", "开源", "贡献", "许可证", "star"),
                "架构" to listOf("设计模式", "重构", "解耦", "分层", "系统设计"),
                "重构" to listOf("代码质量", "设计模式", "技术债", "架构"),
                "测试" to listOf("单元测试", "集成测试", "覆盖率", "tdd", "断言"),

                // —— 语言 / 运行时 ——
                "rust" to listOf("cargo", "所有权", "borrow", "tokio", "异步", "内存安全"),
                "go" to listOf("golang", "goroutine", "并发", "channel"),
                "python" to listOf("pip", "django", "numpy", "异步", "cpython"),
                "kotlin" to listOf("jvm", "协程", "compose", "android"),
                "android" to listOf("kotlin", "compose", "gradle", "apk", "安卓"),

                // —— 量化 / 金融 ——（补「量化交易」被「量化」抢词面的缺口）
                "量化交易" to listOf("a股", "择时", "回测", "因子", "策略", "收益率", "持仓", "止损"),
                "量化" to listOf("回测", "因子", "策略", "收益率"),
                "股票" to listOf("a股", "港股", "美股", "指数", "板块", "行情"),
                "投资" to listOf("资产配置", "估值", "收益率", "风险", "基金"),

                // —— 隐私 / 安全 ——
                "隐私" to listOf("加密", "指纹", "追踪", "零知识", "匿名", "权限", "数据泄露"),
                "隐私安全" to listOf("加密", "端到端", "指纹", "零知识", "权限", "数据泄露", "沙箱"),
                "安全" to listOf("漏洞", "加密", "攻击", "权限", "沙箱", "注入"),
                "加密" to listOf("端到端", "aes", "rsa", "密钥", "哈希", "签名"),

                // —— 产品 / 独立开发 ——
                "独立开发" to listOf("独立开发者", "副业", "个人开发者", "月收入", "上架", "变现", "定价", "solo"),
                "独立开发者" to listOf("独立开发", "副业", "个人开发者", "变现", "定价"),
                "创业" to listOf("融资", "商业模式", "增长", "mvp", "用户"),
                "产品" to listOf("用户体验", "需求", "设计", "增长", "留存"),
                "设计" to listOf("交互", "视觉", "排版", "配色", "用户体验", "figma"),

                // —— 效率 / 工具 ——
                "效率" to listOf("工具", "自动化", "工作流", "快捷键", "笔记"),
                "工具" to listOf("插件", "脚本", "cli", "自动化", "效率"),
                "笔记" to listOf("obsidian", "markdown", "知识管理", "双链", "记录"),
            )
        )
    }
}
