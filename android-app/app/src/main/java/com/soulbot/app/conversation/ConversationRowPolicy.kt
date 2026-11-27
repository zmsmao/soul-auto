package com.soulbot.app.conversation

/** Conservative classification for explicit platform/system notification rows in Soul's inbox. */
internal object ConversationRowPolicy {
    private val exactSystemNames = setOf(
        "官方通知",
        "系统通知",
        "通知中心",
        "平台通知",
        "soul官方通知",
        "soulofficialnotice",
        "soul系统通知",
        "soulsystemnotice",
        "soul通知",
        "soul助手",
        "soul官方",
    )

    private val explicitSuffixes = listOf(
        "官方通知",
        "系统通知",
        "通知中心",
        "平台通知",
    )

    fun isPlatformNotice(displayName: String): Boolean {
        val normalized = normalize(displayName)
        if (normalized.isBlank()) return false
        if (normalized in exactSystemNames) return true
        return explicitSuffixes.any(normalized::endsWith)
    }

    private fun normalize(value: String): String =
        value.trim()
            .lowercase()
            .replace(Regex("[\\s，。！？!?、,.~～·_\\-]+"), "")
}
