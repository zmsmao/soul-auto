package com.soulbot.app.data

enum class ContactMemoryCategory(val label: String) {
    FACT("基本事实"),
    PREFERENCE("兴趣偏好"),
    STATE("近期状态"),
    EXPERIENCE("重要经历");

    companion object {
        fun from(value: String): ContactMemoryCategory? =
            entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
    }
}

enum class ContactMemoryStatus(val label: String) {
    ACTIVE("有效"),
    PENDING("待确认"),
    CONFLICTED("存在冲突"),
    SUPERSEDED("已被更新"),
    REJECTED("已排除"),
    EXPIRED("已过期");

    companion object {
        fun from(value: String): ContactMemoryStatus =
            entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) } ?: PENDING
    }
}

data class ContactMemoryFact(
    val id: Long,
    val threadId: String,
    val category: ContactMemoryCategory,
    val memoryKey: String,
    val content: String,
    val status: ContactMemoryStatus,
    val evidenceLevel: String,
    val firstSeenAt: Long,
    val lastConfirmedAt: Long,
    val expiresAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val evidenceCount: Int,
)

data class ContactMemoryEvidence(
    val id: Long,
    val memoryId: Long,
    val sourceEventKey: String,
    val sourceQuote: String,
    val scene: String,
    val role: String,
    val observedAt: Long,
    val evidenceType: String,
)

data class ContactMemorySource(
    val eventKey: String,
    val threadId: String,
    val scene: String,
    val role: String,
    val content: String,
    val observedAt: Long,
    val attemptCount: Int,
)

data class ContactMemoryDraftEvidence(
    val eventKey: String,
    val quote: String,
)

data class ContactMemoryDraft(
    val category: ContactMemoryCategory,
    val memoryKey: String,
    val content: String,
    val explicit: Boolean,
    val validDays: Int,
    val evidence: List<ContactMemoryDraftEvidence>,
)

data class ContactMemoryStats(
    val active: Int,
    val pending: Int,
    val conflicted: Int,
    val other: Int,
) {
    val total: Int get() = active + pending + conflicted + other
}
