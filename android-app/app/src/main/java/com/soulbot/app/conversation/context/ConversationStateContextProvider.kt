package com.soulbot.app.conversation.context

import com.soulbot.app.conversation.ReplyScene
import com.soulbot.app.conversation.ConversationStatePolicy
import com.soulbot.app.data.ConversationMemoryDatabase

internal object ConversationStateContextProvider {
    fun fragments(
        memoryDatabase: ConversationMemoryDatabase?,
        threadId: String,
        scene: ReplyScene,
    ): List<ContextFragment> {
        val state = memoryDatabase?.loadConversationState(threadId, scene) ?: return emptyList()
        val promptHint = ConversationStatePolicy.promptHint(state)
        if (promptHint.isBlank()) return emptyList()
        return listOf(
            ContextFragment(
                id = "conversation-state",
                content = promptHint,
                priority = 82,
                order = 900,
            ),
        )
    }
}
