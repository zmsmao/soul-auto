package com.soulbot.app.app

import com.soulbot.app.accessibility.*
import com.soulbot.app.conversation.*
import com.soulbot.app.conversation.context.ContextFragment
import com.soulbot.app.conversation.runtime.*
import com.soulbot.app.data.*
import com.soulbot.app.diagnostics.*
import com.soulbot.app.model.*
import com.soulbot.app.policy.*
import com.soulbot.app.workflow.SoulMatchContent
import com.soulbot.app.workflow.square.SoulSquareTiming
import com.soulbot.app.workflow.greeting.*
import com.soulbot.app.workflow.match.*
import com.soulbot.app.workflow.privatechat.*
import com.soulbot.app.workflow.qiyu.*
import com.soulbot.app.workflow.square.*
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.random.Random

/** Composition root for the accessibility, conversation and diagnostics components hosted by SoulBotService. */
internal class SoulServiceComponentFactory(private val service: SoulBotService) {
    val messageSender: SoulMessageSender by lazy(LazyThreadSafetyMode.NONE) {
        SoulMessageSender(object : SoulMessageSenderHost {
            override val context: android.content.Context get() = service.applicationContext
            override val isRunning: Boolean get() = SoulBotService.running
            override val memoryDatabase: ConversationMemoryDatabase? get() = service.conversationMemoryDb
            override val random: Random get() = service.random
            override fun isOnChat(): Boolean = service.isOnChat()
            override fun splitReply(text: String): List<String> = service.splitReply(text)
            override fun hasPendingQiyu(): Boolean = service.hasPendingQiyu()
            override fun currentScreen(): SoulScreen = service.currentScreen()
            override fun memoryThreadId(contactName: String): String = service.memoryThreadId(contactName)
            override fun currentIncomingMessageKeys(contactName: String): List<String> =
                service.currentIncomingMessageKeys(contactName)
            override fun normalizeReply(text: String): String = service.normalizeReply(text)
            override fun outgoingMessageCount(): Int = service.outgoingMessageCount()
            override fun hasConfirmedConversationName(name: String): Boolean =
                service.hasConfirmedConversationName(name)
            override fun setNextStep(action: String, delayMs: Long) = service.setNextStep(action, delayMs)
            override fun findById(id: String): AccessibilityNodeInfo? = service.findById(id)
            override fun tapNode(node: AccessibilityNodeInfo): Boolean = service.tapNode(node)
            override fun sleep(ms: Long) = service.sleep(ms)
            override fun setText(node: AccessibilityNodeInfo, text: String): Boolean = service.setText(node, text)
            override fun outgoingOccurrenceCount(text: String): Int = service.outgoingOccurrenceCount(text)
            override fun visibleMessageOccurrenceCount(text: String): Int = service.visibleMessageOccurrenceCount(text)
            override fun findVisibleById(id: String): AccessibilityNodeInfo? = service.findVisibleById(id)
            override fun textOf(node: AccessibilityNodeInfo?): String = service.textOf(node)
        })
    }

