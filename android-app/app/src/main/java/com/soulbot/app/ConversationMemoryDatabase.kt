package com.soulbot.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import java.io.Reader
import java.io.Writer
import java.security.MessageDigest
import java.util.UUID

data class ConversationMemoryImportResult(
    val added: Int,
    val skipped: Int,
    val invalid: Int,
)

class ConversationMemoryDatabase(context: Context) :
    SQLiteOpenHelper(context, "conversation_memory.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE conversation_messages (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "event_key TEXT NOT NULL UNIQUE, " +
                "contact TEXT NOT NULL, " +
                "role TEXT NOT NULL, " +
                "content TEXT NOT NULL, " +
                "observed_at INTEGER NOT NULL, " +
                "source TEXT NOT NULL, " +
                "thread_id TEXT NOT NULL DEFAULT 'LEGACY_UNKNOWN', " +
                "scene TEXT NOT NULL DEFAULT 'LEGACY_UNKNOWN')
        )
        db.execSQL(
            "CREATE INDEX idx_conversation_contact_time " +
                "ON conversation_messages(contact, observed_at, id)",
        )
        db.execSQL(
            "CREATE INDEX idx_conversation_thread_scene_time " +
                "ON conversation_messages(thread_id, scene, observed_at, id)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Nickname-only legacy rows must not be assigned a guessed identity or scene.
            db.execSQL("ALTER TABLE conversation_messages ADD COLUMN thread_id TEXT NOT NULL DEFAULT 'LEGACY_UNKNOWN'")
            db.execSQL("ALTER TABLE conversation_messages ADD COLUMN scene TEXT NOT NULL DEFAULT 'LEGACY_UNKNOWN'")
            db.execSQL(
                "CREATE INDEX idx_conversation_thread_scene_time " +
                    "ON conversation_messages(thread_id, scene, observed_at, id)",
            )
        }
    }

    /** Nickname-only history lookup remains for legacy replay surfaces, not live retrieval. */
    fun hasHistory(contact: String): Boolean {
        val clean = cleanContact(contact) ?: return false
        readableDatabase.rawQuery(
            "SELECT 1 FROM conversation_messages WHERE contact=? LIMIT 1",
            arrayOf(clean),
        ).use { return it.moveToFirst() }
    }

    fun hasHistory(threadId: String, scene: ReplyScene): Boolean {
        val scope = cleanThreadId(threadId) ?: return false
        readableDatabase.rawQuery(
            "SELECT 1 FROM conversation_messages WHERE thread_id=? AND scene=? LIMIT 1",
            arrayOf(scope, scene.name),
        ).use { return it.moveToFirst() }
    }

    fun syncVisibleThread(
        contact: String,
        visibleThread: List<Pair<String, String>>,
        threadId: String,
        scene: ReplyScene,
        source: String = "chat",
    ): Int {
        val cleanContact = cleanContact(contact) ?: return 0
        val scope = cleanThreadId(threadId) ?: return 0
        val cleanThread = visibleThread.mapNotNull { (role, content) ->
            cleanMessage(role, content)
        }
        if (cleanThread.isEmpty()) return 0
        val existingTail = recentThreadEntries(scope, scene, 120)
            .map { it.role to it.content }
        val additions = ConversationMemoryLogic.messagesAfterOverlap(existingTail, cleanThread)
        if (additions.isEmpty()) return 0

        var added = 0
        val baseTime = System.currentTimeMillis()
        writableDatabase.beginTransaction()
        try {
            additions.forEachIndexed { index, message ->
                if (insert(
                        db = writableDatabase,
                        eventKey = UUID.randomUUID().toString(),
                        contact = cleanContact,
                        role = message.first,
                        content = message.second,
                        observedAt = baseTime + index,
                        source = cleanSource(source),
                        threadId = scope,
                        scene = scene.name,
                    )
                ) added++
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
        return added
    }

    fun recordMessage(
        contact: String,
        role: String,
        content: String,
        threadId: String,
        scene: ReplyScene,
        source: String = "chat",
    ): Boolean {
        val cleanContact = cleanContact(contact) ?: return false
        val scope = cleanThreadId(threadId) ?: return false
        val cleanMessage = cleanMessage(role, content) ?: return false
        return insert(
            db = writableDatabase,
            eventKey = UUID.randomUUID().toString(),
            contact = cleanContact,
            role = cleanMessage.first,
            content = cleanMessage.second,
            observedAt = System.currentTimeMillis(),
            source = cleanSource(source),
            threadId = scope,
            scene = scene.name,
        )
    }

    fun seedLegacyHistory(items: List<HistoryItem>): Int {
        if (items.isEmpty()) return 0
        readableDatabase.rawQuery(
            "SELECT 1 FROM conversation_messages WHERE source='legacy-history' LIMIT 1",
            null,
        ).use { cursor -> if (cursor.moveToFirst()) return 0 }
        var added = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (item in items.asReversed()) {
                val contact = cleanContact(item.name) ?: continue
                val incoming = cleanMessage("in", item.theirMsg)
                val outgoing = cleanMessage("out", item.myMsg)
                if (incoming != null && insert(
                        db,
                        stableImportKey(contact, incoming.first, incoming.second, item.time, "legacy-history"),
                        contact,
                        incoming.first,
                        incoming.second,
                        item.time,
                        "legacy-history",
                    )
                ) added++
                if (outgoing != null && insert(
                        db,
                        stableImportKey(contact, outgoing.first, outgoing.second, item.time + 1, "legacy-history"),
                        contact,
                        outgoing.first,
                        outgoing.second,
                        item.time + 1,
                        "legacy-history",
                    )
                ) added++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return added
    }

    fun relevantOlderMessages(
        threadId: String,
        scene: ReplyScene,
        query: String,
        limit: Int = 6,
        recentWindow: Int = SoulBotService.CONTEXT_LEN,
    ): List<ConversationMemoryEntry> {
        val scope = cleanThreadId(threadId) ?: return emptyList()
        val candidates = mutableListOf<ConversationMemoryEntry>()
        readableDatabase.rawQuery(
            "SELECT event_key, contact, role, content, observed_at, source, thread_id, scene " +
                "FROM conversation_messages WHERE thread_id=? AND scene=? " +
                "ORDER BY observed_at DESC, id DESC LIMIT 500 OFFSET ?",
            arrayOf(scope, scene.name, recentWindow.coerceAtLeast(0).toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val entry = ConversationMemoryEntry(
                    eventKey = cursor.getString(0),
                    contact = cursor.getString(1),
                    role = cursor.getString(2),
                    content = cursor.getString(3),
                    observedAt = cursor.getLong(4),
                    source = cursor.getString(5),
                    threadId = cursor.getString(6),
                    scene = cursor.getString(7),
                )
                if (entry.role != "out" || !ReplyNaturalness.isInternalDraft(entry.content)) {
                    candidates += entry
                }
            }
        }
        return ConversationMemoryLogic.rankRelevant(query, candidates, limit.coerceIn(1, 12))
    }

    fun messageCount(): Int = scalarCount("conversation_messages")

    /** Read-only picker data. Replay never changes the stored conversation or sends a message. */
    fun replayContacts(limit: Int = 100): List<String> = buildList {
        readableDatabase.rawQuery(
            "SELECT contact FROM conversation_messages GROUP BY contact " +
                "ORDER BY MAX(observed_at) DESC, contact ASC LIMIT ?",
            arrayOf(limit.coerceIn(1, 500).toString()),
        ).use { cursor -> while (cursor.moveToNext()) add(cursor.getString(0)) }
    }

    /** Oldest to newest; the picker must cut at the chosen incoming event before generation. */
    fun replayEntries(contact: String, limit: Int = 200): List<ConversationMemoryEntry> {
        val clean = cleanContact(contact) ?: return emptyList()
        return recentEntriesByContact(clean, limit.coerceIn(1, 500))
    }

    fun contactCount(): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(DISTINCT contact) FROM conversation_messages",
            null,
        ).use { cursor -> return if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
    }

    fun clearAll() {
        writableDatabase.delete("conversation_messages", null, null)
    }

    fun writeExport(writer: Writer) {
        JsonWriter(writer).use { json ->
            json.setIndent("  ")
            json.beginObject()
            json.name("schema").value(EXPORT_SCHEMA)
            json.name("version").value(EXPORT_VERSION.toLong())
            json.name("exportedAt").value(System.currentTimeMillis())
            json.name("messages").beginArray()
            readableDatabase.rawQuery(
                "SELECT event_key, contact, role, content, observed_at, source, thread_id, scene " +
                    "FROM conversation_messages ORDER BY observed_at, id",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val role = cursor.getString(2)
                    val content = cursor.getString(3)
                    if (role == "out" && ReplyNaturalness.isInternalDraft(content)) continue
                    json.beginObject()
                    json.name("eventKey").value(cursor.getString(0))
                    json.name("contact").value(cursor.getString(1))
                    json.name("role").value(role)
                    json.name("content").value(content)
                    json.name("observedAt").value(cursor.getLong(4))
                    json.name("source").value(cursor.getString(5))
                    json.name("threadId").value(cursor.getString(6))
                    json.name("scene").value(cursor.getString(7))
                    json.endObject()
                }
            }
            json.endArray()
            json.endObject()
        }
    }

    fun importFrom(reader: Reader): ConversationMemoryImportResult {
        var schema = ""
        var version = 0
        var added = 0
        var skipped = 0
        var invalid = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            JsonReader(reader).use { json ->
                json.beginObject()
                while (json.hasNext()) {
                    when (json.nextName()) {
                        "schema" -> schema = json.nextString()
                        "version" -> version = json.nextInt()
                        "messages" -> {
                            json.beginArray()
                            while (json.hasNext()) {
                                val entry = readEntry(json)
                                if (entry == null) {
                                    invalid++
                                } else {
                                    val inserted = insert(
                                        db = db,
                                        eventKey = entry.eventKey,
                                        contact = entry.contact,
                                        role = entry.role,
                                        content = entry.content,
                                        observedAt = entry.observedAt,
                                        source = entry.source,
                                        threadId = entry.threadId,
                                        scene = entry.scene,
                                    )
                                    if (inserted) added++ else skipped++
                                }
                            }
                            json.endArray()
                        }
                        else -> json.skipValue()
                    }
                }
                json.endObject()
            }
            require(schema == EXPORT_SCHEMA) { "不是聊天记忆备份文件" }
            require(version in 1..EXPORT_VERSION) { "不支持的备份版本：$version" }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return ConversationMemoryImportResult(added, skipped, invalid)
    }

    private fun readEntry(json: JsonReader): ConversationMemoryEntry? {
        if (json.peek() != JsonToken.BEGIN_OBJECT) {
            json.skipValue()
            return null
        }
        var eventKey = ""
        var contact = ""
        var role = ""
        var content = ""
        var observedAt = 0L
        var source = "import"
        var threadId = LEGACY_UNKNOWN
        var scene = LEGACY_UNKNOWN
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "eventKey" -> eventKey = json.nextString()
                "contact" -> contact = json.nextString()
                "role" -> role = json.nextString()
                "content" -> content = json.nextString()
                "observedAt" -> observedAt = json.nextLong()
                "source" -> source = json.nextString()
                "threadId" -> threadId = json.nextString()
                "scene" -> scene = json.nextString()
                else -> json.skipValue()
            }
        }
        json.endObject()
        val cleanContact = cleanContact(contact) ?: return null
        val cleanMessage = cleanMessage(role, content) ?: return null
        val safeTime = observedAt.takeIf { it > 0 } ?: System.currentTimeMillis()
        val safeKey = eventKey.trim().take(160).ifEmpty {
            stableImportKey(cleanContact, cleanMessage.first, cleanMessage.second, safeTime, source)
        }
        val validScene = ReplyScene.entries.firstOrNull { it.name == scene }
        val validThreadId = threadId.trim().take(120).takeIf { it.isNotEmpty() && it != LEGACY_UNKNOWN }
        val isScoped = validScene != null && validThreadId != null
        return ConversationMemoryEntry(
            eventKey = safeKey,
            contact = cleanContact,
            role = cleanMessage.first,
            content = cleanMessage.second,
            observedAt = safeTime,
            source = cleanSource(source),
            threadId = if (isScoped) validThreadId!! else LEGACY_UNKNOWN,
            scene = if (isScoped) validScene!!.name else LEGACY_UNKNOWN,
        )
    }

    private fun recentEntriesByContact(contact: String, limit: Int): List<ConversationMemoryEntry> {
        val result = mutableListOf<ConversationMemoryEntry>()
        readableDatabase.rawQuery(
            "SELECT event_key, contact, role, content, observed_at, source, thread_id, scene " +
                "FROM conversation_messages WHERE contact=? " +
                "ORDER BY observed_at DESC, id DESC LIMIT ?",
            arrayOf(contact, limit.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val entry = ConversationMemoryEntry(
                    cursor.getString(0), cursor.getString(1), cursor.getString(2),
                    cursor.getString(3), cursor.getLong(4), cursor.getString(5),
                    cursor.getString(6), cursor.getString(7),
                )
                if (entry.role != "out" || !ReplyNaturalness.isInternalDraft(entry.content)) {
                    result += entry
                }
            }
        }
        return result.asReversed()
    }

    private fun recentThreadEntries(threadId: String, scene: ReplyScene, limit: Int): List<ConversationMemoryEntry> {
        val result = mutableListOf<ConversationMemoryEntry>()
        readableDatabase.rawQuery(
            "SELECT event_key, contact, role, content, observed_at, source, thread_id, scene " +
                "FROM conversation_messages WHERE thread_id=? AND scene=? " +
                "ORDER BY observed_at DESC, id DESC LIMIT ?",
            arrayOf(threadId, scene.name, limit.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val entry = ConversationMemoryEntry(
                    cursor.getString(0), cursor.getString(1), cursor.getString(2),
                    cursor.getString(3), cursor.getLong(4), cursor.getString(5),
                    cursor.getString(6), cursor.getString(7),
                )
                if (entry.role != "out" || !ReplyNaturalness.isInternalDraft(entry.content)) {
                    result += entry
                }
            }
        }
        return result.asReversed()
    }

    private fun insert(
        db: SQLiteDatabase,
        eventKey: String,
        contact: String,
        role: String,
        content: String,
        observedAt: Long,
        source: String,
        threadId: String = LEGACY_UNKNOWN,
        scene: String = LEGACY_UNKNOWN,
    ): Boolean {
        val values = ContentValues().apply {
            put("event_key", eventKey)
            put("contact", contact)
            put("role", role)
            put("content", content)
            put("observed_at", observedAt)
            put("source", source)
            put("thread_id", threadId)
            put("scene", scene)
        }
        return db.insertWithOnConflict(
            "conversation_messages",
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE,
        ) != -1L
    }

    private fun scalarCount(table: String): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    private fun cleanContact(contact: String): String? =
        contact.trim().take(120).takeIf(String::isNotEmpty)

    private fun cleanThreadId(threadId: String): String? =
        threadId.trim().take(120).takeIf { it.isNotEmpty() && it != LEGACY_UNKNOWN }

    private fun cleanMessage(role: String, content: String): Pair<String, String>? {
        val cleanRole = role.trim().lowercase()
        if (cleanRole != "in" && cleanRole != "out") return null
        val cleanContent = content.trim().take(2000)
        if (cleanContent.isEmpty()) return null
        if (cleanRole == "out" && ReplyNaturalness.isInternalDraft(cleanContent)) return null
        return cleanRole to cleanContent
    }

    private fun cleanSource(source: String): String = source.trim().take(40).ifEmpty { "chat" }

    private fun stableImportKey(
        contact: String,
        role: String,
        content: String,
        observedAt: Long,
        source: String,
    ): String {
        val bytes = "$contact\u0000$role\u0000$content\u0000$observedAt\u0000$source".toByteArray()
        return "import-" + MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val LEGACY_UNKNOWN = "LEGACY_UNKNOWN"
        private const val EXPORT_SCHEMA = "soulbot-conversation-memory"
        private const val EXPORT_VERSION = 2
    }
}
