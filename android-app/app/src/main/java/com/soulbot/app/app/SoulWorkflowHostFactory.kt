package com.soulbot.app.app

import com.soulbot.app.accessibility.*
import com.soulbot.app.automation.*
import com.soulbot.app.conversation.*
import com.soulbot.app.data.*
import com.soulbot.app.diagnostics.*
import com.soulbot.app.model.*
import com.soulbot.app.policy.*
import com.soulbot.app.queue.MessageQueueState
import android.graphics.Rect
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.random.Random
import com.soulbot.app.workflow.SoulMatchContent
import com.soulbot.app.workflow.square.SoulSquareTiming
import com.soulbot.app.workflow.greeting.SoulGreetingHost
import com.soulbot.app.workflow.greeting.SoulGreetingWorkflow
import com.soulbot.app.workflow.match.SoulMatchHost
import com.soulbot.app.workflow.match.SoulMatchWorkflow
import com.soulbot.app.workflow.privatechat.SoulPrivateChatHost
import com.soulbot.app.workflow.privatechat.SoulPrivateChatWorkflow
import com.soulbot.app.workflow.qiyu.SoulQiyuHost
import com.soulbot.app.workflow.qiyu.SoulQiyuWorkflow
import com.soulbot.app.workflow.square.SoulSquareHost
import com.soulbot.app.workflow.square.SoulSquareWorkflow

internal class SoulWorkflowHostFactory(private val service: SoulBotService) {
    val automationHost: SoulAutomationHost by lazy(LazyThreadSafetyMode.NONE) {
            object : SoulAutomationHost {
                override val isRunning: Boolean get() = SoulBotService.running
                override fun setNextStep(action: String, delayMs: Long) = service.setNextStep(action, delayMs)
                override fun sleep(ms: Long) = service.sleep(ms)
                override fun currentScreen(): SoulScreen = service.currentScreen()
                override fun hasUnreadRedDot(): Boolean = service.hasUnreadRedDot()
                override fun findVisibleById(id: String): AccessibilityNodeInfo? = service.findVisibleById(id)
                override fun hasPendingQiyu(): Boolean = service.hasPendingQiyu()
                override fun clearPendingQiyu() = service.clearPendingQiyu()
                override fun handleQiyu(): Boolean = service.handleQiyu()
                override fun resumePendingQiyu(): Boolean = service.resumePendingQiyu()
                override fun gotoChatList(): Boolean = service.gotoChatList()
                override fun skipNoSend() = service.skipNoSend()
                override fun skipGift() = service.skipGift()
                override fun gotoModeIdleSurface(mode: AutomationMode): Boolean = service.gotoModeIdleSurface(mode)
                override fun handleSoulMatchChat(handledMatches: MutableSet<String>, greetDb: GreetDatabase, seen: ConversationSeenTracker): Boolean =
                    service.handleSoulMatchChat(handledMatches, greetDb, seen)
                override fun handlePlanetChatIdle(handledMatches: MutableSet<String>, greetDb: GreetDatabase, seen: ConversationSeenTracker) =
                    service.handlePlanetChatIdle(handledMatches, greetDb, seen)
                override fun isOnSquare(): Boolean = service.isOnSquare()
                override fun gotoSquare(): Boolean = service.gotoSquare()
                override fun browseSquareOnce(seenDm: MutableSet<String>, attemptedDmProfiles: MutableSet<String>, greetDb: GreetDatabase): Boolean =
                    service.browseSquareOnce(seenDm, attemptedDmProfiles, greetDb)
                override fun scanUnread(): List<UnreadConversationCandidate> = service.scanUnread()
                override fun replyDelay(timeStr: String): Long = service.replyDelay(timeStr)
                override fun waitUnlessQiyu(delayMs: Long): Boolean = service.waitUnlessQiyu(delayMs)
                override fun replyTo(name: String, seen: ConversationSeenTracker): Boolean = service.replyTo(name, seen)
                override fun scrollConversationToTop() = service.scrollConversationToTop()
                override fun hasGreeting(): Boolean = service.hasGreeting()
                override fun openGreetingList(): Boolean = service.openGreetingList()
                override fun scanGreetingUsers(handledKeys: Set<String>, greetDb: GreetDatabase): List<String> =
                    service.scanGreetingUsers(handledKeys, greetDb)
                override fun replyToGreeting(name: String, seen: ConversationSeenTracker, handledKeys: MutableSet<String>, greetDb: GreetDatabase): Boolean =
                    service.replyToGreeting(name, seen, handledKeys, greetDb)
                override fun pressBack() = service.pressBack()
                override fun gotoSoulPlanet(): Boolean = service.gotoSoulPlanet()
            }
        }

