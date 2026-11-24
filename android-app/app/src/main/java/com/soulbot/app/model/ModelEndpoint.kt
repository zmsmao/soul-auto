package com.soulbot.app.model

import java.net.URI
import kotlin.math.min

data class ModelEndpoint(
    val id: String,
    val displayName: String,
    val providerName: String,
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val enabled: Boolean,
) {
    fun normalized(): ModelEndpoint = copy(
        displayName = displayName.trim(),
        providerName = providerName.trim(),
        baseUrl = baseUrl.trim().trimEnd('/'),
        model = model.trim(),
        apiKey = apiKey.trim(),
    )

    fun shortLabel(): String = "${displayName.ifBlank { providerName }} / $model"
}

data class ModelEndpointHealth(
    val consecutiveFailures: Int = 0,
    val qualityFailures: Int = 0,
    val cooldownUntil: Long = 0,
    val blockedReason: String = "",
    val lastError: String = "",
    val lastSuccessAt: Long = 0,
)

object ModelEndpointRules {
    const val MAX_ENDPOINTS = 5

    fun validationError(endpoint: ModelEndpoint): String? {
        val value = endpoint.normalized()
        if (value.displayName.isBlank()) return "请填写配置名称"
        if (value.displayName.length > 30) return "配置名称不能超过 30 个字"
        if (value.baseUrl.isBlank()) return "请填写接口地址"
        if (value.model.isBlank()) return "请填写模型名称"
        if (value.apiKey.isBlank()) return "请填写 API Key"
        val uri = runCatching { URI(value.baseUrl) }.getOrNull()
            ?: return "接口地址格式不正确"
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            return "接口地址必须是完整的 http 或 https 地址"
        }
        if (value.baseUrl.contains("/chat/completions", ignoreCase = true)) {
            return "请填写 API 根地址，不要包含 /chat/completions"
        }
        return null
    }

    fun isRunnable(endpoint: ModelEndpoint): Boolean =
        endpoint.enabled && validationError(endpoint) == null

    fun reordered(
        endpoints: List<ModelEndpoint>,
        endpointId: String,
        targetIndex: Int,
    ): List<ModelEndpoint> {
        val result = endpoints.toMutableList()
        val sourceIndex = result.indexOfFirst { it.id == endpointId }
        if (sourceIndex < 0 || targetIndex !in result.indices || sourceIndex == targetIndex) {
            return endpoints
        }
        val endpoint = result.removeAt(sourceIndex)
        result.add(targetIndex, endpoint)
        return result
    }
}

object ModelFailoverPolicy {
    private const val MAX_COOLDOWN_MS = 10 * 60_000L

    fun orderedCandidates(
        endpoints: List<ModelEndpoint>,
        health: Map<String, ModelEndpointHealth>,
        now: Long,
    ): List<ModelEndpoint> = endpoints.filter { endpoint ->
        if (!ModelEndpointRules.isRunnable(endpoint)) return@filter false
        val state = health[endpoint.id] ?: ModelEndpointHealth()
        state.blockedReason.isBlank() && state.cooldownUntil <= now
    }

    fun cooldownMs(
        failureType: ModelFailureType,
        consecutiveFailures: Int,
        retryAfterMs: Long = 0,
    ): Long = when (failureType) {
        ModelFailureType.CONFIGURATION -> 0
        ModelFailureType.RATE_LIMIT -> retryAfterMs.takeIf { it > 0 } ?: 60_000L
        ModelFailureType.TEMPORARY -> {
            val shift = (consecutiveFailures - 1).coerceIn(0, 5)
            min(30_000L * (1L shl shift), MAX_COOLDOWN_MS)
        }
    }

    fun secondsUntilNextCandidate(
        endpoints: List<ModelEndpoint>,
        health: Map<String, ModelEndpointHealth>,
        now: Long,
    ): Long? = endpoints
        .filter(ModelEndpointRules::isRunnable)
        .mapNotNull { endpoint ->
            val state = health[endpoint.id] ?: return@mapNotNull null
            state.cooldownUntil.takeIf { state.blockedReason.isBlank() && it > now }
        }
        .minOrNull()
        ?.let { ((it - now) + 999) / 1000 }
}

object ModelEndpointMigration {
    fun build(
        selectedName: String,
        configs: List<ModelConfig>,
        keyFor: (String) -> String,
        customBaseUrl: String,
        customModel: String,
    ): List<ModelEndpoint> {
        val selectedConfig = configs.firstOrNull { it.name == selectedName } ?: configs.first()
        val selected = endpointFrom(
            id = "legacy-primary",
            config = selectedConfig,
            apiKey = keyFor(selectedConfig.name),
            enabled = true,
            customBaseUrl = customBaseUrl,
            customModel = customModel,
        )
        val result = mutableListOf(selected)

        if (selected.baseUrl.contains("apinebula.ai", ignoreCase = true) &&
            selected.model == "gpt-5.6-sol" && selected.apiKey.isNotBlank()
        ) {
            result += selected.copy(
                id = "legacy-apinebula-terra",
                displayName = "中转站备用",
                model = "gpt-5.6-terra",
            )
        }

        configs.asSequence()
            .filter { it.name != selectedConfig.name }
            .mapNotNull { config ->
                val key = keyFor(config.name)
                if (key.isBlank()) null else endpointFrom(
                    id = "legacy-${config.name.hashCode().toUInt()}",
                    config = config,
                    apiKey = key,
                    enabled = false,
                    customBaseUrl = customBaseUrl,
                    customModel = customModel,
                )
            }
            .take(ModelEndpointRules.MAX_ENDPOINTS - result.size)
            .forEach(result::add)
        return result.take(ModelEndpointRules.MAX_ENDPOINTS)
    }

    private fun endpointFrom(
        id: String,
        config: ModelConfig,
        apiKey: String,
        enabled: Boolean,
        customBaseUrl: String,
        customModel: String,
    ): ModelEndpoint = ModelEndpoint(
        id = id,
        displayName = config.name,
        providerName = config.name,
        baseUrl = if (config.isCustom) customBaseUrl else config.baseUrl,
        model = if (config.isCustom) customModel else config.model,
        apiKey = apiKey,
        enabled = enabled,
    ).normalized()
}
