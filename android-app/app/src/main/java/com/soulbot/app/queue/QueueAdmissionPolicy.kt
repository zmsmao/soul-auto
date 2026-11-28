package com.soulbot.app.queue

import com.soulbot.app.conversation.ConversationIncomingEvent
import com.soulbot.app.conversation.ConversationRowPolicy

internal enum class QueueAdmissionRejection {
    PLATFORM_NOTICE,
    EMPTY_EVENT_KEY,
    EMPTY_CONTENT,
    DUPLICATE_EVENT_KEY,
    SYSTEM_GENERATED_CONTENT,
}

internal data class QueueAdmissionResult(
    val accepted: List<ConversationIncomingEvent>,
    val rejected: Map<QueueAdmissionRejection, Int>,
) {
    val isEmpty: Boolean get() = accepted.isEmpty()
}

/**
 * Admission filter: the durable queue stores only the trailing unanswered inbound
 * run. Earlier transcript messages remain available to the reply context builder,
 * but are deliberately not copied into the delivery ledger.
 */
internal object QueueAdmissionPolicy {
    private val systemMessages = setOf(
        "系统消息",
        "官方通知",
        "平台通知",
        "通知中心",
        "你撤回了一条消息",
        "对方撤回了一条消息",
        "你们已成为好友",
        "你们已经成为好友",
        "你们已成为好友，开始聊天吧",
        "你们已经成为好友，开始聊天吧",
        "你们已经成为好友，可以开始聊天了",
        "我通过了你的好友申请，现在可以开始聊天了",
        "你通过了对方的好友申请",
    )

    fun filter(
        displayName: String,
        incoming: List<ConversationIncomingEvent>,
        pendingCount: Int,
    ): QueueAdmissionResult {
        val rejected = mutableMapOf<QueueAdmissionRejection, Int>()
        fun reject(reason: QueueAdmissionRejection) {
            rejected[reason] = (rejected[reason] ?: 0) + 1
        }

        if (ConversationRowPolicy.isPlatformNotice(displayName)) {
            incoming.forEach { reject(QueueAdmissionRejection.PLATFORM_NOTICE) }
            return QueueAdmissionResult(emptyList(), rejected)
        }
        if (pendingCount <= 0 || incoming.isEmpty()) {
            return QueueAdmissionResult(emptyList(), emptyMap())
        }

        val pending = incoming.takeLast(pendingCount.coerceAtMost(incoming.size))
        val seenKeys = HashSet<String>()
        val accepted = ArrayList<ConversationIncomingEvent>(pending.size)
        pending.forEach { event ->
            when {
                event.key.isBlank() -> reject(QueueAdmissionRejection.EMPTY_EVENT_KEY)
                event.text.isBlank() -> reject(QueueAdmissionRejection.EMPTY_CONTENT)
                !seenKeys.add(event.key) -> reject(QueueAdmissionRejection.DUPLICATE_EVENT_KEY)
                isSystemGeneratedContent(event.text) ->
                    reject(QueueAdmissionRejection.SYSTEM_GENERATED_CONTENT)
                else -> accepted += event.copy(text = event.text.trim())
            }
        }
        return QueueAdmissionResult(accepted, rejected)
    }

    fun isSystemGeneratedContent(raw: String): Boolean {
        val text = normalize(raw)
        if (text.isBlank()) return true
        if (systemMessages.any { normalize(it) == text }) return true
        return listOf(
            Regex("^(?:系统消息|官方通知|平台通知|通知中心)[:：].*$"),
            Regex("^(?:你|对方)撤回了一条消息$"),
        ).any { it.matches(text) }
    }

    private fun normalize(value: String): String = value.trim()
        .lowercase()
        .replace(Regex("[\\s，。！？!?、,.~～·_\\-]+"), "")
}
