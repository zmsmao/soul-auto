package com.soulbot.app.queue

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.soulbot.app.conversation.ConversationIncomingEvent
import com.soulbot.app.conversation.ConversationRowPolicy
import java.util.UUID

internal enum class MessageQueueState {
    PENDING, PROCESSING, SENT, RETRY_WAIT, SUPPRESSED_POLICY,
    HANDLED_MANUALLY, BASELINE, SEND_UNCERTAIN, DEAD_LETTER, NO_REPLY_REQUIRED
}
internal enum class InboxFilter { OPEN, SNOOZED, DONE }
internal data class InboxConversationSummary(
    val threadId: String, val displayName: String, val preview: String,
    val updatedAt: Long, val openCount: Int, val state: MessageQueueState,
)
internal data class QueueMessageEvent(
    val key: String,
    val text: String,
    val state: MessageQueueState,
    val observedAt: Long = 0L,
    val attemptCount: Int = 0,
    val lastError: String = "",
)

/** Durable inbound event ledger, isolated from conversation history schema upgrades. */
internal class MessageDeliveryQueue(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "message_delivery_queue.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE inbox_conversations (
            thread_id TEXT PRIMARY KEY, contact_id TEXT NOT NULL, display_name TEXT NOT NULL,
            scene TEXT NOT NULL, initialized INTEGER NOT NULL DEFAULT 0,
            state TEXT NOT NULL DEFAULT 'OPEN', first_detected_at INTEGER NOT NULL,
            last_detected_at INTEGER NOT NULL, last_error TEXT NOT NULL DEFAULT ''
        )""".trimIndent())
        db.execSQL("""CREATE TABLE conversation_events (
            event_id INTEGER PRIMARY KEY AUTOINCREMENT, thread_id TEXT NOT NULL,
            event_key TEXT NOT NULL, scene TEXT NOT NULL, direction TEXT NOT NULL,
            content TEXT NOT NULL, observed_at INTEGER NOT NULL, queue_state TEXT NOT NULL,
            batch_id TEXT NOT NULL DEFAULT '', attempt_count INTEGER NOT NULL DEFAULT 0,
            next_attempt_at INTEGER NOT NULL DEFAULT 0, lease_until INTEGER NOT NULL DEFAULT 0,
            last_error TEXT NOT NULL DEFAULT '', UNIQUE(thread_id, event_key)
        )""".trimIndent())
        db.execSQL("""CREATE TABLE reply_batches (
            batch_id TEXT PRIMARY KEY, thread_id TEXT NOT NULL, scene TEXT NOT NULL,
            state TEXT NOT NULL, event_count INTEGER NOT NULL, lease_until INTEGER NOT NULL,
            created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, last_error TEXT NOT NULL DEFAULT ''
        )""".trimIndent())
        db.execSQL("CREATE INDEX idx_delivery_event_state ON conversation_events(queue_state, next_attempt_at, observed_at)")
        db.execSQL("CREATE INDEX idx_delivery_event_thread ON conversation_events(thread_id, event_id)")
        db.execSQL("CREATE INDEX idx_delivery_event_batch ON conversation_events(batch_id, queue_state)")
        db.execSQL("CREATE INDEX idx_delivery_inbox_state ON inbox_conversations(state, last_detected_at)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /**
     * Persist only events already admitted by QueueAdmissionPolicy.
     * Transcript history is deliberately never copied into this delivery ledger.
     */
    @Synchronized
    fun enqueueActionableEvents(
        threadId: String,
        contactId: String,
        displayName: String,
        scene: String,
        events: List<ConversationIncomingEvent>,
    ) {
        if (threadId.isBlank() || displayName.isBlank() || events.isEmpty()) return
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("thread_id", threadId)
                put("contact_id", contactId)
                put("display_name", displayName)
                put("scene", scene)
                put("initialized", 1)
                put("state", "OPEN")
                put("first_detected_at", now)
                put("last_detected_at", now)
            }
            db.insertWithOnConflict("inbox_conversations", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            db.update(
                "inbox_conversations",
                ContentValues().apply {
                    put("display_name", displayName)
                    put("contact_id", contactId)
                    put("scene", scene)
                    put("initialized", 1)
                    put("last_detected_at", now)
                    put("state", "OPEN")
                },
                "thread_id=?",
                arrayOf(threadId),
            )
            events.forEachIndexed { index, event ->
                val key = event.key.trim()
                val text = event.text.trim()
                if (key.isEmpty() || text.isEmpty()) return@forEachIndexed
                val eventValues = ContentValues().apply {
                    put("thread_id", threadId)
                    put("event_key", key)
                    put("scene", scene)
                    put("direction", "IN")
                    put("content", text)
                    put("observed_at", now + index)
                    put("queue_state", MessageQueueState.PENDING.name)
                }
                db.insertWithOnConflict(
                    "conversation_events",
                    null,
                    eventValues,
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Claim one contact's pending events; newly arrived events join an active regenerated batch. */
    @Synchronized
    fun claimPending(threadId: String, scene: String, leaseMillis: Long = DEFAULT_LEASE_MS): List<QueueMessageEvent> {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val leaseUntil = now + leaseMillis.coerceAtLeast(60_000L)
        var batchId = ""
        db.beginTransaction()
        try {
            excludeKnownPlatformNoticesLocked(db)
            recoverExpiredLocked(db, now)
            db.rawQuery(
                "SELECT batch_id FROM reply_batches WHERE thread_id=? AND state='PROCESSING' AND lease_until>? ORDER BY created_at DESC LIMIT 1",
                arrayOf(threadId, now.toString()),
            ).use { cursor -> if (cursor.moveToFirst()) batchId = cursor.getString(0) }

            val lowerBound = if (batchId.isNotBlank()) {
                db.rawQuery(
                    "SELECT COALESCE(MAX(event_id), 0) FROM conversation_events WHERE batch_id=? AND queue_state='PROCESSING'",
                    arrayOf(batchId),
                ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
            } else {
                var firstId = -1L
                var firstState = ""
                var firstRetryAt = 0L
                db.rawQuery(
                    """SELECT event_id, queue_state, next_attempt_at FROM conversation_events
                       WHERE thread_id=? AND queue_state IN ('PENDING','PROCESSING','RETRY_WAIT','SEND_UNCERTAIN','DEAD_LETTER')
                       ORDER BY event_id ASC LIMIT 1""".trimIndent(),
                    arrayOf(threadId),
                ).use { cursor ->
                    if (cursor.moveToFirst()) {
                        firstId = cursor.getLong(0)
                        firstState = cursor.getString(1)
                        firstRetryAt = cursor.getLong(2)
                    }
                }
                if (firstId < 0L ||
                    !MessageQueuePolicy.isReadyHead(firstState, firstRetryAt, now)
                ) {
                    db.setTransactionSuccessful()
                    return emptyList()
                }
                firstId - 1L
            }

            val blockerArgs = if (batchId.isBlank()) {
                arrayOf(threadId, lowerBound.toString(), now.toString())
            } else {
                arrayOf(threadId, lowerBound.toString(), now.toString())
            }
            val blockerId = db.rawQuery(
                """SELECT MIN(event_id) FROM conversation_events
                   WHERE thread_id=? AND event_id>?
                     AND (queue_state IN ('PROCESSING','SEND_UNCERTAIN','DEAD_LETTER')
                       OR (queue_state='RETRY_WAIT' AND next_attempt_at>?))""".trimIndent(),
                blockerArgs,
            ).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else Long.MAX_VALUE
            }

            val pendingCount = db.rawQuery(
                """SELECT COUNT(*) FROM conversation_events
                   WHERE thread_id=? AND event_id>? AND event_id<?
                     AND queue_state IN ('PENDING','RETRY_WAIT') AND next_attempt_at<=?""".trimIndent(),
                arrayOf(threadId, lowerBound.toString(), blockerId.toString(), now.toString()),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

            if (batchId.isBlank()) {
                if (pendingCount == 0) {
                    db.setTransactionSuccessful()
                    return emptyList()
                }
                batchId = UUID.randomUUID().toString()
                db.insertOrThrow("reply_batches", null, ContentValues().apply {
                    put("batch_id", batchId)
                    put("thread_id", threadId)
                    put("scene", scene)
                    put("state", "PROCESSING")
                    put("event_count", pendingCount)
                    put("lease_until", leaseUntil)
                    put("created_at", now)
                    put("updated_at", now)
                })
            }

            db.update(
                "conversation_events",
                ContentValues().apply {
                    put("queue_state", "PROCESSING")
                    put("batch_id", batchId)
                    put("lease_until", leaseUntil)
                },
                """thread_id=? AND event_id>? AND event_id<?
                   AND queue_state IN ('PENDING','RETRY_WAIT') AND next_attempt_at<=?""".trimIndent(),
                arrayOf(threadId, lowerBound.toString(), blockerId.toString(), now.toString()),
            )
            val count = db.rawQuery(
                "SELECT COUNT(*) FROM conversation_events WHERE batch_id=? AND queue_state='PROCESSING'",
                arrayOf(batchId),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
            if (count == 0) {
                db.setTransactionSuccessful()
                return emptyList()
            }
            db.update("reply_batches", ContentValues().apply {
                put("event_count", count)
                put("lease_until", leaseUntil)
                put("updated_at", now)
            }, "batch_id=?", arrayOf(batchId))
            db.update(
                "inbox_conversations",
                ContentValues().apply { put("state", "PROCESSING") },
                "thread_id=?",
                arrayOf(threadId),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return queryBatchEvents(batchId)
    }

    @Synchronized
    fun updateEvents(threadId: String, eventKeys: List<String>, state: MessageQueueState, error: String = "") {
        if (eventKeys.isEmpty()) return
        val allowed = setOf(MessageQueueState.SENT, MessageQueueState.SUPPRESSED_POLICY,
            MessageQueueState.HANDLED_MANUALLY, MessageQueueState.PENDING, MessageQueueState.RETRY_WAIT,
            MessageQueueState.SEND_UNCERTAIN, MessageQueueState.DEAD_LETTER,
            MessageQueueState.NO_REPLY_REQUIRED)
        require(state in allowed) { "Unsupported queue transition: $state" }
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val batchIds = linkedSetOf<String>()
        db.beginTransaction()
        try {
            eventKeys.distinct().forEach { key ->
                db.rawQuery("SELECT batch_id FROM conversation_events WHERE thread_id=? AND event_key=? LIMIT 1",
                    arrayOf(threadId, key)).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0).takeIf(String::isNotBlank)?.let(batchIds::add)
                }
                val values = ContentValues().apply {
                    put("queue_state", state.name); put("last_error", error.take(240))
                    put("lease_until", 0); put("updated_at", now)
                    if (state == MessageQueueState.PENDING || state == MessageQueueState.RETRY_WAIT) put("batch_id", "")
                }
                db.update("conversation_events", values, "thread_id=? AND event_key=?", arrayOf(threadId, key))
            }
            batchIds.forEach { id ->
                val remaining = db.rawQuery("SELECT COUNT(*) FROM conversation_events WHERE batch_id=? AND queue_state='PROCESSING'",
                    arrayOf(id)).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
                if (remaining == 0) db.update("reply_batches", ContentValues().apply {
                    put("state", state.name); put("updated_at", now); put("last_error", error.take(240))
                }, "batch_id=?", arrayOf(id))
            }
            val open = db.rawQuery(
                "SELECT COUNT(*) FROM conversation_events WHERE thread_id=? AND queue_state IN ('PENDING','PROCESSING','RETRY_WAIT','SEND_UNCERTAIN','DEAD_LETTER')",
                arrayOf(threadId),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
            db.update("inbox_conversations", ContentValues().apply {
                put("state", if (open > 0) "OPEN" else "DONE")
                put("last_detected_at", now)
            }, "thread_id=?", arrayOf(threadId))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Close queued inbound events that are followed by an outgoing message in the observed thread. */
    @Synchronized
    fun markManuallyHandledIfPending(threadId: String, eventKeys: List<String>) {
        if (eventKeys.isEmpty()) return
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            eventKeys.distinct().forEach { key ->
                db.update(
                    "conversation_events",
                    ContentValues().apply {
                        put("queue_state", "HANDLED_MANUALLY")
                        put("batch_id", "")
                        put("lease_until", 0)
                        put("updated_at", now)
                        put("last_error", "outgoing_message_observed_without_queue_ack")
                    },
                    "thread_id=? AND event_key=? AND queue_state IN ('PENDING','PROCESSING','RETRY_WAIT')",
                    arrayOf(threadId, key),
                )
            }
            db.update(
                "reply_batches",
                ContentValues().apply {
                    put("state", "HANDLED_MANUALLY")
                    put("updated_at", now)
                },
                "thread_id=? AND state='PROCESSING' AND NOT EXISTS " +
                    "(SELECT 1 FROM conversation_events e WHERE e.batch_id=reply_batches.batch_id AND e.queue_state='PROCESSING')",
                arrayOf(threadId),
            )
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized
    fun retryEvents(threadId: String, eventKeys: List<String>, error: String, maxAttempts: Int = 5) {
        if (eventKeys.isEmpty()) return
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            eventKeys.distinct().forEach { key ->
                val attempt = db.rawQuery("SELECT attempt_count FROM conversation_events WHERE thread_id=? AND event_key=? LIMIT 1",
                    arrayOf(threadId, key)).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) + 1 else 1 }
                val state = if (attempt > maxAttempts) "DEAD_LETTER" else "RETRY_WAIT"
                db.update("conversation_events", ContentValues().apply {
                    put("queue_state", state); put("attempt_count", attempt)
                    put("next_attempt_at", now + retryDelay(attempt)); put("lease_until", 0)
                    put("batch_id", ""); put("last_error", error.take(240))
                }, "thread_id=? AND event_key=?", arrayOf(threadId, key))
            }
            db.update("reply_batches", ContentValues().apply {
                put("state", "RETRY_WAIT"); put("updated_at", now); put("last_error", error.take(240))
            }, "thread_id=? AND state='PROCESSING'", arrayOf(threadId))
            db.update("inbox_conversations", ContentValues().apply {
                put("state", "OPEN"); put("last_error", error.take(240))
            }, "thread_id=?", arrayOf(threadId))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun markSendUncertain(threadId: String, eventKeys: List<String>, reason: String) =
        updateEvents(threadId, eventKeys, MessageQueueState.SEND_UNCERTAIN, reason)

    /** At worker startup there cannot be a live owner: recover all abandoned claims immediately. */
    @Synchronized
    fun recoverProcessingAtWorkerStart() {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            db.execSQL("UPDATE conversation_events SET queue_state='PENDING', batch_id='', lease_until=0, last_error='worker_restarted' WHERE queue_state='PROCESSING'")
            db.execSQL("UPDATE reply_batches SET state='INTERRUPTED', lease_until=0, updated_at=? WHERE state='PROCESSING'", arrayOf(now))
            db.execSQL("UPDATE inbox_conversations SET state='OPEN' WHERE state='PROCESSING'")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized
    fun recoverExpiredLeases() {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try { recoverExpiredLocked(db, now); db.setTransactionSuccessful() }
        finally { db.endTransaction() }
    }

    private fun recoverExpiredLocked(db: SQLiteDatabase, now: Long) {
        db.execSQL("UPDATE conversation_events SET queue_state='PENDING', batch_id='', lease_until=0, last_error='lease_expired' WHERE queue_state='PROCESSING' AND lease_until<=?", arrayOf(now))
        db.execSQL("UPDATE reply_batches SET state='INTERRUPTED', updated_at=? WHERE state='PROCESSING' AND lease_until<=?", arrayOf(now, now))
        db.execSQL("UPDATE inbox_conversations SET state='OPEN' WHERE state='PROCESSING'")
    }

    fun markConfirmedKeys(threadId: String, keys: Set<String>) {
        if (keys.isNotEmpty()) updateEvents(threadId, keys.toList(), MessageQueueState.SENT)
    }

    fun summaries(filter: InboxFilter, search: String = "", limit: Int = 100): List<InboxConversationSummary> {
        val db = writableDatabase
        excludeKnownPlatformNoticesLocked(db)
        val states = when (filter) {
            InboxFilter.OPEN -> listOf("PENDING", "PROCESSING", "RETRY_WAIT", "SEND_UNCERTAIN", "DEAD_LETTER")
            InboxFilter.SNOOZED -> listOf("SUPPRESSED_POLICY")
            InboxFilter.DONE -> listOf("SENT", "HANDLED_MANUALLY", "BASELINE", "NO_REPLY_REQUIRED")
        }
        val placeholders = states.joinToString(",") { "?" }
        val searchClause = if (search.isBlank()) "" else "AND (c.display_name LIKE ? OR e.content LIKE ?)"
        val args = states + (if (search.isBlank()) emptyList() else listOf("%" + search.trim() + "%", "%" + search.trim() + "%")) +
            states + listOf(limit.coerceIn(1, 500).toString())
        val rows = mutableListOf<InboxConversationSummary>()
        db.rawQuery(
            """SELECT e.thread_id, c.display_name, e.content, e.observed_at, e.queue_state,
                (SELECT COUNT(*) FROM conversation_events x WHERE x.thread_id=e.thread_id
                 AND x.queue_state IN ('PENDING','PROCESSING','RETRY_WAIT','SEND_UNCERTAIN','DEAD_LETTER'))
               FROM conversation_events e JOIN inbox_conversations c ON c.thread_id=e.thread_id
               WHERE e.queue_state IN ($placeholders) $searchClause
                 AND e.event_id=(SELECT MAX(y.event_id) FROM conversation_events y
                 WHERE y.thread_id=e.thread_id AND y.queue_state IN ($placeholders))
               ORDER BY e.observed_at DESC LIMIT ?""".trimIndent(),
            args.toTypedArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) rows += InboxConversationSummary(
                threadId = cursor.getString(0), displayName = cursor.getString(1),
                preview = cursor.getString(2), updatedAt = cursor.getLong(3),
                state = runCatching { MessageQueueState.valueOf(cursor.getString(4)) }.getOrDefault(MessageQueueState.PENDING),
                openCount = cursor.getInt(5),
            )
        }
        return rows
    }

    fun eventsForThread(threadId: String, limit: Int = 80): List<QueueMessageEvent> =
        readableDatabase.rawQuery(
            """SELECT event_key, content, queue_state, observed_at, attempt_count, last_error
               FROM conversation_events WHERE thread_id=? ORDER BY event_id DESC LIMIT ?""".trimIndent(),
            arrayOf(threadId, limit.coerceIn(1, 300).toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(
                    QueueMessageEvent(
                        key = cursor.getString(0),
                        text = cursor.getString(1),
                        state = runCatching { MessageQueueState.valueOf(cursor.getString(2)) }
                            .getOrDefault(MessageQueueState.PENDING),
                        observedAt = cursor.getLong(3),
                        attemptCount = cursor.getInt(4),
                        lastError = cursor.getString(5),
                    ),
                )
            }
        }

    /** Manual acknowledgement is only permitted for events not currently being processed. */
    @Synchronized
    fun resolveAsManual(threadId: String, eventKeys: List<String>) {
        if (eventKeys.isEmpty()) return
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            eventKeys.distinct().forEach { key ->
                db.update(
                    "conversation_events",
                    ContentValues().apply {
                        put("queue_state", MessageQueueState.HANDLED_MANUALLY.name)
                        put("lease_until", 0)
                        put("updated_at", now)
                        put("last_error", "manually_acknowledged_from_inbox")
                    },
                    "thread_id=? AND event_key=? AND queue_state IN ('PENDING','RETRY_WAIT','SEND_UNCERTAIN','DEAD_LETTER')",
                    arrayOf(threadId, key),
                )
            }
            db.update(
                "reply_batches",
                ContentValues().apply { put("state", "HANDLED_MANUALLY"); put("updated_at", now) },
                "thread_id=? AND state IN ('RETRY_WAIT','INTERRUPTED','SEND_UNCERTAIN','DEAD_LETTER')",
                arrayOf(threadId),
            )
            val open = db.rawQuery(
                "SELECT COUNT(*) FROM conversation_events WHERE thread_id=? AND queue_state IN ('PENDING','PROCESSING','RETRY_WAIT','SEND_UNCERTAIN','DEAD_LETTER')",
                arrayOf(threadId),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
            db.update(
                "inbox_conversations",
                ContentValues().apply { put("state", if (open > 0) "OPEN" else "DONE") },
                "thread_id=?",
                arrayOf(threadId),
            )
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized
    fun unsnooze(threadId: String, eventKeys: List<String>) {
        if (eventKeys.isEmpty()) return
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            eventKeys.distinct().forEach { key ->
                db.update(
                    "conversation_events",
                    ContentValues().apply {
                        put("queue_state", MessageQueueState.PENDING.name)
                        put("next_attempt_at", 0L)
                        put("batch_id", "")
                        put("lease_until", 0L)
                        put("updated_at", now)
                        put("last_error", "manually_unsnoozed_from_inbox")
                    },
                    "thread_id=? AND event_key=? AND queue_state='SUPPRESSED_POLICY'",
                    arrayOf(threadId, key),
                )
            }
            db.update(
                "inbox_conversations",
                ContentValues().apply { put("state", "OPEN"); put("last_detected_at", now) },
                "thread_id=?",
                arrayOf(threadId),
            )
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Explicit user-triggered retry; SEND_UNCERTAIN is retried only after the user confirms it was not sent. */
    @Synchronized
    fun retryNow(threadId: String, eventKeys: List<String>) {
        if (eventKeys.isEmpty()) return
        val db = writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            eventKeys.distinct().forEach { key ->
                db.update(
                    "conversation_events",
                    ContentValues().apply {
                        put("queue_state", MessageQueueState.PENDING.name)
                        put("next_attempt_at", 0L)
                        put("batch_id", "")
                        put("lease_until", 0L)
                        put("updated_at", now)
                        put("last_error", "manual_retry_from_inbox")
                    },
                    "thread_id=? AND event_key=? AND queue_state IN ('RETRY_WAIT','SEND_UNCERTAIN','DEAD_LETTER')",
                    arrayOf(threadId, key),
                )
            }
            db.update(
                "inbox_conversations",
                ContentValues().apply { put("state", "OPEN"); put("last_error", "") },
                "thread_id=?",
                arrayOf(threadId),
            )
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Head-of-line scheduling preserves message order across retries and uncertain sends. */
    fun readyConversations(limit: Int = 100): List<InboxConversationSummary> {
        val db = writableDatabase
        excludeKnownPlatformNoticesLocked(db)
        val now = System.currentTimeMillis()
        val rows = mutableListOf<InboxConversationSummary>()
        db.rawQuery(
            """SELECT e.thread_id, c.display_name, e.content, e.observed_at, e.queue_state,
                (SELECT COUNT(*) FROM conversation_events x WHERE x.thread_id=e.thread_id
                 AND x.queue_state IN ('PENDING','RETRY_WAIT') AND x.next_attempt_at<=?)
               FROM conversation_events e JOIN inbox_conversations c ON c.thread_id=e.thread_id
               WHERE e.queue_state IN ('PENDING','RETRY_WAIT') AND e.next_attempt_at<=?
                 AND e.event_id=(SELECT MIN(y.event_id) FROM conversation_events y
                   WHERE y.thread_id=e.thread_id
                     AND y.queue_state IN ('PENDING','PROCESSING','RETRY_WAIT','SEND_UNCERTAIN','DEAD_LETTER'))
               ORDER BY e.observed_at ASC LIMIT ?""".trimIndent(),
            arrayOf(now.toString(), now.toString(), limit.coerceIn(1, 500).toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) rows += InboxConversationSummary(
                threadId = cursor.getString(0),
                displayName = cursor.getString(1),
                preview = cursor.getString(2),
                updatedAt = cursor.getLong(3),
                state = runCatching { MessageQueueState.valueOf(cursor.getString(4)) }
                    .getOrDefault(MessageQueueState.PENDING),
                openCount = cursor.getInt(5),
            )
        }
        return rows.filterNot { ConversationRowPolicy.isPlatformNotice(it.displayName) }
    }

    fun counts(): Map<InboxFilter, Int> {
        val db = writableDatabase
        excludeKnownPlatformNoticesLocked(db)
        return mapOf(
        InboxFilter.OPEN to countThreads("PENDING", "PROCESSING", "RETRY_WAIT", "SEND_UNCERTAIN", "DEAD_LETTER"),
        InboxFilter.SNOOZED to countThreads("SUPPRESSED_POLICY"),
        InboxFilter.DONE to countThreads("SENT", "HANDLED_MANUALLY", "BASELINE", "NO_REPLY_REQUIRED"),
        )
    }

    private fun excludeKnownPlatformNoticesLocked(db: SQLiteDatabase) {
        val notices = mutableListOf<Pair<String, String>>()
        db.rawQuery("SELECT thread_id, display_name FROM inbox_conversations", null).use { cursor ->
            while (cursor.moveToNext()) {
                val threadId = cursor.getString(0)
                val name = cursor.getString(1)
                if (ConversationRowPolicy.isPlatformNotice(name)) notices += threadId to name
            }
        }
        if (notices.isEmpty()) return
        val now = System.currentTimeMillis()
        notices.forEach { (threadId, name) ->
            db.update(
                "conversation_events",
                ContentValues().apply {
                    put("queue_state", MessageQueueState.NO_REPLY_REQUIRED.name)
                    put("batch_id", "")
                    put("lease_until", 0L)
                    put("updated_at", now)
                    put("last_error", "system_notice_excluded")
                },
                "thread_id=? AND queue_state IN ('PENDING','PROCESSING','RETRY_WAIT','DEAD_LETTER','SUPPRESSED_POLICY')",
                arrayOf(threadId),
            )
            db.update(
                "reply_batches",
                ContentValues().apply {
                    put("state", MessageQueueState.NO_REPLY_REQUIRED.name)
                    put("lease_until", 0L)
                    put("updated_at", now)
                    put("last_error", "system_notice_excluded")
                },
                "thread_id=? AND state IN ('PROCESSING','RETRY_WAIT','INTERRUPTED','DEAD_LETTER')",
                arrayOf(threadId),
            )
            db.update(
                "inbox_conversations",
                ContentValues().apply {
                    put("state", "DONE")
                    put("last_error", "system_notice_excluded")
                    put("last_detected_at", now)
                },
                "thread_id=?",
                arrayOf(threadId),
            )
        }
    }

    private fun countThreads(vararg states: String): Int {
        val placeholders = states.joinToString(",") { "?" }
        return readableDatabase.rawQuery(
            "SELECT COUNT(DISTINCT thread_id) FROM conversation_events WHERE queue_state IN ($placeholders)",
            states,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
    }

    private fun queryBatchEvents(batchId: String): List<QueueMessageEvent> =
        readableDatabase.rawQuery(
            "SELECT event_key, content, queue_state, observed_at, attempt_count, last_error " +
                "FROM conversation_events WHERE batch_id=? ORDER BY event_id",
            arrayOf(batchId),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(
                    QueueMessageEvent(
                        key = cursor.getString(0),
                        text = cursor.getString(1),
                        state = runCatching { MessageQueueState.valueOf(cursor.getString(2)) }
                            .getOrDefault(MessageQueueState.PROCESSING),
                        observedAt = cursor.getLong(3),
                        attemptCount = cursor.getInt(4),
                        lastError = cursor.getString(5),
                    ),
                )
            }
        }

    private fun retryDelay(attempt: Int): Long = MessageQueuePolicy.retryDelay(attempt)
    companion object { private const val DEFAULT_LEASE_MS = 5 * 60_000L }
}