    val soulMatchWorkflow: SoulMatchWorkflow by lazy(LazyThreadSafetyMode.NONE) {
            SoulMatchWorkflow(object : SoulMatchHost {
                override val context: android.content.Context get() = service.applicationContext
                override val isRunning: Boolean get() = SoulBotService.running
                override fun currentScreen(): SoulScreen = service.currentScreen()
                override fun soulMatchProfileTexts(): List<String> = service.soulMatchProfileTexts()
                override fun setNextStep(action: String, delayMs: Long) = service.setNextStep(action, delayMs)
                override fun sleep(ms: Long) = service.sleep(ms)
                override fun waitUnlessQiyu(delayMs: Long): Boolean = service.waitUnlessQiyu(delayMs)
                override fun convertIncomingVoiceMessages(): Boolean = service.convertIncomingVoiceMessages()
                override fun readThread(): List<Pair<String, String>> = service.readThread()
                override fun isExpectedConversation(name: String): Boolean = service.isExpectedConversation(name)
                override fun currentIncomingMessageKeys(name: String): List<String> = service.currentIncomingMessageKeys(name)
                override fun hasConversationHistory(name: String, scene: ReplyScene): Boolean =
                    service.conversationMemoryDb?.hasHistory(memoryThreadId(name), scene) == true
                override fun learnFromManualReplies(name: String, thread: List<Pair<String, String>>, scene: ReplyScene) =
                    service.learnFromManualReplies(name, thread, scene)
                override fun unseenIncomingEvents(name: String, thread: List<Pair<String, String>>, seen: ConversationSeenTracker, pendingCount: Int, scene: ReplyScene): List<ConversationIncomingEvent> =
                    service.unseenIncomingEvents(name, thread, seen, pendingCount, scene)
                override fun memoryThreadId(name: String): String = service.memoryThreadId(name)
                override fun hasUnreadRedDot(): Boolean = service.hasUnreadRedDot()
                override fun gotoChatList(): Boolean = service.gotoChatList()
                override fun gotoSoulPlanet(): Boolean = service.gotoSoulPlanet()
                override fun isSoulForeground(): Boolean = service.isSoulForeground()
                override fun findExactText(text: String): List<AccessibilityNodeInfo> = service.findExactText(text)
                override fun tapNode(node: AccessibilityNodeInfo): Boolean = service.tapNode(node)
                override fun handleQiyu(): Boolean = service.handleQiyu()
                override fun generateValidatedChatReply(contactName: String, thread: List<Pair<String, String>>, focusIncoming: List<String>, scene: ReplyScene): ChatReplyGenerationResult? =
                    service.generateValidatedChatReply(contactName, thread, focusIncoming, scene)
                override fun generateValidatedOpening(action: String, context: OpeningContext): String? =
                    service.generateValidatedOpening(action, context)
                override fun currentConversationHasChanged(name: String, thread: List<Pair<String, String>>): Boolean =
                    service.currentConversationHasChanged(name, thread)
                override fun replyToCurrentConversation(name: String, seen: ConversationSeenTracker): Boolean =
                    service.replyToCurrentConversation(name, seen)
                override fun isOnChat(): Boolean = service.isOnChat()
                override fun pendingIncomingVoiceBubbles(): List<AccessibilityNodeInfo> = service.pendingIncomingVoiceBubbles()
                override fun thinkDelay(): Long = service.thinkDelay()
                override fun send(text: String, expectedContact: String?, idempotencyKey: String?, sceneOverride: ReplyScene?, expectedEventAnchor: String?): Boolean =
                    service.send(text, expectedContact = expectedContact, idempotencyKey = idempotencyKey, sceneOverride = sceneOverride, expectedEventAnchor = expectedEventAnchor)
                override fun recordAutomatedInteraction(contactName: String, incomingMessages: List<String>, reply: String, opening: OpeningContext?, scene: ReplyScene) =
                    service.recordAutomatedInteraction(contactName, incomingMessages, reply, opening, scene)
                override fun recordHistory(contactName: String, context: String, reply: String) {
                    service.historyDb?.record(contactName, context, reply)
                }
                override fun isSoulPlanetSurface(): Boolean = service.isSoulPlanetSurface()
            })
        }

