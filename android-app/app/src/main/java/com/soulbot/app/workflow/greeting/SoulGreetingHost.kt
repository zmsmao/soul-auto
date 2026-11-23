package com.soulbot.app.workflow.greeting
import com.soulbot.app.*
import com.soulbot.app.accessibility.*
import com.soulbot.app.app.*
import com.soulbot.app.automation.*
import com.soulbot.app.conversation.*
import com.soulbot.app.data.*
import com.soulbot.app.diagnostics.*
import com.soulbot.app.model.*
import com.soulbot.app.policy.*
import com.soulbot.app.workflow.greeting.*
import com.soulbot.app.workflow.match.*
import com.soulbot.app.workflow.privatechat.*
import com.soulbot.app.workflow.qiyu.*
import com.soulbot.app.workflow.square.*

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo

/** Android and conversation capabilities used by the greeting workflow. */
internal interface SoulGreetingHost {
    val context: Context
    val isRunning: Boolean
    fun currentScreen(): SoulScreen
    fun findVisibleById(id: String): AccessibilityNodeInfo?
    fun findById(id: String): AccessibilityNodeInfo?
    fun findByIds(id: String): List<AccessibilityNodeInfo>
    fun isVisibleOnScreen(node: AccessibilityNodeInfo): Boolean
    fun textOf(node: AccessibilityNodeInfo?): String
    fun setNextStep(action: String, delayMs: Long = 0L)
    fun tapNode(node: AccessibilityNodeInfo): Boolean
    fun sleep(ms: Long)
    fun isOnChat(): Boolean
    fun isExpectedConversation(name: String): Boolean
    fun pressBack()
    fun handleQiyu(): Boolean
    fun waitForStableThread(name: String): List<Pair<String, String>>
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
    fun replyToCurrentConversation(name: String, seen: ConversationSeenTracker): Boolean
    fun send(text: String, expectedContact: String, expectedEventAnchor: String): Boolean
    fun recordAutomatedInteraction(contactName: String, incomingMessages: List<String>, reply: String)
    fun recordHistory(contactName: String, context: String, reply: String)
}
