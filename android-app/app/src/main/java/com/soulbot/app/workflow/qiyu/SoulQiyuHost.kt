package com.soulbot.app.workflow.qiyu
import com.soulbot.app.*
import com.soulbot.app.conversation.*
import com.soulbot.app.policy.*

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo

/** Android page-agent operations required by the Qiyu conversation workflow. */
internal interface SoulQiyuHost {
    val context: Context
    val isRunning: Boolean
    fun currentScreen(): SoulScreen
    fun findVisibleById(id: String): AccessibilityNodeInfo?
    fun textOf(node: AccessibilityNodeInfo?): String
    fun sleep(ms: Long)
    fun tapNode(node: AccessibilityNodeInfo): Boolean
    fun isSoulForeground(): Boolean
    fun isOnChat(): Boolean
    fun hasConfirmedConversationName(name: String): Boolean
    fun displayedConversationName(): String
    fun skipNoSend()
    fun skipGift()
    fun setNextStep(action: String, delayMs: Long = 0L)
    fun convertIncomingVoiceMessages(): Boolean
    fun readThread(): List<Pair<String, String>>
    fun hasConversationHistory(contactName: String, scene: ReplyScene): Boolean
    fun learnFromManualReplies(contactName: String, thread: List<Pair<String, String>>, scene: ReplyScene)
    fun unseenIncomingEvents(contactName: String, thread: List<Pair<String, String>>, tracker: ConversationSeenTracker, pendingCount: Int): List<ConversationIncomingEvent>
    fun memoryThreadId(contactName: String): String
    fun currentIncomingMessageKeys(contactName: String): List<String>
    fun visibleTitleTexts(): List<String>
    fun readQiyuSignature(): String
    fun generateValidatedChatReply(contactName: String, thread: List<Pair<String, String>>, focusIncoming: List<String>, scene: ReplyScene): String?
    fun generateValidatedOpening(action: String, context: OpeningContext): String?
    fun pendingIncomingVoiceBubbles(): List<AccessibilityNodeInfo>
    fun splitReply(text: String): List<String>
    fun outgoingOccurrenceCount(text: String): Int
    fun send(
        text: String,
        expectedContact: String? = null,
        inputTimeoutMs: Long? = null,
        onInputWritten: (String) -> Unit = {},
        onSendClick: () -> Unit = {},
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
    fun gotoChatList(): Boolean
}
