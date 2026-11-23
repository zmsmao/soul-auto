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

/** Rules for consuming Soul's "new greeting" list without reopening stale rows forever. */
internal object GreetingSessionPolicy {
    const val EMPTY_LIST_RECHECK_MS = 120_000L
    const val SYSTEM_GREETING_CONTEXT = "系统代发的新招呼，聊天页暂无对方真实消息"

    fun userKey(name: String): String = comparableText(name).take(120)

    fun pendingUsers(names: List<String>, handledKeys: Set<String>): List<String> =
        names.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinctBy(::userKey)
            .filter { userKey(it) !in handledKeys }
            .toList()
}
