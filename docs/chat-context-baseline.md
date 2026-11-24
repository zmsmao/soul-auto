# Chat context safety baseline

## Audited source baseline

- Pull request: `#1` (kept open; no merge performed)
- Audited head before this refactor: `defdeb20e0ff363e40af39f908da4ed476544eb9`
- Scope: Android accessibility event -> workflow routing -> conversation context -> model pipeline.
- This refactor does not alter `QingshengSoulPrompt`, `ReplyPipelinePolicy`, `ReplyRhythmPolicy`, `ConversationReplyContext`, or the local reply rejection/validation rules.

## Runtime call chain

1. `SoulBotService` owns the Android AccessibilityService lifecycle, currently visible Soul page, active conversation, and the live database handles.
2. `SoulWorkflowHostFactory` constructs the private-chat, match, greeting, square, and Qiyu workflows. Those workflows call back into `SoulBotService` for reading threads, stable conversation identity, generation, sending, and post-send recording.
3. `SoulServiceComponentFactory` wires `SoulConversationContextManager` and `SoulReplyGenerationEngine` to the service-owned databases and accessibility operations.
4. `SoulConversationContextManager` resolves the conversation identity, synchronizes visible messages, updates persisted conversation state, and records manual/automated interaction learning.
5. `SoulReplyGenerationEngine.generateValidatedChatReplyInternal` applies the existing `ReplyPipelinePolicy` depth selection. Depending on that unchanged decision it runs Analyzer, Writer, and Reviewer; Writer retains the existing maximum of two attempts, local validation, and one targeted correction. The overall deadline and per-stage budget policy remain unchanged.
6. The Writer system prompt is assembled from `ModelClient.buildSystemPrompt`, the current-turn instruction, personalization, repeated-reply avoidance, focus/supporting context, optional correction and analysis summary, `ConversationReplyContext.responseGuidance`, and persisted `ConversationStatePolicy.promptHint`. The Reviewer continues to use `ReplyPipelinePolicy.reviewInstructions`.
7. `ModelRouter` selects/routs the configured endpoint and sends the assembled system prompt plus transcript to the selected compatible chat-completions endpoint.

## Prompt invariants

The baseline prompt is the exact content returned by:

- `ModelClient.buildSystemPrompt(profile, historicalReplay)`
- `SelfProfile.promptContext()`
- `QingshengSoulPrompt.guidance()`
- `ReplyPreferencePrompt.build()`
- `ConversationReplyContext.responseGuidance()`
- `ConversationStatePolicy.promptHint()`

The prompt source text and strategy rules are kept intact. The only persona default correction is that a fresh install has no pre-filled gender or other personal facts; profile completion remains optional. A blank profile contributes no identity facts.

## Conversation identity audit

- When a native Soul user ID is visible, `ConversationIdentity.threadId` hashes it into a `soul-user:<sha256>` scope. The raw platform ID is not stored in the conversation key.
- When the native ID is unavailable, `DiagnosticIdentity.contactId` provides a per-install salted nickname hash. This separates same-name strings only as far as the visible nickname allows; it cannot reliably recognize a renamed user without a native ID.
- Conversation messages and conversation state are scoped by `thread_id` and, for state/history retrieval, by `scene`.
- Contact memory and automated-reply deduplication previously used the display nickname. Runtime reads/writes now use the resolved thread ID instead.
- Contact-specific negative feedback now carries a separate `contact_id`. Free-text feedback is attached to a contact only when the typed display name resolves to exactly one thread ID; ambiguous names are not guessed.
- Quality-event labels remain display-oriented diagnostics and are not used as a source of contact facts.

## Database and legacy-data decision

The audited version used:

- `conversation_memory.db`: `conversation_messages`, `conversation_state`, and `send_attempts`.
- `chat_learning.db`: `style_samples`, `contact_memories(thread_id)`, `automated_replies(thread_id)`, `reply_feedback(contact_id)`, `reply_quality_events`, and sent-opening patterns.
- Separate history/replay databases for display, export, and controlled evaluation.

The old runtime seeded the new conversation memory from legacy history during service startup and from the dashboard. Those implicit imports have been removed. This branch does not migrate old nickname-keyed contact memories or automated-reply records; the learning DB is advanced to a schema with explicit thread_id columns and those old tables are dropped/recreated. Contact-scoped feedback has a separate contact_id and old feedback rows are discarded rather than mis-associated. A clean reinstall is the expected reset path.

## Context Builder rules

- `ModelClient.buildSystemPrompt` still produces the same complete prompt string. At runtime it exposes a composable prefix and shared-rule suffix; `PersonaContextProvider` inserts the same `SelfProfile.promptContext()` between them, then adds learned style profile/preferences as separate fragments.
- A single `ConversationContextBuilder` assembles Writer, Reviewer, and opening prompts from ordered, independently budgeted fragments. Persona identity, shared dialogue rules, current-turn instructions, and review instructions are required fragments; optional style examples, contact memories, state hints, and older history are selected by a shared priority queue.
- Recent history still starts from the existing 20-message window. The latest six messages are retained as mandatory context; older messages are budgeted by recency.
- Required instructions, the current focus, supporting transcript, correction, analysis summary, response guidance, and review instructions are never silently removed.
- Optional contact facts have high priority, followed by persisted state, the most recent optional transcript turns, persona-style profile/preferences, relevant older excerpts, and style examples. Fragment and history candidates compete in the same priority queue; final prompt order remains independent of selection order.
- The estimated input budget is 10,000 tokens with 1,200 reserved for output. The estimator is intentionally provider-neutral and conservative for Chinese; it is an estimate, not an exact tokenizer for every endpoint.
- If required context alone exceeds the estimate budget, it is kept and overflow is logged. Optional context/history is the part eligible for eviction.

## UI scope

The app navigation is now organized around Run, Contacts, Learning, and Settings. Contact detail exposes the current per-contact memory snippets, chat history, and persisted state. It does not display speculative relationship scores or claim that raw excerpts are already verified personality facts.
