package com.soulbot.app.accessibility

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import com.soulbot.app.*
import com.soulbot.app.app.SoulBotService
import com.soulbot.app.conversation.*
import com.soulbot.app.data.ConversationMemoryDatabase
import com.soulbot.app.diagnostics.RuntimeLog
import com.soulbot.app.policy.*
import kotlin.random.Random

internal interface SoulMessageSenderHost {
    val context: Context
    val isRunning: Boolean
    val memoryDatabase: ConversationMemoryDatabase?
    val random: Random
    fun isOnChat(): Boolean
    fun splitReply(text: String): List<String>
    fun hasPendingQiyu(): Boolean
    fun currentScreen(): SoulScreen
    fun memoryThreadId(contactName: String): String
    fun currentIncomingMessageKeys(contactName: String): List<String>
    fun normalizeReply(text: String): String
    fun outgoingMessageCount(): Int
    fun hasConfirmedConversationName(name: String): Boolean
    fun setNextStep(action: String, delayMs: Long = 0L)
    fun findById(id: String): AccessibilityNodeInfo?
    fun tapNode(node: AccessibilityNodeInfo): Boolean
    fun sleep(ms: Long)
    fun setText(node: AccessibilityNodeInfo, text: String): Boolean
    fun outgoingOccurrenceCount(text: String): Int
    fun visibleMessageOccurrenceCount(text: String): Int
    fun findVisibleById(id: String): AccessibilityNodeInfo?
    fun textOf(node: AccessibilityNodeInfo?): String
}

