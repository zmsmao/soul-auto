package com.soulbot.app

import android.content.Context
import android.os.SystemClock

data class ModelRouteOutcome(
    val content: String?,
    val endpointId: String = "",
    val endpointLabel: String = "",
    val model: String = "",
    val durationMs: Long = 0,
    val error: String = "",
)

object ModelRouter {
    const val MAX_GENERATION_WAIT_MS = 75_000L
    private const val MAX_SINGLE_CALL_MS = 30_000L
    private const val MIN_CALL_BUDGET_MS = 5_000L
    private const val SWITCH_DELAY_MS = 3_000L

    @Volatile var lastError: String = ""
        private set
    @Volatile var lastUsedEndpointId: String = ""
        private set
    @Volatile var lastUsedLabel: String = ""
        private set

    /** Compatibility API for existing one-stage call sites. */
    fun chat(
        context: Context,
        systemPrompt: String,
        messages: List<Pair<String, String>>,
        onProgress: (String, Long) -> Unit = { _, _ -> },
        waitBeforeNext: (Long) -> Unit = { Thread.sleep(it) },
        deadlineElapsedMs: Long? = null,
    ): String? = chatDetailed(
        context = context,
        systemPrompt = systemPrompt,
        messages = messages,
        deadlineElapsedMs = deadlineElapsedMs,
        onProgress = onProgress,
        waitBeforeNext = waitBeforeNext,
    ).content

    /**
     * Metadata belongs to this request. Multi-stage generation must not attribute
     * a writer's rejected reply to the endpoint used by a later reviewer request.
     */
    fun chatDetailed(
        context: Context,
        systemPrompt: String,
        messages: List<Pair<String, String>>,
        deadlineElapsedMs: Long? = null,
        onProgress: (String, Long) -> Unit = { _, _ -> },
        waitBeforeNext: (Long) -> Unit = { Thread.sleep(it) },
    ): ModelRouteOutcome {
        val startedAt = SystemClock.elapsedRealtime()
        val deadline = minOf(
            startedAt + MAX_GENERATION_WAIT_MS,
            deadlineElapsedMs ?: Long.MAX_VALUE,
        )
        val endpoints = ModelEndpointPrefs.getEndpoints(context)
        val health = ModelEndpointPrefs.getHealth(context)
        val candidates = ModelFailoverPolicy.orderedCandidates(
            endpoints, health, System.currentTimeMillis(),
        )
        if (candidates.isEmpty()) {
            val enabled = endpoints.filter(ModelEndpointRules::isRunnable)
            val waitSeconds = ModelFailoverPolicy.secondsUntilNextCandidate(
                endpoints, health, System.currentTimeMillis(),
            )
            lastError = when {
                enabled.isEmpty() -> "没有已启用且配置完整的模型"
                waitSeconds != null -> "所有模型正在冷却，${waitSeconds} 秒后可用"
                else -> "所有模型都需要修复配置"
            }
            RuntimeLog.record(
                context,
                "model route unavailable; configured=${endpoints.size}; runnable=${enabled.size}; cooldownSeconds=${waitSeconds ?: 0}",
            )
            onProgress(lastError, (waitSeconds ?: 0) * 1000)
            return ModelRouteOutcome(
                content = null,
                durationMs = SystemClock.elapsedRealtime() - startedAt,
                error = lastError,
            )
        }

        val inputChars = messages.sumOf { it.second.length }
        RuntimeLog.record(
            context,
            "model route started; candidates=${candidates.size}; messages=${messages.size}; inputChars=$inputChars; budgetMs=${(deadline - startedAt).coerceAtLeast(0)}",
        )
        for ((candidateIndex, endpoint) in candidates.withIndex()) {
            val elapsed = SystemClock.elapsedRealtime()
            val remaining = deadline - elapsed
            if (remaining < MIN_CALL_BUDGET_MS) {
                lastError = "本轮模型调用已达到总时间预算"
                RuntimeLog.record(
                    context,
                    "model route budget exhausted; elapsedMs=${elapsed - startedAt}; remainingMs=$remaining",
                )
                break
            }
            val priority = endpoints.indexOfFirst { it.id == endpoint.id } + 1
            val timeout = minOf(MAX_SINGLE_CALL_MS, remaining)
            onProgress("正在调用 P$priority：${endpoint.shortLabel()}", timeout)
            RuntimeLog.record(
                context,
                "model attempt; priority=$priority; endpointId=${endpoint.id}; model=${endpoint.model}; timeoutMs=$timeout",
            )
            val result = ModelClient.chatOnce(
                apiKey = endpoint.apiKey,
                baseUrl = endpoint.baseUrl,
                model = endpoint.model,
                systemPrompt = systemPrompt,
                messages = messages,
                timeoutMs = timeout,
            )
            if (result.content != null) {
                ModelEndpointPrefs.recordSuccess(context, endpoint.id)
                lastError = ""
                lastUsedEndpointId = endpoint.id
                lastUsedLabel = endpoint.shortLabel()
                RuntimeLog.record(
                    context,
                    "model succeeded; priority=$priority; endpointId=${endpoint.id}; http=${result.httpStatus ?: 0}; durationMs=${result.durationMs}; responseBytes=${result.responseBytes}; outputChars=${result.content.length}",
                )
                return ModelRouteOutcome(
                    content = result.content,
                    endpointId = endpoint.id,
                    endpointLabel = endpoint.shortLabel(),
                    model = endpoint.model,
                    durationMs = SystemClock.elapsedRealtime() - startedAt,
                )
            }

            ModelEndpointPrefs.recordFailure(context, endpoint.id, result)
            lastError = result.error.ifBlank { "模型未返回文字" }
            val hasNext = candidateIndex < candidates.lastIndex
            RuntimeLog.record(
                context,
                "model failed; priority=$priority; endpointId=${endpoint.id}; type=${result.failureType}; http=${result.httpStatus ?: 0}; durationMs=${result.durationMs}; retryAfterMs=${result.retryAfterMs}; hasNext=$hasNext",
            )
            if (hasNext && deadline - SystemClock.elapsedRealtime() > SWITCH_DELAY_MS + MIN_CALL_BUDGET_MS) {
                val next = candidates[candidateIndex + 1]
                val nextPriority = endpoints.indexOfFirst { it.id == next.id } + 1
                onProgress("P$priority 失败：${lastError.take(22)}，切换 P$nextPriority", SWITCH_DELAY_MS)
                RuntimeLog.record(
                    context,
                    "model failover scheduled; fromPriority=$priority; toPriority=$nextPriority; delayMs=$SWITCH_DELAY_MS",
                )
                waitBeforeNext(SWITCH_DELAY_MS)
            }
        }
        return ModelRouteOutcome(
            content = null,
            durationMs = SystemClock.elapsedRealtime() - startedAt,
            error = lastError,
        )
    }

    fun markAccepted(context: Context, endpointId: String = lastUsedEndpointId) {
        endpointId.takeIf { it.isNotBlank() }?.let {
            ModelEndpointPrefs.recordSuccess(context, it, accepted = true)
            RuntimeLog.record(context, "model reply accepted; endpointId=$it")
        }
    }

    fun markQualityRejected(
        context: Context,
        endpointId: String = lastUsedEndpointId,
    ): Boolean =
        endpointId.takeIf { it.isNotBlank() }
            ?.let {
                RuntimeLog.record(context, "model reply quality rejected; endpointId=$it")
                ModelEndpointPrefs.recordQualityRejected(context, it)
            }
            ?: false
}
