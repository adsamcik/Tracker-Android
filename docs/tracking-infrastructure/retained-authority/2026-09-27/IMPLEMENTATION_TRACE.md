# Retained data authority: code trace and bounded implementation

2026-09-27. **IMPLEMENTATION_ONLY; authored source is IMPLEMENTED_UNVALIDATED.**

Original user task:

> **Complete retained data authority.** Reconcile consent, correction, retention, erase, replay and restore behavior while preserving independently authorized sibling purposes.

Candidate: `codex/source-native-map-context-root`, base
`15f639b68d76b79f3a06b63275ec1575ab72f00f`. Main `dev/v10` stays at `13f1a99871`.
The full retained-authority task and assembly gate remain open. This checkpoint does not activate
providers/writers, freeze convergence, run validation, integrate or push.

## Applied correctness slice

The existing policy publisher carries an unchanged purpose consent epoch into later immutable
policy revisions. Consent issuance and quality/sibling configuration are different boundaries.
Existing Wi-Fi cleanup already accepts an exact latest revoked epoch issued before current policy.

1. `CellCaptureConsentRevocationDeletionCommand.kt`, `CellCapturedFactMaintenance.kt`,
   `ActivityCapturedFactMaintenance.kt` and `PressureRetentionTruncation.kt`: accept the exact
   latest revoked capture epoch when its issuance revision precedes current policy. Remove false
   equality of consent and policy effective times/boot. Still reject eligible/persistent capture,
   missing or wrong latest epoch, future issuance revision and stale purpose/policy clocks.
   Pressure remains its existing whole-source erase audit, not a new narrower-purpose action.
2. Cell capture cleanup no longer treats the generic evidence heartbeat as a capture erase clock.
   Exact collected-data epoch/high-water, capture facts/WAL/cursors/deletion generations, provider
   callback barrier and policy/consent clocks remain enforced. The complete capture audit used
   by retention also uses its source clocks. Successful erase/retention advances the shared
   revision while preserving `max(previous shared timestamp, operation timestamp)`; scope fences
   and retention proof keep the actual requested mutation time. Apply the same monotonic revision
   publication to existing Wi-Fi and Activity erase/retention, which already exclude unrelated
   heartbeat from their source-specific stale-request checks.
3. Retiring Cell/Wi-Fi demand authentication permits an eligible grant carried into the historical
   demand's quality policy. It still requires the exact referenced grant, historical policy/QoS,
   purpose/persistence shape and valid retiring/blocked lifecycle. Grant wall time and same-boot
   monotonic time must precede the original demand. Future grant policy revisions are rejected.
   Cell uses the actual revoked consent issuance revision as the cutoff, not a later sibling policy.

These changes implement already-approved purpose independence and carried-consent semantics.
They add no schema, dependency, generic authority framework or new erase/restore UX.

## Authored regression sources

- `core/base/.../ActivityCapturedFactMaintenanceTest.kt`: cleanup after carried revocation,
  preserved independent CONTROL demand and rejected future issuance without payload/fence changes;
  successful erase and complete correction-lineage retention preserve a newer shared heartbeat.
- `core/base/.../CellCapturedFactMaintenanceTest.kt`: actual successful capture erase preserves
  an authenticated CONTROL-only provider/demand/WAL and newer evidence heartbeat; retention
  preserves heartbeat and writes retained proof; carried grant on a retiring quality-policy demand
  rejects backward wall/monotonic/future-revision consent before mutations, then cleans legitimately.
- `tracker/engine/.../CellCaptureConsentRevocationDeletionCommandTest.kt`: actual command/preflight
  and Room cleanup accept carried revocation/newer sibling heartbeat; future issuance stops before
  runtime access. Existing regrant/epoch/barrier race cases remain.
- `tracker/engine/.../RoomPressureSelectedSessionDeletionServiceTest.kt`: actual contained-writer
  audit/mutation accepts carried revocation, rejects future issuance before mutation and writes the
  exact capture fence. This is not proof of a narrower-purpose Pressure erase.
- `tracker/engine/.../WifiCaptureConsentRevocationDeletionCommandTest.kt`: actual command and
  maintenance reject backward grant time/future policy without retiring a demand or creating a
  fence, then accept carried grant and retire the selected capture demand.
- `tracker/engine/.../WifiWalQualificationAdapterTest.kt`: actual writer-produced facts,
  maintenance erase/retention, exact fence and rejected delayed replay retain a newer heartbeat.

Tests are **uncompiled and unrun**. Activity's fixture preserves a CONTROL demand without a live
physical provider; it does not establish live Activity sibling-host continuation.

## Cross-cutting trace

