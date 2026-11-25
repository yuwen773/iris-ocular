# Flyway migration-history plan

`V8__add_mcp_server_description.sql` is made idempotent so a database that was
manually aligned with the entity can complete the migration chain. This changes
the V8 checksum.

Before deploying this source to an existing database whose `flyway_schema_history`
already records V8, take a backup and run `flyway repair` during a maintenance
window. Do not edit V1--V7 or delete history rows. New and partially migrated
databases can run `migrate` directly; the pre-migration callback supplies only
the legacy tables required by V1, V5, and V8 on an empty schema.

Validation matrix:

1. Empty database: callback + V1--V9.
2. Pre-V8 legacy snapshot: migrate directly.
3. Existing V8 history: backup, `repair`, then migrate V9.
4. Manually aligned `description` column: migrate directly; V8 performs no-op.

## V15 / V16 (2026-11-10)

Two new migrations, no edits to V1--V9:

- **V15** owns the four legacy tables (`t_user`, `t_news`, `t_agent_session`,
  `t_knowledge_document`) that entities reference but the migration chain never
  created. Previously they came from the now-deleted `db/schema.sql` or the
  `release/v1.0.0`-only `deploy/mysql/init/01_legacy_schema.sql`. All statements
  use `CREATE TABLE IF NOT EXISTS`, so existing databases are unaffected.
- **V16** normalizes `t_diagnosis.weightSha256` to `VARCHAR(64)` (matching
  `t_inference_cache`) and adds `t_diagnosis.version` for optimistic locking.

Numbers start at 15 because `release/v1.0.0`, `worktree-java-multi-agent-arch`
and other branches already occupy V10--V14; merging those lines must not create
duplicate Flyway versions.

`t_agent_message_outbox` is deliberately **kept**: the config warned that its
dispatcher could replay pre-existing rows, so production databases may hold
historical rows. The Java/XML outbox shell is removed and the table is no longer
written or read. Dropping it is a separate, explicit data-retention decision.

Updated validation matrix for this branch:

1. Empty MySQL 8 database: callback + V1--V9 + V15 + V16 = 11 migrations,
   all four legacy tables present (`FlywayMySqlIntegrationTest`, verified
   2026-11-10 against `diagnosis_ci` on 3307).
2. Existing database at V9: V15 is a no-op for the four tables that already
   exist; V16 shortens a column no value exceeds and adds a defaulted column.
3. Existing database at V10--V14 from a sibling branch: verify the version
   numbers before merging; V15/V16 must stay above that branch's head.

## V17 (2026-11-10)

`t_news` business model, on top of V15/V16. One migration, no edits to V1--V16:

- Extends the existing `status` vocabulary with `3 = PENDING_REVIEW` (no second
  status column: 0/1/2/3 are one publish lifecycle, and a parallel review
  column would reintroduce the dual-state drift already seen in `t_diagnosis`).
- Adds `submittedById/At`, `reviewedById/At`, `reviewNote`, `category`, `source`,
  `featured`, `offlineReason`, `isDelete`; all nullable or defaulted, so the 21
  production rows need no backfill and existing 0/1/2 semantics are unchanged.
- Adds `idx_status`, `idx_featured`, `idx_category`.
- Creates `t_news_audit` (field-level diff payload, no rich-text body copies).
- `isDelete` aligns with the global `logic-delete-field: isDelete`; delete
  becomes `@TableLogic` soft delete. No `CHECK` constraint: 3306 is MySQL 5.7,
  which parses but ignores it, so the value domain is guarded in Java instead.

Validation matrix for this branch:

1. Empty MySQL 8 database: callback + V1--V9 + V15 + V16 + V17 = 12 migrations,
   `t_news_audit` present and all new `t_news` columns/indexes present.
   The gate also performs a real `INSERT INTO t_news` filling every NOT NULL
   column and reads back V17 defaults (`featured=0`, `isDelete=0`,
   `reviewNote=NULL`) — table-existence alone did not catch the historical
   `content`/`publishTime` no-default gap. Verified 2026-11-10 against
   `news_p1p2_ci` on 3307; temp schema dropped afterwards.
2. Existing database at V16: every `ADD COLUMN` is nullable/defaulted and
   `CREATE TABLE IF NOT EXISTS` is a no-op when the audit table exists.
3. Rollout: deploy the V17 migration before the new Java code writes to the
   new columns; `featured`/`status` indexes are additive.

## V32 / V33 / V34 (2026-11-23)

Three migrations from the 2026-11-23 repo-wide review remediation. No edits to
V1--V31; each statement is additive and nullable, so existing rows need no
backfill. Per-target upgrade paths stay in
`docs/architecture/migration-matrix.md`; this section only records why the
numbers were taken.

- **V32** `t_knowledge_document.pendingIndexVersion` / `pendingUpdatedTime`:
  a document edit writes a candidate version first and switches
  `indexVersion` only after the candidate is READY, so a failed edit no longer
  destroys the searchable previous version (D-01).
- **V33** `t_diagnosis_task.restrictReason`: a task that lands in `RESTRICTED`
  now says which admission check failed instead of the UI only being able to
  say "restricted" (M-01).
- **V34** six nullable evidence columns on both `t_diagnosis` and
  `t_inference_cache`: `classificationProbability`, `calibrationVersion`,
  `imageQualityState`, `imageQualityVersion`,
  `imageQualityCalibrationState`, `patientConclusionAllowed` — split from the
  single overloaded `probability` / `reliability_score` pair so an uncalibrated
  softmax can no longer be read as medical reliability (M-02).

