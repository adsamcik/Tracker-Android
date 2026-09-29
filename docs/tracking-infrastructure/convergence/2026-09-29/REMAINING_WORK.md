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
- [ ] **Wi-Fi and Cell:** complete per-reading purpose admission and historical
  consent, available network/tower child details and migration, passive callbacks,
  durable finite active-request allowance, optional notification host, source-only
  history/map/one-format file, overlap deduplication, capability gaps and Stop.
  Preserve sibling purposes and omit SIM/subscriber IDs. Radio child/file work is
  currently draft source, not assembled evidence. (`WI-WIFI-VERTICAL-001`,
  `WI-CELL-VERTICAL-001`)
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
- [ ] **Retained data and files:** purpose-scoped consent, correction, erase,
  retention, replay and restore; source file transfers and session bundles;
  one selected format per export; truthful geographic date/session scope; all-data
  backup excluding settings; cancelled/unfinished/private-file cleanup and
  legacy-file disclosure. (`WI-CROSS-SOURCE-PRIVACY-001`,
  `WI-SHARED-FILE-ASSEMBLY-001`)
- [ ] **Product:** bounded day/range/calendar/timeline/detail queries over all
  sources; truthful source-only Dashboard/Tracker/statistics/notifications/widgets;
  qualified Location enrichment for radio views; source-aware actions, period
  statistics, concise copy, localization, accessibility and recovery.
  (`WI-SOURCE-ACTIONS-EXPLANATION-001`, `WI-SHARED-HISTORY-DAY-RANGE-001`,
  `WI-UI-SURFACES-001`)
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
