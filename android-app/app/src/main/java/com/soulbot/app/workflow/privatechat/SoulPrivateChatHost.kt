package com.soulbot.app.workflow.privatechat

import com.soulbot.app.workflow.SoulWorkflowHost
import com.soulbot.app.conversation.ConversationIncomingEvent
import com.soulbot.app.conversation.ConversationSeenTracker
import com.soulbot.app.conversation.ChatReplyGenerationResult
import android.view.accessibility.AccessibilityNodeInfo


/** Android and shared-chat capabilities required by the ordinary private-chat workflow. */
internal interface SoulPrivateChatHost : SoulWorkflowHost {
    fun isOnList(): Boolean
    fun isOnChat(): Boolean
    fun findConversation(name: String): AccessibilityNodeInfo?
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
    fun hasUncertainSendAttempts(name: String): Boolean
    fun memoryThreadId(name: String): String
    fun currentIncomingMessageKeys(name: String): List<String>
    fun generateValidatedChatReply(
        contactName: String,
        thread: List<Pair<String, String>>,
        focusIncoming: List<String>,
    ): ChatReplyGenerationResult?
    fun normalizeReply(text: String): String
    fun send(text: String, expectedContact: String, expectedEventAnchor: String): Boolean
    fun recordAutomatedInteraction(contactName: String, incomingMessages: List<String>, reply: String)
    fun recordHistory(contactName: String, context: String, reply: String)
}