`DiagnosisEvidence` deliberately leaves all six V34 columns NULL when the
Python service does not send the fields. Old rows are not backfilled: the
historical values are not recoverable, and inventing them would be
fabricating evidence.

## V35 (2026-11-24)

One migration from the WeChat mini-program (patient client) work. No edits to
V1--V34; it only creates a new table, so every existing row and query is
untouched. Per-target upgrade paths stay in
`docs/architecture/migration-matrix.md`.

- **V35** `t_user_wechat`: `(appid, openid)` unique key mapping a WeChat
  identity to a `t_user.id`, backing the Bearer token channel that the
  mini-program uses (`wx.login` code -> `jscode2session` -> binding -> token).
  First login creates a `t_user` row (`userAccount = wx_<openid>`, random
  BCrypt password, `role = user`) instead of merging into a password account.
  `session_key` is deliberately absent: it is neither stored nor returned.
  `COLLATE utf8mb4_unicode_ci` matches the V15 baseline tables.

Two consequences:

1. Nothing in the Java cookie-session path reads this table; dropping the
   binding rows would only force a re-login, never create a new account for an
   existing one.
2. The next branch that needs a version takes V36 -- taken by the Control
   Center work below.

## V36 / V37 (2026-11-24)

Two migrations from the Iris Control Center (config drafts, publish, approval)
work. No edits to V1--V35; both only create new tables, so every existing row
and query is untouched. Per-target upgrade paths stay in
`docs/architecture/migration-matrix.md`.

- **V36** `t_config_draft`, `t_config_release`, `t_config_active_pointer`,
  `t_config_request`, `t_config_audit_event`: the config center core, plus one
  seeded active-pointer row `(1, NULL)`. No foreign keys.
- **V37** `t_config_publish_request`: pins the draft revision, payload digest,
  validation digest and expected active version a publish was requested
  against; unique key `(actorId, requestId)`. No foreign keys.

Two consequences:

1. The Control Center is gated by `eye-diagnosis.control-center.enabled`,
   which defaults to `false`, and the publisher allow-list defaults to empty.
   Upgrading alone grants nobody the right to publish.
2. The next branch that needs a version takes V38 -- claimed by the
   user-feedback work below.

## V38 / V39 (2026-11-24)

Two migrations from the user-feedback and in-app-notification work (branch
`t3/add-feedback-feature`, PR #166). No edits to V1--V37; each script only
creates one new table, so existing rows need no backfill. Per-target upgrade
paths stay in `docs/architecture/migration-matrix.md`; section 1.1 there
records the numbering decision.

- **V38** `t_user_feedback`: the product-feedback channel — body, category,
  optional link to a diagnosis task / agent session, status machine, admin
  reply, and the object key of a private screenshot.
- **V39** `t_user_notification`: the in-app inbox — type, the copy frozen at
  send time, a pointer to the related record, and the read timestamp.

Two consequences to know before writing the next migration:

1. V32--V34 landed with #156, V35 with #160, V36--V37 with #164, V38/V39 with
   this branch. With `spring.flyway.out-of-order=false`, merge in version
   order: a database that already applied a higher version rejects the lower
   one arriving later.
2. The next branch that needs a version takes V40: the news line's planned
   V39--V41 must shift to V40--V42 before it opens a PR.

## V40 (2026-11-24)

WP-02 News Engine scheduling, lease fencing, idempotency receipts, and material pool (`V40__news_ingestion_pool.sql`). Preserves the previously applied master migration scripts.

- Creates nine additive tables: `t_news_source` (pre-seeded with `pubmed`, `europe-pmc`, `clinicaltrials-gov`), `t_news_source_cursor`, `t_news_collection_task`, `t_news_collection_lease`, `t_news_collection_batch`, `t_news_batch_item`, `t_news_worker_receipt`, `t_news_material`, and `t_news_material_revision`.
- Existing `t_news` and `t_news_audit` tables are untouched; partial batches stay isolated in `t_news_batch_item` until `complete()` promotes materials and advances the cursor in a single MySQL transaction.

## V41 (2026-11-24)

WP-03 News Engine curation candidates, evidence snapshots, paid model call receipts, daily budget caps, and NewsBench evaluation reports (`V41__news_curation_and_receipts.sql`). Preserves the previously applied master migration scripts.

- Creates four additive tables: `t_news_curation_budget`, `t_news_model_call_receipt`, `t_news_curation_candidate`, and `t_news_bench_run`.
- Leaves `t_news` and `t_news_audit` untouched; publication and Redis cache invalidation stay isolated for WP-04.

## V42 (2026-11-24)

WP-04 News Engine editorial review, service-layer publish gate, evidence-bound immutable content revisions, and durable post-commit cache invalidation (`V42__news_editorial_publish_and_revisions.sql`). Preserves the previously applied master migration scripts.

- Adds nullable/defaulted evidence-binding and revision-counter columns (`curationCandidateId`, `materialRevisionId`, `evidenceSnapshotSha256`, `currentRevisionNo`, `publishedRevisionNo`) plus two lookup indexes to `t_news` while preserving V15/V17 manual authoring compatibility.
- Creates `t_news_revision` (immutable per-edit/publish/delete content & frozen evidence snapshots) and `t_news_cache_epoch` (seeded `news_list` monotonic epoch counter so `afterCommit()` Redis eviction failures never leak withdrawn articles).
