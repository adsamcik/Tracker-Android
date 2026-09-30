# Original import epoch selection

**IMPLEMENTED_UNVALIDATED**, 2026-09-30. The independent seven-source epoch
trace is in `IMPORT_ORIGINAL_EPOCH_SOURCE_REVIEW.md`; its manifest is a selection
aid, not a declaration that all reviewed radio/bundle drafts are committed.

This bounded selection commits:

- The original epoch parameter/guard and receipt stream binding in `ImportJobRunner`.
- Only the runner argument and common stream epoch binding in `ImportWorker`.
- Epoch forwarding in the existing Activity, Pressure, session Steps and background
  Steps file adapters, their test sources, and the Steps v1/v2 API/Room writer checks.
- The direct, archive and bundle runner retry epoch assertions.

`FileImportStream`, its tests, and Location adapter/preview original-epoch behavior
were already selected in `ac9f242199`.

The independent reviewer `/root/completion_requirements_0930` accepted these epoch
boundaries and authored scenarios. Root rechecked constructor call sites, owning
Room rollback checks and typed adapter binding. No original epoch is adopted after
decode or between committed prefixes. The legacy direct Steps API remains optional;
typed-file APIs require the job's original value.

Uncommitted Location warning handoff/cancellation hunks in Worker/DataImporter are
preserved. Radio adapters/child attachment, missing-epoch seven-source fixture and
radio retry fixture remain separate transfer assembly work. Bundle metadata,
replacement restore, physical cancellation/cleanup and native Location Replace
are not closed by this selection. No tests or other gates were executed.
