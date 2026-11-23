package com.soulbot.app.workflow.match
import com.soulbot.app.*
import com.soulbot.app.conversation.*
import com.soulbot.app.policy.*

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo

/** Android capabilities and shared chat services used by Soul Match workflows. */
internal interface SoulMatchHost {
    val context: Context
    val isRunning: Boolean
    fun currentScreen(): SoulScreen
    fun soulMatchProfileTexts(): List<String>
    fun setNextStep(action: String, delayMs: Long = 0L)
    fun sleep(ms: Long)
    fun waitUnlessQiyu(delayMs: Long): Boolean
    fun convertIncomingVoiceMessages(): Boolean
    fun readThread(): List<Pair<String, String>>
    fun isExpectedConversation(name: String): Boolean
    fun currentIncomingMessageKeys(name: String): List<String>
    fun hasConversationHistory(name: String, scene: ReplyScene): Boolean
    fun learnFromManualReplies(name: String, thread: List<Pair<String, String>>, scene: ReplyScene)
    fun unseenIncomingEvents(
        name: String,
        thread: List<Pair<String, String>>,
        seen: ConversationSeenTracker,
        pendingCount: Int,
    ): List<ConversationIncomingEvent>
    fun memoryThreadId(name: String): String
    fun hasUnreadRedDot(): Boolean
    fun gotoChatList(): Boolean
    fun gotoSoulPlanet(): Boolean
    fun isSoulForeground(): Boolean
    fun findExactText(text: String): List<AccessibilityNodeInfo>
    fun tapNode(node: AccessibilityNodeInfo): Boolean
    fun handleQiyu(): Boolean
    fun generateValidatedChatReply(
        contactName: String,
        thread: List<Pair<String, String>>,
        focusIncoming: List<String>,
        scene: ReplyScene,
    ): String?
    fun generateValidatedOpening(action: String, context: OpeningContext): String?
    fun currentConversationHasChanged(name: String, thread: List<Pair<String, String>>): Boolean
    fun replyToCurrentConversation(name: String, seen: ConversationSeenTracker): Boolean
    fun isOnChat(): Boolean
    fun pendingIncomingVoiceBubbles(): List<AccessibilityNodeInfo>
    fun thinkDelay(): Long
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
    fun isSoulPlanetSurface(): Boolean
}
