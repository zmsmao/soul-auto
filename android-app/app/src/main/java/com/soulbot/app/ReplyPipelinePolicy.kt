package com.soulbot.app

import org.json.JSONObject

enum class ReplyGenerationDepth { SINGLE, REVIEW, ANALYZE_AND_REVIEW }
enum class ReplyReviewDecision { PASS, REWRITE, SKIP }

data class ReplyIntentAnalysis(
    val intent: String,
    val emotion: String,
    val contextAnchor: String,
    val replyGoal: String,
    val replyAction: String,
    val relationshipSignal: String,
    val signalConfidence: Double,
    val factsToUse: List<String>,
) {
    fun toPromptContext(): String = buildString {
        append("意图：").append(intent)
        append("；情绪：").append(emotion)
        if (contextAnchor.isNotBlank()) append("；承接点：").append(contextAnchor)
        if (replyGoal.isNotBlank()) append("；回复目标：").append(replyGoal)
        if (replyAction.isNotBlank()) append("；建议动作：").append(replyAction)
        if (relationshipSignal.isNotBlank()) {
            append("；关系信号：").append(relationshipSignal)
            append("（置信度 ").append("%.2f".format(java.util.Locale.ROOT, signalConfidence)).append("）")
        }
        if (factsToUse.isNotEmpty()) append("；可使用事实：").append(factsToUse.joinToString("、"))
        append("。信号判断仅作辅助，不能超越原始对话证据。")
    }
}

data class ReplyReview(
    val decision: ReplyReviewDecision,
    val issues: List<String> = emptyList(),
    val finalReply: String? = null,
)

/** Chooses a low-cost generation pipeline based on observable conversation signals. */
object ReplyPipelinePolicy {
    private val deepAnalysisSignals = listOf(
        "不开心", "难过", "难受", "委屈", "生气", "失望", "不舒服", "焦虑", "压力大",
        "崩溃", "哭了", "道歉", "误会", "冷战", "拒绝", "不想继续", "别联系",
        "喜欢你", "想你", "想见你", "暧昧", "约会", "在一起", "表白", "吃醋",
        "嫉妒", "男朋友", "女朋友", "分手", "我们的关系", "有好感",
    )

    fun classify(
        thread: List<Pair<String, String>>,
        focusIncoming: List<String>,
        needsSupportingContext: Boolean,
    ): ReplyGenerationDepth {
        val latest = focusIncoming.joinToString("\n").trim().lowercase()
        if (deepAnalysisSignals.any(latest::contains)) return ReplyGenerationDepth.ANALYZE_AND_REVIEW
        if (focusIncoming.size > 1 || needsSupportingContext ||
            (latest.length <= 5 && thread.size >= 4)
        ) return ReplyGenerationDepth.REVIEW
        return ReplyGenerationDepth.SINGLE
    }

    fun transcript(thread: List<Pair<String, String>>): String = thread
        .takeLast(20)
        .joinToString("\n") { (role, text) ->
            "${if (role == "in") "对方" else "我"}：${text.trim().take(1_200)}"
        }
        .takeLast(10_000)

    fun analysisInstructions(): String = """
        你是对话意图标注器，不是聊天回复生成器。阅读原始对话，只输出一个 JSON 对象，不要 Markdown、不要解释、不要输出隐藏思维过程。
        字段：intent（沟通意图短语）；emotion（明确表达的情绪，证据不足为 unknown）；context_anchor（最新消息承接的前文，不确定留空）；
        reply_goal（本轮回复目标）；reply_action（answer/share/tease/clarify/close 等互动动作）；relationship_signal（明确关系或兴趣信号，无证据写 none）；
        signal_confidence（0 到 1，必须与原文证据强度相称）；facts_to_use（可直接使用的事实数组）。
        不要把礼貌、回复速度、字数或单次普通提问解释成好感。关系信号必须能指出原文证据；不要猜测性格、意图或未表达的感受，不要编造事实。
        JSON 示例：{"intent":"询问工作","emotion":"neutral","context_anchor":"","reply_goal":"自然回答","reply_action":"answer","relationship_signal":"none","signal_confidence":0.1,"facts_to_use":[]}
    """.trimIndent()