| Boundary | Selected current implementation | Remaining closure |
| --- | --- | --- |
| Consent publication | `data/preferences/.../tracking/RoomSourcePolicyRepository.kt` carries unchanged capture/noncapture epochs through newer quality/sibling revisions; `ConsentEpochReferenceAuthority.kt` validates immutable referenced epoch without revision equality. | Reconcile remaining source admission/read/maintenance equality checks; never make new consent effective backward or use acquisition settings as historical authority. |
| Correction | Source-local revision/cursor integrity and retention proof families authenticate complete Activity/Pressure/radio correction lineages. `DefaultSourceLifecycleProofAdapter.kt` distinguishes retained/expired/integrity failure. | Complete every writer/query/maintenance/import consumer's selected lineage contract; do not infer whole six-source completion from these helpers. |
| Retention | `app/.../maintenance/RetentionPipelineWorker.kt` uses approved policy settlement and authoritative maximum applicable raw/radio floor. `PeriodicAmbientRetentionMaintenance.kt` consumes the settled floor. Cell/Pressure/radio keep source-specific retained proof before payload/WAL removal. | Preserve existing approved durations; finish remaining purpose-specific boundaries and CONTROL owner/48h expiry. Defaults are not user approval. |
| Capture erase | Source/purpose/run deletion fences precede payload cascade. Wi-Fi/Cell callback barriers can retain independent noncapture authorization. Actual Pressure DI binds `PersistenceLegacyPressureWriterLifecycleBarrier` through `PressureDeletionModule.kt`, draining/fencing the canonical writer before erase. | Activity currently blocks all nonterminal physical registrations. It needs acknowledged capture-purpose settlement rather than weakening quiescence. Pressure generic audit clock still includes global evidence time; reconcile separately before claiming no sibling blockage there. |
| Ambient erase | Ambient Wi-Fi/Cell maintenance rejects remaining persistent ambient authorization, installs replay footprints and removes only its own lineage scope. | Ambient Steps blanket owner-scope check can block cleanup while capture survives. Use exact acknowledged purpose retirement; do not bypass it merely because policy is off. |
| Location purpose storage | `LocationPurposeDao.kt` removes ambient associations and deletes accepted samples only after their last association. Shared callback can retain one sample through multiple exact purposes. | Capture-association erase is absent; direct sample deletion cascades both purposes. Route capture-specific actions through association scope before enabling dual-purpose actions. |
| Location Off/read semantics | Policy Off/envelope change invokes `removeLocationAmbientPurposeInCurrentTransaction`; retention revoke also calls it. `LocationReadScope.AMBIENT` requires current consent/policy/retention authority. | This conflicts with the plan's future-collection-versus-erase distinction. Confirmation is pending; dependent historical read/deletion changes are unapplied. |
| Ambient Steps reads | `AmbientStepsDayRepository.kt` and `NativeAmbientStepsRangeReader.kt` still have consent-policy equality in historical fact/demand authentication. | Reconcile carried historical consent and temporal proof without weakening count-domain/coverage/retention authority; maintain honest unknown coverage. |
| Replay/import | `AmbientWifiMaintenance.kt` and Cell equivalent install verified replay footprints before complete lineage removal; imported source readers authenticate portable lineage without issuing local provider/consent authority. | Complete remaining six-source re-admission/read/effect retraction boundaries and delayed callbacks across erase/retention/re-enable; imports never become native motion or trigger authority. |
| Full erase | `app/.../settings/CollectedDataDeletionService.kt` persists an operation, closes admission, settles provider/writer fences, advances durable epoch, clears payload and reconciles rearm. Room keeps high-water/deletion receipts. | Preserve its broader explicit scope and recovery debt; full erase is distinct from retiring one purpose. Feature leases and file/archive paths remain subject to their open contracts. |
| Restore | `DatabaseExporter.kt` copies actual databases individually; it excludes non-database settings/files and is not a coherent full-app snapshot. Protected `DatabaseImport.kt` rejects Tracker backups rather than merging native operational tables. | Implement complete all-data backup/restore after the pending replacement-versus-empty-target decision and owned-store manifest. Restore historical proof, not current leases/permissions/CONTROL/recovery/URI grants. Re-arm remains explicit. |

The actual Pressure lifecycle barrier is implemented; its dormant unavailable fallback is not the
DI binding. A review hypothesis claiming the fallback blocked production was rejected by tracing DI.
Similarly, the extra Cell quality demand has a distinct hashed identity because its original
requested monotonic time differs; the IGNORE insert does not silently replace the seeded demand.

## Evidence boundary and next work

`CONTRACT_DECISIONS.md` records the Location conflict and previously unanswered full-restore
target decision. Earlier shared-radio admission, native aggregate Steps and runtime ownership
contracts remain pending; this task does not silently resolve them.

Next: confirm historical Location semantics, finish purpose-specific Activity/Steps settlement,
Location capture-association actions, historical Steps authentication, CONTROL expiry and complete
backup/restore. Author focused regression sources for each applied behavior, including sibling
survival, correction/replay zero effect, stale consent/epoch, interrupted erase/restore and no
operational authority resurrection. Obtain complete fresh static review before convergence.

No Gradle, compilation, tests, lint/Detekt, Room schema/drift, diff-check, device/emulator, UI,
battery, CI/release/rollout validation was executed. Static review is not runtime proof.
