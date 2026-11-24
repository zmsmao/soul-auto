package com.soulbot.app.policy

/** Pure stale-context policy shared by reply and opening generation watchdogs. */
internal object ConversationGenerationPolicy {
    fun shouldCancel(
        baselineMessageKeys: String,
        currentMessageKeys: String,
        pendingVoice: Boolean,
        expectedConversation: Boolean,
    ): Boolean =
        !expectedConversation || pendingVoice || baselineMessageKeys != currentMessageKeys
}