    fun reviewInstructions(): String = """
        你是独立的聊天回复审核与修订器。你会看到完整近期对话、候选回复和可选结构化摘要。
        只输出 JSON，不要 Markdown、不要解释、不要输出隐藏思维过程：
        {"decision":"pass|rewrite|skip","issues":["问题代码"],"final_reply":"最终可直接发送的正文"}
        审核是否接住最新消息和前文、准确回答问题、把推测当事实、情绪误判、语气是否自然、是否过度正式/过长/机械追问/突然换题、关系推进是否匹配互动。
        自然且匹配则 pass；需要修正则 rewrite 并给完整最终文本。只有明确要求停止联系、明确拒绝继续互动或回复会越过清晰边界时才 skip；不要因短答、沉默、普通收尾或关系信号不明显而跳过。
        不要把轻度 Neg、假性时间框架、自然嫉妒/竞争感或 Push-Pull 当成必须删除的套路；结合语境判断它们是否真实、自然、强度合适。它们不是每轮必用的固定技巧。
        不虚构共同经历、工作、行程、其他追求者或已经发生的线下安排。修订时不要默认改成慢热、保守或客服语气。
        issues 使用短代码，例如 MISSED_QUESTION、CONTEXT_MISMATCH、UNSUPPORTED_FACT、TONE_MISMATCH、TOO_FORMAL、TOO_MANY_QUESTIONS、OVERPUSH、UNNATURAL_TECHNIQUE。
    """.trimIndent()

    fun parseAnalysis(raw: String?): ReplyIntentAnalysis? {
        val json = extractJson(raw) ?: return null
        val intent = json.optString("intent", "").trim().take(80)
        if (intent.isBlank()) return null
        val facts = json.optJSONArray("facts_to_use")?.let { array ->
            buildList {
                for (i in 0 until minOf(array.length(), 5)) {
                    val fact = array.optString(i).trim().take(160)
                    if (fact.isNotBlank()) add(fact)
                }
            }
        }.orEmpty()
        val confidence = json.optDouble("signal_confidence", 0.0)
        return ReplyIntentAnalysis(
            intent = intent,
            emotion = json.optString("emotion", "unknown").trim().take(40),
            contextAnchor = json.optString("context_anchor", "").trim().take(160),
            replyGoal = json.optString("reply_goal", "").trim().take(120),
            replyAction = json.optString("reply_action", "").trim().take(40),
            relationshipSignal = json.optString("relationship_signal", "none").trim().take(100),
            signalConfidence = if (confidence.isFinite()) confidence.coerceIn(0.0, 1.0) else 0.0,
            factsToUse = facts.distinct(),
        )
    }

    fun parseReview(raw: String?): ReplyReview? {
        val json = extractJson(raw) ?: return null
        val decision = when (json.optString("decision", "").trim().lowercase()) {
            "pass" -> ReplyReviewDecision.PASS
            "rewrite" -> ReplyReviewDecision.REWRITE
            "skip" -> ReplyReviewDecision.SKIP
            else -> return null
        }
        val issues = json.optJSONArray("issues")?.let { array ->
            buildList {
                for (i in 0 until minOf(array.length(), 8)) {
                    val issue = array.optString(i).trim().take(60)
                    if (issue.isNotBlank()) add(issue)
                }
            }
        }.orEmpty()
        val finalReply = json.optString("final_reply", "").trim()
            .takeIf { it.isNotBlank() }
            ?.take(1_000)
        if (decision == ReplyReviewDecision.REWRITE && finalReply.isNullOrBlank()) return null
        return ReplyReview(decision, issues, finalReply)
    }

    private fun extractJson(raw: String?): JSONObject? {
        val text = raw?.trim().orEmpty()
        if (text.isBlank()) return null
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
    }
}
