package com.soulbot.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.soulbot.app.conversation.ConversationMemoryEntry
import java.io.Reader
import java.io.Writer
import org.json.JSONArray
import org.json.JSONObject
import com.soulbot.app.conversation.memory.ContactMemoryLogic

/**
 * Independent, evidence-backed contact memory store.
 *
 * It deliberately lives outside chat_learning.db: older APKs can keep using
 * the legacy store, while this database can be discarded or rolled back alone.
 */
data class ContactMemoryImportResult(val added: Int, val skipped: Int, val invalid: Int)

class ContactMemoryDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "contact_memory.db", null, 1) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE contact_memory_facts (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "thread_id TEXT NOT NULL, category TEXT NOT NULL, memory_key TEXT NOT NULL, " +
                "content TEXT NOT NULL, status TEXT NOT NULL, evidence_level TEXT NOT NULL, " +
                "first_seen_at INTEGER NOT NULL, last_confirmed_at INTEGER NOT NULL, " +
                "expires_at INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE INDEX idx_memory_fact_scope_status " +
                "ON contact_memory_facts(thread_id, status, updated_at DESC)",
        )
        db.execSQL(
            "CREATE INDEX idx_memory_fact_key " +
                "ON contact_memory_facts(thread_id, category, memory_key, status)",
        )
        db.execSQL(
            "CREATE TABLE contact_memory_evidence (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, memory_id INTEGER NOT NULL, " +
                "source_event_key TEXT NOT NULL, source_quote TEXT NOT NULL, " +
                "scene TEXT NOT NULL, role TEXT NOT NULL, observed_at INTEGER NOT NULL, " +
                "evidence_type TEXT NOT NULL, " +
                "FOREIGN KEY(memory_id) REFERENCES contact_memory_facts(id) ON DELETE CASCADE, " +
                "UNIQUE(memory_id, source_event_key, source_quote))",
        )
        db.execSQL("CREATE INDEX idx_memory_evidence_fact ON contact_memory_evidence(memory_id, observed_at)")
        db.execSQL(
            "CREATE TABLE contact_memory_sources (" +
                "event_key TEXT PRIMARY KEY, thread_id TEXT NOT NULL, scene TEXT NOT NULL, " +
                "role TEXT NOT NULL, content TEXT NOT NULL, observed_at INTEGER NOT NULL, " +
                "status TEXT NOT NULL DEFAULT 'PENDING', attempt_count INTEGER NOT NULL DEFAULT 0, " +
                "last_error TEXT NOT NULL DEFAULT '', updated_at INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE INDEX idx_memory_source_queue " +
                "ON contact_memory_sources(status, thread_id, scene, observed_at)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun enqueueSources(threadId: String, scene: String, entries: List<ConversationMemoryEntry>): Int {
        val cleanThread = threadId.trim().take(140)
        if (cleanThread.isBlank() || cleanThread == "LEGACY_UNKNOWN") return 0
        val db = writableDatabase
        var added = 0
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            entries.asSequence()
                .filter { it.threadId == cleanThread && it.scene == scene && it.role == "in" }
                .distinctBy { it.eventKey }
                .forEach { entry ->
                    val values = ContentValues().apply {
                        put("event_key", entry.eventKey)
                        put("thread_id", cleanThread)
                        put("scene", scene)
                        put("role", "in")
                        put("content", entry.content.take(2000))
                        put("observed_at", entry.observedAt)
                        put("status", "PENDING")
                        put("attempt_count", 0)
                        put("last_error", "")
                        put("updated_at", now)
                    }
                    if (db.insertWithOnConflict(
                            "contact_memory_sources", null, values, SQLiteDatabase.CONFLICT_IGNORE,
                        ) != -1L
                    ) added++
                }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        val retainedBefore = now - 60L * 24L * 60L * 60L * 1000L
        db.delete(
            "contact_memory_sources",
            "status IN ('DONE','FAILED') AND updated_at<?",
            arrayOf(retainedBefore.toString()),
        )
        return added
    }

    /** Re-queues work that was interrupted by process death. Call once per process. */
    fun recoverInterruptedSources() {
        val values = ContentValues().apply { put("status", "PENDING") }
        writableDatabase.update(
            "contact_memory_sources", values, "status='PROCESSING'", null,
        )
    }

    fun claimNextBatch(threadId: String, scene: String, limit: Int = 12): List<ContactMemorySource> {
        val scope = threadId.trim().take(140)
        if (scope.isBlank()) return emptyList()
        val db = writableDatabase
        val keys = mutableListOf<String>()
        db.beginTransaction()
        try {
            db.rawQuery(
                "SELECT event_key FROM contact_memory_sources " +
                    "WHERE thread_id=? AND scene=? AND status='PENDING' AND attempt_count<3 " +
                    "ORDER BY observed_at ASC LIMIT ?",
                arrayOf(scope, scene, limit.coerceIn(1, 20).toString()),
            ).use { cursor -> while (cursor.moveToNext()) keys += cursor.getString(0) }
            keys.forEach { key ->
                val values = ContentValues().apply {
                    put("status", "PROCESSING")
                    put("attempt_count", getSourceAttemptCount(key) + 1)
                    put("updated_at", System.currentTimeMillis())
                }
                db.update("contact_memory_sources", values, "event_key=?", arrayOf(key))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (keys.isEmpty()) return emptyList()
        val result = mutableListOf<ContactMemorySource>()
        keys.forEach { key ->
            db.rawQuery(
                "SELECT event_key, thread_id, scene, role, content, observed_at, attempt_count " +
                    "FROM contact_memory_sources WHERE event_key=? AND status='PROCESSING' LIMIT 1",
                arrayOf(key),
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    result += ContactMemorySource(
                        eventKey = cursor.getString(0),
                        threadId = cursor.getString(1),
                        scene = cursor.getString(2),
                        role = cursor.getString(3),
                        content = cursor.getString(4),
                        observedAt = cursor.getLong(5),
                        attemptCount = cursor.getInt(6),
                    )
                }
            }
        }
        return result
    }

    private fun getSourceAttemptCount(eventKey: String): Int {
        writableDatabase.rawQuery(
            "SELECT attempt_count FROM contact_memory_sources WHERE event_key=? LIMIT 1",
            arrayOf(eventKey),
        ).use { return if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    fun completeSources(eventKeys: List<String>) {
        updateSources(eventKeys, "DONE", "")
    }

    fun deferSources(eventKeys: List<String>, reason: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("status", "PENDING")
            put("attempt_count", 0)
            put("last_error", reason.take(160))
            put("updated_at", System.currentTimeMillis())
        }
        eventKeys.forEach { key ->
            db.update("contact_memory_sources", values, "event_key=?", arrayOf(key))
        }
    }

    fun failSources(eventKeys: List<String>, reason: String) {
        val db = writableDatabase
        eventKeys.forEach { key ->
            val attempt = getSourceAttemptCount(key)
            val values = ContentValues().apply {
                val failed = attempt >= 3
                put("status", if (failed) "FAILED" else "PENDING")
                if (failed) put("content", "")
                put("last_error", reason.take(160))
                put("updated_at", System.currentTimeMillis())
            }
            db.update("contact_memory_sources", values, "event_key=?", arrayOf(key))
        }
    }

    private fun updateSources(eventKeys: List<String>, status: String, reason: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("status", status)
            put("last_error", reason.take(160))
            put("updated_at", System.currentTimeMillis())
        }
        eventKeys.forEach { key ->
            val sourceValues = ContentValues(values).apply {
                if (status == "DONE") put("content", "")
            }
            db.update("contact_memory_sources", sourceValues, "event_key=?", arrayOf(key))
        }
    }

    fun saveValidatedDraft(
        threadId: String,
        draft: ContactMemoryLogic.ValidatedDraft,
    ): Long? {
        val scope = threadId.trim().take(140)
        if (scope.isBlank()) return null
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val evidence = draft.evidence
        val firstSeen = evidence.minOf { it.source.observedAt }
        val lastSeen = evidence.maxOf { it.source.observedAt }
        val expiresAt = draft.draft.validDays.takeIf { it > 0 }?.let {
            lastSeen + it.coerceAtMost(180) * 24L * 60L * 60L * 1000L
        } ?: 0L
        val normalized = ContactMemoryLogic.normalizeContent(draft.draft.content)
        var memoryId: Long? = null

        db.beginTransaction()
        try {
            val matches = mutableListOf<Pair<Long, String>>()
            db.rawQuery(
                "SELECT id, content FROM contact_memory_facts WHERE thread_id=? AND category=? " +
                    "AND memory_key=? AND status IN ('ACTIVE','CONFLICTED')",
                arrayOf(scope, draft.draft.category.name, draft.draft.memoryKey),
            ).use { cursor ->
                while (cursor.moveToNext()) matches += cursor.getLong(0) to cursor.getString(1)
            }
            val same = matches.firstOrNull {
                ContactMemoryLogic.normalizeContent(it.second) == normalized
            }
            val evidenceLevel = when {
                !draft.draft.explicit -> "UNCERTAIN"
                evidence.map { it.source.eventKey }.distinct().size > 1 -> "REPEATED"
                else -> "DIRECT"
            }
            val status = when {
                !draft.draft.explicit -> ContactMemoryStatus.PENDING
                matches.isNotEmpty() && same == null -> ContactMemoryStatus.CONFLICTED
                matches.isNotEmpty() -> ContactMemoryStatus.ACTIVE
                else -> ContactMemoryStatus.ACTIVE
            }

            if (same != null) {
                memoryId = same.first
                val values = ContentValues().apply {
                    if (draft.draft.explicit) put("last_confirmed_at", lastSeen)
                    put("updated_at", now)
                    if (expiresAt > 0) put("expires_at", expiresAt)
                    if (evidenceLevel == "REPEATED" && draft.draft.explicit) put("evidence_level", evidenceLevel)
                    if (matches.any { it.second != same.second }) put("status", "CONFLICTED")
                }
                db.update("contact_memory_facts", values, "id=?", arrayOf(memoryId.toString()))
            } else {
                if (status == ContactMemoryStatus.CONFLICTED) {
                    matches.forEach { (id, _) ->
                        val conflict = ContentValues().apply {
                            put("status", "CONFLICTED")
                            put("updated_at", now)
                        }
                        db.update("contact_memory_facts", conflict, "id=?", arrayOf(id.toString()))
                    }
                }
                val values = ContentValues().apply {
                    put("thread_id", scope)
                    put("category", draft.draft.category.name)
                    put("memory_key", draft.draft.memoryKey)
                    put("content", draft.draft.content)
                    put("status", status.name)
                    put("evidence_level", evidenceLevel)
                    put("first_seen_at", firstSeen)
                    put("last_confirmed_at", if (draft.draft.explicit) lastSeen else 0L)
                    put("expires_at", expiresAt)
                    put("created_at", now)
                    put("updated_at", now)
                }
                memoryId = db.insert("contact_memory_facts", null, values).takeIf { it > 0 }
            }
            val targetId = memoryId ?: return null
            evidence.forEach { item ->
                val source = item.source
                val evidenceValues = ContentValues().apply {
                    put("memory_id", targetId)
                    put("source_event_key", source.eventKey)
                    put("source_quote", item.quote)
                    put("scene", source.scene)
                    put("role", source.role)
                    put("observed_at", source.observedAt)
                    put("evidence_type", if (draft.draft.explicit) evidenceLevel else "UNCERTAIN")
                }
                db.insertWithOnConflict(
                    "contact_memory_evidence", null, evidenceValues, SQLiteDatabase.CONFLICT_IGNORE,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return memoryId
    }

    fun factsForThread(threadId: String, includeInactive: Boolean = true, limit: Int = 300): List<ContactMemoryFact> {
        val scope = threadId.trim().take(140)
        if (scope.isBlank()) return emptyList()
        expireStaleFacts(scope)
        val result = mutableListOf<ContactMemoryFact>()
        val statusFilter = if (includeInactive) "" else " AND f.status IN ('ACTIVE','PENDING','CONFLICTED')"
        readableDatabase.rawQuery(
            "SELECT f.id, f.thread_id, f.category, f.memory_key, f.content, f.status, f.evidence_level, " +
                "f.first_seen_at, f.last_confirmed_at, f.expires_at, f.created_at, f.updated_at, " +
                "COUNT(e.id) FROM contact_memory_facts f LEFT JOIN contact_memory_evidence e ON e.memory_id=f.id " +
                "WHERE f.thread_id=?$statusFilter GROUP BY f.id ORDER BY " +
                "CASE f.status WHEN 'ACTIVE' THEN 0 WHEN 'CONFLICTED' THEN 1 WHEN 'PENDING' THEN 2 ELSE 3 END, " +
                "f.updated_at DESC LIMIT ?",
            arrayOf(scope, limit.coerceIn(1, 500).toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val category = ContactMemoryCategory.from(cursor.getString(2)) ?: continue
                result += ContactMemoryFact(
                    id = cursor.getLong(0),
                    threadId = cursor.getString(1),
                    category = category,
                    memoryKey = cursor.getString(3),
                    content = cursor.getString(4),
                    status = ContactMemoryStatus.from(cursor.getString(5)),
                    evidenceLevel = cursor.getString(6),
                    firstSeenAt = cursor.getLong(7),
                    lastConfirmedAt = cursor.getLong(8),
                    expiresAt = cursor.getLong(9),
                    createdAt = cursor.getLong(10),
                    updatedAt = cursor.getLong(11),
                    evidenceCount = cursor.getInt(12),
                )
            }
        }
        return result
    }

    fun evidenceForFact(memoryId: Long): List<ContactMemoryEvidence> {
        val result = mutableListOf<ContactMemoryEvidence>()
        readableDatabase.rawQuery(
            "SELECT id, memory_id, source_event_key, source_quote, scene, role, observed_at, evidence_type " +
                "FROM contact_memory_evidence WHERE memory_id=? ORDER BY observed_at ASC, id ASC",
            arrayOf(memoryId.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) result += ContactMemoryEvidence(
                cursor.getLong(0), cursor.getLong(1), cursor.getString(2), cursor.getString(3),
                cursor.getString(4), cursor.getString(5), cursor.getLong(6), cursor.getString(7),
            )
        }
        return result
    }

    fun statsForThread(threadId: String): ContactMemoryStats {
        expireStaleFacts(threadId.trim().take(140))
        val counts = mutableMapOf<String, Int>()
        readableDatabase.rawQuery(
            "SELECT status, COUNT(*) FROM contact_memory_facts WHERE thread_id=? GROUP BY status",
            arrayOf(threadId.trim().take(140)),
        ).use { cursor -> while (cursor.moveToNext()) counts[cursor.getString(0)] = cursor.getInt(1) }
        val active = counts["ACTIVE"] ?: 0
        val pending = counts["PENDING"] ?: 0
        val conflicted = counts["CONFLICTED"] ?: 0
        return ContactMemoryStats(
            active = active,
            pending = pending,
            conflicted = conflicted,
            other = counts.values.sum() - active - pending - conflicted,
        )
    }

    fun confirmFact(memoryId: Long): Boolean {
        val db = writableDatabase
        val target = findFactIdentity(memoryId) ?: return false
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            val superseded = ContentValues().apply {
                put("status", "SUPERSEDED")
                put("updated_at", now)
            }
            db.update(
                "contact_memory_facts", superseded,
                "thread_id=? AND category=? AND memory_key=? AND id<>? AND status IN ('ACTIVE','CONFLICTED')",
                arrayOf(target.first, target.second.first, target.second.second, memoryId.toString()),
            )
            val active = ContentValues().apply {
                put("status", "ACTIVE")
                put("evidence_level", "USER_CONFIRMED")
                put("last_confirmed_at", now)
                put("updated_at", now)
            }
            val updated = db.update("contact_memory_facts", active, "id=?", arrayOf(memoryId.toString()))
            db.setTransactionSuccessful()
            return updated > 0
        } finally {
            db.endTransaction()
        }
    }

    fun updateFactContent(memoryId: Long, content: String): Boolean {
        val clean = content.trim().take(180)
        if (clean.length < 2) return false
        val target = findFactIdentity(memoryId) ?: return false
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            val superseded = ContentValues().apply {
                put("status", "SUPERSEDED")
                put("updated_at", now)
            }
            db.update(
                "contact_memory_facts", superseded,
                "thread_id=? AND category=? AND memory_key=? AND id<>? AND status IN ('ACTIVE','CONFLICTED')",
                arrayOf(target.first, target.second.first, target.second.second, memoryId.toString()),
            )
            val values = ContentValues().apply {
                put("content", clean)
                put("status", "ACTIVE")
                put("evidence_level", "USER_CONFIRMED")
                put("last_confirmed_at", now)
                put("updated_at", now)
            }
            val updated = db.update(
                "contact_memory_facts", values, "id=?", arrayOf(memoryId.toString()),
            )
            db.setTransactionSuccessful()
            return updated > 0
        } finally {
            db.endTransaction()
        }
    }

    fun rejectFact(memoryId: Long): Boolean = updateFactStatus(memoryId, "REJECTED")
    fun expireFact(memoryId: Long): Boolean = updateFactStatus(memoryId, "EXPIRED")

    fun deleteFact(memoryId: Long): Boolean =
        writableDatabase.delete("contact_memory_facts", "id=?", arrayOf(memoryId.toString())) > 0

    private fun updateFactStatus(memoryId: Long, status: String): Boolean {
        val values = ContentValues().apply {
            put("status", status)
            put("updated_at", System.currentTimeMillis())
        }
        return writableDatabase.update("contact_memory_facts", values, "id=?", arrayOf(memoryId.toString())) > 0
    }

    private fun findFactIdentity(memoryId: Long): Pair<String, Pair<String, String>>? {
        readableDatabase.rawQuery(
            "SELECT thread_id, category, memory_key FROM contact_memory_facts WHERE id=? LIMIT 1",
            arrayOf(memoryId.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return cursor.getString(0) to (cursor.getString(1) to cursor.getString(2))
        }
    }

    private fun expireStaleFacts(threadId: String) {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("status", "EXPIRED")
            put("updated_at", now)
        }
        writableDatabase.update(
            "contact_memory_facts", values,
            "thread_id=? AND status IN ('ACTIVE','CONFLICTED','PENDING') AND expires_at>0 AND expires_at<=?",
            arrayOf(threadId, now.toString()),
        )
    }

    fun globalStats(): ContactMemoryStats {
        val now = System.currentTimeMillis()
        val expired = ContentValues().apply {
            put("status", "EXPIRED")
            put("updated_at", now)
        }
        writableDatabase.update(
            "contact_memory_facts", expired,
            "status IN ('ACTIVE','CONFLICTED','PENDING') AND expires_at>0 AND expires_at<=?",
            arrayOf(now.toString()),
        )
        val counts = mutableMapOf<String, Int>()
        readableDatabase.rawQuery(
            "SELECT status, COUNT(*) FROM contact_memory_facts GROUP BY status", null,
        ).use { cursor -> while (cursor.moveToNext()) counts[cursor.getString(0)] = cursor.getInt(1) }
        val active = counts["ACTIVE"] ?: 0
        val pending = counts["PENDING"] ?: 0
        val conflicted = counts["CONFLICTED"] ?: 0
        return ContactMemoryStats(active, pending, conflicted, counts.values.sum() - active - pending - conflicted)
    }


    fun writeExport(writer: Writer) {
        val root = JSONObject()
            .put("schema", "soulbot-contact-memory")
            .put("version", 1)
            .put("exportedAt", System.currentTimeMillis())
        val facts = JSONArray()
        val allIds = mutableListOf<Long>()
        readableDatabase.rawQuery(
            "SELECT id, thread_id, category, memory_key, content, status, evidence_level, " +
                "first_seen_at, last_confirmed_at, expires_at, created_at, updated_at " +
                "FROM contact_memory_facts ORDER BY created_at, id",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                allIds += id
                facts.put(JSONObject()
                    .put("id", id)
                    .put("thread_id", cursor.getString(1))
                    .put("category", cursor.getString(2))
                    .put("memory_key", cursor.getString(3))
                    .put("content", cursor.getString(4))
                    .put("status", cursor.getString(5))
                    .put("evidence_level", cursor.getString(6))
                    .put("first_seen_at", cursor.getLong(7))
                    .put("last_confirmed_at", cursor.getLong(8))
                    .put("expires_at", cursor.getLong(9))
                    .put("created_at", cursor.getLong(10))
                    .put("updated_at", cursor.getLong(11)))
            }
        }
        val evidenceArray = JSONArray()
        allIds.forEach { id ->
            evidenceForFact(id).forEach { item ->
                evidenceArray.put(JSONObject()
                    .put("memory_id", id)
                    .put("source_event_key", item.sourceEventKey)
                    .put("source_quote", item.sourceQuote)
                    .put("scene", item.scene)
                    .put("role", item.role)
                    .put("observed_at", item.observedAt)
                    .put("evidence_type", item.evidenceType))
            }
        }
        root.put("facts", facts)
        root.put("evidence", evidenceArray)
        writer.write(root.toString(2))
    }

    fun importFrom(reader: Reader): ContactMemoryImportResult {
        val raw = reader.readText()
        require(raw.length <= 10_000_000) { "记忆备份文件过大" }
        val root = JSONObject(raw)
        require(root.optString("schema") == "soulbot-contact-memory") { "不是联系人长期记忆备份" }
        require(root.optInt("version") == 1) { "不支持的联系人记忆备份版本" }
        val facts = root.optJSONArray("facts") ?: JSONArray()
        val evidence = root.optJSONArray("evidence") ?: JSONArray()
        val insertedIds = mutableMapOf<Long, Long>()
        val db = writableDatabase
        var added = 0
        var skipped = 0
        var invalid = 0
        db.beginTransaction()
        try {
            for (index in 0 until facts.length()) {
                val item = facts.optJSONObject(index)
                if (item == null) {
                    invalid++
                    continue
                }
                val oldId = item.optLong("id", -1L)
                val threadId = item.optString("thread_id").trim().take(140)
                val category = ContactMemoryCategory.from(item.optString("category"))
                val key = item.optString("memory_key").trim().take(80)
                val content = item.optString("content").trim().take(180)
                val status = ContactMemoryStatus.from(item.optString("status"))
                if (threadId.isBlank() || threadId == "LEGACY_UNKNOWN" || category == null ||
                    key.isBlank() || content.length < 2
                ) {
                    invalid++
                    continue
                }
                var existingId: Long? = null
                db.rawQuery(
                    "SELECT id FROM contact_memory_facts WHERE thread_id=? AND category=? AND memory_key=? AND content=? LIMIT 1",
                    arrayOf(threadId, category.name, key, content),
                ).use { cursor -> if (cursor.moveToFirst()) existingId = cursor.getLong(0) }
                if (existingId != null) {
                    insertedIds[oldId] = existingId!!
                    skipped++
                    continue
                }
                val now = System.currentTimeMillis()
                val values = ContentValues().apply {
                    put("thread_id", threadId)
                    put("category", category.name)
                    put("memory_key", key)
                    put("content", content)
                    put("status", status.name)
                    put("evidence_level", item.optString("evidence_level").take(32).ifBlank { "IMPORTED" })
                    put("first_seen_at", item.optLong("first_seen_at", now).coerceAtLeast(0L))
                    put("last_confirmed_at", item.optLong("last_confirmed_at", 0L).coerceAtLeast(0L))
                    put("expires_at", item.optLong("expires_at", 0L).coerceAtLeast(0L))
                    put("created_at", item.optLong("created_at", now).coerceAtLeast(0L))
                    put("updated_at", now)
                }
                val newId = db.insert("contact_memory_facts", null, values)
                if (newId > 0) {
                    insertedIds[oldId] = newId
                    added++
                } else invalid++
            }

            for (index in 0 until evidence.length()) {
                val item = evidence.optJSONObject(index) ?: continue
                val targetId = insertedIds[item.optLong("memory_id", -1L)] ?: continue
                val quote = item.optString("source_quote").trim().take(500)
                val eventKey = item.optString("source_event_key").trim().take(160)
                if (quote.length < 3 || eventKey.isBlank()) continue
                val values = ContentValues().apply {
                    put("memory_id", targetId)
                    put("source_event_key", eventKey)
                    put("source_quote", quote)
                    put("scene", item.optString("scene").take(40).ifBlank { "CHAT" })
                    put("role", "in")
                    put("observed_at", item.optLong("observed_at", 0L).coerceAtLeast(0L))
                    put("evidence_type", item.optString("evidence_type").take(32).ifBlank { "IMPORTED" })
                }
                db.insertWithOnConflict(
                    "contact_memory_evidence", null, values, SQLiteDatabase.CONFLICT_IGNORE,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return ContactMemoryImportResult(added, skipped, invalid)
    }

    fun clearAll() {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("contact_memory_facts", null, null)
            writableDatabase.delete("contact_memory_sources", null, null)
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }
}
