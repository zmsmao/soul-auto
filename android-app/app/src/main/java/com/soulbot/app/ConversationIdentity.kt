package com.soulbot.app

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import java.security.MessageDigest
import java.util.Locale

internal object SoulAccessibilityIdentity {
    private val userKeys = setOf("uid", "userid", "soulid", "souluserid", "accountid", "imuserid", "targetuserid")
    private val messageKeys = setOf("msgid", "messageid", "chatmessageid", "immessageid", "messagekey")

    fun userId(node: AccessibilityNodeInfo?): String? = explicitIdentifier(node, userKeys)

    fun messageId(node: AccessibilityNodeInfo?): String? = explicitIdentifier(node, messageKeys)

    private fun explicitIdentifier(root: AccessibilityNodeInfo?, acceptedKeys: Set<String>): String? {
        if (root == null) return null
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < 160) {
            val node = stack.removeLast()
            visited++
            val extras = runCatching { node.extras }.getOrNull()
            if (extras != null) explicitValue(extras, acceptedKeys)?.let { return it }
            val description = runCatching { node.contentDescription?.toString().orEmpty() }.getOrDefault("")
            labelledIdentifier(description, acceptedKeys)?.let { return it }
            val childCount = runCatching { node.childCount }.getOrDefault(0)
            for (index in 0 until childCount) {
                runCatching { node.getChild(index) }.getOrNull()?.let(stack::add)
            }
        }
        return null
    }

    private fun explicitValue(bundle: Bundle, acceptedKeys: Set<String>): String? {
        val keys = runCatching { bundle.keySet().toSet() }.getOrDefault(emptySet())
        for (key in keys) {
            val normalizedKey = key.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
            if (normalizedKey !in acceptedKeys) continue
            val value = runCatching { bundle.get(key) }.getOrNull()
            val cleaned = when (value) {
                is String -> cleanIdentifier(value)
                is CharSequence -> cleanIdentifier(value.toString())
                is Number -> value.toString().takeIf { it.length >= 4 }
                else -> null
            }
            if (cleaned != null) return cleaned
        }
        return null
    }

    private fun labelledIdentifier(value: String, acceptedKeys: Set<String>): String? {
        val labels = buildList {
            if ("userid" in acceptedKeys) add("(?:soul\\s*id|user(?:_?\\s*)id|uid|用户ID|账号ID)")
            if ("messageid" in acceptedKeys) add("(?:message(?:_?\\s*)id|msgid|消息ID)")
        }
        if (labels.isEmpty()) return null
        val match = Regex("(?i)(?:" + labels.joinToString("|") + ")\\s*[:：=#]\\s*([a-z0-9_-]{4,80})")
            .find(value) ?: return null
        return cleanIdentifier(match.groupValues[1])
    }

    private fun cleanIdentifier(value: String): String? =
        value.trim().takeIf { it.length in 4..80 && it.none(Char::isWhitespace) }
}

internal object ConversationIdentity {
    fun threadId(nativeUserId: String?, fallbackThreadId: String): String {
        val native = nativeUserId?.trim().orEmpty()
        return if (native.isNotEmpty()) "soul-user:" + digest(native) else fallbackThreadId
    }

    fun replySendKey(
        threadId: String,
        scene: String,
        messageKeys: List<String>,
        body: String,
    ): String? = messageKeys.takeIf { it.isNotEmpty() }
        ?.let { sendKey(threadId, scene, it, body) }

    fun messageKey(
        threadId: String,
        nativeMessageId: String?,
        role: String,
        content: String,
        previousAnchor: String,
        duplicateOrdinal: Int,
    ): String {
        val native = nativeMessageId?.trim().orEmpty()
        val material = if (native.isNotBlank()) {
            "soul-message\u0000" + threadId + "\u0000" + native
        } else {
            listOf("fallback-message", threadId, role, normalize(content), normalize(previousAnchor),
                duplicateOrdinal.coerceAtLeast(0).toString()).joinToString("\u0000")
        }
        return digest(material)
    }

    fun sendKey(threadId: String, scene: String, messageKeys: List<String>, body: String): String =
        digest(listOf("send", threadId, scene,
            messageKeys.joinToString(",").ifBlank { "opening:" + digest(normalize(body)) })
            .joinToString("\u0000"))

    fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun normalize(value: String): String =
        value.trim().lowercase(Locale.ROOT).replace(Regex("""[\s，。！？!?、,.~～…·]+"""), "")
}
