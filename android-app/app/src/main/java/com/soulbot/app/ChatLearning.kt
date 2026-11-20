package com.soulbot.app

import kotlin.math.abs

data class ChatStyleSample(
    val theirMessage: String,
    val myMessage: String,
    val time: Long = 0L,
    val scene: ReplyScene = ReplyScene.CHAT,
)

data class ChatStyleProfile(
    val sampleCount: Int,
    val averageLength: Int,
    val emojiPercent: Int,
    val questionPercent: Int,
    val multilinePercent: Int,
    val punctuationPercent: Int = 0,
    val averageSentenceLength: Int = 0,
    val commonWords: List<String> = emptyList(),
    val commonEndings: List<String> = emptyList(),
) {
    fun promptLine(): String =
        "已学习 $sampleCount 条人工回复：平均约 $averageLength 字，" +
        "约 $emojiPercent% 使用表情，约 $questionPercent% 使用问句，" +
        "约 $multilinePercent% 会分行，约 $punctuationPercent% 会用句末标点。" +
        "平均每句约 $averageSentenceLength 字。只把这些数字当成倾向，不要机械套用。"

    fun compactStyleHints(): String = buildString {
        if (commonWords.isNotEmpty()) {
            append("常见口头词：").append(commonWords.joinToString("、")).append("。")
        }
        if (commonEndings.isNotEmpty()) {
            append("常见收尾：").append(commonEndings.joinToString("、")).append("。")
        }
    }
}

object ChatSampleParser {
    fun parse(raw: String, limit: Int = 200): List<ChatStyleSample> {
        val result = mutableListOf<ChatStyleSample>()
        var their = ""
        var mine = ""
        var activeRole = ""
        var scene = ReplyScene.CHAT

        fun flush() {
            val cleanTheir = their.trim()
            val cleanMine = mine.trim()
            if (cleanTheir.isNotEmpty() && cleanMine.isNotEmpty()) {
                result += ChatStyleSample(cleanTheir, cleanMine, scene = scene)
            }
            their = ""
            mine = ""
            activeRole = ""
        }

        for (rawLine in raw.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) {
                if (their.isNotEmpty() && mine.isNotEmpty()) flush()
                continue
            }

            if (line.startsWith("场景：") || line.startsWith("场景:")) {
                flush()
                scene = parseScene(line.substringAfterAny("场景：", "场景:").trim())
                continue
            }

            val tabParts = line.split('\t', limit = 2)
            if (tabParts.size == 2 && tabParts.all { it.isNotBlank() }) {
                flush()
                result += ChatStyleSample(tabParts[0].trim(), tabParts[1].trim(), scene = scene)
                continue
            }

            when {
                line.startsWith("对方：") || line.startsWith("对方:") -> {
                    if (their.isNotEmpty() && mine.isNotEmpty()) flush()
                    their = line.substringAfterAny("对方：", "对方:").trim()
                    activeRole = "their"
                }

                line.startsWith("我：") || line.startsWith("我:") -> {
                    mine = line.substringAfterAny("我：", "我:").trim()
                    activeRole = "mine"
                }

                activeRole == "their" -> their = appendLine(their, line)
                activeRole == "mine" -> mine = appendLine(mine, line)
            }
        }
        flush()

        return result
            .distinctBy { it.scene.name + "\u0000" + comparableText(it.theirMessage) + "\u0000" + comparableText(it.myMessage) }
            .take(limit)
    }

    private fun parseScene(label: String): ReplyScene = when (label) {
        "灵魂匹配", "SOUL_MATCH" -> ReplyScene.SOUL_MATCH
        "奇遇铃", "QIYU" -> ReplyScene.QIYU
        "广场私聊", "SQUARE" -> ReplyScene.SQUARE
        else -> ReplyScene.CHAT
    }

    private fun String.substringAfterAny(vararg prefixes: String): String {
        val prefix = prefixes.firstOrNull { startsWith(it) } ?: return this
        return substring(prefix.length)
    }

    private fun appendLine(existing: String, line: String): String =
        if (existing.isEmpty()) line else "$existing\n$line"
}

