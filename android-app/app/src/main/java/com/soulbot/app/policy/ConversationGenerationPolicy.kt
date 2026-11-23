package com.soulbot.app.policy
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
