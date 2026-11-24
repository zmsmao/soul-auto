package com.soulbot.app.data

import com.soulbot.app.model.ModelCallResult
import com.soulbot.app.model.ModelConfig
import com.soulbot.app.model.ModelEndpoint
import com.soulbot.app.model.ModelEndpointHealth
import com.soulbot.app.model.ModelEndpointMigration
import com.soulbot.app.model.ModelEndpointRules
import com.soulbot.app.model.ModelFailoverPolicy
import com.soulbot.app.model.ModelFailureType
import com.soulbot.app.model.ModelList

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.min

object ModelEndpointPrefs {
    private const val PREFS = "soulbot_prefs"
    private const val KEY_ENDPOINTS = "model_endpoints_v1"
    private const val KEY_HEALTH = "model_endpoint_health_v1"

    @Synchronized
    fun getEndpoints(ctx: Context): List<ModelEndpoint> {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_ENDPOINTS, null)
        if (stored != null) return decodeEndpoints(stored)

        val migrated = ModelEndpointMigration.build(
            selectedName = Prefs.getSelectedModel(ctx),
            configs = ModelList.MODELS,
            keyFor = { Prefs.getApiKey(ctx, it) },
            customBaseUrl = Prefs.getCustomBaseUrl(ctx),
            customModel = Prefs.getCustomModel(ctx),
        )
        saveEndpoints(ctx, migrated)
        return migrated
    }

    @Synchronized
    fun saveEndpoints(ctx: Context, endpoints: List<ModelEndpoint>) {
        val unique = endpoints
            .map(ModelEndpoint::normalized)
            .distinctBy(ModelEndpoint::id)
            .take(ModelEndpointRules.MAX_ENDPOINTS)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ENDPOINTS, encodeEndpoints(unique))
            .apply()
        pruneHealth(ctx, unique.mapTo(mutableSetOf(), ModelEndpoint::id))
    }

    fun newEndpoint(provider: ModelConfig = ModelList.MODELS.first()): ModelEndpoint =
        ModelEndpoint(
            id = UUID.randomUUID().toString(),
            displayName = provider.name,
            providerName = provider.name,
            baseUrl = provider.baseUrl,
            model = provider.model,
            apiKey = "",
            enabled = false,
        )

    @Synchronized
    fun getHealth(ctx: Context): Map<String, ModelEndpointHealth> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HEALTH, "{}").orEmpty()
        return decodeHealth(raw)
    }

    @Synchronized
    fun clearHealth(ctx: Context, endpointId: String) {
        val health = getHealth(ctx).toMutableMap()
        health.remove(endpointId)
        saveHealth(ctx, health)
    }

    @Synchronized
    fun recordSuccess(ctx: Context, endpointId: String, accepted: Boolean = false) {
        val health = getHealth(ctx).toMutableMap()
        val previous = health[endpointId] ?: ModelEndpointHealth()
        health[endpointId] = previous.copy(
            consecutiveFailures = 0,
            qualityFailures = if (accepted) 0 else previous.qualityFailures,
            cooldownUntil = 0,
            blockedReason = "",
            lastError = "",
            lastSuccessAt = System.currentTimeMillis(),
        )
        saveHealth(ctx, health)
    }

    @Synchronized
    fun recordFailure(ctx: Context, endpointId: String, result: ModelCallResult) {
        val now = System.currentTimeMillis()
        val health = getHealth(ctx).toMutableMap()
        val previous = health[endpointId] ?: ModelEndpointHealth()
        val failures = previous.consecutiveFailures + 1
        val blocked = if (result.failureType == ModelFailureType.CONFIGURATION) {
            result.error.ifBlank { "配置异常" }
        } else {
            ""
        }
        val cooldown = if (blocked.isBlank()) {
            now + ModelFailoverPolicy.cooldownMs(
                result.failureType,
                failures,
                result.retryAfterMs,
            )
        } else {
            0
        }
        health[endpointId] = previous.copy(
            consecutiveFailures = failures,
            cooldownUntil = cooldown,
            blockedReason = blocked,
            lastError = result.error,
        )
        saveHealth(ctx, health)
    }

    @Synchronized
    fun recordQualityRejected(ctx: Context, endpointId: String): Boolean {
        val health = getHealth(ctx).toMutableMap()
        val previous = health[endpointId] ?: ModelEndpointHealth()
        val failures = previous.qualityFailures + 1
        val shouldRotate = failures >= 2
        health[endpointId] = previous.copy(
            qualityFailures = if (shouldRotate) 0 else failures,
            cooldownUntil = if (shouldRotate) {
                maxOf(previous.cooldownUntil, System.currentTimeMillis() + 30_000L)
            } else {
                previous.cooldownUntil
            },
            lastError = if (shouldRotate) "连续生成内容不合格" else previous.lastError,
        )
        saveHealth(ctx, health)
        return shouldRotate
    }

    fun statusText(health: ModelEndpointHealth?, now: Long = System.currentTimeMillis()): String = when {
        health == null -> "未测试"
        health.blockedReason.isNotBlank() -> "需修复 · ${health.blockedReason.take(24)}"
        health.cooldownUntil > now -> "冷却中 · ${((health.cooldownUntil - now + 999) / 1000)} 秒"
        health.lastError.isNotBlank() -> "上次失败 · ${health.lastError.take(24)}"
        health.lastSuccessAt > 0 -> "最近调用成功"
        else -> "未测试"
    }

    private fun pruneHealth(ctx: Context, retainedIds: Set<String>) {
        val retained = getHealth(ctx).filterKeys(retainedIds::contains)
        saveHealth(ctx, retained)
    }

    private fun saveHealth(ctx: Context, health: Map<String, ModelEndpointHealth>) {
        val json = JSONObject()
        health.forEach { (id, value) ->
            json.put(id, JSONObject().apply {
                put("consecutiveFailures", value.consecutiveFailures)
                put("qualityFailures", value.qualityFailures)
                put("cooldownUntil", value.cooldownUntil)
                put("blockedReason", value.blockedReason)
                put("lastError", value.lastError)
                put("lastSuccessAt", value.lastSuccessAt)
            })
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_HEALTH, json.toString())
            .apply()
    }

    private fun encodeEndpoints(endpoints: List<ModelEndpoint>): String = JSONArray().apply {
        endpoints.forEach { endpoint ->
            put(JSONObject().apply {
                put("id", endpoint.id)
                put("displayName", endpoint.displayName)
                put("providerName", endpoint.providerName)
                put("baseUrl", endpoint.baseUrl)
                put("model", endpoint.model)
                put("apiKey", endpoint.apiKey)
                put("enabled", endpoint.enabled)
            })
        }
    }.toString()

    private fun decodeEndpoints(raw: String): List<ModelEndpoint> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until min(array.length(), ModelEndpointRules.MAX_ENDPOINTS)) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    ModelEndpoint(
                        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                        displayName = item.optString("displayName"),
                        providerName = item.optString("providerName"),
                        baseUrl = item.optString("baseUrl"),
                        model = item.optString("model"),
                        apiKey = item.optString("apiKey"),
                        enabled = item.optBoolean("enabled", false),
                    ).normalized(),
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun decodeHealth(raw: String): Map<String, ModelEndpointHealth> = runCatching {
        val json = JSONObject(raw)
        buildMap {
            json.keys().forEach { id ->
                val value = json.optJSONObject(id) ?: return@forEach
                put(
                    id,
                    ModelEndpointHealth(
                        consecutiveFailures = value.optInt("consecutiveFailures"),
                        qualityFailures = value.optInt("qualityFailures"),
                        cooldownUntil = value.optLong("cooldownUntil"),
                        blockedReason = value.optString("blockedReason"),
                        lastError = value.optString("lastError"),
                        lastSuccessAt = value.optLong("lastSuccessAt"),
                    ),
                )
            }
        }
    }.getOrDefault(emptyMap())
}
