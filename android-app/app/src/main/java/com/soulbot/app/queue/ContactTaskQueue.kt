package com.soulbot.app.queue

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.soulbot.app.conversation.ConversationRowPolicy

internal enum class ContactTaskState {
    PENDING, PROCESSING, RETRY_WAIT, VERIFY_SEND, DONE
}

internal enum class ContactTaskOutcome {
    SENT, NO_REPLY, MANUAL
}

internal data class ContactTask(
    val contactKey: String,
    val displayName: String,
    val memoryThreadId: String,
    val queueOrder: Long,
    val state: ContactTaskState,
    val outcome: ContactTaskOutcome?,
    val attemptCount: Int,
    val nextAttemptAt: Long,
    val leaseUntil: Long,
    val unreadCleared: Boolean,
    val lastError: String,
    val createdAt: Long,
    val updatedAt: Long,
)

internal data class ContactTaskSummary(
    val contactKey: String,
    val displayName: String,
    val memoryThreadId: String,
    val state: ContactTaskState,
    val outcome: ContactTaskOutcome?,
    val attemptCount: Int,
    val lastError: String,
    val updatedAt: Long,
    val queueOrder: Long,
)

/**
 * Durable FIFO queue for ordinary private-chat contacts.
 *
 * The queue stores one row per contact and never stores message bodies, previews,
 * message timestamps, or message-level event keys. Existing message-event records
 * are left intact for non-standard conversation workflows and send reconciliation.
 */