    val privateChatWorkflow: SoulPrivateChatWorkflow by lazy(LazyThreadSafetyMode.NONE) {
            SoulPrivateChatWorkflow(object : SoulPrivateChatHost {
                override val context: android.content.Context get() = service.applicationContext
                override val isRunning: Boolean get() = SoulBotService.running
                override fun currentScreen(): SoulScreen = service.currentScreen()
                override fun isOnList(): Boolean = service.isOnList()
                override fun isOnChat(): Boolean = service.isOnChat()
                override fun findConversation(name: String): AccessibilityNodeInfo? =
                    service.findConversation(name)
                override fun setNextStep(action: String, delayMs: Long) =
                    service.setNextStep(action, delayMs)
                override fun sleep(ms: Long) = service.sleep(ms)
                override fun tapNode(node: AccessibilityNodeInfo): Boolean = service.tapNode(node)
                override fun gotoChatList(): Boolean = service.gotoChatList()
                override fun isExpectedConversation(name: String): Boolean =
                    service.isExpectedConversation(name)
                override fun waitForStableThread(name: String): List<Pair<String, String>> =
                    service.waitForStableThread(name)
                override fun readThread(): List<Pair<String, String>> = service.readThread()
                override fun learnFromManualReplies(name: String, thread: List<Pair<String, String>>) =
                    service.learnFromManualReplies(name, thread)
                override fun pendingIncomingVoiceBubbles(): List<AccessibilityNodeInfo> =
                    service.pendingIncomingVoiceBubbles()
                override fun unseenIncomingEvents(
                    name: String,
                    thread: List<Pair<String, String>>,
                    seen: ConversationSeenTracker,
                    pendingCount: Int,
                ): List<ConversationIncomingEvent> =
                    service.unseenIncomingEvents(name, thread, seen, pendingCount)
                override fun memoryThreadId(name: String): String = service.memoryThreadId(name)
                override fun currentIncomingMessageKeys(name: String): List<String> =
                    service.currentIncomingMessageKeys(name)
                override fun generateValidatedChatReply(
                    contactName: String,
                    thread: List<Pair<String, String>>,
                    focusIncoming: List<String>,
                ): String? =
                    service.generateValidatedChatReply(contactName, thread, focusIncoming)
                override fun normalizeReply(text: String): String = service.normalizeReply(text)
                override fun send(text: String, expectedContact: String, expectedEventAnchor: String): Boolean =
                    service.send(
                        text,
                        expectedContact = expectedContact,
                        expectedEventAnchor = expectedEventAnchor,
                    )
                override fun recordAutomatedInteraction(
                    contactName: String,
                    incomingMessages: List<String>,
                    reply: String,
                ) = service.recordAutomatedInteraction(contactName, incomingMessages, reply)
                override fun recordHistory(contactName: String, context: String, reply: String) {
                    service.historyDb?.record(contactName, context, reply)
                }
            })
        }

