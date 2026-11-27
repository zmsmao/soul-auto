package com.soulbot.app.conversation

/**
 * A generation result distinguishes a valid reply from a deliberate no-send decision.
 * A null result remains reserved for transient generation failure or cancellation.
 */
internal sealed interface ChatReplyGenerationResult {
    data class ReadyToSend(val text: String) : ChatReplyGenerationResult
    data class NoReplyRequired(val reason: String) : ChatReplyGenerationResult
}