object ManualStyleLearner {
    fun extract(
        thread: List<Pair<String, String>>,
        scene: ReplyScene = ReplyScene.CHAT,
        isAutomatedReply: (String) -> Boolean,
    ): List<ChatStyleSample> {
        val result = mutableListOf<ChatStyleSample>()
        val incoming = mutableListOf<String>()
        for ((role, rawText) in thread) {
            val text = rawText.trim()
            if (text.isEmpty()) continue
            if (role == "in") {
                incoming += text
                if (incoming.size > 3) incoming.removeAt(0)
            } else if (role == "out") {
                if (incoming.isNotEmpty() && !isAutomatedReply(text)) {
                    result += ChatStyleSample(incoming.joinToString("\n"), text, scene = scene)
                }
                // Any outgoing message closes the current reply turn. Without
                // this reset, the next sample would accidentally include the
                // previous conversation turn as part of its incoming context.
                incoming.clear()
            }
        }
        return result.distinctBy {
            it.scene.name + "\u0000" + comparableText(it.theirMessage) + "\u0000" + comparableText(it.myMessage)
        }
    }
}

object StyleSampleMatcher {
    // Common connective phrases are not enough evidence that two topics match.
    private val genericBigrams = setOf(
        "今天", "最近", "现在", "你是", "我是", "这个", "那个", "什么", "怎么",
        "了吗", "了嘛", "呢吗", "哈哈", "呵呵", "嗯嗯", "好的", "是的",
        "有点", "感觉", "觉得", "真的", "就是", "一下", "可以", "喜欢",
    )

    fun rank(
        query: String,
        samples: List<ChatStyleSample>,
        limit: Int = 5,
        scene: ReplyScene = ReplyScene.CHAT,
    ): List<ChatStyleSample> {
        if (samples.isEmpty() || limit <= 0) return emptyList()
        val normalizedQuery = comparableText(query)
        if (normalizedQuery.length < 2 || tokens(normalizedQuery).isEmpty() ||
            ConversationReplyContext.isLowInformation(query)) return emptyList()
        return samples
            .filter { it.scene == scene }
            .map { it to similarity(normalizedQuery, comparableText(it.theirMessage)) }
            .filter { it.second >= 0.24 }
            .sortedWith(
                compareByDescending<Pair<ChatStyleSample, Double>> { it.second }
                    .thenByDescending { it.first.time },
            )
            .take(limit)
            .map { it.first }
    }

    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 2.0
        val aTokens = tokens(a)
        val bTokens = tokens(b)
        if (aTokens.isEmpty() || bTokens.isEmpty()) return 0.0
        val overlap = aTokens.intersect(bTokens).size.toDouble()
        if (overlap == 0.0) return 0.0
        val union = aTokens.union(bTokens).size.coerceAtLeast(1).toDouble()
        val shorter = minOf(aTokens.size, bTokens.size).toDouble()
        val lengthPenalty = abs(a.length - b.length).toDouble() / (a.length + b.length).coerceAtLeast(1)
        return 0.7 * overlap / shorter + 0.3 * overlap / union - lengthPenalty * 0.08
    }

    private fun tokens(text: String): Set<String> =
        text.windowed(2).filterNot { it in genericBigrams }.toSet()
}

object ReplyStyleFeedback {
    private val allowedLabels = listOf("昵称硬聊", "同城套话", "连续盘问", "客服腔", "编造经历")

    fun labels(notes: List<String>): List<String> = notes
        .flatMap { note -> allowedLabels.filter(note::contains) }
        .distinct()
}

object ReplyNaturalness {
    private val artificialPhrases = listOf(
        "作为一个AI",
        "作为AI",
        "我理解你的感受",
        "听起来你",
        "如果你愿意的话",
        "随时可以找我",
        "我会一直陪着你",
        "我可以陪你做很多事情",
        "有什么想聊的都可以告诉我",
        "很高兴认识你",
        "感谢你的分享",
        "希望对你有所帮助",
        "如果方便的话",
        "从你的描述来看",
        "总的来说",
        "综上所述",
        "我建议你",
        "你可以尝试",
        "这个问题很有意思",
    )

    private val flatClosers = listOf(
        "哈哈确实", "确实是", "确实不想", "听着就挺", "这种还好", "那就行",
        "那挺好", "基本没瘾了", "先睡够再说", "早点休息就好",
    )

