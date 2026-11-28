package com.soulbot.app.app

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.soulbot.app.data.ModelEndpointPrefs
import com.soulbot.app.model.ModelEndpointRules

data class RuntimeReadinessSnapshot(
    val soulInstalled: Boolean,
    val accessibilityEnabled: Boolean,
    val accessibilityConnected: Boolean,
    val overlayGranted: Boolean,
    val runnableModelCount: Int,
    val modelSummary: String,
    val automationRunning: Boolean,
) {
    val checklistCompletedCount: Int
        get() = listOf(
            soulInstalled,
            accessibilityEnabled && accessibilityConnected,
            overlayGranted,
            runnableModelCount > 0,
        ).count { it }

    val isReadyForManualStart: Boolean
        get() = automationStartBlockers().isEmpty()

    fun automationStartBlockers(): List<String> = buildList {
        if (!soulInstalled) add("请先安装 Soul 客户端")
        if (!accessibilityEnabled) {
            add("请先开启无障碍服务")
        } else if (!accessibilityConnected) {
            add("无障碍服务尚未连接，请返回应用后重试")
        }
        if (runnableModelCount <= 0) add("请至少配置并启用一个完整的模型通道")
    }
}

object RuntimeReadinessProvider {
    const val SOUL_PACKAGE = "cn.soulapp.android"

    fun snapshot(context: Context): RuntimeReadinessSnapshot {
        val appContext = context.applicationContext
        val endpoints = ModelEndpointPrefs.getEndpoints(appContext)
        val runnable = endpoints.filter(ModelEndpointRules::isRunnable)
        val health = ModelEndpointPrefs.getHealth(appContext)
        val modelSummary = when {
            runnable.isNotEmpty() -> {
                val primary = runnable.first()
                "可用 " + runnable.size + " 个通道 · " +
                    ModelEndpointPrefs.statusText(health[primary.id])
            }
            endpoints.isEmpty() -> "尚未配置模型通道"
            else -> "已保存 " + endpoints.size + " 个通道，但没有启用且完整的配置"
        }

        return RuntimeReadinessSnapshot(
            soulInstalled = appContext.packageManager.getLaunchIntentForPackage(SOUL_PACKAGE) != null,
            accessibilityEnabled = isAccessibilityEnabled(appContext),
            accessibilityConnected = SoulBotService.instance != null,
            overlayGranted = Settings.canDrawOverlays(appContext),
            runnableModelCount = runnable.size,
            modelSummary = modelSummary,
            automationRunning = SoulBotService.running,
        )
    }

    fun isAccessibilityEnabled(context: Context): Boolean {
        val expected = ComponentName(context, SoulBotService::class.java)
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        return enabledServices.split(':').any { flattened ->
            ComponentName.unflattenFromString(flattened) == expected
        }
    }
}
