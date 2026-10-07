# Steps reward retraction selected source

Phase: **IMPLEMENTATION_ONLY**. Source: **IMPLEMENTED_UNVALIDATED**. Tests: **NOT_RUN**. Independent static review: **ACCEPTED**, see SOURCE_REVIEW.md.

Baseline: `037eb7e9b2c4f902aec46d5a85f3c43a59172b72`.

Nine clean committed-HEAD selections are in `SELECTED`; exact original Git bytes are in `BASE` and exact working bytes are in `WORKING_BEFORE`. All nine original working sources match the baseline after line-ending normalization, but their raw hashes differ. No shared live source, Git index, protected path or frozen awards/importer draft was changed. Root owns selection, review and commits.

## Approved scope

The user required exact qualified Steps effects and correction/deletion retraction, while preserving independently authorized sibling purposes and existing legacy awards. Root explicitly directed this smallest consistency slice using existing receipts, transactions and historical repair queue. No new public API, state, schema, scoring rule, reward type, cache, engine, UI or provider is introduced.

- `PointsAwardedDao` and `XpLedgerDao` acknowledge a same-revision **identical** payload without writing. Conflicting numeric value or earned time is refused. SQL updates require a strictly higher revision. A zero receipt remains present and cannot be rewritten as a positive same-revision reward.
- Points equality includes saved micros and its derived Double. Every revisioned XP apply rejects a legacy source ID before replay or replacement, and the raw SQL update independently requires `source_id IS NULL`. Existing query predicates provide exact source/key selection.
- The two existing goal projectors retain the original time of an exact current-revision zero receipt only for a never-completed zero effect. `recordDecision` intentionally permits a diagnostic `updatedAtMs` refresh without a new semantic revision. Existing zero receipts finish interrupted acknowledgement using their saved time; positive, foreign, inconsistent or older-revision receipts cannot supply it. Completed and retracted effects continue to use frozen `firstCompletedAtMs`.
- A current XP receipt conflict is withheld via the existing projector retryable-failure catch. It is not falsely classified as a superseded effect, which could leave the actionable drain spinning without its retry delay.
- Authenticated all-day native Steps consent-reset deletion invokes the existing `enqueueAllStepsGoalRepairs` after advancing source evidence, in the same Room transaction as its payload removal. Repair reuses each existing decision's exact stored calendar. Deletion does not assume independently retained session/imported counts vanished or rewrite those rewards itself. Cancellation rolls back the queue, source evidence, invalidation and deletion together.

## Authored evidence

Nineteen new methods use actual Room DAOs/deletion or the production two-ledger goal projector with real databases. They cover exact replay, direct SQL same-revision refusal, changed values/time, zero retraction and higher-revision restoration, inconsistent/foreign receipts, diagnostic-refresh crash gaps, frozen completion time, source deletion queueing, blocked mutation and cancellation rollback. Existing correction/retraction, legacy receipt and startup-generation tests are preserved.

Only static source inspection and test-source authorship occurred. No compilation, tests, Gradle, lint, schema generation, diff-check, provider activation, device/emulator, CI or integration ran. The goal coordinator remains deliberately dormant. This is not T12/T15 completion or proof of a working production rewards chain. See `CALLER_TRACE.md` and `SESSION_REWARD_GAP.md` for remaining connections.

## V1 independent review repair

The reviewer found that V1 rejected legacy-owned XP only on same-revision replay; its higher-revision update could still overwrite a `GOAL` row carrying a non-null recording `source_id`. V2 rejects that ownership before either update branch and adds the same predicate to direct SQL. Three new actual Room/projector cases cover a newer desired revision, direct SQL refusal, unchanged legacy reward/profile and withheld XP acknowledgement. The independent Points component may still settle its own valid correction through the existing two-ledger retry model. No new revision or legacy ownership is guessed.

Exact V1 selected sources and metadata are preserved in `REVIEW_V1`. V2 remains IMPLEMENTED_UNVALIDATED / NOT_RUN and has independent static recheck acceptance. No live source or Git mutation occurred.
