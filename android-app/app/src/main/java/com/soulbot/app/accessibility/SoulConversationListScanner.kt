package com.soulbot.app.accessibility

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.soulbot.app.logging.RuntimeLog
import com.soulbot.app.logging.DiagnosticIdentity
import com.soulbot.app.conversation.*

internal interface SoulConversationListHost {
    val context: Context
    fun isOnList(): Boolean
    fun scrollToTop()
    fun scrollPageDown(): Boolean
    fun findByIds(id: String): List<AccessibilityNodeInfo>
    fun isVisibleOnScreen(node: AccessibilityNodeInfo): Boolean
    fun rect(node: AccessibilityNodeInfo): Rect
    fun contains(outer: Rect, inner: Rect): Boolean
    fun textOf(node: AccessibilityNodeInfo?): String
}

/** Reads visible inbox rows and previews; unread ranking remains in policy. */
internal class SoulConversationListScanner(private val host: SoulConversationListHost) {
    fun snapshot(): String =
        host.findByIds(SoulAccessibilityIds.ID_CONV_NAME)
            .filter(host::isVisibleOnScreen)
            .map { node -> host.rect(node).top to host.textOf(node) }
            .sortedBy { it.first }
            .joinToString("|") { (top, name) -> "$top:$name" }

    /** Scan every visible viewport; names are deduplicated while the list is paged. */
    fun scanUnread(): List<UnreadConversationCandidate> {
        if (!host.isOnList()) return emptyList()
        val scannedPages = mutableListOf<List<UnreadConversationCandidate>>()
        var pages = 0
        var visibleRows = 0
        var visibleBadges = 0
        host.scrollToTop()
        try {
            while (host.isOnList() && pages < MAX_INBOX_PAGES) {
                val page = scanVisibleUnread()
                visibleRows += page.first
                visibleBadges += page.second
                scannedPages += page.third
                pages += 1
                if (!host.scrollPageDown()) break
            }
        } finally {
            if (host.isOnList()) host.scrollToTop()
        }
        val merged = UnreadConversationPolicy.mergeVisiblePages(scannedPages)
        val ordered = UnreadConversationPolicy.oldestFirst(merged)
        RuntimeLog.record(
            host.context,
            "unread scan completed; pages=$pages; visibleRows=$visibleRows; visibleBadges=$visibleBadges; candidates=${ordered.size}; selectedContact=${ordered.firstOrNull()?.let { DiagnosticIdentity.contactId(host.context, it.name) } ?: "none"}",
        )
        return ordered
    }

    private fun scanVisibleUnread(): Triple<Int, Int, List<UnreadConversationCandidate>> {
        val items = host.findByIds(SoulAccessibilityIds.ID_CONV_ITEM)
            .filter(host::isVisibleOnScreen)
            .sortedBy { host.rect(it).top }
        val names = host.findByIds(SoulAccessibilityIds.ID_CONV_NAME).filter(host::isVisibleOnScreen)
        val times = host.findByIds(SoulAccessibilityIds.ID_CONV_TIME).filter(host::isVisibleOnScreen)
        // Only visible badges count: Soul keeps recycled/hidden unread badges in the tree.
        val badges = host.findByIds(SoulAccessibilityIds.ID_UNREAD_BADGE).filter(host::isVisibleOnScreen)
        val result = mutableListOf<UnreadConversationCandidate>()
        for ((displayOrder, item) in items.withIndex()) {
            val bounds = host.rect(item)
            if (badges.none { host.contains(bounds, host.rect(it)) }) continue
            val name = names.firstOrNull { host.contains(bounds, host.rect(it)) }
                ?.let(host::textOf).orEmpty()
            val time = times.firstOrNull { host.contains(bounds, host.rect(it)) }
                ?.let(host::textOf).orEmpty()
            if (name.isNotBlank()) {
                if (ConversationRowPolicy.isPlatformNotice(name)) {
                    RuntimeLog.record(
                        host.context,
                        "unread row excluded; reason=platform_notice; displayName=$name",
                    )
                    continue
                }
                result += UnreadConversationCandidate(
                    name = name,
                    timeText = time,
                    displayOrder = displayOrder,
                    previewText = conversationRowPreview(item, name, time),
                )
            }
        }
        return Triple(items.size, badges.size, result)
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
                if (contactName.isNotBlank() && clean.contains(contactName)) continue
                if (timeText.isNotBlank() && clean.contains(timeText)) continue
                if (Regex("^\\d+$").matches(clean)) continue
                if (clean.contains("未读") || clean.contains("条消息")) continue
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
        if (!host.isOnList() || name.isBlank()) return null
        host.scrollToTop()
        for (page in 0 until MAX_INBOX_PAGES) {
            val items = host.findByIds(SoulAccessibilityIds.ID_CONV_ITEM)
                .filter(host::isVisibleOnScreen)
            val names = host.findByIds(SoulAccessibilityIds.ID_CONV_NAME)
                .filter(host::isVisibleOnScreen)
            for (item in items) {
                val bounds = host.rect(item)
                val match = names.firstOrNull {
                    host.contains(bounds, host.rect(it)) && host.textOf(it) == name
                }
                if (match != null) return item
            }
            if (!host.scrollPageDown()) break
        }
        host.scrollToTop()
        return null
    }

    companion object {
        private const val MAX_INBOX_PAGES = 60
    }

    // ===== LLM =====
}