    val greetingWorkflow: SoulGreetingWorkflow by lazy(LazyThreadSafetyMode.NONE) {
            SoulGreetingWorkflow(object : SoulGreetingHost {
                override val context: android.content.Context get() = service.applicationContext
                override val isRunning: Boolean get() = SoulBotService.running
                override fun currentScreen(): SoulScreen = service.currentScreen()
                override fun findVisibleById(id: String): AccessibilityNodeInfo? =
                    service.findVisibleById(id)
                override fun findById(id: String): AccessibilityNodeInfo? = service.findById(id)
                override fun findByIds(id: String): List<AccessibilityNodeInfo> = service.findByIds(id)
                override fun isVisibleOnScreen(node: AccessibilityNodeInfo): Boolean =
                    service.isVisibleOnScreen(node)
                override fun textOf(node: AccessibilityNodeInfo?): String = service.textOf(node)
                override fun setNextStep(action: String, delayMs: Long) =
                    service.setNextStep(action, delayMs)
                override fun tapNode(node: AccessibilityNodeInfo): Boolean = service.tapNode(node)
                override fun sleep(ms: Long) = service.sleep(ms)
                override fun isOnChat(): Boolean = service.isOnChat()
                override fun isExpectedConversation(name: String): Boolean =
                    service.isExpectedConversation(name)
                override fun pressBack() = service.pressBack()
                override fun handleQiyu(): Boolean = service.handleQiyu()
                override fun waitForStableThread(name: String): List<Pair<String, String>> =
                    service.waitForStableThread(name)
                override fun learnFromManualReplies(name: String, thread: List<Pair<String, String>>) =
                    service.learnFromManualReplies(name, thread)
                override fun pendingIncomingVoiceBubbles(): List<AccessibilityNodeInfo> =
                    service.pendingIncomingVoiceBubbles()
                override fun unseenIncomingEvents(
                    name: String,
                    thread: List<Pair<String, String>>,
                    seen: ConversationSeenTracker,
                    pendingCount: Int,
                ): List<ConversationIncomingEvent> =
                    service.unseenIncomingEvents(name, thread, seen, pendingCount)
                override fun memoryThreadId(name: String): String = service.memoryThreadId(name)
                override fun currentIncomingMessageKeys(name: String): List<String> =
                    service.currentIncomingMessageKeys(name)
                override fun generateValidatedChatReply(
                    contactName: String,
                    thread: List<Pair<String, String>>,
                    focusIncoming: List<String>,
                ): String? =
                    service.generateValidatedChatReply(contactName, thread, focusIncoming)
                override fun replyToCurrentConversation(name: String, seen: ConversationSeenTracker): Boolean =
                    service.replyToCurrentConversation(name, seen)
                override fun send(text: String, expectedContact: String, expectedEventAnchor: String): Boolean =
                    service.send(
                        text,
                        expectedContact = expectedContact,
                        expectedEventAnchor = expectedEventAnchor,
                    )
                override fun recordAutomatedInteraction(
                    contactName: String,
                    incomingMessages: List<String>,
                    reply: String,
                ) = service.recordAutomatedInteraction(contactName, incomingMessages, reply)
                override fun recordHistory(contactName: String, context: String, reply: String) {
                    service.historyDb?.record(contactName, context, reply)
                }
            })
        }

