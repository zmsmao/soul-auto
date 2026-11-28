package com.soulbot.app.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeReadinessSnapshotTest {
    @Test
    fun completeEnvironmentHasNoManualStartBlockers() {
        val snapshot = RuntimeReadinessSnapshot(
            soulInstalled = true,
            accessibilityEnabled = true,
            accessibilityConnected = true,
            overlayGranted = true,
            runnableModelCount = 1,
            modelSummary = "可用 1 个通道",
            automationRunning = false,
        )

        assertTrue(snapshot.automationStartBlockers().isEmpty())
        assertEquals(4, snapshot.checklistCompletedCount)
        assertTrue(snapshot.isReadyForManualStart)
        assertTrue(snapshot.canOpenFloatingController)
    }

    @Test
    fun enabledButDisconnectedAccessibilityServiceIsNotReady() {
        val snapshot = RuntimeReadinessSnapshot(
            soulInstalled = true,
            accessibilityEnabled = true,
            accessibilityConnected = false,
            overlayGranted = true,
            runnableModelCount = 1,
            modelSummary = "可用 1 个通道",
            automationRunning = false,
        )

        assertTrue(snapshot.automationStartBlockers().any { it.contains("尚未连接") })
        assertEquals(3, snapshot.checklistCompletedCount)
    }

    @Test
    fun missingModelBlocksManualStartButNotAppAccess() {
        val snapshot = RuntimeReadinessSnapshot(
            soulInstalled = true,
            accessibilityEnabled = true,
            accessibilityConnected = true,
            overlayGranted = true,
            runnableModelCount = 0,
            modelSummary = "尚未配置模型通道",
            automationRunning = false,
        )

        assertTrue(snapshot.automationStartBlockers().any { it.contains("模型通道") })
        assertEquals(3, snapshot.checklistCompletedCount)
    }

    @Test
    fun missingOverlayIsSeparateFromAutomationPrerequisites() {
        val snapshot = RuntimeReadinessSnapshot(
            soulInstalled = true,
            accessibilityEnabled = true,
            accessibilityConnected = true,
            overlayGranted = false,
            runnableModelCount = 1,
            modelSummary = "可用 1 个通道",
            automationRunning = false,
        )

        assertTrue(snapshot.automationStartBlockers().isEmpty())
        assertEquals(3, snapshot.checklistCompletedCount)
        assertTrue(snapshot.isReadyForManualStart)
        assertTrue(!snapshot.canOpenFloatingController)
    }
}
