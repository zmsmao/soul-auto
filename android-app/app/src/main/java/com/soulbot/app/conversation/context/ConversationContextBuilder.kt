package com.soulbot.app.conversation.context

import com.soulbot.app.conversation.ReplyNaturalness
import com.soulbot.app.model.ReplyGenerationConfig
import kotlin.math.ceil

/**
 * A prompt fragment is a separately budgeted piece of context. Required fragments
 * preserve policy/instruction semantics; optional fragments are evicted by priority.
 */
internal data class ContextFragment(
    val id: String,
    val content: String,
    val priority: Int,
    val order: Int,
    val required: Boolean = false,
)

internal data class BuiltConversationContext(
    val systemPrompt: String,
    val messages: List<Pair<String, String>>,
    val estimatedInputTokens: Int,
    val inputTokenBudget: Int,
    val overflowTokens: Int,
    val droppedFragmentIds: List<String>,
    val droppedHistoryMessages: Int,
)

internal object ContextTokenEstimator {
    private val asciiWords = Regex("[A-Za-z0-9_]+")

    /**
     * Provider-neutral estimate, not a provider tokenizer. CJK characters are
     * weighted more heavily than Latin characters to avoid under-budgeting Chinese.
     */
    fun estimate(text: String): Int {
        if (text.isBlank()) return 0
        val cjkCharacters = text.count { ch ->
            ch in '\u3400'..'\u4DBF' || ch in '\u4E00'..'\u9FFF' ||
                ch in '\uF900'..'\uFAFF'
        }
        val asciiTokens = asciiWords.findAll(text).sumOf { match ->
            ceil(match.value.length / 4.0).toInt().coerceAtLeast(1)
        }
        val otherCharacters = text.count { !it.isWhitespace() && it !in '\u3400'..'\u9FFF' }
        val punctuationAllowance = ceil(otherCharacters / 8.0).toInt()
        return ceil(cjkCharacters * 1.35).toInt() + asciiTokens + punctuationAllowance
    }
}

internal object ConversationContextBuilder {
    private data class HistoryCandidate(
        val index: Int,
        val role: String,
        val content: String,
        val priority: Int,
        val required: Boolean,
    )

    private sealed class OptionalCandidate {
        abstract val priority: Int
        abstract val tieBreakOrder: Int

        data class Fragment(val value: ContextFragment) : OptionalCandidate() {
            override val priority: Int get() = value.priority
            override val tieBreakOrder: Int get() = Int.MAX_VALUE - value.order
        }

        data class History(val value: HistoryCandidate) : OptionalCandidate() {
            override val priority: Int get() = value.priority
            override val tieBreakOrder: Int get() = value.index
        }
    }

    fun build(
        baseSystemPrompt: String,
        fragments: List<ContextFragment>,
        history: List<Pair<String, String>>,
        focusIncoming: List<String>,
        maxInputTokens: Int = ReplyGenerationConfig.MAX_INPUT_TOKENS,
        reservedOutputTokens: Int = ReplyGenerationConfig.RESERVED_OUTPUT_TOKENS,
    ): BuiltConversationContext {
        val budget = (maxInputTokens - reservedOutputTokens).coerceAtLeast(0)
        val validHistory = history.mapIndexedNotNull { index, pair ->
            val role = pair.first.trim()
            val content = pair.second.trim()
            if (role !in setOf("in", "out") || content.isBlank()) return@mapIndexedNotNull null
            if (role == "out" && ReplyNaturalness.isInternalDraft(content)) return@mapIndexedNotNull null
            HistoryCandidate(
                index = index,
                role = if (role == "in") "user" else "assistant",
                content = content,
                priority = 70 + index,
                required = index >= history.lastIndex - ReplyGenerationConfig.REQUIRED_RECENT_MESSAGES + 1 ||
                    (role == "in" && content in focusIncoming),
            )
        }

        val requiredFragments = fragments.filter { it.required }
        val optionalFragments = fragments.filterNot { it.required }
        val requiredHistory = validHistory.filter { it.required }
        val optionalHistory = validHistory.filterNot { it.required }

        var usedTokens = ContextTokenEstimator.estimate(baseSystemPrompt)
        requiredFragments.forEach { usedTokens += ContextTokenEstimator.estimate(it.content) }
        requiredHistory.forEach { usedTokens += ContextTokenEstimator.estimate(it.content) }

        var remaining = (budget - usedTokens).coerceAtLeast(0)
        val selectedFragments = requiredFragments.toMutableList()
        val droppedFragments = mutableListOf<String>()
        val selectedHistory = requiredHistory.toMutableList()
        var droppedHistory = 0

        // Fragment and historical-message priorities share one budget queue.
        val optionalCandidates = buildList {
            optionalFragments.forEach { add(OptionalCandidate.Fragment(it)) }
            optionalHistory.forEach { add(OptionalCandidate.History(it)) }
        }.sortedWith(
            compareByDescending<OptionalCandidate> { it.priority }
                .thenByDescending { it.tieBreakOrder },
        )

        optionalCandidates.forEach { candidate ->
            when (candidate) {
                is OptionalCandidate.Fragment -> {
                    val fragment = candidate.value
                    val cost = ContextTokenEstimator.estimate(fragment.content)
                    if (cost <= remaining) {
                        selectedFragments += fragment
                        remaining -= cost
                        usedTokens += cost
                    } else {
                        droppedFragments += fragment.id
                    }
                }
                is OptionalCandidate.History -> {
                    val message = candidate.value
                    val cost = ContextTokenEstimator.estimate(message.content)
                    if (cost <= remaining) {
                        selectedHistory += message
                        remaining -= cost
                        usedTokens += cost
                    } else {
                        droppedHistory++
                    }
                }
            }
        }

        val prompt = buildString {
            append(baseSystemPrompt)
            selectedFragments.sortedBy { it.order }.forEach { append(it.content) }
        }
        val messages = selectedHistory.sortedBy { it.index }.map { it.role to it.content }
        return BuiltConversationContext(
            systemPrompt = prompt,
            messages = messages,
            estimatedInputTokens = usedTokens,
            inputTokenBudget = budget,
            overflowTokens = (usedTokens - budget).coerceAtLeast(0),
            droppedFragmentIds = droppedFragments,
            droppedHistoryMessages = droppedHistory,
        )
    }

    fun buildSystemPrompt(
        baseSystemPrompt: String,
        fragments: List<ContextFragment>,
        maxInputTokens: Int = ReplyGenerationConfig.MAX_INPUT_TOKENS,
        reservedOutputTokens: Int = ReplyGenerationConfig.RESERVED_OUTPUT_TOKENS,
    ): BuiltConversationContext = build(
        baseSystemPrompt = baseSystemPrompt,
        fragments = fragments,
        history = emptyList(),
        focusIncoming = emptyList(),
        maxInputTokens = maxInputTokens,
        reservedOutputTokens = reservedOutputTokens,
    )
}
