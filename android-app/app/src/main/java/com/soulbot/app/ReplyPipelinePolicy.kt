package com.soulbot.app

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
    const val ANALYZER_BUDGET_MS = 15_000L
    const val WRITER_BUDGET_MS = 35_000L
    const val REVIEWER_BUDGET_MS = 25_000L

    fun stageDeadline(
        nowElapsedMs: Long,
        overallDeadlineElapsedMs: Long,
        stageBudgetMs: Long,
    ): Long = minOf(overallDeadlineElapsedMs, nowElapsedMs + stageBudgetMs.coerceAtLeast(1L))

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
        可保留自然的轻度调侃、接梗和双方都接受的暧昧；不要为了“推进”而编造时间压力、其他追求者或现实借口，也不要通过故意冷热、失联或嫉妒制造焦虑。是否保留某种表达，以事实有依据、语境自然、对方接受为准。
        不虚构共同经历、工作、行程、其他追求者或已经发生的线下安排。修订时不要默认改成慢热、保守或客服语气。
        issues 使用短代码，例如 MISSED_QUESTION、CONTEXT_MISMATCH、UNSUPPORTED_FACT、TONE_MISMATCH、TOO_FORMAL、TOO_MANY_QUESTIONS、OVERPUSH、UNNATURAL_TECHNIQUE。
    """.trimIndent()

    fun parseAnalysis(raw: String?): ReplyIntentAnalysis? {
        val json = extractJson(raw) ?: return null
        val intent = (json["intent"] as? String).orEmpty().trim().take(80)
        if (intent.isBlank()) return null
        val facts = (json["facts_to_use"] as? List<*>)
            .orEmpty()
            .take(5)
            .mapNotNull { (it as? String)?.trim()?.take(160)?.takeIf(String::isNotBlank) }
        val confidence = (json["signal_confidence"] as? Number)?.toDouble() ?: 0.0
        return ReplyIntentAnalysis(
            intent = intent,
            emotion = (json["emotion"] as? String).orEmpty().ifBlank { "unknown" }.trim().take(40),
            contextAnchor = (json["context_anchor"] as? String).orEmpty().trim().take(160),
            replyGoal = (json["reply_goal"] as? String).orEmpty().trim().take(120),
            replyAction = (json["reply_action"] as? String).orEmpty().trim().take(40),
            relationshipSignal = (json["relationship_signal"] as? String).orEmpty().ifBlank { "none" }.trim().take(100),
            signalConfidence = if (confidence.isFinite()) confidence.coerceIn(0.0, 1.0) else 0.0,
            factsToUse = facts.distinct(),
        )
    }

    fun parseReview(raw: String?): ReplyReview? {
        val json = extractJson(raw) ?: return null
        val decision = when ((json["decision"] as? String).orEmpty().trim().lowercase()) {
            "pass" -> ReplyReviewDecision.PASS
            "rewrite" -> ReplyReviewDecision.REWRITE
            "skip" -> ReplyReviewDecision.SKIP
            else -> return null
        }
        val issues = (json["issues"] as? List<*>)
            .orEmpty()
            .take(8)
            .mapNotNull { (it as? String)?.trim()?.take(60)?.takeIf(String::isNotBlank) }
        val finalReply = (json["final_reply"] as? String)?.trim()
            ?.takeIf(String::isNotBlank)
            ?.take(1_000)
        if (decision == ReplyReviewDecision.REWRITE && finalReply.isNullOrBlank()) return null
        return ReplyReview(decision, issues, finalReply)
    }

    private fun extractJson(raw: String?): Map<String, Any?>? {
        val text = raw?.trim()?.take(20_000).orEmpty()
        if (text.isBlank()) return null
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            JsonObjectReader(text.substring(start, end + 1)).parseRoot()
        }.getOrNull()
    }

    /**
     * Small bounded JSON reader for the analyzer/reviewer contract.
     * It avoids android.jar's mocked org.json methods in local JVM tests and
     * rejects malformed or excessively nested model output.
     */
    private class JsonObjectReader(private val source: String) {
        private var index = 0

        fun parseRoot(): Map<String, Any?> {
            skipWhitespace()
            val result = parseObject(0)
            skipWhitespace()
            require(index == source.length) { "Trailing JSON data" }
            return result
        }

        private fun parseObject(depth: Int): Map<String, Any?> {
            require(depth <= 8) { "JSON nesting too deep" }
            expect('{')
            skipWhitespace()
            val result = linkedMapOf<String, Any?>()
            if (consume('}')) return result
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                result[key] = parseValue(depth + 1)
                skipWhitespace()
                if (consume('}')) break
                expect(',')
            }
            return result
        }

        private fun parseArray(depth: Int): List<Any?> {
            require(depth <= 8) { "JSON nesting too deep" }
            expect('[')
            skipWhitespace()
            val result = mutableListOf<Any?>()
            if (consume(']')) return result
            while (true) {
                skipWhitespace()
                result += parseValue(depth + 1)
                skipWhitespace()
                if (consume(']')) break
                expect(',')
            }
            return result
        }

        private fun parseValue(depth: Int): Any? {
            skipWhitespace()
            require(index < source.length) { "Missing JSON value" }
            return when (source[index]) {
                '"' -> parseString()
                '{' -> parseObject(depth)
                '[' -> parseArray(depth)
                't' -> { expectLiteral("true"); true }
                'f' -> { expectLiteral("false"); false }
                'n' -> { expectLiteral("null"); null }
                '-', in '0'..'9' -> parseNumber()
                else -> error("Invalid JSON value")
            }
        }

        private fun parseString(): String {
            expect('"')
            val result = StringBuilder()
            while (index < source.length) {
                val current = source[index++]
                when (current) {
                    '"' -> return result.toString()
                    '\\' -> {
                        require(index < source.length) { "Incomplete JSON escape" }
                        when (val escaped = source[index++]) {
                            '"' -> result.append('"')
                            '\\' -> result.append('\\')
                            '/' -> result.append('/')
                            'b' -> result.append('\b')
                            'f' -> result.append('\u000C')
                            'n' -> result.append('\n')
                            'r' -> result.append('\r')
                            't' -> result.append('\t')
                            'u' -> {
                                require(index + 4 <= source.length) { "Incomplete unicode escape" }
                                val code = source.substring(index, index + 4).toInt(16)
                                result.append(code.toChar())
                                index += 4
                            }
                            else -> error("Invalid JSON escape: $escaped")
                        }
                    }
                    else -> {
                        require(current.code >= 0x20) { "Control character in JSON string" }
                        result.append(current)
                    }
                }
            }
            error("Unterminated JSON string")
        }

        private fun parseNumber(): Number {
            val start = index
            consume('-')
            if (!consume('0')) {
                require(index < source.length && source[index] in '1'..'9') { "Invalid JSON number" }
                while (index < source.length && source[index].isDigit()) index++
            }
            if (consume('.')) {
                require(index < source.length && source[index].isDigit()) { "Invalid JSON fraction" }
                while (index < source.length && source[index].isDigit()) index++
            }
            if (index < source.length && (source[index] == 'e' || source[index] == 'E')) {
                index++
                if (index < source.length && (source[index] == '+' || source[index] == '-')) index++
                require(index < source.length && source[index].isDigit()) { "Invalid JSON exponent" }
                while (index < source.length && source[index].isDigit()) index++
            }
            val token = source.substring(start, index)
            return token.toLongOrNull() ?: token.toDoubleOrNull()?.takeIf(Double::isFinite)
            ?: error("Invalid JSON number")
        }

        private fun expectLiteral(literal: String) {
            require(source.regionMatches(index, literal, 0, literal.length)) { "Invalid JSON literal" }
            index += literal.length
        }

        private fun expect(char: Char) {
            require(index < source.length && source[index] == char) { "Expected $char" }
            index++
        }

        private fun consume(char: Char): Boolean {
            if (index < source.length && source[index] == char) {
                index++
                return true
            }
            return false
        }

        private fun skipWhitespace() {
            while (index < source.length && source[index] in charArrayOf(' ', '\n', '\r', '\t')) {
                index++
            }
        }
    }
}
