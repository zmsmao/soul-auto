package com.soulbot.app.conversation.memory

import com.soulbot.app.data.ContactMemoryCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactMemoryLogicTest {
    @Test
    fun parsesOnlySupportedCategoriesAndKeepsExplicitEvidence() {
        val raw = """{"memories":[
            {"category":"PREFERENCE","key":"outdoor hobbies","content":"喜欢爬山","certainty":"EXPLICIT","evidence":[{"event_key":"m-1","quote":"我平时比较喜欢爬山"}]},
            {"category":"PERSONALITY","key":"personality","content":"性格外向","certainty":"EXPLICIT","evidence":[{"event_key":"m-1","quote":"我平时比较喜欢爬山"}]}
        ]}"""

        val drafts = ContactMemoryLogic.parseDrafts(raw)

        assertEquals(1, drafts.size)
        assertEquals(ContactMemoryCategory.PREFERENCE, drafts.single().category)
        assertEquals("outdoor_hobbies", drafts.single().memoryKey)
        assertTrue(drafts.single().explicit)
    }

    @Test
    fun temporaryStateGetsBoundedDefaultExpiry() {
        val raw = """{"memories":[{"category":"STATE","key":"busy","content":"最近工作很忙","certainty":"EXPLICIT","evidence":[{"event_key":"m-2","quote":"我最近工作很忙"}]}]}"""

        val draft = ContactMemoryLogic.parseDrafts(raw).single()

        assertEquals(30, draft.validDays)
    }

    @Test
    fun sourceQuoteMustExactlyExistInAnIncomingMessage() {
        val raw = """{"memories":[{"category":"FACT","key":"city","content":"目前在广州工作","certainty":"EXPLICIT","evidence":[
            {"event_key":"in-1","quote":"我现在在广州工作"},
            {"event_key":"in-2","quote":"我现在在深圳工作"},
            {"event_key":"out-1","quote":"我现在在广州工作"}
        ]}]}"""
        val drafts = ContactMemoryLogic.parseDrafts(raw)
        val sourceMap = mapOf(
            "in-1" to ContactMemoryLogic.SourceEvidence("in-1", "in", "我现在在广州工作", "CHAT", 10L),
            "in-2" to ContactMemoryLogic.SourceEvidence("in-2", "in", "我现在在深圳上班", "CHAT", 11L),
            "out-1" to ContactMemoryLogic.SourceEvidence("out-1", "out", "我现在在广州工作", "CHAT", 12L),
        )

        val validated = ContactMemoryLogic.validateEvidence(drafts, sourceMap)

        assertEquals(1, validated.size)
        assertEquals(listOf("in-1"), validated.single().evidence.map { it.source.eventKey })
        assertEquals("我现在在广州工作", validated.single().evidence.single().quote)
    }

    @Test
    fun uncertainMemoryCanBeParsedButIsNotMarkedExplicit() {
        val raw = """{"memories":[{"category":"EXPERIENCE","key":"travel","content":"可能去过云南","certainty":"UNCERTAIN","evidence":[{"event_key":"m-3","quote":"云南那边景色不错"}]}]}"""

        val draft = ContactMemoryLogic.parseDrafts(raw).single()

        assertFalse(draft.explicit)
    }

    @Test
    fun rejectsMalformedOrMissingEnvelope() {
        assertTrue(ContactMemoryLogic.parseDrafts("not json").isEmpty())
        assertFalse(ContactMemoryLogic.hasValidEnvelope("""{"facts":[]}"""))
        assertTrue(ContactMemoryLogic.hasValidEnvelope("""{"memories":[]}"""))
    }

    @Test
    fun normalizesKeysWithoutChangingChineseContent() {
        assertEquals("current_city", ContactMemoryLogic.normalizeKey(" current city "))
        assertEquals("喜欢爬山", ContactMemoryLogic.normalizeContent("喜欢爬山！"))
    }
}
