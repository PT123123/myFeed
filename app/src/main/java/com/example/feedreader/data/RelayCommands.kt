package com.example.feedreader.data

/**
 * 手机向电脑下的采集命令：回包形状与解析。
 *
 * 对面是 crawlbase 仓库 `harvest/command.py`，信封与 CLI 同一个 `harvest/1` 形状：
 * `{"protocol","ok","action","data","meta"}`，失败时 `error{code,message}` 在顶层。
 * 这一层只把回包翻成本 App 的类型，**不碰网络**（在 `RelayCommandClient`），
 * 也**不写文案**（在 `RelayStatus`）—— 因为回包形状属于另一个仓库，
 * 那边改了这里要能用单测先红，而文案改词不该惊动判定。
 */
object RelayCommands {

    /** 三条命令。名字与对面 `/command/<action>` 一一对上。 */
    const val ACTION_PLAN = "plan"
    const val ACTION_REGEN = "regen"
    const val ACTION_RUN = "run"
    val ACTIONS = listOf(ACTION_PLAN, ACTION_REGEN, ACTION_RUN)

    /**
     * 手机上的一条源 → 电脑上的 feed id。
     *
     * harvest 写产物时用的就是 feed id 当文件名（`zhihu-sample-zhi-75-54.xml`），
     * 而手机记着的是这条源的地址，所以去扩展名即 id。
     */
    fun feedIdOf(endpoint: String): String = endpoint.substringBeforeLast('.').trim()

    /**
     * 跳过原因对用户怎么说。
     *
     * 对面 `Safety.should_run` 返回的是「机器名: 一句中文」，所以这里只认冒号前那段；
     * 后半句每条源都不一样（「还差 3 分钟」「还差 5 分钟」），拿去分组会把一类拆成十行。
     * 键集合的出处是 crawlbase 的 `harvest/safety.py`，测试里钉了一份对照，
     * 那边新增原因时这边会红 —— 理由同 `RelayGuidanceTest` 的命令白名单。
     */
    private val REASONS = mapOf(
        "due" to "到点了",
        "forced" to "强制",
        "disabled" to "已停用",
        "suspended" to "停机",
        "needs_login" to "要登录",
        "cooling" to "冷却中",
        "daily_cap" to "今日次数已满",
        "interval" to "未到间隔",
    )

    fun reasonKey(reason: String): String = reason.substringBefore(':').trim()

    /**
     * 这边认识哪些跳过原因。
     *
     * 暴露出来只为一件事：单元测试里钉着 crawlbase `harvest/safety.py` 那份键的抄本，
     * 那边新增原因时这颗测试会红，提醒这边补一句中文说法 —— 不然新原因在手机上
     * 变成一串英文，而用户无从知道它挡住了什么。
     */
    fun knownReasonKeys(): Set<String> = REASONS.keys

    fun reasonLabel(reason: String): String =
        REASONS[reasonKey(reason)] ?: reason.ifBlank { "没说原因" }

    /** 一次命令的回包。 */
    sealed interface Reply {
        /** 这轮谁会跑、谁被跳过、为什么。 */
        data class Plan(val rows: List<PlanRow>) : Reply

        /** 离线重出了多少条产物。 */
        data class Regen(val feeds: Int) : Reply

        /** 已被受理，真结果靠轮询状态。 */
        data class Accepted(val feed: String?) : Reply

        /** 已经有一轮在跑。 */
        data class Busy(val currentFeed: String?) : Reply

        /** 电脑听懂了但拒绝：缺 token、没开命令口、feed 不存在之类。 */
        data class Refused(val code: String, val message: String) : Reply

        /** 没连上 / 超时 / HTTP 不是 2xx —— [message] 交给 `RelayGuidance.issueFromFetchMessage`。 */
        data class Transport(val message: String) : Reply

        /** 连上了但读不懂回包：对面协议改了，或中间有个代理塞了 HTML。 */
        data class Unreadable(val message: String) : Reply
    }

    data class PlanRow(
        val feed: String,
        val wouldRun: Boolean,
        val reason: String,
        val knownItems: Int,
        val lastStatus: String,
    )

    /** `GET /command/status` 的摘要，轮询用。 */
    data class Status(
        val running: Boolean,
        val currentFeed: String?,
        val startedAt: Long?,
        val last: Last?,
    ) {
        data class Last(
            val action: String,
            val ok: Boolean,
            val feed: String?,
            val ran: Int,
            val skipped: Int,
            val needsLogin: Int,
            val errors: Int,
            val newItems: Int,
            val seconds: Int,
            val finishedAt: Long?,
            val message: String,
        )
    }

