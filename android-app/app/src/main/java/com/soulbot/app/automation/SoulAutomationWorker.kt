package com.soulbot.app.automation

import com.soulbot.app.data.GreetDatabase
import com.soulbot.app.data.ModelEndpointPrefs
import com.soulbot.app.data.Prefs
import com.soulbot.app.diagnostics.RuntimeLog
import com.soulbot.app.model.ModelEndpointRules
import android.content.Context

/**
 * Owns the preflight and per-run assembly of the automation session.
 * Android service lifecycle and worker-thread cleanup remain in SoulBotService.
 */
internal class SoulAutomationWorker(
    private val context: Context,
    private val automationHost: SoulAutomationHost,
    private val updateStatus: (String, Long) -> Unit,
    private val setNextStep: (String, Long) -> Unit,
    private val initializeSessionDatabases: () -> Unit,
) {
    fun run() {
        val automationMode = Prefs.getAutomationMode(context)
        val configuredModels = ModelEndpointPrefs.getEndpoints(context)
        val enabledModels = configuredModels.filter(ModelEndpointRules::isRunnable)
        if (configuredModels.none { it.enabled && it.apiKey.isNotBlank() }) {
            val reason = "未配置 API Key"
            updateStatus(reason, 0L)
            Prefs.setTaskShouldRun(context, false)
            Prefs.setLastStopReason(context, reason)
            RuntimeLog.record(context, "worker preflight failed; reason=no_api_key")
            return
        }
        if (enabledModels.isEmpty()) {
            val reason = "模型配置不完整"
            updateStatus(reason, 0L)
            Prefs.setTaskShouldRun(context, false)
            Prefs.setLastStopReason(context, reason)
            RuntimeLog.record(context, "worker preflight failed; reason=no_runnable_model")
            return
        }
        setNextStep("${automationMode.displayName}：检查当前页面", 0L)
        RuntimeLog.record(
            context,
            "worker initialized; mode=${automationMode.name}; enabledModels=${enabledModels.size}; primary=${enabledModels.first().displayName}",
        )

        val greetDb = GreetDatabase(context)
        initializeSessionDatabases()
        val squareInterval = Prefs.getSquareInterval(context).toLong()
        SoulAutomationCoordinator(
            context = context,
            automationMode = automationMode,
            squareIntervalSeconds = squareInterval,
            greetDb = greetDb,
            host = automationHost,
        ).run()
    }
}
