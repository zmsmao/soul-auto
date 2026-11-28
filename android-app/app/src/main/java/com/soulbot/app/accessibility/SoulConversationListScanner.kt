package com.soulbot.app.accessibility

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.soulbot.app.conversation.ConversationRowPolicy
import com.soulbot.app.conversation.ConversationIdentity
import com.soulbot.app.conversation.SoulAccessibilityIdentity
import com.soulbot.app.conversation.ConversationListEntry
import com.soulbot.app.conversation.UnreadConversationCandidate
import com.soulbot.app.logging.DiagnosticIdentity
import com.soulbot.app.logging.RuntimeLog

internal interface SoulConversationListHost {
    val context: Context
    fun isOnList(): Boolean
    fun findByIds(id: String): List<AccessibilityNodeInfo>
    fun isVisibleOnScreen(node: AccessibilityNodeInfo): Boolean
    fun rect(node: AccessibilityNodeInfo): Rect
    fun contains(outer: Rect, inner: Rect): Boolean
    fun textOf(node: AccessibilityNodeInfo?): String
}

/** Reads visible conversation identity and unread state only. */
internal class SoulConversationListScanner(private val host: SoulConversationListHost) {
    fun snapshot(): String =
        host.findByIds(SoulAccessibilityIds.ID_CONV_NAME)
            .filter(host::isVisibleOnScreen)
            .map { node -> host.rect(node).top to host.textOf(node) }
            .sortedBy { it.first }
            .joinToString("|") { (top, name) -> "$top:$name" }

    /** Returns every named row on the current viewport so completed contacts can release their unread guard. */
    fun scanCurrentPage(): List<ConversationListEntry> {
        if (!host.isOnList()) return emptyList()
        val items = host.findByIds(SoulAccessibilityIds.ID_CONV_ITEM)
            .filter(host::isVisibleOnScreen)
            .sortedBy { host.rect(it).top }
        val names = host.findByIds(SoulAccessibilityIds.ID_CONV_NAME)
            .filter(host::isVisibleOnScreen)
        val badges = host.findByIds(SoulAccessibilityIds.ID_UNREAD_BADGE)
            .filter(host::isVisibleOnScreen)
        val rows = mutableListOf<ConversationListEntry>()

        for ((displayOrder, item) in items.withIndex()) {
            val bounds = host.rect(item)
            val name = names.firstOrNull { host.contains(bounds, host.rect(it)) }
                ?.let(host::textOf)
                .orEmpty()
                .trim()
            if (name.isBlank()) continue
            if (ConversationRowPolicy.isPlatformNotice(name)) {
                RuntimeLog.record(
                    host.context,
                    "private contact row excluded; reason=platform_notice; displayName=$name",
                )
                continue
            }
            rows += ConversationListEntry(
                name = name,
                contactKey = ConversationIdentity.threadId(
                    SoulAccessibilityIdentity.userId(item),
                    DiagnosticIdentity.contactId(host.context, name),
                ),
                displayOrder = displayOrder,
                hasUnread = badges.any { host.contains(bounds, host.rect(it)) },
            )
        }

        RuntimeLog.record(
            host.context,
            "private contact page scanned; rows=" + rows.size + "; unread=" + rows.count { it.hasUnread },
        )
        return rows
    }

    /** Compatibility adapter for callers that still need unread-only candidates. */
    fun scanUnread(): List<UnreadConversationCandidate> =
        scanCurrentPage().filter { it.hasUnread }.map {
            UnreadConversationCandidate(
                name = it.name,
                timeText = "",
                displayOrder = it.displayOrder,
                previewText = "",
                contactKey = it.contactKey,
            )
        }

    /** Locate only on the current viewport. Pagination is owned by the FIFO scheduler. */
    fun findConversation(name: String, contactKey: String): AccessibilityNodeInfo? {
        if (!host.isOnList() || name.isBlank()) return null
        val items = host.findByIds(SoulAccessibilityIds.ID_CONV_ITEM)
            .filter(host::isVisibleOnScreen)
            .sortedBy { host.rect(it).top }
        val names = host.findByIds(SoulAccessibilityIds.ID_CONV_NAME)
            .filter(host::isVisibleOnScreen)
        for (item in items) {
            val bounds = host.rect(item)
            val match = names.firstOrNull {
                host.contains(bounds, host.rect(it)) &&
                    host.textOf(it).trim() == name &&
                    ConversationIdentity.threadId(
                        SoulAccessibilityIdentity.userId(item),
                        DiagnosticIdentity.contactId(host.context, name),
                    ) == contactKey
            }
            if (match != null) return item
        }
        return null
    }
}
