package com.soulbot.app.accessibility

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.soulbot.app.*
import com.soulbot.app.app.SoulBotService
import com.soulbot.app.diagnostics.*
import com.soulbot.app.policy.*

internal interface SoulConversationListHost {
    val context: Context
    fun isOnList(): Boolean
    fun findByIds(id: String): List<AccessibilityNodeInfo>
    fun isVisibleOnScreen(node: AccessibilityNodeInfo): Boolean
    fun rect(node: AccessibilityNodeInfo): Rect
    fun contains(outer: Rect, inner: Rect): Boolean
    fun textOf(node: AccessibilityNodeInfo?): String
}

/** Reads conversation-list rows and unread previews without owning inbox policy. */
internal class SoulConversationListScanner(private val host: SoulConversationListHost) {
    fun snapshot(): String =
        host.findByIds(SoulBotService.ID_CONV_NAME)
            .map { node -> host.rect(node).top to host.textOf(node) }
            .sortedBy { it.first }
            .joinToString("|") { (top, name) -> "$top:$name" }

    fun scanUnread(): List<UnreadConversationCandidate> {
        if (!host.isOnList()) return emptyList()
        val items = host.findByIds(SoulBotService.ID_CONV_ITEM)
            .filter(host::isVisibleOnScreen)
            .sortedBy { host.rect(it).top }
        val names = host.findByIds(SoulBotService.ID_CONV_NAME).filter(host::isVisibleOnScreen)
        val times = host.findByIds(SoulBotService.ID_CONV_TIME).filter(host::isVisibleOnScreen)
        // Soul keeps recycled/hidden unread badges in the accessibility tree. Using
        // them made an already-read row reopen forever and wait for content that
        // could never be "unread" again.
        val badges = host.findByIds(SoulBotService.ID_UNREAD_BADGE).filter(host::isVisibleOnScreen)
        val result = mutableListOf<UnreadConversationCandidate>()
        for ((displayOrder, item) in items.withIndex()) {
            val ib = host.rect(item)
            val hasBadge = badges.any { host.contains(ib, host.rect(it)) }
            if (!hasBadge) continue
            var name = ""
            for (n in names) { if (host.contains(ib, host.rect(n))) { name = host.textOf(n); break } }
            var time = ""
            for (tm in times) { if (host.contains(ib, host.rect(tm))) { time = host.textOf(tm); break } }
            if (name.isNotBlank()) {
                result += UnreadConversationCandidate(
                    name = name,
                    timeText = time,
                    displayOrder = displayOrder,
                    previewText = conversationRowPreview(item, name, time),
                )
            }
        }
        val ordered = UnreadConversationPolicy.oldestFirst(result)
        RuntimeLog.record(
            host.context,
            "unread scan completed; visibleRows=${items.size}; visibleBadges=${badges.size}; candidates=${ordered.size}; selectedContact=${ordered.firstOrNull()?.let { DiagnosticIdentity.contactId(host.context, it.name) } ?: "none"}",
        )
        return ordered
    }

    private fun conversationRowPreview(
        row: AccessibilityNodeInfo,
        contactName: String,
        timeText: String,
    ): String {
        val candidates = mutableListOf<String>()
        var visited = 0

        fun collect(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 5 || visited >= 80) return
            visited++
            val values = listOf(
                node.text?.toString().orEmpty(),
                node.contentDescription?.toString().orEmpty(),
            )
            for (value in values) {
                val clean = value.trim().replace(Regex("\\s+"), " ")
                if (clean.length !in 2..160) continue
                if (clean == contactName || clean == timeText) continue
                if (contactName.isNotBlank() && clean.host.contains(contactName)) continue
                if (timeText.isNotBlank() && clean.host.contains(timeText)) continue
                if (Regex("^\\d+$").matches(clean)) continue
                if (clean.host.contains("未读") || clean.host.contains("条消息")) continue
                candidates += clean
            }
            for (index in 0 until minOf(node.childCount, 24)) {
                val child = node.getChild(index) ?: continue
                collect(child, depth + 1)
                if (visited >= 80) break
            }
        }

        collect(row, 0)
        val distinct = candidates.distinct()
        return distinct.maxByOrNull(UnreadConversationPolicy::priorityScore)
            ?.takeIf { UnreadConversationPolicy.priorityScore(it) > 0 }
            ?: distinct.firstOrNull()
            ?: ""
    }

    fun findConversation(name: String): AccessibilityNodeInfo? {
        if (!host.isOnList()) return null
        val items = host.findByIds(SoulBotService.ID_CONV_ITEM).filter(host::isVisibleOnScreen)
        val names = host.findByIds(SoulBotService.ID_CONV_NAME).filter(host::isVisibleOnScreen)
        for (item in items) {
            val ib = host.rect(item)
            for (n in names) {
                if (host.contains(ib, host.rect(n)) && host.textOf(n) == name) return item
            }
        }
        return null
    }

    // ===== LLM =====
}
