package com.soulbot.app.conversation.context

import com.soulbot.app.conversation.ReplyScene
import com.soulbot.app.conversation.ReplyNaturalness
import com.soulbot.app.data.ChatLearningDatabase
import com.soulbot.app.data.ConversationMemoryDatabase

/** All contact-scoped memory reads are keyed by stable thread id, never display name. */
internal object ContactMemoryContextProvider {
    fun fragments(
        learningDatabase: ChatLearningDatabase?,
        memoryDatabase: ConversationMemoryDatabase?,
        threadId: String,
        scene: ReplyScene,
        latestMessage: String,
        includeMemory: Boolean,
    ): List<ContextFragment> {
        if (!includeMemory || threadId.isBlank()) return emptyList()
        val result = mutableListOf<ContextFragment>()
        val memories = learningDatabase?.contactMemories(threadId, 5).orEmpty()
        if (memories.isNotEmpty()) {
            result += ContextFragment(
                id = "contact-facts",
                content = buildString {
                    append("\n\n对方过去提到过这些内容，相关时再自然参考，不相关就忽略，也不要说自己在读取记忆：")
                    memories.forEach { append("\n- ").append(it) }
                },
                priority = 88,
                order = 60,
            )
        }
        val olderMessages = runCatching {
            memoryDatabase?.relevantOlderMessages(threadId, scene, latestMessage, 6)
        }.getOrNull().orEmpty().filterNot { message ->
            message.role == "out" && ReplyNaturalness.isInternalDraft(message.content)
        }
        if (olderMessages.isNotEmpty()) {
            result += ContextFragment(
                id = "contact-older-history",
                content = buildString {
                    append("\n\n下面是与当前话题相关的更早聊天片段。只在确实相关时自然接上，")
                    append("不要复述整段，不要声称自己在读取数据库，也不要把推测当事实：")
                    olderMessages.forEach { message ->
                        append("\n")
                        append(if (message.role == "in") "对方曾说：" else "我曾说：")
                        append(message.content)
                    }
                },
                priority = 62,
                order = 70,
            )
        }
        return result
    }
}