    val voiceMessageController: SoulVoiceMessageController by lazy(LazyThreadSafetyMode.NONE) {
        SoulVoiceMessageController(object : SoulVoiceMessageHost {
            override val context: android.content.Context get() = service.applicationContext
            override val isRunning: Boolean get() = SoulBotService.running
            override fun isOnChat(): Boolean = service.isOnChat()
            override fun findByIds(id: String): List<AccessibilityNodeInfo> = service.findByIds(id)
            override fun findExactText(text: String): List<AccessibilityNodeInfo> = service.findExactText(text)
            override fun descendantsById(root: AccessibilityNodeInfo, id: String): List<AccessibilityNodeInfo> =
                service.descendantsById(root, id)
            override fun rect(node: AccessibilityNodeInfo): Rect = service.rect(node)
            override fun contains(outer: Rect, inner: Rect): Boolean = service.contains(outer, inner)
            override fun textOf(node: AccessibilityNodeInfo?): String = service.textOf(node)
            override fun messageRole(
                item: AccessibilityNodeInfo,
                avatars: List<AccessibilityNodeInfo>,
                readReceipts: List<AccessibilityNodeInfo>,
            ): String = service.messageRole(item, avatars, readReceipts)
            override fun activeWindowRegion(): UiRegion = service.activeWindowRegion()
            override fun dp(value: Int): Int = service.dp(value)
            override fun isVisibleOnScreen(node: AccessibilityNodeInfo): Boolean =
                service.isVisibleOnScreen(node)
            override fun setNextStep(action: String, delayMs: Long) = service.setNextStep(action, delayMs)
            override fun tapNode(node: AccessibilityNodeInfo): Boolean = service.tapNode(node)
            override fun sleep(ms: Long) = service.sleep(ms)
            override fun longPressNode(node: AccessibilityNodeInfo): Boolean = service.longPressNode(node)
            override fun tap(x: Float, y: Float): Boolean = service.tap(x, y)
            override fun recordVoiceMenuOffset(voiceBounds: UiRegion, menuBounds: UiRegion, activeArea: UiRegion) =
                DeviceCompatibilityStore.recordVoiceMenuOffset(service.applicationContext, voiceBounds, menuBounds, activeArea)
            override fun learnedVoiceMenuPoint(voiceBounds: UiRegion, activeArea: UiRegion, edgeInset: Int): UiPoint? =
                DeviceCompatibilityStore.learnedVoiceMenuPoint(service.applicationContext, voiceBounds, activeArea, edgeInset)
            override fun hasLearnedVoiceMenuOffset(): Boolean =
                DeviceCompatibilityStore.hasLearnedVoiceMenuOffset(service.applicationContext)
        })
    }

    val conversationListScanner: SoulConversationListScanner by lazy(LazyThreadSafetyMode.NONE) {
        SoulConversationListScanner(object : SoulConversationListHost {
            override val context: android.content.Context get() = service.applicationContext
            override fun isOnList(): Boolean = service.isOnList()
            override fun findByIds(id: String): List<AccessibilityNodeInfo> = service.findByIds(id)
            override fun isVisibleOnScreen(node: AccessibilityNodeInfo): Boolean =
                service.isVisibleOnScreen(node)
            override fun rect(node: AccessibilityNodeInfo): Rect = service.rect(node)
            override fun contains(outer: Rect, inner: Rect): Boolean = service.contains(outer, inner)
            override fun textOf(node: AccessibilityNodeInfo?): String = service.textOf(node)
        })
    }

    val replyGenerationEngine: SoulReplyGenerationEngine by lazy(LazyThreadSafetyMode.NONE) {
        SoulReplyGenerationEngine(object : SoulReplyGenerationHost {
            override val context: android.content.Context get() = service.applicationContext
            override val isRunning: Boolean get() = SoulBotService.running
            override var learningDatabase: ChatLearningDatabase?
                get() = service.learningDb
                set(value) { service.learningDb = value }
            override var memoryDatabase: ConversationMemoryDatabase?
                get() = service.conversationMemoryDb
                set(value) { service.conversationMemoryDb = value }
            override var activeGenerationCancellation: ModelCallCancellation?
                get() = service.activeGenerationCancellation
                set(value) { service.activeGenerationCancellation = value }
            override fun currentIncomingMessageKeys(contactName: String): List<String> =
                service.currentIncomingMessageKeys(contactName)
            override fun isOnChat(): Boolean = service.isOnChat()
            override fun isExpectedConversation(contactName: String): Boolean =
                service.isExpectedConversation(contactName)
            override fun pendingIncomingVoiceBubbles(): List<AccessibilityNodeInfo> =
                service.pendingIncomingVoiceBubbles()
            override fun hasUnreadRedDot(): Boolean = service.hasUnreadRedDot()
            override fun personalizationContext(
                contactName: String,
                latestMessage: String,
                scene: ReplyScene,
                includeMemory: Boolean,
                styleQuery: String,
                excludeContextKey: String,
            ): String = service.personalizationContext(
                contactName, latestMessage, scene, includeMemory, styleQuery, excludeContextKey,
            )
            override fun contextFragments(
                contactName: String,
                latestMessage: String,
                scene: ReplyScene,
                includeMemory: Boolean,
                styleQuery: String,
                excludeContextKey: String,
            ): List<ContextFragment> = service.contextFragments(
                contactName, latestMessage, scene, includeMemory, styleQuery, excludeContextKey,
            )
            override fun memoryThreadId(contactName: String): String = service.memoryThreadId(contactName)
            override fun setNextStep(action: String, delayMs: Long) =
                service.setNextStep(action, delayMs)
            override fun sleep(ms: Long) = service.sleep(ms)
        })
    }

