package com.soulbot.app.conversation

enum class ReplyScene { CHAT, SOUL_MATCH, QIYU, SQUARE }

data class OpeningContext(
    val scene: ReplyScene,
    val contactName: String = "",
    val content: String = "",
    val zodiac: String = "",
    val matchReason: String = "",
)

/** Only an anonymized form of our own sent opening is retained across contacts. */
data class OpeningFingerprint(val normalized: String, val tactic: String)
