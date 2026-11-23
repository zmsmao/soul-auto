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

enum class OpeningConversationAction { OPEN, REPLY, SKIP }

/** Matching is not a message: never restart an already answered conversation. */
object OpeningConversationRoute {
    fun pendingIncoming(thread: List<Pair<String, String>>): List<String> =
        thread.takeLastWhile { it.first == "in" }.map { it.second }.filter(String::isNotBlank)

    fun action(thread: List<Pair<String, String>>, hasStoredHistory: Boolean): OpeningConversationAction = when {
        pendingIncoming(thread).isNotEmpty() -> OpeningConversationAction.REPLY
        thread.isNotEmpty() || hasStoredHistory -> OpeningConversationAction.SKIP
        else -> OpeningConversationAction.OPEN
    }
}
