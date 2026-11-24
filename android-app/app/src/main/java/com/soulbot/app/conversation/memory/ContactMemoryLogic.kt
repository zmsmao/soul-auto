package com.soulbot.app.conversation.memory

import com.soulbot.app.data.ContactMemoryCategory
import com.soulbot.app.data.ContactMemoryDraft
import com.soulbot.app.data.ContactMemoryDraftEvidence
import org.json.JSONArray
import org.json.JSONObject

object ContactMemoryLogic {
    fun parseDrafts(raw: String): List<ContactMemoryDraft> {
        val jsonText = extractJsonObject(raw) ?: return emptyList()
        val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return emptyList()
        val memories = root.optJSONArray("memories") ?: return emptyList()
        return buildList {
            for (index in 0 until memories.length().coerceAtMost(12)) {
                val item = memories.optJSONObject(index) ?: continue
                val category = ContactMemoryCategory.from(item.optString("category")) ?: continue
                val key = normalizeKey(item.optString("key"))
                val content = item.optString("content").trim().take(180)
                if (key.isBlank() || content.length !in 3..180) continue
                val evidenceArray = item.optJSONArray("evidence") ?: JSONArray()
                val evidence = buildList {
                    for (evidenceIndex in 0 until evidenceArray.length().coerceAtMost(6)) {
                        val source = evidenceArray.optJSONObject(evidenceIndex) ?: continue
                        val eventKey = source.optString("event_key").trim().take(160)
                        val quote = source.optString("quote").trim().take(500)
                        if (eventKey.isNotBlank() && quote.isNotBlank()) add(ContactMemoryDraftEvidence(eventKey, quote))
                    }
                }.distinctBy { it.eventKey to it.quote }
                if (evidence.isEmpty()) continue
                val certainty = item.optString("certainty").uppercase()
                val explicit = certainty == "EXPLICIT"
                val suggestedDays = item.optInt("valid_days", 0).coerceIn(0, 180)
                val validDays = when {
                    category == ContactMemoryCategory.STATE && suggestedDays == 0 -> 30
                    category == ContactMemoryCategory.STATE -> suggestedDays
                    else -> 0
                }
                add(ContactMemoryDraft(category, key, content, explicit, validDays, evidence))
            }
        }.distinctBy { Triple(it.category, it.memoryKey, normalizeContent(it.content)) }
    }

    fun hasValidEnvelope(raw: String): Boolean {
        val jsonText = extractJsonObject(raw) ?: return false
        val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return false
        return root.optJSONArray("memories") != null
    }

    fun validateEvidence(
        drafts: List<ContactMemoryDraft>,
        sourceByKey: Map<String, SourceEvidence>,
    ): List<ValidatedDraft> = buildList {
        drafts.forEach { draft ->
            val valid = draft.evidence.mapNotNull { evidence ->
                val source = sourceByKey[evidence.eventKey] ?: return@mapNotNull null
                if (source.role != "in" || evidence.quote.length < 3 ||
                    !source.content.contains(evidence.quote)
                ) return@mapNotNull null
                ValidatedEvidence(source, evidence.quote)
            }.distinctBy { it.source.eventKey to it.quote }
            if (valid.isNotEmpty()) add(ValidatedDraft(draft, valid))
        }
    }

    fun normalizeKey(value: String): String =
        value.trim().lowercase()
            .replace(Regex("[^\\p{L}\\p{N}_-]+"), "_")
            .trim('_', '-')
            .take(80)

    fun normalizeContent(value: String): String =
        value.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")

    private fun extractJsonObject(raw: String): String? {
        val clean = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = clean.indexOf('{')
        val end = clean.lastIndexOf('}')
        return if (start >= 0 && end > start) clean.substring(start, end + 1) else null
    }

    data class SourceEvidence(
        val eventKey: String,
        val role: String,
        val content: String,
        val scene: String,
        val observedAt: Long,
    )

    data class ValidatedEvidence(val source: SourceEvidence, val quote: String)
    data class ValidatedDraft(val draft: ContactMemoryDraft, val evidence: List<ValidatedEvidence>)
}