    val squareWorkflow: SoulSquareWorkflow by lazy(LazyThreadSafetyMode.NONE) {
            SoulSquareWorkflow(object : SoulSquareHost {
                override val context: android.content.Context get() = service.applicationContext
                override val isRunning: Boolean get() = SoulBotService.running
                override fun setStatusText(text: String) { SoulBotService.statusText = text }
                override fun setNextStep(action: String, delayMs: Long) = service.setNextStep(action, delayMs)
                override fun sleep(ms: Long) = service.sleep(ms)
                override fun currentScreen(): SoulScreen = service.currentScreen()
                override fun hasUnreadRedDot(): Boolean = service.hasUnreadRedDot()
                override fun isOnSquare(): Boolean = service.isOnSquare()
                override fun selectedSquareChannel(): String = service.selectedSquareChannel()
                override fun ensureLocalSquareChannel(): String? = service.ensureLocalSquareChannel()
                override fun findById(id: String): AccessibilityNodeInfo? = service.findById(id)
                override fun findByIds(id: String): List<AccessibilityNodeInfo> = service.findByIds(id)
                override fun descendantsById(node: AccessibilityNodeInfo, id: String): List<AccessibilityNodeInfo> =
                    service.descendantsById(node, id)
                override fun isVisibleOnScreen(node: AccessibilityNodeInfo): Boolean = service.isVisibleOnScreen(node)
                override fun rect(node: AccessibilityNodeInfo): Rect = service.rect(node)
                override fun textOf(node: AccessibilityNodeInfo?): String = service.textOf(node)
                override fun dp(value: Int): Int = service.dp(value)
                override fun tap(x: Float, y: Float): Boolean = service.tap(x, y)
                override fun tapNode(node: AccessibilityNodeInfo): Boolean = service.tapNode(node)
                override fun swipeVertically(containerId: String, startRatio: Float, endRatio: Float, duration: Long) =
                    service.swipeVertically(containerId, startRatio, endRatio, duration)
                override fun isOnProfile(): Boolean = service.isOnProfile()
                override fun isSoulForeground(): Boolean = service.isSoulForeground()
                override fun backToSquare(): Boolean = service.backToSquare()
                override fun isOnNoSend(): Boolean = service.isOnNoSend()
                override fun skipNoSend() = service.skipNoSend()
                override fun isOnGift(): Boolean = service.isOnGift()
                override fun skipGift() = service.skipGift()
                override fun isOnChat(): Boolean = service.isOnChat()
                override fun isExpectedConversation(name: String): Boolean = service.isExpectedConversation(name)
                override fun readThread(): List<Pair<String, String>> = service.readThread()
                override fun currentIncomingMessageKeys(name: String): List<String> = service.currentIncomingMessageKeys(name)
                override fun pendingIncomingVoiceBubbles(): List<AccessibilityNodeInfo> = service.pendingIncomingVoiceBubbles()
                override fun generateValidatedOpening(action: String, context: OpeningContext): String? =
                    service.generateValidatedOpening(action, context)
                override fun replyToCurrentConversation(name: String, seen: ConversationSeenTracker): Boolean =
                    service.replyToCurrentConversation(name, seen)
                override fun returnToList(): Boolean = service.returnToList()
                override fun memoryThreadId(name: String): String = service.memoryThreadId(name)
                override fun send(text: String, expectedContact: String?, idempotencyKey: String?, sceneOverride: ReplyScene?, expectedEventAnchor: String?): Boolean =
                    service.send(text, expectedContact = expectedContact, idempotencyKey = idempotencyKey, sceneOverride = sceneOverride, expectedEventAnchor = expectedEventAnchor)
                override fun recordAutomatedInteraction(contactName: String, incomingMessages: List<String>, reply: String, opening: OpeningContext?, scene: ReplyScene) =
                    service.recordAutomatedInteraction(contactName, incomingMessages, reply, opening, scene)
                override fun recordHistory(contactName: String, context: String, reply: String) {
                    service.historyDb?.record(contactName, context, reply)
                }
            })
        }