/** Sends text through Soul's accessibility UI and confirms the resulting chat event. */
internal class SoulMessageSender(private val host: SoulMessageSenderHost) {
    fun send(
        text: String,
        expectedContact: String? = null,
        inputTimeoutMs: Long? = null,
        onInputWritten: (String) -> Unit = {},
        onSendClick: () -> Unit = {},
        idempotencyKey: String? = null,
        sceneOverride: ReplyScene? = null,
        expectedEventAnchor: String? = null,
    ): Boolean {
        if (!host.isOnChat()) {
            RuntimeLog.record(host.context, "message send rejected; reason=not_on_chat")
            return false
        }
        val pieces = host.splitReply(text)
        val sendScene = sceneOverride ?: when {
            host.hasPendingQiyu() -> ReplyScene.QIYU
            host.currentScreen() == SoulScreen.SOUL_MATCH_CHAT -> ReplyScene.SOUL_MATCH
            else -> ReplyScene.CHAT
        }
        val sendThreadId = expectedContact?.let(host::memoryThreadId).orEmpty()
        val incomingEventKeys = expectedContact?.let(host::currentIncomingMessageKeys).orEmpty()
        val incomingEventAnchor = incomingEventKeys.joinToString(",")
        if (expectedEventAnchor != null && incomingEventAnchor != expectedEventAnchor) {
            RuntimeLog.record(host.context, "message send rejected; reason=stale_event_before_input")
            return false
        }
        val generatedSendKey = expectedContact?.let {
            ConversationIdentity.replySendKey(sendThreadId, sendScene.name, incomingEventKeys, text)
        }
        val durableSendKey = (idempotencyKey ?: generatedSendKey)?.takeIf { host.memoryDatabase != null }
        fun expectedEventIsCurrent(): Boolean {
            if (expectedContact == null || expectedEventAnchor == null) return true
            return host.currentIncomingMessageKeys(expectedContact).joinToString(",") == expectedEventAnchor
        }
        val reservation = durableSendKey?.let { key ->
            host.memoryDatabase?.reserveSendAttempt(
                sendKey = key,
                threadId = sendThreadId,
                scene = sendScene,
                bodyHash = ConversationIdentity.digest(host.normalizeReply(text)),
                eventAnchor = incomingEventKeys.joinToString(","),
                outgoingBaseline = host.outgoingMessageCount(),
            )
        }
        if (durableSendKey != null && reservation != null) {
            when (reservation.disposition) {
                SendAttemptDisposition.ALREADY_CONFIRMED -> {
                    if (!expectedEventIsCurrent()) return false
                    RuntimeLog.record(host.context, "message send suppressed; reason=already_confirmed")
                    return true
                }
                SendAttemptDisposition.UNCERTAIN -> {
                    if (host.outgoingMessageCount() > reservation.outgoingBaseline) {
                        host.memoryDatabase?.markSendAttemptConfirmed(durableSendKey)
                        return expectedEventIsCurrent()
                    }
                    if (!expectedEventIsCurrent()) return false
                    host.memoryDatabase?.markSendAttemptUncertain(durableSendKey)
                    RuntimeLog.record(host.context, "message send suppressed; reason=previous_click_unconfirmed")
                    return false
                }
                SendAttemptDisposition.READY -> Unit
            }
        }
        val attemptId = System.nanoTime().toString(36)
        val startedAt = android.os.SystemClock.elapsedRealtime()
        RuntimeLog.record(
            host.context,
            "message send started; attemptId=$attemptId; parts=${pieces.size}; chars=${text.length}",
        )
        for ((i, piece) in pieces.withIndex()) {
            var inputReady = false
            val inputDeadline = inputTimeoutMs?.let { System.currentTimeMillis() + it }
            while (host.isRunning && host.isOnChat() && !inputReady) {
                if (expectedContact != null && !host.hasConfirmedConversationName(expectedContact)) return false
                if (inputDeadline != null && System.currentTimeMillis() >= inputDeadline) {
                    RuntimeLog.record(host.context, "message input timed out; attemptId=$attemptId; part=${i + 1}")
                    return false
                }
                host.setNextStep("输入第 ${i + 1} 段回复")
                val input = host.findById(SoulBotService.ID_INPUT_BOX)
                if (input == null) {
                    RuntimeLog.record(
                        host.context,
                        "message input missing; attemptId=$attemptId; part=${i + 1}",
                    )
                    host.setNextStep("等待输入框后重试", SoulBotService.SQUARE_RETRY_DELAY_MS)
                    host.sleep(SoulBotService.SQUARE_RETRY_DELAY_MS)
                    continue
                }
                host.tapNode(input)
                host.sleep(300)
                if (expectedContact != null && !host.hasConfirmedConversationName(expectedContact)) return false
                if (!host.setText(input, piece)) {
                    RuntimeLog.record(
                        host.context,
                        "message input injection failed; attemptId=$attemptId; part=${i + 1}",
                    )
                    host.setNextStep("输入失败，重新输入第 ${i + 1} 段", SoulBotService.SQUARE_RETRY_DELAY_MS)
                    host.sleep(SoulBotService.SQUARE_RETRY_DELAY_MS)
                    continue
                }
                onInputWritten(piece)
                inputReady = true
            }
            if (!inputReady) return false
            if (!expectedEventIsCurrent()) {
                RuntimeLog.record(host.context, "message send rejected; reason=stale_event_after_input")
                return false
            }

            host.sleep(500)
            val beforeOutgoingCount = host.outgoingOccurrenceCount(piece)
            val beforeVisibleCount = host.visibleMessageOccurrenceCount(piece)
            val sendButton = host.findVisibleById(SoulBotService.ID_SEND_BUTTON) ?: run {
                RuntimeLog.record(
                    host.context,
                    "message send button missing; attemptId=$attemptId; part=${i + 1}",
                )
                host.setNextStep("未找到发送按钮，重新检查", 5000)
                host.sleep(5000)
                return false
            }

            var sent = false
            var durableClickPerformed = false
            for (sendAttempt in 0 until (if (durableSendKey == null) 2 else 1)) {
                if (expectedContact != null && !host.hasConfirmedConversationName(expectedContact)) return false
                host.setNextStep(
                    if (sendAttempt == 0) "发送第 ${i + 1} 段回复"
                    else "上次点击未生效，再发送一次",
                )
                val currentButton = host.findVisibleById(SoulBotService.ID_SEND_BUTTON) ?: break
                if (!expectedEventIsCurrent()) {
                    RuntimeLog.record(host.context, "message send rejected; reason=stale_event_before_click")
                    return false
                }
                if (durableSendKey != null) host.memoryDatabase?.markSendAttemptClicked(durableSendKey)
                val clickAccepted = host.tapNode(if (sendAttempt == 0) sendButton else currentButton)
                if (!clickAccepted) {
                    if (durableSendKey != null) host.memoryDatabase?.markSendAttemptPrepared(durableSendKey)
                    break
                }
                onSendClick()
                if (durableSendKey != null) durableClickPerformed = true

                val confirmDeadline = System.currentTimeMillis() + 10_000L
                while (host.isRunning && host.isOnChat() && System.currentTimeMillis() < confirmDeadline) {
                    if (expectedContact != null && !host.hasConfirmedConversationName(expectedContact)) return false
                    if (host.outgoingOccurrenceCount(piece) > beforeOutgoingCount ||
                        (expectedContact == null && host.visibleMessageOccurrenceCount(piece) > beforeVisibleCount)
                    ) {
                        sent = true
                        break
                    }
                    val remaining = confirmDeadline - System.currentTimeMillis()
                    host.setNextStep("确认第 ${i + 1} 段已出现在聊天中", remaining)
                    host.sleep(500)
                }

                if (sent) {
                    if (durableSendKey != null) host.memoryDatabase?.markSendAttemptConfirmed(durableSendKey)
                    break
                }
                // Only click again when the original text is still in the input box.
                // If it has disappeared, the result is ambiguous and another click could duplicate it.
                if (host.normalizeReply(host.textOf(host.findVisibleById(SoulBotService.ID_INPUT_BOX))) != host.normalizeReply(piece)) break
            }
            if (!sent) {
                if (durableSendKey != null && durableClickPerformed) {
                    host.memoryDatabase?.markSendAttemptUncertain(durableSendKey)
                }
                RuntimeLog.record(
                    host.context,
                    "message send unconfirmed; attemptId=$attemptId; part=${i + 1}; durationMs=${android.os.SystemClock.elapsedRealtime() - startedAt}",
                )
                host.setNextStep("消息未确认发出，停止重复点击", 1500)
                host.sleep(1500)
                return false
            }
            if (i < pieces.size - 1) {
                val nextPartDelay = host.random.nextLong(2000, 5000)
                host.setNextStep("发送第 " + (i + 2) + " 段回复", nextPartDelay)
                host.sleep(nextPartDelay)
            }
        }
        RuntimeLog.record(
            host.context,
            "message send confirmed; attemptId=$attemptId; parts=${pieces.size}; durationMs=${android.os.SystemClock.elapsedRealtime() - startedAt}",
        )
        return true
    }

    // ===== 读聊天消息（仅聊天界面） =====
}
