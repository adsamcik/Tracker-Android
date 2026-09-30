# Six-source tracking: remaining work (2026-09-29)

**IMPLEMENTATION_ONLY — not frozen, not activated, not validated.** This is a live
assembly ledger, not a completion receipt. The [September 28 input audit](../2026-09-28/PRE_FREEZE_STATUS.md)
pins the earlier candidate and preserved drafts. Its counts and SHA predate later
`IMPLEMENTED_UNVALIDATED` commits and today's additional working-tree drafts. The
[25-prerequisite trace](../../assembly-coverage/2026-09-28/PREREQUISITE_TRACE.md)
remains the acceptance boundary: all 25 full source-to-product chains are open.
Authored tests, static review and isolated commits do not satisfy those chains.

## Assemble production and test source

- [ ] **Steps:** one all-day choice inherited from released `main`'s single toggle;
  independently record daily totals; complete direct sensor and supported on-device
  provider scheduling, permission/gap/reboot/revision handling, exact count domains,
  manual and automatic session annotation, selected history, summaries, goals,
  export, deletion and Stop. (`WI-STEPS-MANUAL-001`, `WI-STEPS-AUTOMATIC-001`,
  `WI-STEPS-AMBIENT-001`, `WI-STEPS-NUMERIC-001`)

  Six-source first-run setup and the single all-day Steps policy choice were
  source-authored at `b5ba0a6f9e` with recovery and permission tests,
  **IMPLEMENTED_UNVALIDATED**. The Tracker settings surface and Health Connect
  repair action were source-authored at `372d0264f6` with focused tests,
  **IMPLEMENTED_UNVALIDATED**. The provider-to-day query and export chains
  still need assembly.
- [ ] **Location:** compose one physical owner for active, rearmable passive and
  temporary user-requested leases; retire old registrations before replacement;
  finish authenticated cold delivery, durable delivery-gap reporting, historical
  read, map, exact file, process/boot recovery, permission and Stop. The passive
  provider and historical-Off changes exist as unvalidated source slices, not a
  final chain. (`WI-LOCATION-VERTICAL-001`)
- [ ] **Activity and Pressure:** complete independent native and imported facts,
  timestamp-based detail, qualified period statistics, actions, exports and
  correction/deletion effects. Automatic Activity trigger authority remains
  separate from recorded Activity. (`WI-ACTIVITY-VERTICAL-001`,
  `WI-PRESSURE-VERTICAL-001`, `WI-NONSTEPS-EFFECTS-001`)

  Rearmed Pressure writer proof was source-authored at `406bfd64c1` with an
  authored rejection test, **IMPLEMENTED_UNVALIDATED**. The end-to-end Pressure
  chain and provider behavior remain unverified.
- [ ] **Wi-Fi and Cell:** complete per-reading purpose admission and historical
  consent, available network/tower child details and migration, passive callbacks,
  durable finite active-request allowance, optional notification host, source-only
  history/map/one-format file, overlap deduplication, capability gaps and Stop.
  Preserve sibling purposes and omit SIM/subscriber IDs. Radio child/file work is
  currently draft source, not assembled evidence. The durable one-request-per-
  session active-radio allowance is source-authored at `a2a4cddc91` with
  restart/cooldown/passive-continuity tests, **IMPLEMENTED_UNVALIDATED**;
  provider and battery behavior is still unmeasured. (`WI-WIFI-VERTICAL-001`,
  `WI-CELL-VERTICAL-001`)

  Purpose-owned radio child checksums and the development v29 child schema,
  legacy-row copy, containment and invalidation were source-authored at
  `b1d9c1e99f` and `78e3bd35e0`, **IMPLEMENTED_UNVALIDATED**. Product writers,
  readers and files still need the final chain and schema/gate validation.
  The bounded one-source Wi-Fi/Cell file value and JSON codec with authored
  round-trip and malformed-file tests were source-authored at `b32c60d107`,
  **IMPLEMENTED_UNVALIDATED**. The exporter/importer adapters, original-data
  checks, user-facing format selection and actual round trip remain pending.
- [ ] **Shared authority and runtime:** final atomic policy/demand/registration/
  receipt/publication path; authenticated source callers; one physical owner per
  source; independent purpose lifetimes; five-level quality without changing
  participation; session quality preference only for active participants; correct
  foreground hosting and bounded thermal/power fallback. (`WI-AUTHORITY-PURPOSE-001`,
  `WI-PURPOSE-PUBLICATION-001`, `WI-BROKER-PROVIDER-OWNERSHIP-001`,
  `WI-SOURCE-CALLER-GUARD-001`, `WI-QOS-BATTERY-001`)
- [ ] **Lifecycle:** complete one-use Start through permission repair; exact End
  and Turn off tracking from app, notification, widget and shortcut; automatic
  trigger/capture separation; joined-source continuity; process/boot/service
  recovery, FGS refusal and user-stop fencing. (`WI-LIFECYCLE-RUNTIME-001`,
  `WI-LIFECYCLE-PRODUCT-PROOF-001`, `WI-CROSS-SOURCE-RECOVERY-001`)

  The durable all-purpose Turn off command, atomic Off/STOP reservation and
  startup cleanup retry were source-authored at `3cfe9ff81c` with focused
  tests, **IMPLEMENTED_UNVALIDATED**. The Settings Turn off action was
  source-authored at `9f9468af3c` and the shortcut End/Turn off actions at
  `fde45fa227`, both **IMPLEMENTED_UNVALIDATED**. Widget End/Turn off action
  routing and Start-replay fencing were source-authored at `a895c494e4`,
  **IMPLEMENTED_UNVALIDATED**. Notification End/Off receiver routing was
  source-authored at `e4d01dbf7d` without an unscoped timeout retry, and
  shortcut/widget End waits were bounded at `b083e4f2c0`; all remain
  **IMPLEMENTED_UNVALIDATED**. The larger session widget received distinct,
  localized End/Turn off controls at `8023ea7422`, also
  **IMPLEMENTED_UNVALIDATED**. The compact widget layout and notification action
  selection remain drafts. Dashboard's permission callback still issues a new Start without a
  saved, one-use request tied to the original source choices and Stop generation;
  both in-app and external Start repair therefore remain incomplete. The
  already-open Dashboard also leaves the external Start extra unconsumed until a
  later recreation. No real Stop or device behavior has been measured.
