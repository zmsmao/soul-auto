package com.soulbot.app.accessibility

import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/** Navigation helpers; reply generation and conversation strategy remain in SoulBotService. */
internal class SoulNavigationController(
    private val context: Context,
    private val ui: SoulAccessibilityUi,
    private val isSoulForegroundNow: () -> Boolean,
    private val screenNow: () -> SoulScreen,
    private val isPlanetSurface: () -> Boolean,
    private val updateStatus: (String) -> Unit,
    private val pause: (Long) -> Unit,
    private val conversationListSnapshot: () -> String,
 ) {
    private fun isOnList(): Boolean = screenNow() == SoulScreen.LIST
    private fun isOnSquare(): Boolean = screenNow() == SoulScreen.SQUARE
    fun selectedSquareChannel(): String =
        ui.findByIds(SoulAccessibilityIds.ID_SQUARE_CHANNEL_TAB)
            .firstOrNull {
                runCatching { it.isSelected }.getOrDefault(false) && ui.isVisibleOnScreen(it)
            }
            ?.let({ ui.textOf(it) })
            .orEmpty()
    fun ensureLocalSquareChannel(): String? {
        if (!isOnSquare()) return null
        val localTab = ui.findByIds(SoulAccessibilityIds.ID_SQUARE_CHANNEL_TAB)
            .filter({ ui.isVisibleOnScreen(it) })
            .firstOrNull {
                val title = ui.textOf(it)
                title.isNotEmpty() && title != "关注" && title != "推荐"
            } ?: return null
        val localTitle = ui.textOf(localTab)
        if (runCatching { localTab.isSelected }.getOrDefault(false)) return localTitle

        updateStatus("切换到同城·$localTitle")
        ui.tapNode(localTab)
        repeat(10) {
            pause(250)
            if (selectedSquareChannel() == localTitle) return localTitle
        }
        RuntimeLog.record(context, "square channel switch failed")
        return null
    }

    // ===== 导航（都带验证） =====
    fun scrollConversationToTop() {
        if (!isOnList()) return
        updateStatus("回到会话顶部")

        // RecyclerView exposes this action on supported Soul versions and can jump
        // directly to the first row without depending on device height.
        ui.findById(SoulAccessibilityIds.ID_CONV_LIST)?.let { list ->
            val args = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_ROW_INT, 0)
            }
            runCatching {
                list.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION.id,
                    args,
                )
            }
            pause(500)
        }

        // Verify that the list is really at the beginning. Keep scrolling backward
        // until the visible conversations stop changing, rather than assuming a
        // fixed number of gestures is enough.
        repeat(MAX_SCROLLS_TO_TOP) {
            if (!isOnList()) return
            val before = conversationListSnapshot()
            val list = ui.findById(SoulAccessibilityIds.ID_CONV_LIST)
            val accepted = runCatching {
                list?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) == true
            }.getOrDefault(false)
            if (!accepted) {
                // Fallback for Soul versions whose list does not publish scroll actions.
                ui.swipeVertically(SoulAccessibilityIds.ID_CONV_LIST, 0.30f, 0.86f, 450)
            }
            pause(500)
            val after = conversationListSnapshot()
            if (before.isNotEmpty() && after == before) return
        }
    }
    fun returnToList(): Boolean {
        updateStatus("返回会话列表")
        // 从任意界面返回聊天列表：二级界面按 back，广场点聊天 tab
        for (i in 0 until 4) {
            if (!isSoulForegroundNow()) return false
            if (screenNow() == SoulScreen.QIYU_POPUP) return false
            if (isOnList()) return true
            if (isOnSquare()) return gotoChatList()
            ui.pressBack(); pause(1000)
        }
        return isOnList()
    }
    fun gotoSquare(): Boolean {
        if (!isSoulForegroundNow()) return false
        if (screenNow() == SoulScreen.QIYU_POPUP) return false
        updateStatus("打开同城广场")
        for (i in 0 until 3) {
            val tab = ui.findById(SoulAccessibilityIds.ID_SQUARE_TAB)
            if (tab != null) {
                ui.tapNode(tab)
                for (w in 0 until 8) {
                    if (isOnSquare()) return ensureLocalSquareChannel() != null
                    pause(800)
                }
                return isOnSquare() && ensureLocalSquareChannel() != null
            }
            if (!isSoulForegroundNow()) return false
            if (screenNow() == SoulScreen.QIYU_POPUP) return false
            ui.pressBack(); pause(1000)
        }
        return isOnSquare() && ensureLocalSquareChannel() != null
    }
    fun gotoChatList(): Boolean {
        if (!isSoulForegroundNow()) return false
        if (screenNow() == SoulScreen.QIYU_POPUP) return false
        updateStatus("返回会话列表")
        for (i in 0 until 3) {
            val tab = ui.findById(SoulAccessibilityIds.ID_MSG_TAB)
            if (tab != null) {
                ui.tapNode(tab)
                for (w in 0 until 8) {
                    if (isOnList()) return true
                    pause(800)
                }
                return isOnList()
            }
            if (!isSoulForegroundNow()) return false
            if (screenNow() == SoulScreen.QIYU_POPUP) return false
            ui.pressBack(); pause(1000)
        }
        return isOnList()
    }
    fun gotoSoulPlanet(): Boolean {
        if (!isSoulForegroundNow()) return false
        if (isPlanetSurface()) return true
        updateStatus("打开星球里的灵魂匹配")
        for (i in 0 until 3) {
            val tab = ui.findVisibleById(SoulAccessibilityIds.ID_PLANET_TAB)
                ?: ui.findExactText("星球").maxByOrNull { ui.rect(it).top }
            if (tab != null) {
                ui.tapNode(tab)
                repeat(12) {
                    if (isPlanetSurface()) return true
                    pause(500)
                }
                return isPlanetSurface()
            }
            if (!isSoulForegroundNow()) return false
            ui.pressBack()
            pause(1000)
        }
        return isPlanetSurface()
    }
    fun gotoModeIdleSurface(mode: AutomationMode): Boolean =
        when (mode.idleSurface) {
            IdleSurface.SOUL_PLANET -> gotoSoulPlanet()
            IdleSurface.LOCAL_SQUARE -> gotoSquare()
        }
    fun backToSquare(): Boolean {
        for (i in 0 until 4) {
            if (!isSoulForegroundNow()) return false
            if (screenNow() == SoulScreen.QIYU_POPUP) return false
            if (isOnSquare()) return ensureLocalSquareChannel() != null
            ui.pressBack(); pause(1000)
        }
        return isOnSquare() && ensureLocalSquareChannel() != null
    }

    // ===== 延时 =====

    companion object {
        const val MAX_SCROLLS_TO_TOP = 20
    }
}