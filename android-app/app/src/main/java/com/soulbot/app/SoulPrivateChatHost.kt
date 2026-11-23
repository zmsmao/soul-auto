package com.soulbot.app

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo

/** Android and shared-chat capabilities required by the ordinary private-chat workflow. */
internal interface SoulPrivateChatHost {
    val context: Context
    val isRunning: Boolean
    fun currentScreen(): SoulScreen
    fun isOnList(): Boolean
    fun isOnChat(): Boolean
    fun findConversation(name: String): AccessibilityNodeInfo?
    fun setNextStep(action: String, delayMs: Long = 0L)
    fun sleep(ms: Long)
    fun tapNode(node: AccessibilityNodeInfo): Boolean
    fun gotoChatList(): Boolean
    fun isExpectedConversation(name: String): Boolean
    fun waitForStableThread(name: String): List<Pair<String, String>>
    fun readThread(): List<Pair<String, String>>
    fun learnFromManualReplies(name: String, thread: List<Pair<String, String>>)
    fun pendingIncomingVoiceBubbles(): List<AccessibilityNodeInfo>
    fun unseenIncomingEvents(
        name: String,
        thread: List<Pair<String, String>>,
        seen: ConversationSeenTracker,
        pendingCount: Int,
    ): List<ConversationIncomingEvent>
    fun memoryThreadId(name: String): String
    fun currentIncomingMessageKeys(name: String): List<String>
    fun generateValidatedChatReply(
        contactName: String,
        thread: List<Pair<String, String>>,
        focusIncoming: List<String>,
    ): String?
    fun normalizeReply(text: String): String
    fun send(text: String, expectedContact: String, expectedEventAnchor: String): Boolean
    fun recordAutomatedInteraction(contactName: String, incomingMessages: List<String>, reply: String)
    fun recordHistory(contactName: String, context: String, reply: String)
}
