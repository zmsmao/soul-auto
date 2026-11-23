package com.soulbot.app.data

import com.soulbot.app.conversation.*
import com.soulbot.app.model.ReplyGenerationConfig

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

data class ConversationContactSummary(
    val threadId: String,
    val displayName: String,
    val messageCount: Int,
    val lastActivityAt: Long,
    val lastMessage: String,
    val lastRole: String,
    val lastScene: String,
)

class ConversationMemoryDatabase(context: Context) :
    SQLiteOpenHelper(context, "conversation_memory.db", null, 3) {

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
                "scene TEXT NOT NULL DEFAULT 'LEGACY_UNKNOWN')",
        )
        db.execSQL(
            "CREATE INDEX idx_conversation_contact_time " +
                "ON conversation_messages(contact, observed_at, id)",
        )
        db.execSQL(
            "CREATE INDEX idx_conversation_thread_scene_time " +
                "ON conversation_messages(thread_id, scene, observed_at, id)",
        )
        createReliabilityTables(db)
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
        if (oldVersion < 3) createReliabilityTables(db)
    }

    private fun createReliabilityTables(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS conversation_state (" +
                "thread_id TEXT NOT NULL, scene TEXT NOT NULL, current_topic TEXT NOT NULL DEFAULT '', " +
                "topic_turn_count INTEGER NOT NULL DEFAULT 0, last_question TEXT NOT NULL DEFAULT '', " +
                "last_banter TEXT NOT NULL DEFAULT '', low_information_count INTEGER NOT NULL DEFAULT 0, " +
                "last_progress_at INTEGER NOT NULL DEFAULT 0, last_incoming_fingerprint TEXT NOT NULL DEFAULT '', " +
                "updated_at INTEGER NOT NULL, PRIMARY KEY(thread_id, scene))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS send_attempts (" +
                "send_key TEXT PRIMARY KEY, thread_id TEXT NOT NULL, scene TEXT NOT NULL, " +
                "body_hash TEXT NOT NULL, event_anchor TEXT NOT NULL DEFAULT '', state TEXT NOT NULL, " +
                "outgoing_baseline INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_send_attempt_state_time ON send_attempts(state, updated_at)")
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


    fun loadConversationState(threadId: String, scene: ReplyScene): ConversationState? {
        val scope = cleanThreadId(threadId) ?: return null
        readableDatabase.rawQuery(
            "SELECT current_topic, topic_turn_count, last_question, last_banter, low_information_count, " +
                "last_progress_at, last_incoming_fingerprint, updated_at " +
                "FROM conversation_state WHERE thread_id=? AND scene=? LIMIT 1",
            arrayOf(scope, scene.name),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return ConversationState(
                threadId = scope, scene = scene.name, currentTopic = cursor.getString(0),
                topicTurnCount = cursor.getInt(1), lastQuestion = cursor.getString(2),
                lastBanter = cursor.getString(3), lowInformationCount = cursor.getInt(4),
                lastProgressAt = cursor.getLong(5), lastIncomingFingerprint = cursor.getString(6),
                updatedAt = cursor.getLong(7),
            )
        }
    }

    fun updateConversationState(
        threadId: String,
        scene: ReplyScene,
        thread: List<Pair<String, String>>,
    ): ConversationState? {
        val scope = cleanThreadId(threadId) ?: return null
        val state = ConversationStatePolicy.evolve(scope, scene, thread, loadConversationState(scope, scene))
        val values = ContentValues().apply {
            put("thread_id", state.threadId)
            put("scene", state.scene)
            put("current_topic", state.currentTopic)
            put("topic_turn_count", state.topicTurnCount)
            put("last_question", state.lastQuestion)
            put("last_banter", state.lastBanter)
            put("low_information_count", state.lowInformationCount)
            put("last_progress_at", state.lastProgressAt)
            put("last_incoming_fingerprint", state.lastIncomingFingerprint)
            put("updated_at", state.updatedAt)
        }
        writableDatabase.insertWithOnConflict("conversation_state", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        return state
    }

    internal fun reserveSendAttempt(
        sendKey: String,
        threadId: String,
        scene: ReplyScene,
        bodyHash: String,
        eventAnchor: String,
        outgoingBaseline: Int,
    ): SendAttemptReservation {
        val db = writableDatabase
        var result = SendAttemptReservation(SendAttemptDisposition.READY, outgoingBaseline)
        db.beginTransaction()
        try {
            var existingState: String? = null
            var storedBodyHash = ""
            var storedBaseline = outgoingBaseline
            db.rawQuery(
                "SELECT state, body_hash, outgoing_baseline FROM send_attempts WHERE send_key=? LIMIT 1",
                arrayOf(sendKey),
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    existingState = cursor.getString(0)
                    storedBodyHash = cursor.getString(1)
                    storedBaseline = cursor.getInt(2)
                }
            }
            if (existingState == null) {
                val values = ContentValues().apply {
                    put("send_key", sendKey)
                    put("thread_id", threadId)
                    put("scene", scene.name)
                    put("body_hash", bodyHash)
                    put("event_anchor", eventAnchor)
                    put("state", "PREPARED")
                    put("outgoing_baseline", outgoingBaseline)
                    put("updated_at", System.currentTimeMillis())
                }
                db.insertWithOnConflict("send_attempts", null, values, SQLiteDatabase.CONFLICT_IGNORE)
                result = SendAttemptReservation(SendAttemptDisposition.READY, outgoingBaseline)
            } else if (existingState == "PREPARED") {
                if (storedBodyHash != bodyHash) {
                    val values = ContentValues().apply {
                        put("body_hash", bodyHash)
                        put("updated_at", System.currentTimeMillis())
                    }
                    db.update("send_attempts", values, "send_key=?", arrayOf(sendKey))
                }
                result = SendAttemptReservation(SendAttemptDisposition.READY, storedBaseline)
            } else {
                val disposition = if (storedBodyHash != bodyHash) SendAttemptDisposition.UNCERTAIN
                    else SendAttemptPolicy.disposition(existingState, storedBaseline, storedBaseline)
                result = SendAttemptReservation(disposition, storedBaseline)
            }
            db.delete(
                "send_attempts", "state='CONFIRMED' AND updated_at < ?",
                arrayOf((System.currentTimeMillis() - 90L * 24L * 60L * 60L * 1000L).toString()),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return result
    }

    /** Event-level ledger view: handles partial overlap when later messages arrive after restart. */
    fun confirmedMessageKeys(threadId: String): Set<String> {
        val scope = cleanThreadId(threadId) ?: return emptySet()
        val result = linkedSetOf<String>()
        readableDatabase.rawQuery(
            "SELECT event_anchor FROM send_attempts WHERE thread_id=? AND state='CONFIRMED' AND event_anchor<>''",
            arrayOf(scope),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                cursor.getString(0).split(',').filter(String::isNotBlank).forEach(result::add)
            }
        }
        return result
    }

    fun hasConfirmedSendForEvent(threadId: String, scene: ReplyScene, eventAnchor: String): Boolean {
        if (eventAnchor.isBlank()) return false
        readableDatabase.rawQuery(
            "SELECT 1 FROM send_attempts WHERE thread_id=? AND scene=? AND event_anchor=? AND state='CONFIRMED' LIMIT 1",
            arrayOf(threadId, scene.name, eventAnchor),
        ).use { return it.moveToFirst() }
    }

    fun markSendAttemptClicked(sendKey: String) = updateSendAttemptState(sendKey, "CLICKED")
    fun markSendAttemptPrepared(sendKey: String) = updateSendAttemptState(sendKey, "PREPARED")
    fun markSendAttemptUncertain(sendKey: String) = updateSendAttemptState(sendKey, "UNCERTAIN")
    fun markSendAttemptConfirmed(sendKey: String) = updateSendAttemptState(sendKey, "CONFIRMED")

    private fun updateSendAttemptState(sendKey: String, state: String) {
        if (state !in setOf("PREPARED", "CLICKED", "UNCERTAIN", "CONFIRMED")) return
        val values = ContentValues().apply {
            put("state", state)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.update("send_attempts", values, "send_key=?", arrayOf(sendKey))
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
        recentWindow: Int = ReplyGenerationConfig.CONTEXT_LEN,
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

    fun contacts(search: String = "", limit: Int = 300): List<ConversationContactSummary> {
        val result = mutableListOf<ConversationContactSummary>()
        readableDatabase.rawQuery(
            "SELECT m.thread_id, " +
                "COALESCE((SELECT x.contact FROM conversation_messages x WHERE x.thread_id=m.thread_id " +
                    "ORDER BY x.observed_at DESC, x.id DESC LIMIT 1), ''), " +
                "COUNT(*), MAX(m.observed_at), " +
                "COALESCE((SELECT x.content FROM conversation_messages x WHERE x.thread_id=m.thread_id " +
                    "ORDER BY x.observed_at DESC, x.id DESC LIMIT 1), ''), " +
                "COALESCE((SELECT x.role FROM conversation_messages x WHERE x.thread_id=m.thread_id " +
                    "ORDER BY x.observed_at DESC, x.id DESC LIMIT 1), ''), " +
                "COALESCE((SELECT x.scene FROM conversation_messages x WHERE x.thread_id=m.thread_id " +
                    "ORDER BY x.observed_at DESC, x.id DESC LIMIT 1), 'CHAT') " +
                "FROM conversation_messages m WHERE m.thread_id<>? AND m.thread_id<>'' " +
                "GROUP BY m.thread_id ORDER BY MAX(m.observed_at) DESC LIMIT ?",
            arrayOf(LEGACY_UNKNOWN, limit.coerceIn(1, 500).toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val item = ConversationContactSummary(
                    threadId = cursor.getString(0),
                    displayName = cursor.getString(1).orEmpty().ifBlank { "未命名联系人" },
                    messageCount = cursor.getInt(2),
                    lastActivityAt = cursor.getLong(3),
                    lastMessage = cursor.getString(4).orEmpty(),
                    lastRole = cursor.getString(5).orEmpty(),
                    lastScene = cursor.getString(6).orEmpty(),
                )
                if (search.isBlank() || item.displayName.contains(search.trim(), ignoreCase = true)) {
                    result += item
                }
            }
        }
        return result
    }

    fun contactEntries(threadId: String, limit: Int = 200): List<ConversationMemoryEntry> {
        val scope = cleanThreadId(threadId) ?: return emptyList()
        val result = mutableListOf<ConversationMemoryEntry>()
        readableDatabase.rawQuery(
            "SELECT event_key, contact, role, content, observed_at, source, thread_id, scene " +
                "FROM conversation_messages WHERE thread_id=? " +
                "ORDER BY observed_at DESC, id DESC LIMIT ?",
            arrayOf(scope, limit.coerceIn(1, 500).toString()),
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
                    result += entry
                }
            }
        }
        return result.asReversed()
    }

    fun conversationStates(threadId: String): List<ConversationState> {
        val scope = cleanThreadId(threadId) ?: return emptyList()
        val result = mutableListOf<ConversationState>()
        readableDatabase.rawQuery(
            "SELECT scene, current_topic, topic_turn_count, last_question, last_banter, " +
                "low_information_count, last_progress_at, last_incoming_fingerprint, updated_at " +
                "FROM conversation_state WHERE thread_id=? ORDER BY updated_at DESC",
            arrayOf(scope),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += ConversationState(
                    threadId = scope,
                    scene = cursor.getString(0),
                    currentTopic = cursor.getString(1),
                    topicTurnCount = cursor.getInt(2),
                    lastQuestion = cursor.getString(3),
                    lastBanter = cursor.getString(4),
                    lowInformationCount = cursor.getInt(5),
                    lastProgressAt = cursor.getLong(6),
                    lastIncomingFingerprint = cursor.getString(7),
                    updatedAt = cursor.getLong(8),
                )
            }
        }
        return result
    }

    /** Only resolves a name when it maps to exactly one stable conversation identity. */
    fun uniqueThreadIdForName(name: String): String? {
        val cleanName = name.trim().take(120)
        if (cleanName.isBlank()) return null
        val ids = mutableSetOf<String>()
        readableDatabase.rawQuery(
            "SELECT DISTINCT thread_id FROM conversation_messages WHERE contact=? AND thread_id<>?",
            arrayOf(cleanName, LEGACY_UNKNOWN),
        ).use { cursor ->
            while (cursor.moveToNext()) ids += cursor.getString(0)
        }
        return ids.singleOrNull()
    }

    fun contactCount(): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(DISTINCT thread_id) FROM conversation_messages WHERE thread_id<>?",
            arrayOf(LEGACY_UNKNOWN),
        ).use { cursor -> return if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
    }

    fun clearAll() {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("conversation_messages", null, null)
            writableDatabase.delete("conversation_state", null, null)
            // Keep the compact send-attempt ledger: clearing chat memory must not make
            // a previously clicked message eligible for an accidental duplicate send.
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
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