    /**
     * [httpStatus] 是 HTTP 码，[body] 是原文。
     *
     * 认码的顺序是有理由的：401/404/409 都带 JSON 信封，先按信封读；
     * 只有信封读不动时才退回「按码说一句话」—— 对面换了措辞不该让这边变成空白。
     */
    fun parseReply(httpStatus: Int, body: String): Reply {
        val envelope = runCatching { MiniJson.objectOf(MiniJson.parse(body)) }
            .getOrElse { return Reply.Unreadable(it.message ?: body.take(80)) }
        if (envelope == null) return Reply.Unreadable("回包不是一个对象")

        val error = MiniJson.objectOf(envelope["error"])
        if (error != null || !MiniJson.boolean(envelope, "ok")) {
            val code = MiniJson.string(error ?: emptyMap<String, Any?>(), "code", "RUNTIME_ERROR")
            val message = MiniJson.string(error ?: emptyMap<String, Any?>(), "message", "")
            return when {
                httpStatus == 409 -> Reply.Busy(currentFeedOf(envelope))
                code.isNotBlank() && code != "RUNTIME_ERROR" -> Reply.Refused(code, message)
                message.isNotBlank() -> Reply.Refused(code, message)
                else -> Reply.Unreadable("电脑没说清为什么拒绝")
            }
        }

        val data = MiniJson.objectOf(envelope["data"]) ?: emptyMap<String, Any?>()
        return when (MiniJson.string(envelope, "action")) {
            ACTION_PLAN -> Reply.Plan(
                (MiniJson.listOf(data["plan"]) ?: emptyList<Any?>()).mapNotNull { row ->
                    val map = MiniJson.objectOf(row) ?: return@mapNotNull null
                    PlanRow(
                        feed = MiniJson.string(map, "feed"),
                        wouldRun = MiniJson.boolean(map, "would_run"),
                        reason = MiniJson.string(map, "reason"),
                        knownItems = MiniJson.int(map, "known_items"),
                        lastStatus = MiniJson.string(map, "last_status"),
                    )
                },
            )

            ACTION_REGEN -> Reply.Regen(
                (MiniJson.objectOf(data["outputs"]) ?: emptyMap<String, Any?>()).size,
            )

            ACTION_RUN -> Reply.Accepted(
                feed = MiniJson.string(MiniJson.objectOf(data["job"]) ?: data, "feed")
                    .takeIf { it.isNotBlank() },
            )

            else -> Reply.Unreadable("认不回包是哪条命令的结果")
        }
    }

    /** 状态回包：读不懂就返回 null，让调用方保留上一份而不是显示半截。 */
    fun parseStatus(body: String): Status? {
        val envelope = runCatching { MiniJson.objectOf(MiniJson.parse(body)) }.getOrNull()
            ?: return null
        val data = MiniJson.objectOf(envelope["data"]) ?: return null
        val last = MiniJson.objectOf(data["last"])
        return Status(
            running = MiniJson.boolean(data, "running"),
            currentFeed = MiniJson.string(
                MiniJson.objectOf(data["current"]) ?: emptyMap<String, Any?>(), "feed",
            ).takeIf { it.isNotBlank() },
            startedAt = MiniJson.millis(
                MiniJson.objectOf(data["current"]) ?: emptyMap<String, Any?>(), "started_at",
            ),
            last = last?.let {
                Status.Last(
                    action = MiniJson.string(it, "action"),
                    ok = MiniJson.boolean(it, "ok"),
                    feed = MiniJson.string(it, "feed").takeIf { value -> value.isNotBlank() },
                    ran = MiniJson.int(it, "ran"),
                    skipped = MiniJson.int(it, "skipped"),
                    needsLogin = MiniJson.int(it, "needs_login"),
                    errors = MiniJson.int(it, "errors"),
                    newItems = MiniJson.int(it, "new_items"),
                    seconds = MiniJson.int(it, "seconds"),
                    finishedAt = MiniJson.millis(it, "finished_at"),
                    message = MiniJson.string(
                        MiniJson.objectOf(it["error"]) ?: emptyMap<String, Any?>(), "message",
                    ),
                )
            },
        )
    }

    private fun currentFeedOf(envelope: Map<*, *>): String? =
        MiniJson.string(
            MiniJson.objectOf(MiniJson.objectOf(envelope["data"])?.get("current"))
                ?: emptyMap<String, Any?>(), "feed",
        ).takeIf { it.isNotBlank() }
}
