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

## V37 / V38 (2026-11-24)

Two migrations from the user-feedback and in-app-notification work (branch
`t3/add-feedback-feature`). No edits to V1--V34; each script only creates one
new table, so existing rows need no backfill. Per-target upgrade paths stay in
`docs/architecture/migration-matrix.md`; §1.1 there records the numbering
decision.

- **V37** `t_user_feedback`: the product-feedback channel — body, category,
  optional link to a diagnosis task / agent session, status machine, admin
  reply, and the object key of a private screenshot.
- **V38** `t_user_notification`: the in-app inbox — type, the copy frozen at
  send time, a pointer to the related record, and the read timestamp.

Two consequences to know before writing the next migration:

1. V35--V36 are owned by the in-flight Iris Control Center branch (PR #164).
   This branch took V37/V38 to avoid a clash, which creates an ordering
   constraint: with `spring.flyway.out-of-order=false`, a database that already
   applied V37/V38 rejects V35--V36 arriving later. Merge the Control Center
   branch first, or renumber it to V39+ inside the same release window.
2. Renumbering is cheap: change the file name plus the version / migration-count
   assertions in `FlywayMySqlIntegrationTest`; the scripts contain no version
   number of their own (V35→V37 was rehearsed once).

Also still binding for every new script: never edit an applied script, only
append a higher version.

