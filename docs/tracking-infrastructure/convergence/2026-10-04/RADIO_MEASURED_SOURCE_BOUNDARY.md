# Measured radio bounds â€” native source prerequisite, frozen 2026-10-04

**IMPLEMENTED_UNVALIDATED / IMPLEMENTATION_ONLY.** Six clean-at-start production/test paths
are selected by `RADIO_MEASURED_SOURCE_FILES.json`. Parent supplied fixture clean status at
`a2a5e5131162505d19ea614f95e2a55707953a41`; exact working-before bytes were captured before
this slice. No tests, compiler, Gradle, lint, schema drift, diff-check, providers/device, CI,
validation gates, Git or integration were executed by this author.

## Approved source repair

- Both background projectors map the original callback wall/elapsed anchor directly to the
  earliest accepted reading's nominal coverage start even when it precedes the latest reading's midnight. Subtraction is guarded;
  unsupported negative bounds produce the existing clock-unverifiable outcome.
- Each group remains one unsplit aggregate. Original accepted indexes, actual child provider/wall
  timestamps, uncertainties, payloads, authority references, semantic revisions and identity
  derivation are unchanged. Existing per-purpose item admission/retention filters are unchanged.
- Native entity shape permits nonnegative coverage start before the structural day and still
  requires ordered coverage and a latest observed timestamp inside the exact saved local day.
  Existing structural date/zone/DST validation is unchanged. No database column, index, schema,
  migration, format version, acquisition policy or replay identity was added or changed.
- Existing clamped saved entities still satisfy the shape. Existing duplicate projection verifies
  their saved checksum/children and returns Duplicate without rewriting their coverage or identity.
  Actual read-only recovery of old bounds and local measured-day presentation are separate root
  work, excluded here; this packet does not claim that consumer chain complete.

## Static compatibility trace and coordinated followup

The old native rows already retain `observedIntervalStartNanos` and `observedElapsedNanos`.
Root's read-side repair can recover the nominal start as the lesser of saved coverage and
`observedWallTimeMs - ceil((observedElapsedNanos - observedIntervalStartNanos)/1_000_000)`, then
apply existing uncertainty. It must retain the original entity/checksum/ID, authenticate the
same historical full elapsed interval, and keep strict full child uncertainty bounds.
`RetainedAmbientRadioReader` lifecycle and approval floors must use that recovered bound too;
otherwise older clamped rows could hide a child below the retention floor. Root owns that repair.

Existing portable ambient V1 validates structural day against observedTime, not coverageStart.
It already accepts coverage across days. Existing radio file V2/JSON child validation requires
full child uncertainty intervals inside the portable parent; truthful widened read-side bounds
fit the established contract without tolerant import rules or a new format. New native facts
will flow through the same mapping. Imported files have their own saved portable interval and
remain unchanged. Whole original windows remain intact; no artificial daily split is introduced.

Existing maintenance expiration is whole-entry end-before handling. No additional concrete
maintenance-floor defect was found beyond the retained qualification comparisons reported to
root. Ordinary standalone date-export expansion, old file conflict handling and pending overlap
aliases are outside this source prerequisite and are not silently changed.

## Authored coverage and limits

Eight new unexecuted cases use the actual existing Room ingress/projector fixtures: Wi-Fi and
Cell groups crossing UTC midnight preserve both exact child times and one aggregate on duplicate
replay; Prague's 2026-03-29 midnight keeps a 23-hour structural day with prior-day coverage.
The DST cases also check negative/future starts, latest timestamps outside the structural day,
and compatibility of old clamped entity shape without changing original identity/elapsed fields.

Fresh review M1 found that subtracting a floored source span from an already rounded latest
wall timestamp can place the parent start 1 ms after its oldest child. The new projectors now
map callback-to-earliest directly, matching the original child conversion. Four additional actual
ingress cases use callback10,000,000/latest9,600,000/earliest8,900,000 ns at UTC/Prague midnight,
assert strict full child uncertainty bounds and exact duplicate replay. The root-owned read helper
and DAO recovery use conservative ceiling for older rows whose original callback fraction is gone.

Existing child rounding cases remain unchanged. These new cases do not execute history grouping,
retained authority, portable JSON, Android delivery, battery or device lifecycle. Written source
and static trace are not passing test evidence. Overall assembly readiness remains unproven.

Patch SHA-256: `516dda7e74cebb893d99272ae3f2481d34ba106d24695dfeaa7bc002e846a6f7`.
Manifest SHA-256: `0f6e4ca60452d81b0df9ad358acac189b54a3e5ae5286408179e929a522412c9`.
