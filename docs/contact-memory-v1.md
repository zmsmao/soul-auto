# Contact Memory v1

## Scope

This version adds an evidence-backed contact memory store and a best-effort background extraction queue. It intentionally does not replace or inject the legacy contact-memory fragments into Writer. Existing reply prompts, Analyzer/Writer/Reviewer selection, pacing, relationship strategy, and model routing remain unchanged.

## Storage

```text
contact_memory.db
├── contact_memory_facts
├── contact_memory_evidence
└── contact_memory_sources
```

- Facts are scoped by the existing stable `thread_id`.
- Evidence points to an exact `conversation_messages.event_key`, with scene, timestamp and a source quote.
- The old `chat_learning.db/contact_memories` table is left intact and is shown separately as unverified legacy clues.
- No automatic migration is performed: legacy snippets have no reliable message-level provenance.
- The independent database avoids bumping or destructively migrating the two existing databases.

## Categories and lifecycle

- `FACT`: explicit, durable information.
- `PREFERENCE`: explicitly stated interests, preferences and boundaries.
- `STATE`: temporary state; defaults to a 30-day expiry when the extractor omits one, capped at 180 days.
- `EXPERIENCE`: a clearly stated event or experience.

Statuses: `ACTIVE`, `PENDING`, `CONFLICTED`, `SUPERSEDED`, `REJECTED`, `EXPIRED`.

Only explicit evidence can create an active candidate. Uncertain candidates stay pending. Different values under the same category/key are marked conflicted rather than guessed as an update. A user confirmation resolves the chosen value and supersedes other active/conflicted values for that same key. Manual correction is marked `USER_CONFIRMED`.

## Extraction

- Extraction is off by default; the user must opt in from the contact detail page.
- Only incoming messages are queued. Outgoing bot text is never an evidence source.
- Messages are batched by contact and scene, with up to 12 messages per request and at most two batches per wake.
- The first enabled, valid configured endpoint is called directly with a 20-second timeout. This avoids changing ModelRouter health/cooldown state for background memory calls.
- Each source message is deduplicated by event key, retried up to three times, and recovered from `PROCESSING` after process restart.
- The returned quote must be an exact substring of an incoming source message and its event key must belong to the submitted batch. Invalid evidence is discarded.
- Completed and permanently failed source payloads are blanked; queue metadata is pruned after 60 days.
- If no runnable endpoint is configured, the batch is returned to pending without consuming retries.
- Extraction calls may consume provider API quota. The UI states this before the user enables it.

## Privacy boundaries

The extractor is instructed not to infer personality, psychology or relationship intent, and not to retain health, religion, political position, sexual history, precise address, financial information, identity credentials or account details. Message contents are supplied as untrusted data, not executable instructions.

## Management and backup

The contact page supports viewing exact evidence, confirming, correcting, rejecting and deleting individual facts. The data/backup page has a separate JSON export/import for structured memories and their evidence. Its existing full-clear operation now clears structured facts, pending extraction sources and legacy contact snippets as well as conversation history. It continues to preserve the send-attempt ledger.

## Deliberate non-goals in v1

- No new memory injection into Writer or Reviewer.
- No automatic personality, relationship or psychological profile.
- No speculative confidence score.
- No vector database.
- No automatic identity merging if a nickname-derived fallback identity changes.
- No automatic migration from legacy snippets.

The next behavior-changing phase must compare retrieval quality and approve the injection rules separately.
