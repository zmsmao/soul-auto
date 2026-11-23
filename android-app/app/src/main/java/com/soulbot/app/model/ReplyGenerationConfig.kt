package com.soulbot.app.model

/** Context window limit used by reply generation. */
internal object ReplyGenerationConfig {
    const val CONTEXT_LEN = 20

    // Provider-neutral context budget estimate; output space is reserved separately.
    const val MAX_INPUT_TOKENS = 10_000
    const val RESERVED_OUTPUT_TOKENS = 1_200
    const val REQUIRED_RECENT_MESSAGES = 6
}
