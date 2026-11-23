package com.soulbot.app.diagnostics

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.soulbot.app.*
import com.soulbot.app.app.SoulBotService
import com.soulbot.app.policy.*
import com.soulbot.app.accessibility.AdaptiveUiPolicy
import com.soulbot.app.accessibility.UiRegion

internal interface SoulCompatibilitySnapshotHost {
    val context: Context
    var lastDiagnosticsSignature: String
    var lastDiagnosticVisited: Int
    var lastDiagnosticTruncated: Boolean
    fun isSamplerClosed(): Boolean
    fun isCurrentThreadInterrupted(): Boolean
    fun root(): AccessibilityNodeInfo?
    fun rect(node: AccessibilityNodeInfo): Rect
    fun screenWidth(): Int
    fun screenHeight(): Int
    fun textOf(node: AccessibilityNodeInfo?): String
}

internal class SoulCompatibilitySnapshotCollector(private val host: SoulCompatibilitySnapshotHost) {
    private fun Rect.toUiRegion(): UiRegion = UiRegion(left, top, right, bottom)

    fun capture() {
        if (host.isSamplerClosed() || host.isCurrentThreadInterrupted()) return
        host.lastDiagnosticVisited = 0
        host.lastDiagnosticTruncated = false
        val now = System.currentTimeMillis()
        val currentRoot = host.root() ?: return
        if (currentRoot.packageName?.toString() != SoulBotService.SOUL_PACKAGE) {
            @Suppress("DEPRECATION")
            currentRoot.recycle()
            return
        }

        val rootBounds = host.rect(currentRoot).toUiRegion()
        val effectiveBounds = AdaptiveUiPolicy.effectiveWindow(
            host.screenWidth(),
            host.screenHeight(),
            rootBounds,
        )
        // Collect ids/capabilities in one bounded traversal of this root. The former
        // helpers repeatedly refreshed rootInActiveWindow and traversed the tree on main.
        val ids = mutableSetOf<String>()
        var localChannel = false
        var matchLabel = false
        var matchProfile = false
        var visibleNodeCount = 0
        val scan = countVisibleNodes(currentRoot) { node ->
            if (!runCatching { node.isVisibleToUser }.getOrDefault(false)) return@countVisibleNodes
            val bounds = host.rect(node).toUiRegion()
            if (!bounds.isValid || bounds.intersect(effectiveBounds) == null) return@countVisibleNodes
            visibleNodeCount++
            val id = node.viewIdResourceName.orEmpty()
            if (id.isNotBlank()) ids += id
            val title = host.textOf(node)
            if (id == SoulBotService.ID_SQUARE_CHANNEL_TAB && title.isNotBlank() && title != "关注" && title != "推荐") localChannel = true
            if (title == "灵魂匹配" || title == "开始匹配") matchLabel = true
            if (id == SoulBotService.ID_GENERIC_TITLE && (title.startsWith("Ta的引力签") || title.startsWith("Ta的星座"))) matchProfile = true
        }
        host.lastDiagnosticVisited = scan.visited
        host.lastDiagnosticTruncated = scan.truncated
        if (host.isSamplerClosed() || host.isCurrentThreadInterrupted()) return
        val hasInput = SoulBotService.ID_INPUT_BOX in ids
        val capabilities = buildSet {
            if (SoulBotService.ID_PLANET_TAB in ids || SoulBotService.ID_SQUARE_TAB in ids || SoulBotService.ID_MSG_TAB in ids) add(SoulUiCapability.BOTTOM_NAV)
            if (SoulBotService.ID_CONV_LIST in ids || SoulBotService.ID_CONV_NAME in ids) add(SoulUiCapability.CHAT_LIST)
            if (hasInput) add(SoulUiCapability.CHAT_INPUT)
            if (SoulBotService.ID_SEND_BUTTON in ids) add(SoulUiCapability.SEND_BUTTON)
            if (SoulBotService.ID_SQUARE_PAGER in ids) add(SoulUiCapability.SQUARE)
            if (localChannel) add(SoulUiCapability.LOCAL_CHANNEL)
            if (SoulBotService.ID_SOUL_MATCH_PERCENT in ids || (!hasInput && matchLabel)) add(SoulUiCapability.SOUL_MATCH)
            if (SoulBotService.ID_VOICE_BUBBLE in ids) add(SoulUiCapability.VOICE_MESSAGE)
            if (SoulBotService.ID_QIYU_CHAT in ids) add(SoulUiCapability.QIYU)
            if (SoulBotService.ID_CHAT_SECRET in ids) add(SoulUiCapability.PROFILE_DM)
        }
        val page = when {
            SoulBotService.ID_QIYU_CHAT in ids -> "奇遇铃"
            SoulBotService.ID_NO_SEND_CLOSE in ids -> "暂不聊天弹窗"
            SoulBotService.ID_GIFT_CLOSE in ids -> "礼物限制弹窗"
            hasInput && (SoulBotService.ID_SOUL_MATCH_PERCENT in ids || matchProfile) -> "灵魂匹配聊天"
            hasInput -> "普通聊天"
            SoulBotService.ID_CHAT_SECRET in ids -> "用户主页"
            SoulBotService.ID_GREETING_TIPS in ids -> "新招呼列表"
            SoulBotService.ID_SQUARE_PAGER in ids -> "广场"
            SoulBotService.ID_CONV_NAME in ids -> "聊天列表"
            matchLabel -> "星球"
            else -> "未识别页面"
        }
        val signature = listOf(
            page,
            rootBounds,
            effectiveBounds,
            capabilities.sortedBy(SoulUiCapability::ordinal).joinToString { it.key },
            visibleNodeCount / 10,
        ).joinToString("|")
        if (signature == host.lastDiagnosticsSignature) return
        host.lastDiagnosticsSignature = signature
        DeviceCompatibilityStore.record(
            host.context,
            SoulUiDiagnosticSnapshot(
                capturedAt = now,
                page = page,
                rootBounds = rootBounds,
                effectiveBounds = effectiveBounds,
                visibleNodeCount = visibleNodeCount,
                capabilities = capabilities,
            ),
        )
    }

    private fun countVisibleNodes(
        rootNode: AccessibilityNodeInfo,
        inspect: (AccessibilityNodeInfo) -> Unit,
    ): DiagnosticTreeScan = DiagnosticTreeSampler.scan(
        root = rootNode,
        maxNodes = 320,
        timeBudgetMs = 100L,
        clock = { android.os.SystemClock.elapsedRealtime() },
        cancelled = { host.isSamplerClosed() || host.isCurrentThreadInterrupted() },
        childCount = { runCatching { it.childCount }.getOrDefault(0) },
        childAt = { node, index -> runCatching { node.getChild(index) }.getOrNull() },
        visit = inspect,
        release = { node ->
            @Suppress("DEPRECATION")
            runCatching { node.recycle() }
        },
    )
}