    val qiyuWorkflow: SoulQiyuWorkflow by lazy(LazyThreadSafetyMode.NONE) {
            SoulQiyuWorkflow(object : SoulQiyuHost {
                override val context: android.content.Context get() = service.applicationContext
                override val isRunning: Boolean get() = SoulBotService.running
                override fun currentScreen(): SoulScreen = service.currentScreen()
                override fun findVisibleById(id: String): AccessibilityNodeInfo? = service.findVisibleById(id)
                override fun textOf(node: AccessibilityNodeInfo?): String = service.textOf(node)
                override fun sleep(ms: Long) = service.sleep(ms)
                override fun tapNode(node: AccessibilityNodeInfo): Boolean = service.tapNode(node)
                override fun isSoulForeground(): Boolean = service.isSoulForeground()
                override fun isOnChat(): Boolean = service.isOnChat()
                override fun hasConfirmedConversationName(name: String): Boolean =
                    service.hasConfirmedConversationName(name)
                override fun displayedConversationName(): String = service.displayedConversationName()
                override fun skipNoSend() = service.skipNoSend()
                override fun skipGift() = service.skipGift()
                override fun setNextStep(action: String, delayMs: Long) = service.setNextStep(action, delayMs)
                override fun convertIncomingVoiceMessages(): Boolean = service.convertIncomingVoiceMessages()
                override fun readThread(): List<Pair<String, String>> = service.readThread()
                override fun hasConversationHistory(contactName: String, scene: ReplyScene): Boolean =
                    service.conversationMemoryDb?.hasHistory(memoryThreadId(contactName), scene) == true
                override fun learnFromManualReplies(contactName: String, thread: List<Pair<String, String>>, scene: ReplyScene) =
                    service.learnFromManualReplies(contactName, thread, scene)
                override fun unseenIncomingEvents(contactName: String, thread: List<Pair<String, String>>, tracker: ConversationSeenTracker, pendingCount: Int): List<ConversationIncomingEvent> =
                    service.unseenIncomingEvents(contactName, thread, tracker, pendingCount)
                override fun memoryThreadId(contactName: String): String = service.memoryThreadId(contactName)
                override fun currentIncomingMessageKeys(contactName: String): List<String> =
                    service.currentIncomingMessageKeys(contactName)
                override fun updateQueueEvents(
                    contactName: String,
                    eventKeys: List<String>,
                    state: MessageQueueState,
                    reason: String,
                ) = service.updateQueueEvents(contactName, eventKeys, state, reason)
                override fun visibleTitleTexts(): List<String> = service.visibleTitleTexts()
                override fun readQiyuSignature(): String = service.readQiyuSignature()
                override fun generateValidatedChatReply(contactName: String, thread: List<Pair<String, String>>, focusIncoming: List<String>, scene: ReplyScene): String? =
                    service.generateValidatedChatReply(contactName, thread, focusIncoming, scene)
                override fun generateValidatedOpening(action: String, context: OpeningContext): String? =
                    service.generateValidatedOpening(action, context)
                override fun pendingIncomingVoiceBubbles(): List<AccessibilityNodeInfo> = service.pendingIncomingVoiceBubbles()
                override fun splitReply(text: String): List<String> = service.splitReply(text)
                override fun outgoingOccurrenceCount(text: String): Int = service.outgoingOccurrenceCount(text)
                override fun send(text: String, expectedContact: String?, inputTimeoutMs: Long?, onInputWritten: (String) -> Unit, onSendClick: () -> Unit, idempotencyKey: String?, sceneOverride: ReplyScene?, expectedEventAnchor: String?): Boolean =
                    service.send(text, expectedContact, inputTimeoutMs, onInputWritten, onSendClick, idempotencyKey, sceneOverride, expectedEventAnchor)
                override fun recordAutomatedInteraction(contactName: String, incomingMessages: List<String>, reply: String, opening: OpeningContext?, scene: ReplyScene) =
                    service.recordAutomatedInteraction(contactName, incomingMessages, reply, opening, scene)
                override fun recordHistory(contactName: String, context: String, reply: String) {
                    service.historyDb?.record(contactName, context, reply)
                }
                override fun gotoChatList(): Boolean = service.gotoChatList()
            })
        }

}