    private val allowedTrailingLatin = setOf("ok", "lol", "emo", "ktv", "soul", "citywalk")
    private val internalMetaLead = Regex("""(?is)^\s*(?:用户|对方)(?:问|说|提到|发来|想知道)""")
    private val internalMetaLabel = Regex(
        """(?im)(?:^|[。！？\n])\s*(?:资料|上下文|已知信息|回复要求|输出要求)\s*[：:]""",
    )
    private val internalAnswerLabel = Regex(
        """(?im)(?:^|[。！？\n])\s*(?:答|答复|建议回复|最终回复|回复内容)\s*[：:]""",
    )
    private val internalAnalysisLead = Regex(
        """(?im)^\s*(?:分析|思考|判断|回复规划|生成思路)\s*[：:]""",
    )
    private val internalInstructionPhrases = listOf(
        "直接回答", "只回答", "不盘问", "简短自然", "不要编造", "不编造具体",
        "回复要接住", "之前发过", "不能重复", "不要追问", "不追加问题",
        "具体岗位可以模糊说", "只回答，不盘问",
    )

    /**
     * Detects high-confidence model planning text, not ordinary short or explanatory
     * chat. Used before sending and when filtering historical conversation context.
     */
    fun isInternalDraft(text: String): Boolean {
        val clean = text.trim()
        if (clean.isEmpty()) return false
        if (internalAnalysisLead.containsMatchIn(clean)) return true
        if (Regex("""(?is)<(?:think|analysis|reasoning|thinking)\b""").containsMatchIn(clean)) return true
        val hasMetaLead = internalMetaLead.containsMatchIn(clean)
        val hasMetaLabel = internalMetaLabel.containsMatchIn(clean)
        val hasAnswerLabel = internalAnswerLabel.containsMatchIn(clean)
        val instructionCount = internalInstructionPhrases.count(clean::contains)
        return (hasMetaLead && (hasMetaLabel || hasAnswerLabel || instructionCount >= 1)) ||
            (hasMetaLabel && instructionCount >= 2) ||
            (hasAnswerLabel && instructionCount >= 2)
    }

    fun sanitize(raw: String): String = raw.trim()
        .removePrefix("回复：")
        .removePrefix("回复:")
        .removeSurrounding("\"")
        .trim()

    // Legacy guidance flags stay source-compatible, but style preferences alone
    // must not reject a usable answer and trigger a regenerate-until-question loop.
    @Suppress("UNUSED_PARAMETER")
    fun rejectionReason(
        text: String,
        requireTopicContinuation: Boolean = false,
        requireEngagingHook: Boolean = false,
        forbidQuestion: Boolean = false,
        profile: ChatStyleProfile? = null,
        recentReplies: List<String> = emptyList(),
    ): String? {
        val clean = text.trim()
        val trailingLatin = Regex("""([A-Za-z]{2,})$""").find(clean)
            ?.groupValues?.getOrNull(1)?.lowercase()
        return when {
            clean.isEmpty() -> "内容为空"
            isInternalDraft(clean) -> "疑似模型内部分析或回复指令泄漏"
            artificialPhrases.any(clean::contains) -> "包含明显的 AI 套话"
            recentReplies.any { comparableText(it) == comparableText(clean) } -> "与最近回复重复"
            trailingLatin != null && trailingLatin !in allowedTrailingLatin ->
                "结尾包含疑似模型残片"
            else -> null
        }
    }

    /** Review hints only. A short answer or a missing question mark is not a failure. */
    fun styleWarnings(
        text: String,
        requireTopicContinuation: Boolean = false,
        requireEngagingHook: Boolean = false,
        forbidQuestion: Boolean = false,
        profile: ChatStyleProfile? = null,
    ): List<String> = buildList {
        val clean = text.trim()
        if (clean.isEmpty()) return@buildList
        if (clean.length > 100) add("回复偏长，可检查是否有多余解释")
        if (profile != null && profile.sampleCount >= 8 &&
            clean.length > profile.averageLength * 3 + 12) {
            add("长度偏离已有样本，仅供人工判断")
        }
        if (requireEngagingHook && flatClosers.any(clean::contains)) {
            add("可能只是在附和或替对方下结论，请结合上下文判断")
        }
        if (requireTopicContinuation && ConversationReplyContext.isLowInformation(clean)) {
            add("回复较简短，检查是否已接住前文；自然收尾不必延长")
        }
        if (forbidQuestion && ConversationReplyContext.asksQuestion(clean)) {
            add("可能连续提问，检查问题是否必要")
        }
    }
}

internal fun comparableText(text: String): String =
    text.lowercase().replace(Regex("""[^\p{L}\p{N}]"""), "")
