package com.soulbot.app.workflow.square

import com.soulbot.app.workflow.SoulWorkflowHost
import com.soulbot.app.conversation.ConversationSeenTracker
import com.soulbot.app.conversation.OpeningContext
import com.soulbot.app.conversation.ReplyScene
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** Android page-agent operations required by Square browsing and its follow-up chat. */
internal interface SoulSquareHost : SoulWorkflowHost {
    fun setStatusText(text: String)
    fun hasUnreadRedDot(): Boolean
    fun isOnSquare(): Boolean
    fun selectedSquareChannel(): String
    fun ensureLocalSquareChannel(): String?
    fun findById(id: String): AccessibilityNodeInfo?
    fun findByIds(id: String): List<AccessibilityNodeInfo>
    fun descendantsById(node: AccessibilityNodeInfo, id: String): List<AccessibilityNodeInfo>
    fun isVisibleOnScreen(node: AccessibilityNodeInfo): Boolean
    fun rect(node: AccessibilityNodeInfo): Rect
    fun textOf(node: AccessibilityNodeInfo?): String
    fun dp(value: Int): Int
    fun tap(x: Float, y: Float): Boolean
    fun tapNode(node: AccessibilityNodeInfo): Boolean
    fun swipeVertically(containerId: String, startRatio: Float, endRatio: Float, duration: Long = 300L)
    fun isOnProfile(): Boolean
    fun isSoulForeground(): Boolean
    fun backToSquare(): Boolean
    fun isOnNoSend(): Boolean
    fun skipNoSend()
    fun isOnGift(): Boolean
    fun skipGift()
    fun isOnChat(): Boolean
    fun isExpectedConversation(name: String): Boolean
    fun readThread(): List<Pair<String, String>>
    fun currentIncomingMessageKeys(name: String): List<String>
    fun pendingIncomingVoiceBubbles(): List<AccessibilityNodeInfo>
    fun generateValidatedOpening(action: String, context: OpeningContext): String?
    fun replyToCurrentConversation(name: String, seen: ConversationSeenTracker): Boolean
    fun returnToList(): Boolean
    fun memoryThreadId(name: String): String
    fun send(
        text: String,
        expectedContact: String? = null,
        idempotencyKey: String? = null,
        sceneOverride: ReplyScene? = null,
        expectedEventAnchor: String? = null,
    ): Boolean
    fun recordAutomatedInteraction(
        contactName: String,
        incomingMessages: List<String>,
        reply: String,
        opening: OpeningContext? = null,
        scene: ReplyScene = opening?.scene ?: ReplyScene.CHAT,
    )
    fun recordHistory(contactName: String, context: String, reply: String)
}