    val conversationContextManager: SoulConversationContextManager by lazy(LazyThreadSafetyMode.NONE) {
        SoulConversationContextManager(object : SoulConversationContextHost {
            override val context: android.content.Context get() = service.applicationContext
            override val learningDatabase: ChatLearningDatabase? get() = service.learningDb
            override val memoryDatabase: ConversationMemoryDatabase? get() = service.conversationMemoryDb
            override val replyReplayDatabase: ReplyReplayDatabase? get() = service.replyReplayDb
            override fun currentIncomingMessageKeys(contactName: String): List<String> =
                service.currentIncomingMessageKeys(contactName)
            override fun outgoingMessageCount(): Int = service.outgoingMessageCount()
            override fun openingFingerprint(reply: String, context: OpeningContext): OpeningFingerprint =
                OpeningReplyPolicy.fingerprint(reply, context)
            override fun isOnChat(): Boolean = service.isOnChat()
            override fun isExpectedConversation(contactName: String): Boolean =
                service.isExpectedConversation(contactName)
            override fun readThread(): List<Pair<String, String>> = service.readThread()
            override fun findVisibleById(id: String): AccessibilityNodeInfo? =
                service.findVisibleById(id)
            override fun findByIds(id: String): List<AccessibilityNodeInfo> = service.findByIds(id)
            override fun isVisibleOnScreen(node: AccessibilityNodeInfo): Boolean =
                service.isVisibleOnScreen(node)
            override fun rect(node: AccessibilityNodeInfo): Rect = service.rect(node)
            override fun activeWindowRegion(): UiRegion = service.activeWindowRegion()
            override fun screenHeight(): Int = service.screenHeight()
        })
    }

    val compatibilitySnapshotCollector: SoulCompatibilitySnapshotCollector by lazy(LazyThreadSafetyMode.NONE) {
        SoulCompatibilitySnapshotCollector(object : SoulCompatibilitySnapshotHost {
            override val context: android.content.Context get() = service.applicationContext
            override var lastDiagnosticsSignature: String
                get() = service.lastDiagnosticsSignature
                set(value) { service.lastDiagnosticsSignature = value }
            override var lastDiagnosticVisited: Int
                get() = service.lastDiagnosticVisited
                set(value) { service.lastDiagnosticVisited = value }
            override var lastDiagnosticTruncated: Boolean
                get() = service.lastDiagnosticTruncated
                set(value) { service.lastDiagnosticTruncated = value }
            override fun isSamplerClosed(): Boolean = service.diagnosticsSampler.isClosed()
            override fun isCurrentThreadInterrupted(): Boolean = Thread.currentThread().isInterrupted
            override fun root(): AccessibilityNodeInfo? = service.root()
            override fun rect(node: AccessibilityNodeInfo): Rect = service.rect(node)
            override fun screenWidth(): Int = service.screenWidth()
            override fun screenHeight(): Int = service.screenHeight()
            override fun textOf(node: AccessibilityNodeInfo?): String = service.textOf(node)
        })
    }


}
