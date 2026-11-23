package com.soulbot.app.accessibility
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

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** Reads message content and stable event keys from the currently visible Soul chat. */
internal class SoulMessageReader(
    private val ui: SoulAccessibilityUi,
    private val isChatActive: () -> Boolean,
    private val isExpectedConversation: (String) -> Boolean,
    private val memoryThreadId: (String) -> String,
    private val normalizeMessage: (String) -> String,
    private val resolveVoiceTranscript: (AccessibilityNodeInfo) -> String,
 ) {
    fun messageRole(
        item: AccessibilityNodeInfo,
        avatars: List<AccessibilityNodeInfo>,
        readReceipts: List<AccessibilityNodeInfo>,
    ): String {
        val bounds = ui.rect(item)
        val avatarBounds = avatars.firstOrNull { ui.contains(bounds, ui.rect(it)) }
            ?.let({ ui.rect(it) })
            ?.toUiRegion()
        val hasReadReceipt = readReceipts.any { ui.contains(bounds, ui.rect(it)) }
        val contentBounds = ui.findByIds(SoulBotService.ID_CONTENT_TEXT)
            .firstOrNull { ui.contains(bounds, ui.rect(it)) }
            ?.let({ ui.rect(it) })
            ?.toUiRegion()
        val chatArea = ui.activeWindowRegion()
        return when (
            AdaptiveUiPolicy.messageSide(
                chatArea = chatArea,
                avatarBounds = avatarBounds,
                contentBounds = contentBounds,
                hasReadReceipt = hasReadReceipt,
            )
        ) {
            MessageSide.OUTGOING -> "out"
            MessageSide.INCOMING -> "in"
        }
    }
    fun nonTextMessageFrom(item: AccessibilityNodeInfo): String {
        fun descendantsById(id: String): List<AccessibilityNodeInfo> = runCatching {
            item.findAccessibilityNodeInfosByViewId(id) ?: emptyList()
        }.getOrDefault(emptyList())

        if (descendantsById(SoulBotService.ID_EXPRESSION).isNotEmpty()) return "[表情]"

        val sharedPostText = descendantsById(SoulBotService.ID_SHARED_POST_TEXT)
            .asSequence()
            .map({ ui.textOf(it) })
            .firstOrNull(String::isNotBlank)
        if (sharedPostText != null) return "[分享动态] $sharedPostText"

        // Some Soul versions expose picture/sticker messages only through the
        // descendant's accessibility description rather than a text node.
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(item)
        var visited = 0
        while (stack.isNotEmpty() && visited < 80) {
            val node = stack.removeLast()
            visited++
            val description = runCatching {
                node.contentDescription?.toString()?.trim().orEmpty()
            }.getOrDefault("")
            when {
                description.contains("表情") -> return "[表情]"
                description.contains("图片") || description.contains("照片") -> return "[图片]"
            }
            val childCount = runCatching { node.childCount }.getOrDefault(0)
            for (index in 0 until childCount) {
                runCatching { node.getChild(index) }.getOrNull()?.let(stack::add)
            }
        }
        return ""
    }
    fun readThread(): List<Pair<String, String>> {
        if (!isChatActive()) return emptyList()
        val items = ui.findByIds(SoulBotService.ID_CHAT_ITEM).sortedBy { ui.rect(it).top }
        val avatars = ui.findByIds(SoulBotService.ID_CHAT_AVATAR)
        val texts = ui.findByIds(SoulBotService.ID_CONTENT_TEXT)
        val readReceipts = ui.findByIds(SoulBotService.ID_MESSAGE_READ)
        val result = mutableListOf<Pair<String, String>>()
        for (item in items) {
            val ib = ui.rect(item)
            if (ib.width() <= 0 || ib.height() <= 0) continue
            var text = ""
            for (t in texts) {
                if (ui.contains(ib, ui.rect(t))) { text = ui.textOf(t); break }
            }
            if (text.isEmpty()) text = resolveVoiceTranscript(item)
            if (text.isEmpty()) text = nonTextMessageFrom(item)
            if (text.isEmpty()) continue
            val role = messageRole(item, avatars, readReceipts)
            result.add(role to text)
        }
        return result
    }
    fun currentIncomingMessageKeys(contactName: String): List<String> {
        if (!isChatActive() || !isExpectedConversation(contactName)) return emptyList()
        val threadId = memoryThreadId(contactName)
        val items = ui.findByIds(SoulBotService.ID_CHAT_ITEM).sortedBy { ui.rect(it).top }
        val avatars = ui.findByIds(SoulBotService.ID_CHAT_AVATAR)
        val texts = ui.findByIds(SoulBotService.ID_CONTENT_TEXT)
        val readReceipts = ui.findByIds(SoulBotService.ID_MESSAGE_READ)
        val occurrences = mutableMapOf<String, Int>()
        val keys = mutableListOf<String>()
        var previousAnchor = ""
        for (item in items) {
            val bounds = ui.rect(item)
            if (bounds.width() <= 0 || bounds.height() <= 0) continue
            var content = ""
            for (textNode in texts) {
                if (ui.contains(bounds, ui.rect(textNode))) { content = ui.textOf(textNode); break }
            }
            if (content.isEmpty()) content = resolveVoiceTranscript(item)
            if (content.isEmpty()) content = nonTextMessageFrom(item)
            if (content.isEmpty()) continue
            val role = messageRole(item, avatars, readReceipts)
            val normalized = normalizeMessage(content)
            val occurrenceKey = role + ":" + normalized
            val ordinal = occurrences[occurrenceKey] ?: 0
            occurrences[occurrenceKey] = ordinal + 1
            if (role == "in") {
                keys += ConversationIdentity.messageKey(
                    threadId = threadId,
                    nativeMessageId = SoulAccessibilityIdentity.messageId(item),
                    role = role,
                    content = content,
                    previousAnchor = previousAnchor,
                    duplicateOrdinal = ordinal,
                )
            }
            previousAnchor = occurrenceKey
        }
        return keys.takeLast(8)
    }

    private fun Rect.toUiRegion(): UiRegion = UiRegion(left, top, right, bottom)
}