- [ ] **Retained data and files:** purpose-scoped consent, correction, erase,
  retention, replay and restore; source file transfers and session bundles;
  one selected format per export; truthful geographic date/session scope; all-data
  backup excluding settings; cancelled/unfinished/private-file cleanup and
  legacy-file disclosure. (`WI-CROSS-SOURCE-PRIVACY-001`,
  `WI-SHARED-FILE-ASSEMBLY-001`)

  The current data-backup draft enumerates durable files and hashes them, but
  `DatabaseExporter` does not yet own the writer barrier its contract requires.
  It holds database transaction locks while copying the whole archive; the
  effect on live provider admission and the interrupted-restore path remain
  unproved. Treat this as unfinished data handling, not a verified backup.

- [ ] **Product:** bounded day/range/calendar/timeline/detail queries over all
  sources; truthful source-only Dashboard/Tracker/statistics/notifications/widgets;
  qualified Location enrichment for radio views; source-aware actions, period
  statistics, concise copy, localization, accessibility and recovery.
  (`WI-SOURCE-ACTIONS-EXPLANATION-001`, `WI-SHARED-HISTORY-DAY-RANGE-001`,
  `WI-UI-SURFACES-001`)

  Notification customization now has authored load/save retry, ordered latest-state
  persistence and accessibility reorder actions in the candidate. This is
  **IMPLEMENTED_UNVALIDATED**; its tests and the full source-to-notification
  chain still require the final convergence and device proof. Interim independent
  review found that leaving during a pending save could lose edits and that
  reordering could cancel its own drag. Corrections and regression sources are
  in the working tree; the unsaved-exit behavior awaits a user decision before
  that follow-up is committed. The interim review is not the required final
  review of validated code and device results.
- [ ] **Schema and scenarios:** assemble final entities/DAOs/indexes/triggers/
  migrations/DI and generated schema; migrate a populated released `main` v12
  database, contain unknown development databases, and author provider-to-fact-
  to-UI/file-to-Stop tests for each solo source, supported purpose, representative
  subsets, joins, lifecycle, privacy and recovery. (`WI-SCHEMA-CONVERGENCE-001`,
  `WI-INTEGRATION-SCENARIOS-001`)

  A populated released-v12 fixture and importer test are already authored but
  unrun. Static review found that the importer still leaves old Wi-Fi count rows,
  carrier names and some one-reading sessions in the original database; it also
  accepts unrecognized development database versions. Preserve the original until
  those rows have a verified destination or the user has exported the old file.

## Convergence and device proof

- [ ] Reconcile every committed candidate and preserved draft with a selected
  disposition. Resolve overlapping edits; stage only reviewed source/test slices
  and commit each as `IMPLEMENTED_UNVALIDATED`.
- [ ] Obtain answers to the still-open design questions before implementing the
  affected choices. Keep questions in plain user terms; do not infer consent from
  a default recommendation.
- [ ] Have an independent, review-only assembly pass check the complete source
  and test inventory. Record the exact commits, draft dispositions, final schema,
  capability limits, test inventory and commands in a freeze receipt. Only then
  declare the implementation assembly complete and lift the implementation-only
  validation stop.
- [ ] Run the frozen compile/Hilt/Room/schema and migration gates, `ciUnitTest`,
  and `ciCheck`; repair failures and rerun the required gates. Passing host tests
  is not physical provider evidence.
- [ ] Obtain bounded provider activation authority. On one representative
  device/emulator configuration, trace every supported source and purpose through
  real provider, authenticated storage, selected UI, export/import and Stop; test
  joined sources, process/boot/permission/FGS recovery, battery use across five
  levels and honest unsupported/gap states. Record environment, logs and artifacts.
- [ ] Give a **fresh** independent reviewer the original task/follow-up text,
  approved decisions, final diff, gates and device evidence. Address findings,
  rerun affected checks, then rebase and merge into clean local `dev/v10` and
  verify that integrated result. Do not push.

No box above is complete merely because an isolated source slice was committed.

## 2026-09-30 continuation

- [x] Source-authored the shared Wi-Fi/network and Cell/tower detail API and the
  bounded native/imported background readers. They retain available identifiers,
  preserve older summary-only records, read only current corrections, and reject
  partial or damaged detail sets. Whitespace-only network names are preserved.
  Focused API, helper and Room regression sources cover these cases and cleanup.
  An independent, review-only static pass accepted this isolated reader slice
  after the SSID correction. **IMPLEMENTED_UNVALIDATED**: no compilation, test,
  schema, device, or source activation commands were run. Native session readers,
  admission, writer/maintenance and actual file round trips remain open.
- [ ] Repair the independent radio review findings: new shared deliveries still
  encounter whole-batch authorization/freshness checks in ingress; background
  projectors and Wi-Fi maintenance retain incompatible whole-batch predicates.
  Apply the approved versioned per-reading rule without altering legacy receipt
  meaning, then author join/leave, stale-session/fresh-background and replay cases.
- [ ] Await the user choices for background Steps catch-up cadence and explicit
  Retry/Leave-without-saving behavior after notification customization save failure.

All 25 full-chain prerequisites and final convergence/integration remain pending.
