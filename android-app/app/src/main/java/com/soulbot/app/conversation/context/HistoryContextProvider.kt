package com.soulbot.app.conversation.context

import com.soulbot.app.conversation.ReplyNaturalness
import com.soulbot.app.model.ReplyGenerationConfig

/** Selects the same recent transcript window used by the previous generation path. */
internal object HistoryContextProvider {
    fun recent(thread: List<Pair<String, String>>): List<Pair<String, String>> =
        thread.takeLast(ReplyGenerationConfig.CONTEXT_LEN).filterNot { (role, text) ->
            role == "out" && ReplyNaturalness.isInternalDraft(text)
        }
}