internal class ContactTaskQueue(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {
    private val appContext = context.applicationContext

    @Volatile private var legacyMigrationChecked = false

    init {
        writableDatabase
        migrateLegacyPrivateContacts()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE contact_tasks (
                task_id INTEGER PRIMARY KEY AUTOINCREMENT,
                contact_key TEXT NOT NULL UNIQUE,
                display_name TEXT NOT NULL,
                memory_thread_id TEXT NOT NULL DEFAULT '',
                queue_order INTEGER NOT NULL,
                state TEXT NOT NULL,
                outcome TEXT NOT NULL DEFAULT '',
                attempt_count INTEGER NOT NULL DEFAULT 0,
                next_attempt_at INTEGER NOT NULL DEFAULT 0,
                lease_until INTEGER NOT NULL DEFAULT 0,
                unread_cleared INTEGER NOT NULL DEFAULT 0,
                last_error TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_contact_task_queue ON contact_tasks(state, queue_order)")
        db.execSQL("CREATE TABLE queue_meta (meta_key TEXT PRIMARY KEY, meta_value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /** Migrate only contacts with an outstanding ordinary-chat event; message text is never copied. */
    @Synchronized
    private fun migrateLegacyPrivateContacts() {
        if (legacyMigrationChecked) return
        val db = writableDatabase
        val alreadyDone = db.rawQuery(
            "SELECT 1 FROM queue_meta WHERE meta_key='legacy_private_contacts_v1' LIMIT 1",
            null,
        ).use { it.moveToFirst() }
        if (alreadyDone) {
            legacyMigrationChecked = true
            return
        }

        val legacyFile = appContext.getDatabasePath(LEGACY_DB_NAME)
        if (legacyFile.exists()) {
            runCatching {
                SQLiteDatabase.openDatabase(
                    legacyFile.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                ).use { legacy ->
                    val migrated = mutableListOf<Pair<Triple<String, String, Long>, String>>()
                    val uncertain = mutableSetOf<String>()
                    legacy.rawQuery(
                        """SELECT c.contact_id, c.display_name, c.first_detected_at, c.thread_id
                           FROM inbox_conversations c
                           WHERE c.scene='CHAT'
                             AND EXISTS (
                               SELECT 1 FROM conversation_events e
                               WHERE e.thread_id=c.thread_id
                                 AND e.queue_state IN ('PENDING','PROCESSING','RETRY_WAIT','SEND_UNCERTAIN','DEAD_LETTER')
                             )
                           ORDER BY c.first_detected_at ASC""".trimIndent(),
                        null,
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            val contactId = cursor.getString(0).orEmpty().ifBlank { cursor.getString(3).orEmpty() }
                            val name = cursor.getString(1).orEmpty()
                            if (contactId.isBlank() || name.isBlank() || ConversationRowPolicy.isPlatformNotice(name)) continue
                            val threadId = cursor.getString(3).orEmpty()
                            migrated += Triple(contactId, name, cursor.getLong(2)) to threadId
                            val hasUncertain = legacy.rawQuery(
                                "SELECT 1 FROM conversation_events WHERE thread_id=? AND queue_state='SEND_UNCERTAIN' LIMIT 1",
                                arrayOf(threadId),
                            ).use { it.moveToFirst() }
                            if (hasUncertain) uncertain += contactId
                        }
                    }
                    db.beginTransaction()
                    try {
                        var order = nextQueueOrderLocked(db)
                        migrated.forEach { (legacyRow, memoryThreadId) ->
                            val (key, name, detectedAt) = legacyRow
                            val state = if (key in uncertain) ContactTaskState.VERIFY_SEND else ContactTaskState.PENDING
                            db.insertWithOnConflict(
                                "contact_tasks",
                                null,
                                ContentValues().apply {
                                    put("contact_key", key)
                                    put("display_name", name)
                                    put("memory_thread_id", memoryThreadId)
                                    put("queue_order", order++)
                                    put("state", state.name)
                                    put("created_at", detectedAt)
                                    put("updated_at", System.currentTimeMillis())
                                    put("last_error", "migrated_from_2_1_0")
                                },
                                SQLiteDatabase.CONFLICT_IGNORE,
                            )
                        }
                        db.setTransactionSuccessful()
                    } finally {
                        db.endTransaction()
                    }
                }
            }
        }
        db.insertWithOnConflict(
            "queue_meta",
            null,
            ContentValues().apply {
                put("meta_key", "legacy_private_contacts_v1")
                put("meta_value", System.currentTimeMillis().toString())
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
        legacyMigrationChecked = true
    }

    /** Enqueue a newly observed unread contact once; existing active tasks keep their FIFO position. */
    @Synchronized
    fun enqueueUnread(contactKey: String, displayName: String): Boolean {
        val key = contactKey.trim()
        val name = displayName.trim()
        if (key.isBlank() || name.isBlank() || ConversationRowPolicy.isPlatformNotice(name)) return false
        val db = writableDatabase
        val now = System.currentTimeMillis()
        var inserted = false
        db.beginTransaction()
        try {
            val existing = readTaskLocked(db, key)
            when {
                existing == null -> {
                    val order = nextQueueOrderLocked(db)
                    inserted = db.insertOrThrow(
                        "contact_tasks",
                        null,
                        ContentValues().apply {
                            put("contact_key", key)
                            put("display_name", name)
                            put("queue_order", order)
                            put("state", ContactTaskState.PENDING.name)
                            put("created_at", now)
                            put("updated_at", now)
                        },
                    ) > 0
                }
                ContactTaskPolicy.shouldEnqueueAgain(existing.state, existing.unreadCleared) -> {
                    val order = nextQueueOrderLocked(db)
                    db.update(
                        "contact_tasks",
                        ContentValues().apply {
                            put("display_name", name)
                            put("queue_order", order)
                            put("state", ContactTaskState.PENDING.name)
                            put("outcome", "")
                            put("attempt_count", 0)
                            put("next_attempt_at", 0L)
                            put("lease_until", 0L)
                            put("unread_cleared", 0)
                            put("last_error", "")
                            put("created_at", now)
                            put("updated_at", now)
                        },
                        "contact_key=?",
                        arrayOf(key),
                    )
                    inserted = true
                }
                else -> {
                    db.update(
                        "contact_tasks",
                        ContentValues().apply {
                            put("display_name", name)
                            put("updated_at", now)
                        },
                        "contact_key=?",
                        arrayOf(key),
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return inserted
    }

    @Synchronized
    fun attachMemoryThreadId(contactKey: String, memoryThreadId: String) {
        if (contactKey.isBlank() || memoryThreadId.isBlank()) return
        writableDatabase.update(
            "contact_tasks",
            ContentValues().apply {
                put("memory_thread_id", memoryThreadId)
                put("updated_at", System.currentTimeMillis())
            },
            "contact_key=?",
            arrayOf(contactKey),
        )
    }

    @Synchronized
    fun markOpened(contactKey: String) {
        if (contactKey.isBlank()) return
        writableDatabase.update(
            "contact_tasks",
            ContentValues().apply { put("unread_cleared", 1); put("updated_at", System.currentTimeMillis()) },
            "contact_key=? AND state='PROCESSING'",
            arrayOf(contactKey),
        )
    }

    /** Release the per-cycle duplicate guard only after Soul no longer shows an unread badge. */
    @Synchronized
    fun markUnreadCleared(contactKey: String) {
        if (contactKey.isBlank()) return
        writableDatabase.update(
            "contact_tasks",
            ContentValues().apply { put("unread_cleared", 1); put("updated_at", System.currentTimeMillis()) },
            "contact_key=? AND state='DONE'",
            arrayOf(contactKey),
        )
    }

    /** The oldest active contact blocks later contacts, including while it waits or needs send verification. */
    @Synchronized
    fun peekHead(): ContactTask? {
        val db = readableDatabase
        return db.rawQuery(
            """SELECT contact_key, display_name, memory_thread_id, queue_order, state, outcome, attempt_count,
                      next_attempt_at, lease_until, unread_cleared, last_error, created_at, updated_at
               FROM contact_tasks
               WHERE state IN ('PENDING','PROCESSING','RETRY_WAIT','VERIFY_SEND')
               ORDER BY queue_order ASC LIMIT 1""".trimIndent(),
            null,
        ).use { if (it.moveToFirst()) it.toTask() else null }
    }

    /** Atomically claim only the FIFO head; a delayed or uncertain head blocks later contacts. */
    @Synchronized
    fun claimHead(leaseMillis: Long = DEFAULT_LEASE_MS): ContactTask? {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        var claimedKey: String? = null
        db.beginTransaction()
        try {
            val head = db.rawQuery(
                """SELECT contact_key, state, next_attempt_at
                   FROM contact_tasks
                   WHERE state IN ('PENDING','PROCESSING','RETRY_WAIT','VERIFY_SEND')
                   ORDER BY queue_order ASC LIMIT 1""".trimIndent(),
                null,
            ).use { cursor ->
                if (!cursor.moveToFirst()) null
                else Triple(cursor.getString(0), cursor.getString(1), cursor.getLong(2))
            }
            if (head == null ||
                !ContactTaskPolicy.canClaim(
                    state = runCatching { ContactTaskState.valueOf(head.second) }.getOrDefault(ContactTaskState.DONE),
                    nextAttemptAt = head.third,
                    now = now,
                )
            ) {
                db.setTransactionSuccessful()
                return null
            }
            val updated = db.update(
                "contact_tasks",
                ContentValues().apply {
                    put("state", ContactTaskState.PROCESSING.name)
                    put("lease_until", now + leaseMillis.coerceAtLeast(60_000L))
                    put("updated_at", now)
                },
                "contact_key=? AND state IN ('PENDING','RETRY_WAIT')",
                arrayOf(head.first),
            )
            if (updated > 0) {
                db.execSQL(
                    "UPDATE contact_tasks SET attempt_count=attempt_count+1 WHERE contact_key=?",
                    arrayOf(head.first),
                )
                claimedKey = head.first
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return claimedKey?.let(::getTask)
    }

    @Synchronized
    fun complete(contactKey: String, outcome: ContactTaskOutcome) {
        if (contactKey.isBlank()) return
        writableDatabase.update(
            "contact_tasks",
            ContentValues().apply {
                put("state", ContactTaskState.DONE.name)
                put("outcome", outcome.name)
                put("lease_until", 0L)
                put("next_attempt_at", 0L)
                put("last_error", "")
                put("updated_at", System.currentTimeMillis())
            },
            "contact_key=? AND state IN ('PROCESSING','RETRY_WAIT')",
            arrayOf(contactKey),
        )
    }

    @Synchronized
    fun retry(contactKey: String, reason: String) {
        if (contactKey.isBlank()) return
        val db = writableDatabase
        val attempts = db.rawQuery(
            "SELECT attempt_count FROM contact_tasks WHERE contact_key=? LIMIT 1",
            arrayOf(contactKey),
        ).use { if (it.moveToFirst()) it.getInt(0) else 1 }
        val delay = ContactTaskPolicy.retryDelay(attempts)
        db.update(
            "contact_tasks",
            ContentValues().apply {
                put("state", ContactTaskState.RETRY_WAIT.name)
                put("next_attempt_at", System.currentTimeMillis() + delay)
                put("lease_until", 0L)
                put("last_error", reason.take(240))
                put("updated_at", System.currentTimeMillis())
            },
            "contact_key=? AND state='PROCESSING'",
            arrayOf(contactKey),
        )
    }

    @Synchronized
    fun markSendUncertain(contactKey: String, reason: String) {
        if (contactKey.isBlank()) return
        writableDatabase.update(
            "contact_tasks",
            ContentValues().apply {
                put("state", ContactTaskState.VERIFY_SEND.name)
                put("lease_until", 0L)
                put("last_error", reason.take(240))
                put("updated_at", System.currentTimeMillis())
            },
            "contact_key=? AND state IN ('PROCESSING','RETRY_WAIT','PENDING')",
            arrayOf(contactKey),
        )
    }

    @Synchronized
    fun confirmSent(contactKey: String) {
        completeRegardlessOfState(contactKey, ContactTaskOutcome.SENT)
    }

    @Synchronized
    fun markManuallyDone(contactKey: String) {
        completeRegardlessOfState(contactKey, ContactTaskOutcome.MANUAL)
    }

    @Synchronized
    fun retryNow(contactKey: String) {
        if (contactKey.isBlank()) return
        writableDatabase.update(
            "contact_tasks",
            ContentValues().apply {
                put("state", ContactTaskState.PENDING.name)
                put("next_attempt_at", 0L)
                put("lease_until", 0L)
                put("last_error", "manual_retry_from_dashboard")
                put("updated_at", System.currentTimeMillis())
            },
            "contact_key=? AND state IN ('RETRY_WAIT','PENDING')",
            arrayOf(contactKey),
        )
    }

    @Synchronized
    fun retryAfterSendConfirmedNotSent(contactKey: String) {
        if (contactKey.isBlank()) return
        writableDatabase.update(
            "contact_tasks",
            ContentValues().apply {
                put("state", ContactTaskState.PENDING.name)
                put("outcome", "")
                put("next_attempt_at", 0L)
                put("lease_until", 0L)
                put("last_error", "user_confirmed_not_sent")
                put("updated_at", System.currentTimeMillis())
            },
            "contact_key=? AND state='VERIFY_SEND'",
            arrayOf(contactKey),
        )
    }

    private fun completeRegardlessOfState(contactKey: String, outcome: ContactTaskOutcome) {
        if (contactKey.isBlank()) return
        writableDatabase.update(
            "contact_tasks",
            ContentValues().apply {
                put("state", ContactTaskState.DONE.name)
                put("outcome", outcome.name)
                put("lease_until", 0L)
                put("next_attempt_at", 0L)
                put("last_error", "")
                put("updated_at", System.currentTimeMillis())
            },
            "contact_key=? AND state IN ('PENDING','PROCESSING','RETRY_WAIT','VERIFY_SEND')",
            arrayOf(contactKey),
        )
    }

    @Synchronized
    fun getTask(contactKey: String): ContactTask? =
        readableDatabase.rawQuery(
            """SELECT contact_key, display_name, memory_thread_id, queue_order, state, outcome, attempt_count,
                      next_attempt_at, lease_until, unread_cleared, last_error, created_at, updated_at
               FROM contact_tasks WHERE contact_key=? LIMIT 1""".trimIndent(),
            arrayOf(contactKey),
        ).use { if (it.moveToFirst()) it.toTask() else null }

    @Synchronized
    fun summaries(filter: InboxFilter, search: String = "", limit: Int = 100): List<ContactTaskSummary> {
        val states = when (filter) {
            InboxFilter.OPEN -> listOf("PENDING", "PROCESSING", "RETRY_WAIT", "VERIFY_SEND")
            InboxFilter.SNOOZED -> emptyList()
            InboxFilter.DONE -> listOf("DONE")
        }
        if (states.isEmpty()) return emptyList()
        val placeholders = states.joinToString(",") { "?" }
        val searchClause = if (search.isBlank()) "" else "AND display_name LIKE ?"
        val args = states + (if (search.isBlank()) emptyList() else listOf("%" + search.trim() + "%")) +
            listOf(limit.coerceIn(1, 500).toString())
        return readableDatabase.rawQuery(
            """SELECT contact_key, display_name, memory_thread_id, state, outcome, attempt_count, last_error, updated_at, queue_order
               FROM contact_tasks WHERE state IN ($placeholders) $searchClause
               ORDER BY queue_order ASC LIMIT ?""".trimIndent(),
            args.toTypedArray(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(
                    ContactTaskSummary(
                        contactKey = cursor.getString(0),
                        displayName = cursor.getString(1),
                        memoryThreadId = cursor.getString(2),
                        state = runCatching { ContactTaskState.valueOf(cursor.getString(3)) }
                            .getOrDefault(ContactTaskState.PENDING),
                        outcome = cursor.getString(4).takeIf(String::isNotBlank)
                            ?.let { runCatching { ContactTaskOutcome.valueOf(it) }.getOrNull() },
                        attemptCount = cursor.getInt(5),
                        lastError = cursor.getString(6),
                        updatedAt = cursor.getLong(7),
                        queueOrder = cursor.getLong(8),
                    ),
                )
            }
        }
    }

    @Synchronized
    fun counts(): Map<InboxFilter, Int> {
        fun count(states: List<String>): Int {
            if (states.isEmpty()) return 0
            val placeholders = states.joinToString(",") { "?" }
            return readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM contact_tasks WHERE state IN ($placeholders)",
                states.toTypedArray(),
            ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        }
        return mapOf(
            InboxFilter.OPEN to count(listOf("PENDING", "PROCESSING", "RETRY_WAIT", "VERIFY_SEND")),
            InboxFilter.SNOOZED to 0,
            InboxFilter.DONE to count(listOf("DONE")),
        )
    }

    @Synchronized
    fun recoverProcessingAtWorkerStart() {
        writableDatabase.execSQL(
            """UPDATE contact_tasks
               SET state='PENDING', lease_until=0, last_error='worker_restarted', updated_at=?
               WHERE state='PROCESSING'""".trimIndent(),
            arrayOf(System.currentTimeMillis()),
        )
    }

    @Synchronized
    fun recoverExpiredLeases() {
        val now = System.currentTimeMillis()
        writableDatabase.execSQL(
            """UPDATE contact_tasks
               SET state='RETRY_WAIT', next_attempt_at=?, lease_until=0,
                   last_error='processing_lease_expired', updated_at=?
               WHERE state='PROCESSING' AND lease_until<=?""".trimIndent(),
            arrayOf(now + 3_000L, now, now),
        )
    }

    private fun readTaskLocked(db: SQLiteDatabase, key: String): ContactTask? =
        db.rawQuery(
            """SELECT contact_key, display_name, memory_thread_id, queue_order, state, outcome, attempt_count,
                      next_attempt_at, lease_until, unread_cleared, last_error, created_at, updated_at
               FROM contact_tasks WHERE contact_key=? LIMIT 1""".trimIndent(),
            arrayOf(key),
        ).use { if (it.moveToFirst()) it.toTask() else null }

    private fun nextQueueOrderLocked(db: SQLiteDatabase): Long =
        db.rawQuery("SELECT COALESCE(MAX(queue_order), 0)+1 FROM contact_tasks", null)
            .use { if (it.moveToFirst()) it.getLong(0) else 1L }

    private fun android.database.Cursor.toTask(): ContactTask = ContactTask(
        contactKey = getString(0),
        displayName = getString(1),
        memoryThreadId = getString(2),
        queueOrder = getLong(3),
        state = runCatching { ContactTaskState.valueOf(getString(4)) }.getOrDefault(ContactTaskState.PENDING),
        outcome = getString(5).takeIf(String::isNotBlank)
            ?.let { runCatching { ContactTaskOutcome.valueOf(it) }.getOrNull() },
        attemptCount = getInt(6),
        nextAttemptAt = getLong(7),
        leaseUntil = getLong(8),
        unreadCleared = getInt(9) != 0,
        lastError = getString(10),
        createdAt = getLong(11),
        updatedAt = getLong(12),
    )

    companion object {
        private const val DB_NAME = "contact_task_queue.db"
        private const val DB_VERSION = 1
        private const val LEGACY_DB_NAME = "message_delivery_queue.db"
        private const val DEFAULT_LEASE_MS = 30L * 60L * 1000L
    }
}

internal object ContactTaskPolicy {
    fun shouldEnqueueAgain(state: ContactTaskState, unreadCleared: Boolean): Boolean =
        state == ContactTaskState.DONE && unreadCleared

    fun canClaim(state: ContactTaskState, nextAttemptAt: Long, now: Long): Boolean =
        state == ContactTaskState.PENDING ||
            (state == ContactTaskState.RETRY_WAIT && nextAttemptAt <= now)

    fun shouldResumeCurrentChat(state: ContactTaskState, sameContact: Boolean): Boolean =
        sameContact && state in setOf(
            ContactTaskState.PENDING,
            ContactTaskState.PROCESSING,
            ContactTaskState.RETRY_WAIT,
        )

    fun retryDelay(attempt: Int): Long = when (attempt.coerceAtLeast(1)) {
        1 -> 3_000L
        2 -> 10_000L
        3 -> 30_000L
        4 -> 120_000L
        else -> 300_000L
    }

    fun isTerminal(state: ContactTaskState): Boolean = state == ContactTaskState.DONE
}
