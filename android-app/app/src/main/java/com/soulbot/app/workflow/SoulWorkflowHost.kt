package com.soulbot.app.workflow

import android.content.Context
import com.soulbot.app.accessibility.SoulScreen

/**
 * Common Android runtime capabilities shared by the scene workflows.
 * Scene-specific hosts extend this contract with only their own operations.
 */
internal interface SoulWorkflowHost {
    val context: Context
    val isRunning: Boolean
    fun currentScreen(): SoulScreen
    fun setNextStep(action: String, delayMs: Long = 0L)
    fun sleep(ms: Long)
}
